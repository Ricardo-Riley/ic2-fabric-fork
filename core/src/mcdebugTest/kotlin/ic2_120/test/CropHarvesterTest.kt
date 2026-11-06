package ic2_120.test

import com.mcdebug.runner.McDebugTest
import com.mcdebug.runner.TestContext
import com.mcdebug.runner.assertBlockId
import com.mcdebug.runner.assertSlotEmpty
import com.mcdebug.runner.assertSlotHas
import com.mcdebug.runner.getBlockProp
import com.mcdebug.runner.getBeNumber
import com.mcdebug.runner.place
import com.mcdebug.runner.setBlocks
import com.mcdebug.runner.setBeField
import com.mcdebug.runner.setSlot
import ic2_120.content.block.machines.CropHarvesterBlockEntity
import ic2_120.content.screen.CropHarvesterScreenHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 作物收割机 (Crop Harvester) 测试。
 *
 * 槽位布局（与 `CropHarvesterBlockEntity` 保持一致）：
 *   slot 0-14  = 输出 (SLOT_CONTENT_0..14)
 *   slot 15-18 = 升级 (SLOT_UPGRADE_0..3)
 *   slot 19    = 放电槽 (SLOT_DISCHARGING)
 *
 * ⚠️ 驱动方式（2026-08-18 mdb 手测 + 本测试设计，mcdebug 0.5.0）：
 * `be.tick` **不推进 world.time**，收割机的工作窗口门控是
 * `(world.time + workOffset) % interval == 0`：
 *  - 单次 `be.tick(N)` 批量驱动期间 world.time 冻结 ⇒ 门控要么 N 次全真
 *    （对齐），要么 N 次全假（错位），不存在中间态；
 *  - 自然 tick（50ms/个）会翻转对齐状态，因此**探测到对齐的瞬间必须立即
 *    执行后续布置 + 批量驱动**（全部在几 ms 内完成，落在同一自然 tick 窗口）。
 *
 * 由此得到两个原语（全部毫秒级，无自然等待）：
 *  - `alignedBatch`：扫描 WorkOffset 0..9 找到使门控为真的值（游标会动即为真），
 *    命中瞬间执行 [setup]（种作物/充能）并立刻 `be.tick(ticks)` = ticks 个
 *    连续工作步，等效加速。
 *  - `misaligned`：对齐后 +1 错开并复核游标不动，建立"门控恒假"窗口。
 *
 * 超频证明的非对称设计（overclockerRequiredToFinish）：
 * 错位 + 600 次 be.tick → 无超频（interval=10）一次都不工作；
 * 插 5 个超频（interval=1，门控恒真）+ 同样 600 次 → 全部收割。
 * 同一预算下"没有超频干不完、有超频干得完"。
 *
 * 顺序要点：作物一律在对齐/错位确立**之后**再种——探测工作步不会误收割，
 * 空场探测也不消耗任何能量（扫描费只对真实作物架收取）。
 */
@McDebugTest
class CropHarvesterTest {

    /** 小麦：maxAge=7，optimalHarvestAge=7（CropSystem 定义），收割后退回低 stage 再生长 */
    private val wheatMaxAge = "7"

    /** 4 株成熟小麦：东北/西北/东南/西南，均在 4 格水平范围内 */
    private val cropOffsets = listOf(
        Triple(-2, 0, -2), Triple(2, 0, -2),
        Triple(-2, 0, 2), Triple(2, 0, 2)
    )

    private fun setupHarvester(ctx: TestContext, energy: Int = 10000) {
        // 强制重建 BE：dev 世界持久化且 origin 跨运行会被复用（并发下分配顺序不定），
        // 直接 place() 对已存在方块返回 ok:false 并保留旧 BE（含残留升级/能量/游标，
        // 例如上次运行超频测试留下的 5 个超频会把耗能门槛拍到 220 EU）。
        // 先置石再放机器，保证拿到全新 BE。
        setBlocks(ctx, listOf(ctx.origin to "minecraft:stone"))
        place(ctx, ctx.origin, "ic2_120:crop_harvester")
        setBeField(ctx, ctx.origin, "EnergyStored", energy)
    }

