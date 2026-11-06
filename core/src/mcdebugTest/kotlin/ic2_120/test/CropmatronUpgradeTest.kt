package ic2_120.test

import com.mcdebug.runner.McDebugTest
import com.mcdebug.runner.TestContext
import com.mcdebug.runner.place
import ic2_120.content.block.machines.CropmatronBlockEntity
import ic2_120.content.screen.CropmatronScreenHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 作物监管机升级槽回归测试。
 *
 * 监管机的 tooltip 现在会列出物品弹出/抽入升级；这里验证它们确实能通过 GUI 放入，
 * 防止“提示里写了但实际放不进去”再次出现。
 */
@McDebugTest
class CropmatronUpgradeTest {

    /**
     * 玩家快捷栏在 Handler 中的起始索引 = PLAYER_INV_START + 27；
     * CropmatronScreenHandler 的水/除草剂/肥料/升级槽之后还有 27 格主背包。
     */
    private val hotbarHandlerStart = CropmatronScreenHandler.PLAYER_INV_START + 27

    @Test
    fun `screen upgrade slots accept item ejector and pulling`(ctx: TestContext) {
        place(ctx, ctx.origin, "ic2_120:cropmatron")
        val opened = ctx.api.screen.openBlock(ctx.origin, player = "fake").asJsonObject
        val screenId = opened.get("screenId").asString
        val upgrades = listOf(
            "ic2_120:ejector_upgrade",
            "ic2_120:pulling_upgrade"
        )

        for ((i, item) in upgrades.withIndex()) {
            ctx.api.screen.setPlayerSlot(screenId, i, mapOf("item" to item, "count" to 1))
            val after = ctx.api.screen.quickMove(screenId, hotbarHandlerStart + i).asJsonObject
            val slots = after.get("slots").asJsonArray
            val upgradeSlot = CropmatronBlockEntity.SLOT_UPGRADE_0 + i
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
}
