package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * QA 独立复审（**必修⑤** 的证据）：**银行空商户 + 跨渠道 ⇒ 不能静默双记**。
 *
 * ## 缺陷链条（本批新发现 P2）
 * 一条银行消费常常同时到达两个渠道：银行短信（`sourceId=sms`）与银行 App 动账通知（`sourceId=notify`），
 * 二者都落同一条 `bank` 通道、商户**经常抽不出来**（银行短信尤其如此）。
 *  - **Tier-1**：`fingerprintOf` 在商户为空时退化成 `金额|blank|sourceId|sourceRef` ⇒
 *    `sourceId` / `sourceRef` 不同 ⇒ **跨渠道指纹必然不等** ⇒ Tier-1 永远查不到对方；
 *  - **Tier-2**：`bank↔bank` 当前判 REJECT ⇒ **连候选都不是** ⇒ 不合并。
 *  ⇒ 同一笔银行流水被记**两行**；若分类置信度达标，两条都会静默 CONFIRMED（**虚增支出**）。
 *
 * ## 必修⑤ 的裁决
 * 同一 `bank` 通道 + 跨采集来源 + 同金额 + 短窗 ⇒ **大概率是同一笔**（银行两个渠道都推送），
 * 但"同金额的两笔真实银行扣款"也不能排除 ⇒ 改判 **REVIEW**：浮出候选交用户，既**不静默合并**、也**不静默双记**。
 *
 * ## 与既有 `CrossChannelBankMergeTest` 的关系
 * 那份文件只在"两渠道都抽到**同一银行名**"（写死 `counterparty="工商银行"`）时成立，
 * 即指纹相同、走 Tier-1；它**覆盖不到空商户**这条更常见的路径 —— 本文件补这个盲区。
 *
 * ⚠️ 本文件在必修⑤ 落地前**预期失败**（红 = 缺陷证据），落地后转绿。
 */
class BankChannelDuplicateAuditTest {

    private val anchor = 1_700_000_000_000L
    private val bank = PlatformCatalog.BANK_ID

    private fun txn(
        id: String,
        sourceId: String,
        counterparty: String,
        occurredAt: Long = anchor,
        fingerprint: String = "",
    ) = LedgerTransaction(
        id = id,
        amountMinor = -8_800L,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = TxnStatus.RAW,
        fingerprint = fingerprint,
        platformId = bank,
    )

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    @Test
    fun `bank sms and bank app with blank merchants share no fingerprint (root cause)`() {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val fromSms = txn("sms", "sms", counterparty = "")
        val fromApp = txn("app", "notify", counterparty = "", occurredAt = anchor + 25_000L)
        assertNotEquals(
            probe.fingerprintOf(fromSms),
            probe.fingerprintOf(fromApp),
            "空商户指纹退化成含 sourceId/sourceRef ⇒ 跨渠道必然不等（这正是 Tier-1 查不到对方的根因）",
        )
    }

    /**
     * ⚠️ **预期失败**（必修⑤ 落地前）。
     * 同一银行通道 + 跨来源 + 同金额 + 短窗，商户都为空 ⇒ 至少要浮出候选（REVIEW），不得静默双记。
     */
    @Test
    fun `blank-merchant bank twin across channels must surface as a REVIEW candidate`() = runBlocking<Unit> {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val sms = txn("sms", "sms", counterparty = "").let { it.copy(fingerprint = probe.fingerprintOf(it)) }
        val app = txn("app", "notify", counterparty = "", occurredAt = anchor + 30_000L)
            .let { it.copy(fingerprint = probe.fingerprintOf(it)) }

        val r = resolver(sms)
        val dups = r.findDuplicates(app)

        assertTrue(
            dups.isNotEmpty(),
            "同一 bank 通道被短信与 App 两个来源抓到 ⇒ 必须至少浮出候选，否则同一笔银行流水静默双记（虚增支出）",
        )
        assertEquals(MatchTier.COMPLEMENTARY, dups.first().tier, "商户名不同 ⇒ 只能走 Tier-2 互补通道")
        assertFalse(
            r.canAutoMerge(app, dups.first()),
            "同通道的两笔银行扣款无法从证据上排除 ⇒ REVIEW 交用户，绝不是静默合并",
        )
    }

    /**
     * 反例护栏（应始终保持绿）：**同渠道同金额的两笔真实银行扣款**（相隔较久）仍不得被合并。
     * 时间窗口把"短窗内的疑似同一笔"与"隔得很久的两笔"区分开。
     */
    @Test
    fun `two bank charges of the same amount well outside the window are not candidates`() = runBlocking<Unit> {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val early = txn("sms", "sms", counterparty = "", occurredAt = anchor - 30 * 60 * 1000L)
            .let { it.copy(fingerprint = probe.fingerprintOf(it)) }
        val late = txn("app", "notify", counterparty = "", occurredAt = anchor)
            .let { it.copy(fingerprint = probe.fingerprintOf(it)) }

        val r = resolver(early)
        assertTrue(
            r.findDuplicates(late).isEmpty(),
            "超出 3 分钟窗口 ⇒ 不得作为候选（可能是两笔真实扣款）",
        )
    }
}
