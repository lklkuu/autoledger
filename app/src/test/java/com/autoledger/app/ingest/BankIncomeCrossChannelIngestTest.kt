package com.autoledger.app.ingest

import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.feature.capture.notify.NotificationParser
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 用户实测 Bug 1 + Bug 2 的**端到端**护栏（解析 → 入账 → 去重 → 合并）。
 *
 * 单模块单测只能各管一段：
 *  - `BankIncomeDirectionTest`（feature:capture）锁「两条文本都解析成 IN +505500，且抽出同一个银行名」；
 *  - `CrossChannelBankMergeTest`（feature:dedup）锁「同一个银行名 ⇒ 同一个指纹 ⇒ 合并」。
 *
 * 中间那一段**没人管**：如果哪天解析侧只改了一个渠道（例如短信抽出「工商银行」、
 * 动账通知抽出「中国工商银行」），两边单测都不会红，但用户看到的仍然是两条重复流水。
 * 本文件把两段接起来，用**用户截图的原文**跑完整条链路，堵住这个缝。
 */
class BankIncomeCrossChannelIngestTest {

    private val parser = NotificationParser()

    /** 银行短信（发件号 95588） */
    private val smsBody =
        "[3条]尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元，余额6,056.05元。【工商银行】"

    /** 工行 App 动账通知 */
    private val appBody =
        "尾号9783卡9月30日07:16工商银行收入(整整到期)5,055元。请点击查看详情。"

    private val anchor = 1_700_000_000_000L

    /**
     * 按 IngestPipeline 的口径把解析结果落成一条流水
     * （类型判定对应 `resolveInitialType`：显式类型 > 金额为负/缺失 → EXPENSE > 否则 INCOME）。
     */
    private fun toTxn(
        sourceId: String,
        sourceRef: String,
        occurredAt: Long,
        parsed: NotificationParser.ParseResult,
    ): LedgerTransaction {
        val amount = parsed.amountMinor
        return LedgerTransaction(
            id = sourceRef,
            amountMinor = amount ?: 0L,
            occurredAtMillis = occurredAt,
            type = if (amount == null || amount < 0L) TxnType.EXPENSE else TxnType.INCOME,
            counterparty = parsed.counterparty.orEmpty(),
            sourceId = sourceId,
            sourceRef = sourceRef,
            status = TxnStatus.RAW,
        )
    }

    // ------------------------------------------------------------------ Bug 1：方向

    @Test
    fun `both channels are booked as income of plus 5055 yuan`() {
        val sms = assertNotNull(parser.parse("sms:inbox", "95588", smsBody))
        val app = assertNotNull(parser.parse("com.icbc", "动账通知", appBody))
        for ((tag, txn) in listOf("短信" to toTxn("sms", "sms:42", anchor, sms), "动账通知" to toTxn("notify", "notify:k1", anchor, app))) {
            assertEquals(TxnType.INCOME, txn.type, "$tag 不得落成 EXPENSE")
            assertEquals(505_500L, txn.amountMinor, "$tag 金额必须是 +505500 分")
            assertTrue(txn.amountMinor > 0, "$tag 收入必须为正数")
        }
    }

    // ------------------------------------------------------------------ Bug 2：跨渠道合并

