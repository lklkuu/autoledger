package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 账单页搜索口径的纯 JVM 单测：商户 / **消费平台展示名** / 备注 / 金额。
 *
 * 锁两件事：
 * 1. 平台**展示名**可搜、内部 ID 不可搜（用户看到的是「微信」，不是 `wechat`）；
 * 2. 「未知」平台**不参与**文本搜索（否则搜「平台」「未知」会把全部未识别流水捞出来）。
 */
class LedgerQueryTest {

    private fun txn(
        counterparty: String = "瑞幸咖啡",
        note: String? = null,
        amountMinor: Long = -1_350L,
        platformId: String = "unknown",
    ) = LedgerTransaction(
        id = "t1",
        amountMinor = amountMinor,
        occurredAtMillis = 1_700_000_000_000L,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        note = note,
        sourceId = "notify",
        sourceRef = "notify:t1",
        fingerprint = "fp",
        status = TxnStatus.CONFIRMED,
        platformId = platformId,
    )

    @Test
    fun `matches the merchant name`() {
        assertTrue(matchesQuery(txn(counterparty = "瑞幸咖啡"), "瑞幸"))
        assertTrue(matchesQuery(txn(counterparty = "Luckin Coffee"), "luckin"), "拉丁商户名大小写不敏感")
        assertFalse(matchesQuery(txn(counterparty = "瑞幸咖啡"), "星巴克"))
    }

    @Test
    fun `matches the note`() {
        assertTrue(matchesQuery(txn(counterparty = "便利店", note = "给同事带的"), "同事"))
        assertFalse(matchesQuery(txn(counterparty = "便利店", note = null), "同事"), "备注为 null 不得炸")
    }

    @Test
    fun `matches the platform display name`() {
        assertTrue(
            matchesQuery(txn(counterparty = "早餐铺", platformId = "wechat"), "微信"),
            "平台展示名必须可搜 —— 列表上显示的就是它",
        )
        assertTrue(matchesQuery(txn(counterparty = "早餐铺", platformId = "meituan"), "美团"))
    }

    @Test
    fun `the internal platform id is NOT searchable`() {
        assertFalse(
            matchesQuery(txn(counterparty = "早餐铺", platformId = "wechat"), "wechat"),
            "内部 ID 不该被搜到：界面上从不出现它，搜到会让人以为匹配错了",
        )
    }

    @Test
    fun `an unrecognized platform is NOT matched by text`() {
        assertFalse(
            matchesQuery(txn(counterparty = "早餐铺", platformId = "unknown"), "未知"),
            "「未知」若可搜，输入「平台」就会捞出全部未识别流水 —— 筛这一类走 FilterChip",
        )
        assertFalse(
            matchesQuery(txn(counterparty = "早餐铺", platformId = "not-in-catalog"), "未知"),
            "未收录 ID 的兜底展示名同样含「未知」，一并挡掉，口径才一致",
        )
    }

    @Test
    fun `matches the amount as displayed in yuan`() {
        assertTrue(matchesQuery(txn(amountMinor = -1_350L), "13.5"), "分 → 元，与列表展示一致")
        assertTrue(matchesQuery(txn(amountMinor = 1_350L), "13.5"), "收入按绝对值搜，不看符号")
        assertFalse(matchesQuery(txn(amountMinor = -1_350L), "1350"), "内部存的是分，不该被搜到")
    }

    @Test
    fun `a blank query keeps everything`() {
        assertTrue(matchesQuery(txn(), ""))
        assertTrue(matchesQuery(txn(), "   "))
    }

    @Test
    fun `any single field hit is enough`() {
        // 商户与备注都不含「美团」，仅平台命中 ⇒ 仍应命中
        assertTrue(matchesQuery(txn(counterparty = "午餐", note = "工作餐", platformId = "meituan"), "美团"))
    }
}
