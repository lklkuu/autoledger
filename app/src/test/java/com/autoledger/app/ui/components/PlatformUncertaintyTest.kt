package com.autoledger.app.ui.components

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「消费平台是否需要打「?`」提示的判定护栏。
 *
 * 背景（v1.1.7 修的问题）：用户在账单列表看到「银行卡?」，末尾那个 `?` 让人以为数据有问题。
 * 实际上 `?` 是**置信度不足**的提示，而 `bank`（银行卡）是**兜底归类**而非识别结果 ——
 * 银行侧通知本来就不含平台标识，只能靠弱关键词（"尾号"/"银行"，0.35 分）落到它，
 * 必然低于 0.75 阈值，于是每条银行卡流水都被标成"不确定"。
 *
 * 这些用例同时防止**反向回归**：真正的不确定（美团 vs 拼多多争胜）必须继续打「?」，
 * 否则用户失去了"该去确认哪一笔"的线索。
 */
class PlatformUncertaintyTest {

    private fun txn(
        platformId: String,
        source: PlatformSource,
        confidence: Float,
    ): LedgerTransaction = LedgerTransaction(
        id = "t1",
        amountMinor = -1000L,
        occurredAtMillis = 1_700_000_000_000L,
        type = TxnType.EXPENSE,
        status = TxnStatus.CONFIRMED,
        counterparty = "某商户",
        categoryId = "cat_food",
        sourceId = "notify",
        sourceRef = "key-1",
        platformId = platformId,
        platformConfidence = confidence,
        platformSource = source,
    )

    @Test
    fun `兜底归类的银行卡不再显示问号`() {
        // 银行侧通知只能靠 weak 关键词命中，置信度必然低于阈值 —— 但它不是"识别不确定"
        assertFalse(isPlatformUncertain(txn(PlatformCatalog.BANK_ID, PlatformSource.AUTO, 0.35f)))
    }

    @Test
    fun `真实识别不确定的平台仍然要显示问号`() {
        // 这是本次修复最需要防的回归：不能把"不确定提示"整体关掉
        assertTrue(isPlatformUncertain(txn("meituan", PlatformSource.AUTO, 0.5f)))
        assertTrue(isPlatformUncertain(txn("pdd", PlatformSource.AUTO, 0.6f)))
    }

    @Test
    fun `置信度达标时不显示问号`() {
        assertFalse(isPlatformUncertain(txn("meituan", PlatformSource.AUTO, 0.95f)))
        assertFalse(
            isPlatformUncertain(
                txn("meituan", PlatformSource.AUTO, PlatformResolver.CONFIRM_THRESHOLD)
            )
        )
    }

    @Test
    fun `用户手动指定过平台时不显示问号`() {
        // USER 源是权威值，自动识别的置信度已无关紧要
        assertFalse(isPlatformUncertain(txn("meituan", PlatformSource.USER, 0.2f)))
        assertFalse(isPlatformUncertain(txn(PlatformCatalog.BANK_ID, PlatformSource.USER, 0.1f)))
    }
}