    /** 放置 [stage] 阶段小麦（单次 setBlocks RPC）；返回位置列表 */
    private fun plantWheat(ctx: TestContext, stage: String = "7"): List<List<Int>> {
        val ops = cropOffsets.map { (dx, dy, dz) -> ctx.pos(dx, dy, dz) to "ic2_120:crop" }
        setBlocks(ctx, ops, mapOf("crop_type" to "wheat", "stage" to stage))
        return ops.map { it.first }
    }

    /** 把 15 个输出槽全部塞满圆石（模拟输出满） */
    private fun fillOutputSlots(ctx: TestContext) {
        for (slot in 0..14) {
            setSlot(ctx, ctx.origin, slot, "minecraft:cobblestone", 64)
        }
    }

    /**
     * 游标的线性索引（x + z*9 + y*81，均从最小值起算）。
     * 单看 ScanX 无法判断是否推进（会回绕），用复合索引做单调性检测。
     */
    private fun cursorIndex(ctx: TestContext): Int {
        val x = getBeNumber(ctx, ctx.origin, "ScanX").toInt()
        val y = getBeNumber(ctx, ctx.origin, "ScanY").toInt()
        val z = getBeNumber(ctx, ctx.origin, "ScanZ").toInt()
        return (x + 4) + (z + 4) * 9 + (y + 1) * 81
    }

    /**
     * 对齐门控后立即批量驱动：扫描 WorkOffset 0..9，某次探测使游标推进
     * 即为对齐（此时该 offset 已生效）；执行 [setup]（种作物/设置能量等）后
     * **复核窗口仍在**（单 tick 游标继续推进）才执行 `be.tick(ticks)`，
     * 否则自然 tick 边界已跨越，整轮重试。[setup] 必须幂等（重试会重复执行；
     * setBlocks/setSlot 幂等安全）。
     * 探测步在空场上进行——不收割、零能量消耗。
     */
    private fun alignedBatch(ctx: TestContext, ticks: Int, setup: (TestContext) -> Unit) {
        var attempts = 0
        while (attempts++ < 12) {
            for (offset in 0..9) {
                setBeField(ctx, ctx.origin, "WorkOffset", offset)
                val before = cursorIndex(ctx)
                ctx.api.be.tick(ctx.origin, 1)
                if (cursorIndex(ctx) != before) {
                    setup(ctx)
                    // 复核：setup 耗时后窗口是否仍在（游标应继续推进）
                    val verify = cursorIndex(ctx)
                    ctx.api.be.tick(ctx.origin, 1)
                    if (cursorIndex(ctx) == verify) break // 窗口丢失，重试外层
                    ctx.api.be.tick(ctx.origin, ticks)
                    return
                }
            }
        }
        error("12 轮探测均未建立稳定对齐窗口 —— 门控永假，机器可能损坏")
    }

    /**
     * 建立错位门控：先探测到对齐 offset，+1（mod 10）错开，单 tick 复核
     * 游标不动即确认。失败（边界跨越）则整轮重试。确认后执行 [setup]。
     */
    private fun misaligned(ctx: TestContext, setup: (TestContext) -> Unit) {
        repeat(8) {
            for (offset in 0..9) {
                setBeField(ctx, ctx.origin, "WorkOffset", offset)
                val before = cursorIndex(ctx)
                ctx.api.be.tick(ctx.origin, 1)
                if (cursorIndex(ctx) != before) {
                    // 找到对齐值，错开一格
                    setBeField(ctx, ctx.origin, "WorkOffset", (offset + 1) % 10)
                    val verify = cursorIndex(ctx)
                    ctx.api.be.tick(ctx.origin, 1)
                    if (cursorIndex(ctx) == verify) {
                        setup(ctx) // 错位确认，布置（不触发任何工作）
                        return
                    }
                    break // 边界跨越，重试外层
                }
            }
        }
        error("8 轮探测均未建立错位窗口")
    }

    private fun assertHarvested(ctx: TestContext, p: List<Int>) {
        val stage = getBlockProp(ctx, p, "stage") ?: error("无 stage 属性 @ $p")
        assertTrue(stage.toInt() < 7, "作物应被收割退回低 stage，实际 stage=$stage @ $p")
    }

    @Test
    fun `place`(ctx: TestContext) {
        place(ctx, ctx.origin, "ic2_120:crop_harvester")
        assertBlockId(ctx, ctx.origin, "ic2_120:crop_harvester")
    }