    @Test
    fun `the sms record and the notification record of one bank income merge into one`() = runBlocking {
        val smsParsed = assertNotNull(parser.parse("sms:inbox", "95588", smsBody))
        val appParsed = assertNotNull(parser.parse("com.icbc", "动账通知", appBody))

        val repo = InMemoryLedgerRepository()
        val resolver = LedgerDuplicateResolver(repo)

        // ① 银行短信先入库
        val first = toTxn("sms", "sms:42", anchor, smsParsed).let { it.copy(fingerprint = resolver.fingerprintOf(it)) }
        repo.upsert(first)

        // ② 35 秒后工行动账通知到达：不同 sourceId、不同 sourceRef、金额时间都相同
        val second = toTxn("notify", "notify:k1", anchor + 35_000L, appParsed)
            .let { it.copy(fingerprint = resolver.fingerprintOf(it)) }

        assertEquals(first.fingerprint, second.fingerprint, "两条跨渠道记录必须算出同一个指纹")
        assertTrue(resolver.isAutoMergeSafe(second), "信息足够可靠时必须允许自动合并")

        val dups = resolver.findDuplicates(second)
        assertEquals(1, dups.size, "必须判为重复，实际=${dups.size}")
        assertEquals(first.id, dups.first().txnId)
        assertTrue(dups.first().crossSource, "sms 与 notify 属于跨渠道")

        // ③ 合并
        repo.upsert(second)
        resolver.merge(dups.first().txnId, listOf(second.id))

        val alive = repo.snapshot().filter { it.status != TxnStatus.MERGED }
        assertEquals(1, alive.size, "合并后账本里只应剩一条这笔收入")
        assertEquals(505_500L, alive.single().amountMinor, "保留下来的必须是 +505500 分，不是 −505500")
        assertEquals(TxnStatus.CONFIRMED, repo.findById(first.id)!!.status)
        assertEquals(TxnStatus.MERGED, repo.findById(second.id)!!.status)
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 只实现本用例需要的三个方法（upsert / findByFingerprintNear / markStatus + findById）。
     *
     * 语义对齐 Room 实现：指纹 + 时间窗口 + 排除自身 + 排除已合并。
     * 其余方法在本用例里不会被调用，直接抛错以免被误当成真实实现。
     */
    private class InMemoryLedgerRepository : LedgerRepository {
        private val store = LinkedHashMap<String, LedgerTransaction>()

        fun snapshot(): List<LedgerTransaction> = store.values.toList()

        override suspend fun upsert(txn: LedgerTransaction) {
            store[txn.id] = txn
        }

        override suspend fun upsertAll(txns: List<LedgerTransaction>) {
            txns.forEach { store[it.id] = it }
        }

        override suspend fun delete(id: String) {
            store.remove(id)
        }

        override suspend fun findById(id: String): LedgerTransaction? = store[id]

        override suspend fun findByFingerprintNear(
            fingerprint: String,
            anchor: Long,
            windowMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.fingerprint == fingerprint &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                kotlin.math.abs(txn.occurredAtMillis - anchor) <= windowMillis
        }

        override suspend fun markStatus(id: String, status: TxnStatus) {
            store[id]?.let { store[id] = it.copy(status = status) }
        }

        /**
         * Tier-2 候选查询：金额带符号相等 + 时间窗口，排除自身与已合并/已忽略。
         * 与 Room 侧 `findByAmountWithin` 的 WHERE 子句逐条对应。
         */
        override suspend fun findByAmountWithin(
            amountMinor: Long,
            fromMillis: Long,
            toMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.amountMinor == amountMinor &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                txn.status != TxnStatus.IGNORED &&
                txn.occurredAtMillis in fromMillis..toMillis
        }.sortedByDescending { it.occurredAtMillis }

        /** 状态 + 溯源一次写入（与 Room 侧 updateMergeState 语义一致）。 */
        override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) {
            store[id]?.let { store[id] = it.copy(status = status, mergedIntoId = primaryId) }
        }

        override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
            store.values.filter { it.mergedIntoId == primaryId }.sortedByDescending { it.occurredAtMillis }

        override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> = unused()

        override suspend fun upsertUserPlatform(platform: UserPlatform) = unused()

        override suspend fun archiveUserPlatform(id: String) = unused()

        override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()

        override suspend fun listRange(
            fromMillis: Long,
            toMillis: Long,
            includeTransfers: Boolean,
        ): List<LedgerTransaction> = unused()

        override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> = unused()

        override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = unused()

        override suspend fun assignPlatform(id: String, platformId: String) = unused()

        override suspend fun listAccounts(): List<Account> = emptyList()

        override suspend fun listCategories(): List<Category> = emptyList()

        override suspend fun upsertCategory(category: Category) = unused()

        override suspend fun deleteCategory(id: String) = unused()

        override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeRawCount(): Flow<Int> = emptyFlow()

        override fun observeRaw(): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeRange(
            fromMillis: Long,
            toMillis: Long,
            includeTransfers: Boolean,
        ): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeCategories(): Flow<List<Category>> = emptyFlow()

        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

        private fun unused(): Nothing = error("本集成夹具未实现该方法")
    }
}
