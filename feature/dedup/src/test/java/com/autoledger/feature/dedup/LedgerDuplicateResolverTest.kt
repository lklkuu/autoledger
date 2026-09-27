package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 跨渠道去重 —— 纯 JVM 单元测试。
 *
 * 这是「同一杯咖啡不会被记成两笔」的唯一保障，也是最容易误伤真实消费的地方。
 */
class LedgerDuplicateResolverTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        amountMinor: Long,
        counterparty: String,
        occurredAt: Long = anchor,
        sourceId: String = "notify",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "",
        type: TxnType = TxnType.EXPENSE,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = type,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
    )

    private fun resolver(vararg existing: LedgerTransaction): Pair<LedgerDuplicateResolver, FakeLedgerRepository> {
        val repo = FakeLedgerRepository(existing.toList())
        return LedgerDuplicateResolver(repo) to repo
    }

    // ------------------------------------------------------------ 指纹

    @Test
    fun `fingerprint is stable for same amount and merchant`() {
        val (r, _) = resolver()
        val a = txn("a", -2500, "星巴克")
        val b = txn("b", -2500, "星巴克")
        assertEquals(r.fingerprintOf(a), r.fingerprintOf(b))
    }

    @Test
    fun `fingerprint ignores parentheses decoration case and symbols`() {
        val (r, _) = resolver()
        val plain = txn("a", -2500, "星巴克")
        val decorated = txn("b", -2500, "星巴克（南京西路店）")
        val spaced = txn("c", -2500, " 星巴克 ")
        val latin = txn("d", -2500, "Starbucks")
        assertEquals(r.fingerprintOf(plain), r.fingerprintOf(decorated))
        assertEquals(r.fingerprintOf(plain), r.fingerprintOf(spaced))
        assertEquals(r.fingerprintOf(latin), r.fingerprintOf(txn("e", -2500, "STARBUCKS")))
    }

    @Test
    fun `fingerprint differs by amount`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", -2501, "星巴克")),
        )
    }

    @Test
    fun `fingerprint differs by merchant`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", -2500, "瑞幸")),
        )
    }

    @Test
    fun `fingerprint sign matters so refund is not merged with payment`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", 2500, "星巴克")),
        )
    }

    @Test
    fun `fingerprint is a 64 char lowercase hex digest`() {
        val (r, _) = resolver()
        val fp = r.fingerprintOf(txn("a", -2500, "星巴克"))
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' }, "指纹必须是小写 hex")
    }

    // ------------------------------------------------------------ findDuplicates

    @Test
    fun `finds cross channel duplicate inside window`() = runBlocking {
        val existing = txn("old", -2500, "星巴克", sourceId = "sms").let {
            it.copy(fingerprint = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(it))
        }
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").let {
            it.copy(fingerprint = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(it))
        }
        val (r, _) = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertEquals("old", dups.first().txnId)
    }

    @Test
    fun `ignores candidates outside time window`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", -2500, "星巴克"))
        val old = txn("old", -2500, "星巴克", occurredAt = anchor - 30 * 60 * 1000L, sourceId = "sms").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(old)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "超过 3 分钟窗口不应判重")
    }

    @Test
    fun `never returns the transaction itself`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("self", -2500, "星巴克"))
        val self = txn("self", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(self)
        assertTrue(r.findDuplicates(self).isEmpty())
    }

    @Test
    fun `skips already merged records`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", -2500, "星巴克"))
        val merged = txn("merged", -2500, "星巴克", status = TxnStatus.MERGED).copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(merged)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "已合并的记录不应再次参与判重")
    }

    @Test
    fun `cross channel duplicate ranks above same channel one`() = runBlocking {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val fp = probe.fingerprintOf(txn("x", -2500, "星巴克"))
        val sameChannel = txn("same", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val crossChannel = txn("cross", -2500, "星巴克", sourceId = "sms").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val (r, _) = resolver(sameChannel, crossChannel)
        val dups = r.findDuplicates(incoming)
        assertEquals(2, dups.size)
        assertEquals("cross", dups.first().txnId, "跨渠道候选必须排在前面")
        assertTrue(dups.first().score > dups.last().score)
    }

    @Test
    fun `finds nothing when repository is empty`() = runBlocking {
        val (r, _) = resolver()
        assertTrue(r.findDuplicates(txn("new", -2500, "星巴克")).isEmpty())
    }

    @Test
    fun `same channel same amount within window is flagged non-cross-source`() = runBlocking {
        // 同一商家、同金额、1 分钟内连续消费多次：指纹相同，但必须标记为「同渠道」，
        // 让 IngestPipeline 降级到待确认，而不是静默自动合并（否则真实消费会被吞）。
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val fp = probe.fingerprintOf(txn("x", -2500, "星巴克"))
        val existing = txn("old", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val (r, _) = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertEquals(false, dups.first().crossSource)
    }

    // ------------------------------------------------------------ merge / unmerge

    @Test
    fun `merge marks duplicates MERGED and primary CONFIRMED`() = runBlocking {
        val primary = txn("p", -2500, "星巴克", status = TxnStatus.RAW)
        val dup = txn("d", -2500, "星巴克", status = TxnStatus.RAW)
        val (r, repo) = resolver(primary, dup)
        r.merge("p", listOf("d"))
        assertEquals(TxnStatus.MERGED, repo.findById("d")!!.status)
        assertEquals(TxnStatus.CONFIRMED, repo.findById("p")!!.status)
    }

    @Test
    fun `unmerge restores RAW for manual review`() = runBlocking {
        val dup = txn("d", -2500, "星巴克", status = TxnStatus.MERGED)
        val (r, repo) = resolver(dup)
        r.unmerge("d")
        assertEquals(TxnStatus.RAW, repo.findById("d")!!.status)
    }

    @Test
    fun `resolver id is stable`() {
        val (r, _) = resolver()
        assertEquals("ledger_dup", r.id)
        assertEquals(LedgerDuplicateResolver.RESOLVER_ID, r.id)
    }

    // ------------------------------------------------------------ RED 用例（已知缺陷）

    @Test
    fun `Red_blank merchant must not collapse every same amount txn into one fingerprint`() {
        // 缺陷 LedgerDuplicateResolver.kt:31 —— 指纹只有 hash(金额|归一化商户)。
        // 商户名为空（通知/短信解析失败的常见结果）时，同金额的所有流水指纹完全相同，
        // 3 分钟窗口内会被 IngestPipeline 自动合并，真实消费被静默吞掉。
        val (r, _) = resolver()
        val coffee = txn("a", -2500, "", sourceId = "notify")
        val taxi = txn("b", -2500, "", sourceId = "notify")
        assertNotEquals(
            r.fingerprintOf(coffee),
            r.fingerprintOf(taxi),
            "商户名为空时必须引入 sourceRef 等第二信号，不能让所有同金额流水同指纹",
        )
    }
}