    /** 无电空转：有成熟作物但能量=0，600 次驱动机会内不收割、游标不动（能量门槛在门控之后） */
    @Test
    fun idleWithoutEnergy(ctx: TestContext) {
        setupHarvester(ctx, energy = 0)
        plantWheat(ctx)
        val before = cursorIndex(ctx)
        ctx.api.be.tick(ctx.origin, 600)
        assertEquals(before, cursorIndex(ctx), "无电时游标不应推进")
        cropOffsets.forEach { (dx, dy, dz) ->
            assertEquals(wheatMaxAge, getBlockProp(ctx, ctx.pos(dx, dy, dz), "stage"), "无电不应收割")
        }
        assertSlotEmpty(ctx, ctx.origin, 0)
    }

    /**
     * 基线收割（对齐 + be.tick 加速）：
     * 600 个连续工作步 >> 覆盖 243 格扫描体所需（~31 次跳跃）+ 4 次收割，
     * 4 株小麦应全部收割且产物入库。
     */
    @Test
    fun harvestsMatureWheat(ctx: TestContext) {
        setupHarvester(ctx)
        alignedBatch(ctx, ticks = 600) { plantWheat(it) }
        cropOffsets.forEach { (dx, dy, dz) -> assertHarvested(ctx, ctx.pos(dx, dy, dz)) }
        assertSlotHas(ctx, ctx.origin, 0, "minecraft:wheat")
    }

    /**
     * 空气格跳过 + 零扫描能耗：机器能量恰好只够一次真实检查的成本
     * （1 EU 扫描费）时依然能完成收割——证明空气格不扣费。
     * 预置能量 = 1(扫描) + 20(一个产物堆) = 21 EU，无 batbox 回充。
     */
    @Test
    fun emptyCellsSkippedWithoutScanCost(ctx: TestContext) {
        setupHarvester(ctx, energy = 21)
        var cropPos: List<Int>? = null
        alignedBatch(ctx, ticks = 600) { c ->
            cropPos = c.pos(2, 0, 2)
            setBlocks(c, listOf(cropPos!! to "ic2_120:crop"), mapOf("crop_type" to "wheat", "stage" to "7"))
        }
        assertHarvested(ctx, cropPos!!)
        assertTrue(getBeNumber(ctx, ctx.origin, "EnergyStored") < 21.0, "能量应被消耗")
    }

    /**
     * 超频有效性的非对称证明（全程 be.tick 加速，无自然等待）：
     *
     * 1. 错位门控 + 无超频：600 次 be.tick（interval=10，门控恒假）→ 0 次工作，
     *    游标不动、作物保持 stage 7 —— 没有超频干不完；
     * 2. 插入 5 个超频（interval=1，门控恒真，WorkOffset 无关紧要）：
     *    同样的 600 次 be.tick → 4 株全部收割 —— 有超频干得完。
     *
     * 同时断言能耗按 1.6^5≈10.5 倍放大：
     * 4 次扫描(10.5) + 4 个产物堆(210) ≈ 880 EU >> 基线的 84 EU。
     */
    @Test
    fun overclockerRequiredToFinish(ctx: TestContext) {
        setupHarvester(ctx, energy = 10000)
        var crops: List<List<Int>> = emptyList()
        misaligned(ctx) { c -> crops = plantWheat(c) }

        // 阶段 1：错位 + 600 tick，无超频必须零工作
        val cursorBefore = cursorIndex(ctx)
        ctx.api.be.tick(ctx.origin, 600)
        assertEquals(cursorBefore, cursorIndex(ctx), "错位门控下批量 be.tick 不应推进游标")
        crops.forEach { p ->
            assertEquals(wheatMaxAge, getBlockProp(ctx, p, "stage"), "无超频（错位）不应收割")
        }

        // 阶段 2：同样 600 tick 预算，加 5 个超频后必须全部收割
        setSlot(ctx, ctx.origin, 15, "ic2_120:overclocker_upgrade", 5)
        ctx.api.be.tick(ctx.origin, 600)
        crops.forEach { assertHarvested(ctx, it) }
        assertSlotHas(ctx, ctx.origin, 0, "minecraft:wheat")
        assertTrue(
            getBeNumber(ctx, ctx.origin, "EnergyStored") < 10000 - 880,
            "超频耗能应按 1.6^5 倍率放大（4 扫描+4 产物堆 ≈ 880 EU）"
        )
    }

