package ic2_120_advanced_solar_addon.content.block

import ic2_120.content.energy.EnergyTier
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.screen.ScreenHandler
import net.minecraft.state.property.BooleanProperty
import net.minecraft.util.math.BlockPos

/**
 * 太阳能板家族的 Sinytra Connector 兼容基类（仅 1.20.1 / Forge 47 需要）。
 *
 * Connector 1.0.0-beta.49 在把 Fabric 模组重映射到 SRG 时，偶尔不会把
 * `NamedScreenHandlerFactory#createMenu` 由 Yarn 名 `createMenu` 改成 SRG 名 `m_7208_`
 * （同类 jar 的其他 BE 正常，`SolarPanelBlockEntity` 被漏掉）。Forge 端
 * `NetworkHooks.openScreen` 会按 `MenuProvider#m_7208_` 分派，于是抛
 * `AbstractMethodError` 并崩掉集成服务端。
 *
 * 这里直接声明 SRG 名方法作为兜底：
 * - 原生 Fabric：不会被调用，仍走 `createMenu`，行为不变；
 * - Forge/Connector：若基类已被正常改名，本方法只是覆盖同名方法；
 *   若基类被漏改，本方法直接满足 `MenuProvider`。
 *
 * 关键：**不能**把 `m_7208_` 放进 [SolarPanelBlockEntity] 本身。若 Connector 某次
 * 正常把基类的 `createMenu` 改名为 `m_7208_`，同一类里就会有两个同签名方法，
 * 类加载直接 `ClassFormatError`。本类不声明 `createMenu`，因此两种情况都安全。
 */
abstract class SolarPanelForgeBridgeBlockEntity(
    type: BlockEntityType<out SolarPanelBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    dayPower: Int,
    nightPower: Int,
    maxStorage: Long,
    tier: Int,
    activeProperty: BooleanProperty,
    rainPower: Int = 0,
    maxOutput: Long = EnergyTier.euPerTickFromTier(tier)
) : SolarPanelBlockEntity(
    type, pos, state, dayPower, nightPower, maxStorage, tier, activeProperty, rainPower, maxOutput
) {

    /** `net.minecraft.world.MenuProvider#m_7208_`（SRG 名）的显式实现。 */
    @Suppress("unused", "FunctionName")
    fun m_7208_(syncId: Int, playerInventory: PlayerInventory, player: PlayerEntity?): ScreenHandler =
        createSolarPanelScreenHandler(syncId, playerInventory)
}
