package com.autoledger.feature.dedup

import com.autoledger.core.model.DuplicateCandidate
import com.autoledger.core.model.DuplicateResolver
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.platform.priorityOf
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
 * ## 两级匹配
 * - **Tier-1 指纹精确**（本条的老路）：商户名一致 ⇒ 判重同时长窗口。同一平台被重复抓取、
 *   或银行短信与通知恰好同名商户，都走这条。
 * - **Tier-2 层级互补**（新增）：商户名**不同**但描述同一笔（美团通知「美团外卖」↔ 银行短信「财付通」）。
 *   因为商户名不同 ⇒ 指纹必然不同 ⇒ Tier-1 永远查不到，所以必须另开一条按
 *   「同金额 + 时间窗口 + 跨 source + 平台层级互补」匹配的通道，见 [complementaryVerdict]。
 *   **只在 Tier-1 未命中时才执行** ⇒ 不增加既有热路径成本，也不构成 O(n²)。
 *
 * 边界与取舍：
 * - 同一家店、同金额、3 分钟内的**两笔真实消费**指纹相同，无法从指纹本身区分；
 *   因此只对「跨渠道」候选自动合并，同渠道候选降级为待确认，交由用户判断，
 *   宁可多一步确认，也不静默吞掉真实消费。
 * - Tier-2 的护栏比 Tier-1 更严（层级必须互补），理由同上：同一家店 3 分钟内两笔真实消费
 *   在 Tier-2 里是 `ORDER ↔ ORDER` 或同 tier ⇒ [ComplementaryVerdict.REJECT]，连候选都不是。
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
        // Tier-1：直接走数据库指纹索引查询，避免历史账单逐笔导入时形成 O(n²) 全表扫描。
        val candidates = repository.findByFingerprintNear(
            fingerprint = txn.fingerprint,
            anchor = txn.occurredAtMillis,
            windowMillis = windowMillis,
            excludeId = txn.id,
        )
        val precise = candidates.mapNotNull { other -> tierOneCandidate(txn, other) }
            .sortedByDescending { it.score }

        // Tier-1 命中就够了：指纹一致是比层级互补更强的证据，不必再去扫同金额窗口。
        if (precise.isNotEmpty()) return precise

        return tierTwoCandidates(txn)
    }

    /** Tier-1 候选：指纹精确命中，且时间漂移在窗口内。 */
    private fun tierOneCandidate(txn: LedgerTransaction, other: LedgerTransaction): DuplicateCandidate? {
        if (other.status == TxnStatus.MERGED) return null
        val drift = kotlin.math.abs(other.occurredAtMillis - txn.occurredAtMillis)
        if (drift > windowMillis) return null
        return DuplicateCandidate(
            txnId = other.id,
            score = scoreOf(drift, other.sourceId == txn.sourceId),
            crossSource = other.sourceId != txn.sourceId,
            platformId = other.platformId,
            priorityRank = priorityOf(other.platformId).rank,
            platformSource = other.platformSource,
            tier = MatchTier.FINGERPRINT,
            // 原始商户名与来源带进候选：前者供「不同门店」护栏用（normalize 抹了括号，候选必须保留原文），
            // 后者供 Tier-2 的「权威来源」护栏用（Tier-1 也会填，保持一致）。
            sourceId = other.sourceId,
            counterparty = other.counterparty,
        )
    }

    /**
     * Tier-2 候选：同金额 + 时间窗口 + **跨 source** + 层级不为 [ComplementaryVerdict.REJECT]。
     *
     * 时间窗口用**对称**的 ±[windowMillis]：不要求「同一天」——
     * 23:59:30 的微信通知与 00:00:10 的银行短信是同一笔，要求同天会漏合并。
     *
     * 金额是**带符号**比较的（`amountMinor` 原值），这天然挡住「退款(+88) 与原单(−88)」被合并。
     */
    private suspend fun tierTwoCandidates(txn: LedgerTransaction): List<DuplicateCandidate> {
        // 金额未解析出（0）时没有任何可靠锚点，绝不据此合并 —— 与 isAutoMergeSafe 的既有口径一致。
        if (txn.amountMinor == 0L) return emptyList()

        val others = repository.findByAmountWithin(
            amountMinor = txn.amountMinor,
            fromMillis = txn.occurredAtMillis - windowMillis,
            toMillis = txn.occurredAtMillis + windowMillis,
            excludeId = txn.id,
        )

        return others.mapNotNull { other ->
            // 仓储契约已排除 MERGED / IGNORED 与自身；这里再核一遍，
            // 因为「被吸收的记录不得再当候选」是不能只靠一层的硬约束（见设计 §4.6）。
            if (other.status == TxnStatus.MERGED || other.status == TxnStatus.IGNORED) return@mapNotNull null
            // 必须跨渠道：同渠道同金额更像两笔真实消费，交给 Tier-1 / 人工。
            if (other.sourceId == txn.sourceId) return@mapNotNull null
            // 护栏：层级不互补的**连候选都不是**（这是防误合并的关键，见 ComplementaryMatch）。
            if (complementaryVerdict(txn.platformId, other.platformId) == ComplementaryVerdict.REJECT) {
                return@mapNotNull null
            }
            DuplicateCandidate(
                txnId = other.id,
                // 可信但低于精确指纹：Tier-1 的分档在 0~100，这里固定 50 作为"中级可信"。
                score = TIER_TWO_SCORE,
                crossSource = true,
                platformId = other.platformId,
                priorityRank = priorityOf(other.platformId).rank,
                platformSource = other.platformSource,
                tier = MatchTier.COMPLEMENTARY,
                // 权威来源护栏要用候选的 sourceId（unknown 一侧若是手工录入 / 账单导入 ⇒ 不得静默吸收）。
                sourceId = other.sourceId,
                counterparty = other.counterparty,
            )
        }.sortedByDescending { it.score }
    }

    /**
     * 该候选能否被**静默**自动合并（护栏的最终出口）。
     *
     * Tier-1 沿用既有保守语义（跨渠道 + 商户非空 + 金额非 0）。
     *
     * Tier-2 **刻意不要求商户非空**：`isAutoMergeSafe` 里「商户为空 ⇒ 不自动合并」
     * 是为**指纹退化**设的 —— 商户空时指纹退化成 `金额|blank|sourceId|sourceRef`，
     * 跨渠道必然不等，拿它当依据等于盲猜（这正是历史 Bug 2 的根因）。
     * Tier-2 **不依赖指纹**，依据是「层级互补 + 同金额 + 3 分钟内 + 跨渠道」，
     * 这比"商户名恰好相同"更强。反而银行短信本来就常常抽不出商户名（见设计 §4.5：
     * Tier-2 的主要价值就是让主记录补上真实商户），要求非空会让需求场景整条失效。
     */
    override fun canAutoMerge(txn: LedgerTransaction, candidate: DuplicateCandidate): Boolean =
        when (candidate.tier) {
            MatchTier.FINGERPRINT ->
                candidate.crossSource &&
                    isAutoMergeSafe(txn) &&
                    // ⚠️ 层级护栏（P0-1）：Tier-1 曾经**完全不看层级**，
                    // 使「微信通知 + 支付宝通知」（同店同金额）被静默合并 —— 那是两笔真实消费。
                    // 见 [tierOneAllowsAutoMerge]：只拒绝「同层级且不同通道」，
                    // 保留「同一条银行通道被重复抓取 ⇒ 合并」（Bug 2 的核心能力）。
                    tierOneAllowsAutoMerge(txn.platformId, candidate.platformId) &&
                    // ⚠️ 门店护栏（P0-2）：`normalize()` 抹括号 ⇒「中石化(朝阳站)」与「中石化(海淀站)」
                    // 指纹相同；两侧层级不同（微信通知 = PAYMENT / 银行短信 = BANK）时，上面那条层级护栏
                    // 会放行 ⇒ 两笔真实消费被吞。见 [branchSuffixesConflict]：仅当**两侧原始商户名
                    // 都带门店信息且不同**时才拒绝（降级待确认，候选仍浮出）。
                    !branchSuffixesConflict(txn.counterparty, candidate.counterparty)
            MatchTier.COMPLEMENTARY ->
                txn.amountMinor != 0L &&
                    candidate.crossSource &&
                    complementaryVerdict(txn.platformId, candidate.platformId) == ComplementaryVerdict.AUTO_MERGE &&
                    // ⚠️ 权威来源护栏（P0-4）：`unknown` 一侧若是「手工录入 / 账单导入」，
                    // 它只是**没识别出平台**，并不代表是某笔订单的银行侧 ⇒ 不得被静默吸收。
                    // 见 [noneSideIsAuthoritative]。
                    !noneSideIsAuthoritative(
                        txn.platformId,
                        txn.sourceId,
                        candidate.platformId,
                        candidate.sourceId,
                    )
        }

    /**
     * 合并：把 [duplicateIds] 吸收进 [primaryId]。
     *
     * 用 [LedgerRepository.setMergeState] 而不是裸 [LedgerRepository.markStatus]：
     * 状态（MERGED）与溯源（`mergedIntoId`）必须**一次写入**，
     * 否则会留下「置了 MERGED 但查不出被谁吸收」的中间态 —— 那一行既不在账单里，也无法撤销。
     */
    override suspend fun merge(primaryId: String, duplicateIds: List<String>) {
        duplicateIds.forEach { repository.setMergeState(it, TxnStatus.MERGED, primaryId) }
        repository.markStatus(primaryId, TxnStatus.CONFIRMED)
    }

    /**
     * 撤销合并：被吸收的记录恢复成待确认 + **清空溯源**。
     *
     * 刻意**不**回滚继承到主记录的字段（设计 §4.6）：用户在合并之后可能又编辑过主记录，
     * 自动回滚会覆盖他的新编辑。UI 负责提示「主记录的商户/平台可能仍含继承值，请核对」。
     */
    suspend fun unmerge(txnId: String) = repository.setMergeState(txnId, TxnStatus.RAW, null)

    private fun scoreOf(driftMillis: Long, sameSource: Boolean): Int {
        // 时间越近越像重复；跨渠道的重复比同渠道的更值得警惕（真正的一鱼两吃）
        val timeScore = (100 - (driftMillis * 100 / windowMillis).toInt()).coerceIn(0, 100)
        return if (sameSource) timeScore / 2 else timeScore
    }

    /**
     * 商户名归一化：抹掉括号门店后缀、标点与大小写差异，取前 32 字符。
     *
     * **为什么要抹括号内容**（勿删）：同笔交易的两个渠道常常一个带门店、一个不带 ——
     * 银行短信写「星巴克(国贸店)」、微信通知写「星巴克」。不抹括号，这两条会被算成
     * 两个不同的指纹 ⇒ **同一笔永远合并不了**（这正是 Tier-1 的核心价值）。
     *
     * **已知边界（抹括号的副作用，已由 [branchSuffixesConflict] 兜住，本函数仍不改）**：
     * 抹括号会把「同一品牌的两个不同门店、同金额、3 分钟内」的两笔**真实消费**算成同一指纹。
     * 原先以为「层级护栏能兜住（两侧通常同层级或都是 unknown）」—— 这个理由**是错的**：
     * 微信支付通知（`PAYMENT`）「中石化(朝阳站)」+ 银行 POS 短信（`BANK`）「中石化(海淀站)」
     * 层级不同 ⇒ [tierOneAllowsAutoMerge] 会放行 ⇒ 吞账。
     * 因此自动合并出口处另加了 [branchSuffixesConflict]：**两侧原始商户名的括号内容都非空且不同
     * ⇒ 不自动合并**（降级待确认）。它**不能**靠「不抹括号」实现 —— 抹括号正是 Tier-1 的核心能力
     * （银行短信「星巴克(国贸店)」要能匹配微信通知「星巴克」），所以本函数一个字都不改。
     * 「宁可多一步确认，也不静默吞掉真实消费」是本模块一贯口径。
     */
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

        /**
         * Tier-2 候选的固定分。
         *
         * 语义：**可信但低于精确指纹**。Tier-1 的分数是 0~100 的时间接近度，
         * Tier-2 没有"时间有多近"这个维度可用（它靠的是层级互补这个定性证据），
         * 故取区间中位 50，与 Tier-1 的分数处在同一量纲里，便于 UI 排序时两者可比。
         */
        const val TIER_TWO_SCORE = 50
    }
}