    /** 输出满：15 槽全满时跳过收割，作物保持 stage 7（游标仍推进、扫描费仍扣） */
    @Test
    fun skipsWhenOutputFull(ctx: TestContext) {
        setupHarvester(ctx)
        alignedBatch(ctx, ticks = 600) { c ->
            fillOutputSlots(c)
            plantWheat(c)
        }
        cropOffsets.forEach { (dx, dy, dz) ->
            assertEquals(wheatMaxAge, getBlockProp(ctx, ctx.pos(dx, dy, dz), "stage"), "输出满应跳过收割")
        }
        // 输出槽仍是被手动塞的圆石（未被清空/替换）
        assertSlotHas(ctx, ctx.origin, 0, "minecraft:cobblestone")
    }

    /** 未成熟作物（stage<7）不收割 */
    @Test
    fun skipsImmatureCrop(ctx: TestContext) {
        setupHarvester(ctx)
        var cropPos: List<Int>? = null
        alignedBatch(ctx, ticks = 600) { c ->
            cropPos = c.pos(2, 0, 2)
            setBlocks(c, listOf(cropPos!! to "ic2_120:crop"), mapOf("crop_type" to "wheat", "stage" to "3"))
        }
        assertEquals("3", getBlockProp(ctx, cropPos!!, "stage"), "未成熟作物不应被收割")
        assertSlotEmpty(ctx, ctx.origin, 0)
    }

    /**
     * GUI 回归：升级槽必须能通过普通点击/Shift-click 放入。
     *
     * 修复前 CropHarvesterBlockEntity.itemStorage.insertRoutes 不包含升级槽 15–18，
     * deriveSlotSpec 返回 canInsert=false，服务端会拒绝所有升级（不只是弹出升级）。
     * 玩家快捷栏在 Handler 中的起始索引 = PLAYER_INV_START + 27。
     */
    @Test
    fun `screen upgrade slots accept supported upgrades`(ctx: TestContext) {
        setupHarvester(ctx)
        val opened = ctx.api.screen.openBlock(ctx.origin, player = "fake").asJsonObject
        val screenId = opened.get("screenId").asString
        val hotbarHandlerStart = CropHarvesterScreenHandler.PLAYER_INV_START + 27
        val upgrades = listOf(
            "ic2_120:overclocker_upgrade",
            "ic2_120:transformer_upgrade",
            "ic2_120:energy_storage_upgrade",
            "ic2_120:ejector_upgrade"
        )

        for ((i, item) in upgrades.withIndex()) {
            ctx.api.screen.setPlayerSlot(screenId, i, mapOf("item" to item, "count" to 1))
            val after = ctx.api.screen.quickMove(screenId, hotbarHandlerStart + i).asJsonObject
            val slots = after.get("slots").asJsonArray
            val upgradeSlot = CropHarvesterBlockEntity.SLOT_UPGRADE_0 + i
            assertEquals(
                item,
                slots.get(upgradeSlot).asJsonObject.get("item").asString,
                "upgrade $item should be routed into BE slot $upgradeSlot"
            )
            assertTrue(
                slots.get(hotbarHandlerStart + i).asJsonObject.get("item").isJsonNull,
                "player hotbar slot $i should be emptied after quick move"
            )
        }

        ctx.api.screen.close(screenId)
    }

    /**
     * 弹出升级效果：输出槽物品应被推入相邻容器。
     * 这里用 setSlot 直接写升级槽，单独验证 tick 中的 EjectorUpgradeComponent 路径。
     */
    @Test
    fun `ejector upgrade moves output to adjacent chest`(ctx: TestContext) {
        setupHarvester(ctx)
        val chestPos = ctx.pos(1, 0, 0)
        ctx.api.world.setBlock(chestPos, "minecraft:chest")
        setSlot(ctx, ctx.origin, CropHarvesterBlockEntity.SLOT_UPGRADE_0, "ic2_120:ejector_upgrade", 1)
        setSlot(ctx, ctx.origin, CropHarvesterBlockEntity.SLOT_CONTENT_0, "minecraft:wheat", 1)

        ctx.api.be.tick(ctx.origin, 1)

        assertSlotEmpty(ctx, ctx.origin, CropHarvesterBlockEntity.SLOT_CONTENT_0)
        val chestSlot = ctx.api.inv.getSlot(chestPos, 0).asJsonObject.get("slot").asJsonObject
        assertEquals("minecraft:wheat", chestSlot.get("item").asString)
        assertEquals(1, chestSlot.get("count").asInt)
    }
}
