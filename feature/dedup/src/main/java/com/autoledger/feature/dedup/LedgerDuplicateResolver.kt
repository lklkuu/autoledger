package com.autoledger.feature.dedup

import com.autoledger.core.model.DuplicateCandidate
import com.autoledger.core.model.DuplicateResolver
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import java.security.MessageDigest

/**
 * 跨渠道去重。
 *
 * 典型案例：一次微信支付会同时产生
 *  ① 微信「支付成功」通知 ② 银行卡「消费短信」——两条渠道都能抓到，
 * 若不处理，同一杯咖啡会被记成两笔。
 *
 * 指纹 = hash(金额 + 归一化商户名)，**刻意不含时间**：
 * 两个渠道的时间戳可能差几十秒甚至跨分钟，放进指纹反而会漏判。
 * 时间作为第二道过滤条件用在查询窗口里。
 *
 * 边界与取舍：
 * - 同一家店、同金额、3 分钟内的**两笔真实消费**指纹相同，无法从指纹本身区分；
 *   因此只对「跨渠道」候选自动合并，同渠道候选降级为待确认，交由用户判断，
 *   宁可多一步确认，也不静默吞掉真实消费。
 */
class LedgerDuplicateResolver(
    private val repository: LedgerRepository,
    private val windowMillis: Long = DEFAULT_WINDOW,
) : DuplicateResolver {

    override val id: String = RESOLVER_ID

    override fun fingerprintOf(txn: LedgerTransaction): String {
        val entity = normalize(txn.counterparty)
        // 商户缺失时纳入渠道侧唯一标识，宁可漏判也不能误吞同金额真实消费。
        val fingerprintMaterial = if (entity.isBlank()) {
            "${txn.amountMinor}|blank|${txn.sourceId}|${txn.sourceRef}"
        } else {
            "${txn.amountMinor}|$entity"
        }
        return sha256(fingerprintMaterial)
    }

    override fun isAutoMergeSafe(txn: LedgerTransaction): Boolean =
        txn.amountMinor != 0L && normalize(txn.counterparty).isNotBlank()

    override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> {
        // 直接走数据库指纹索引查询，避免历史账单逐笔导入时形成 O(n²) 全表扫描。
        val candidates = repository.findByFingerprintNear(
            fingerprint = txn.fingerprint,
            anchor = txn.occurredAtMillis,
            windowMillis = windowMillis,
            excludeId = txn.id,
        )
        return candidates.mapNotNull { other ->
            if (other.status == TxnStatus.MERGED) return@mapNotNull null
            val drift = kotlin.math.abs(other.occurredAtMillis - txn.occurredAtMillis)
            if (drift > windowMillis) return@mapNotNull null
            DuplicateCandidate(
                txnId = other.id,
                score = scoreOf(drift, other.sourceId == txn.sourceId),
                crossSource = other.sourceId != txn.sourceId,
            )
        }.sortedByDescending { it.score }
    }

    override suspend fun merge(primaryId: String, duplicateIds: List<String>) {
        duplicateIds.forEach { repository.markStatus(it, TxnStatus.MERGED) }
        repository.markStatus(primaryId, TxnStatus.CONFIRMED)
    }

    /** 撤销合并：把被合并的记录恢复成待确认 */
    suspend fun unmerge(txnId: String) = repository.markStatus(txnId, TxnStatus.RAW)

    private fun scoreOf(driftMillis: Long, sameSource: Boolean): Int {
        // 时间越近越像重复；跨渠道的重复比同渠道的更值得警惕（真正的一鱼两吃）
        val timeScore = (100 - (driftMillis * 100 / windowMillis).toInt()).coerceIn(0, 100)
        return if (sameSource) timeScore / 2 else timeScore
    }

    private fun normalize(name: String): String = name
        .replace(Regex("""[（(].*?[)）]"""), "")
        .replace(Regex("""[^\p{L}\p{N}]"""), "")
        .lowercase()
        .take(32)

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val RESOLVER_ID = "ledger_dup"
        const val DEFAULT_WINDOW = 3L * 60 * 1000L
    }
}
