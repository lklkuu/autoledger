package com.autoledger.feature.capture

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 合并后「主记录该不该改用被吸收记录的平台」的纯 JVM 护栏（设计 §4.5 第三条）。
 *
 * 为什么单独测这个纯函数：这条规则**在自动合并路径上几乎到不了** ——
 * 自动合并时主记录必然是层级更高的一方（那个 ORDER 平台），它的平台不会是待补的空值。
 * 但它对**用户手动合并**与将来的路径都必须成立，挂在私有方法里就只能靠造完整
 * ingest 链路去碰运气覆盖。抽成顶层 `internal` 纯函数后可以逐格钉死
 * （与 `resolveInitialType` 同一处理）。
 */
class IngestPipelineInheritanceTest {

    @Test
    fun `an unknown primary platform is filled from the absorbed record`() {
        // 主记录没识别出平台，被吸收那条有（例如账单导入的模糊行 + 美团通知）
        assertEquals(
            "meituan",
            inheritedPlatformId(
                primaryPlatformId = PlatformCatalog.UNKNOWN_ID,
                primarySource = PlatformSource.AUTO,
                absorbedPlatformId = "meituan",
            ),
        )
    }

    @Test
    fun `a lower ranked primary platform is upgraded to the absorbed one`() {
        // 银行卡(1) < 支付通道(2) < 下单平台(3)
        assertEquals(
            "meituan",
            inheritedPlatformId(PlatformCatalog.BANK_ID, PlatformSource.AUTO, "meituan"),
        )
        assertEquals(
            "alipay",
            inheritedPlatformId(PlatformCatalog.BANK_ID, PlatformSource.AUTO, "alipay"),
        )
    }

    @Test
    fun `a higher ranked primary platform is not downgraded`() {
        // 主记录已经是美团(ORDER)，被吸收的是银行卡 ⇒ 绝不能退回银行卡
        // （否则用户在账单里看到的平台会"越合并越差"）
        assertEquals(
            "meituan",
            inheritedPlatformId("meituan", PlatformSource.AUTO, PlatformCatalog.BANK_ID),
        )
    }

    @Test
    fun `a user chosen primary platform is never overwritten`() {
        // PlatformSource.USER 存在的全部意义：用户改过即权威，自动流程不得再动它。
        // 即便被吸收那条层级更高，也不许覆盖。
        assertEquals(
            PlatformCatalog.BANK_ID,
            inheritedPlatformId(PlatformCatalog.BANK_ID, PlatformSource.USER, "meituan"),
            "用户手选过平台的记录必须豁免自动继承",
        )
        assertEquals(
            "wechat",
            inheritedPlatformId("wechat", PlatformSource.USER, "meituan"),
        )
    }

    @Test
    fun `an identical platform is left untouched`() {
        assertEquals("meituan", inheritedPlatformId("meituan", PlatformSource.AUTO, "meituan"))
        assertEquals(
            PlatformCatalog.UNKNOWN_ID,
            inheritedPlatformId(PlatformCatalog.UNKNOWN_ID, PlatformSource.AUTO, PlatformCatalog.UNKNOWN_ID),
            "两边都是 unknown 时不该产生无意义的写入",
        )
    }

    @Test
    fun `an unregistered platform id is treated as the lowest tier`() {
        // 未收录 ID（旧备份 / 远端下发）按 NONE 处理：可以被更强的层级覆盖，绝不抛异常
        assertEquals("meituan", inheritedPlatformId("未收录", PlatformSource.AUTO, "meituan"))
        assertEquals(
            "未收录",
            inheritedPlatformId("未收录", PlatformSource.AUTO, PlatformCatalog.UNKNOWN_ID),
            "NONE ↔ NONE 都是最低层，保持原值（不写入）",
        )
    }
}
