package com.autoledger.feature.platform

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.platform.PlatformMatch
import com.autoledger.core.model.platform.PlatformResolution
import com.autoledger.core.model.platform.PlatformResolver

/**
 * 关键词 + 包名驱动的消费平台识别引擎（纯 JVM，无 Android / Room 依赖）。
 *
 * ## 在流水线中的位置
 *
 * ```
 * RawEnvelope(rawText, packageName)
 *    ↓ NotificationParser.parse()        ← 职责：这一串文本是不是一笔钱、多少钱、哪个商户（**不改**）
 *    ↓ KeywordPlatformResolver.resolve() ← 职责：这笔钱花在哪个平台（本类）
 *    ↓ IngestPipeline 装配
 * ```
 *
 * 之所以不塞进 `NotificationParser`：两者职责正交，且后者已有 8 条规则 × 6 平台 = 配置爆炸的问题。
 *
 * ## 判定原则
 *
 * 1. **宁可 unknown，不可瞎猜** —— 取不到确定信号时落 `unknown`，由 UI 打角标让用户确认，
 *    而不是硬塞一个看起来差不多的平台。
 * 2. **平台不参与去重指纹**（见 `LedgerDuplicateResolver`），因此本类的输出**不会影响去重行为**。
 * 3. **不因歧义而丢弃这笔账** —— 歧义只影响角标与候选展示，不影响是否入账。
 *
 * ## ⚠️ 「银行兜底」不需要单独写一条规则（防后人照着旧设计稿补死代码）
 *
 * 设计稿曾把「没有任何其它线索时落 `bank`」列为一个独立分支（在"全部未命中"的
 * `best.isEmpty()` 之前插入），但**那条分支永远走不到**：
 * `bank` 是目录里的普通条目（`weakKeywords = 储蓄卡/信用卡/借记卡/尾号/银行`，分 0.35），
 * 会被下面第 ① 步的通用打分循环正常打分；而"落 unknown"的分界是
 * `winner.score < UNKNOWN_THRESHOLD`，**0.35 < 0.35 为假** ⇒ 循环结束时已经返回 `bank` 了。
 *
 * 也就是说：**`bank` 兜底由「目录条目 + 第 ① 步打分循环 + 第 ③ 步阈值判断」天然实现**，
 * 它同时还天然满足「只有当没有任何更强线索时才轮到 bank」——
 * 任何 strong(0.90)/medium(0.60) 命中都会压过 bank(0.35)，无需额外的 `best.isEmpty()` 前提。
 *
 * 因此**不要再加那条分支**：它看起来是关键路径，实际是死的，
 * 只会让后人误以为"银行识别靠这条规则"，从而在改动时进错文件、改错地方。
 * 想调整银行识别的强弱，改 `PlatformCatalog` 里 `bank` 条目的关键词分档即可（R12：引擎里不得硬编码关键词）。
 */
class KeywordPlatformResolver : PlatformResolver {

    override val id: String = ID

    override fun resolve(ctx: PlatformContext): PlatformResolution {
        // ① 收集信号：包名（最强）→ 正文 → 商户名（可作弱/中线索）
        val pkg = ctx.packageName?.trim().orEmpty()
        val texts = buildList {
            ctx.rawText?.takeIf { it.isNotBlank() }?.let { add(TextSource.BODY to it) }
            ctx.counterparty?.takeIf { it.isNotBlank() }?.let { add(TextSource.COUNTERPARTY to it) }
        }

        // platformId → 该平台命中的最高分（同一平台多个信号只保留最强的一个）
        val best = LinkedHashMap<String, PlatformMatch>()

        for (entry in PlatformCatalog.all()) {
            if (entry.id == PlatformCatalog.UNKNOWN_ID) continue
            // 已停用的自定义平台**不再参与识别**（用户停用后不该再有新流水落到它上面）。
            // 注意：它仍在目录里，所以 find()/displayNameOf() 照样能查到 ——
            // 这是必须的：历史流水的平台名还要靠它显示，否则会塌成「未知平台」（R7）。
            if (entry.archived) continue

            if (pkg.isNotEmpty() && entry.packageNames.any { it.equals(pkg, ignoreCase = true) }) {
                keepBest(best, PlatformMatch(entry.id, SCORE_PACKAGE, "包名 $pkg"))
            }

            for ((source, text) in texts) {
                entry.strongKeywords.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
                    keepBest(best, PlatformMatch(entry.id, SCORE_STRONG, "命中强词「$it」${source.suffix}"))
                }
                entry.mediumKeywords.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
                    keepBest(best, PlatformMatch(entry.id, SCORE_MEDIUM, "命中中词「$it」${source.suffix}"))
                }
                entry.weakKeywords.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
                    keepBest(best, PlatformMatch(entry.id, SCORE_WEAK, "命中弱词「$it」${source.suffix}"))
                }
            }
        }

        // ② 无任何命中 ⇒ unknown
        if (best.isEmpty()) {
            return PlatformResolution(
                platformId = PlatformCatalog.UNKNOWN_ID,
                confidence = 0f,
                candidates = emptyList(),
                ambiguous = false,
            )
        }

        // ③ 排序：分数降序，同分按目录顺序（保证候选顺序稳定，不会每次刷新都跳）
        val ranked = best.values.sortedWith(
            compareByDescending<PlatformMatch> { it.score }
                .thenBy { PlatformCatalog.find(it.platformId)?.sortOrder ?: Int.MAX_VALUE },
        )
        val top1 = ranked.first()

        // 规则 1：包名是系统给的确认事实（这条通知由哪个 App 发出），且它赢了通常没有不确定的余地。
        // 现实里「美团 App 通知写着『已通过支付宝支付』」极常见 ——
        // 包名 0.95 与强词 0.90 只差 0.05，若照搬分差规则会误判歧义，
        // 于是每一笔美团订单都被打上「不确定」角标。
        //
        // 例外（关键）：**「发出通知的 App」不一定就是「消费发生的平台」**。
        // 淘宝下单的付款通知往往由**支付宝 App** 发出（包名 = alipay），
        // 若一律以包名压顶，「淘宝 / 美团」这些用户真正关心的下单平台将永不出现，
        // 「按消费平台统计」也就名存实亡。故：
        //   - 包名本身是**下单平台** ⇒ 维持最高优先（消费就发生在该平台）；
        //   - 包名是**支付通道**、且文本给出了下单平台 ⇒ 让下单平台胜出。
        val packageWinner = top1.takeIf { it.score >= SCORE_PACKAGE }
        if (packageWinner != null) {
            val orderFromText = ranked
                .filter { kindOf(it.platformId) == PlatformKind.ORDER }
                .filter { it.score < SCORE_PACKAGE } // 只看来自文本的下单平台信号
                .firstOrNull { it.score >= PlatformResolver.AMBIGUOUS_TOP_FLOOR }

            if (kindOf(packageWinner.platformId) == PlatformKind.PAYMENT && orderFromText != null) {
                val rest = ranked.filter { it.platformId != orderFromText.platformId }
                // 置信度上调到「确认」档：这笔消费发生在哪个平台其实是清楚的，不该打「待确认」角标
                return PlatformResolution(
                    platformId = orderFromText.platformId,
                    confidence = SCORE_ORDER_OVERRIDE,
                    candidates = topCandidates(listOf(orderFromText) + rest),
                    ambiguous = false,
                )
            }
            return PlatformResolution(packageWinner.platformId, packageWinner.score, topCandidates(ranked), ambiguous = false)
        }

        // 规则 2：无包名时，**下单平台优先于支付通道**。
        // 一次消费通常同时涉及两者（淘宝下单 + 支付宝付款），而支付通道的措辞更强、得分更高，
        // 不区分的话「淘宝 / 美团」这些用户真正关心的消费平台在账单里几乎永不出现。
        //
        // 护栏：下单平台必须至少是**中词强度**才配覆盖支付通道。
        // 否则「支付宝付款 88 元，收款方 阿里巴巴集团」这种弱线索（0.35）会盖掉确定的支付宝（0.90）。
        val orderHits = ranked.filter { kindOf(it.platformId) == PlatformKind.ORDER }
        val pool = if (orderHits.firstOrNull()?.let { it.score >= PlatformResolver.AMBIGUOUS_TOP_FLOOR } == true) {
            orderHits
        } else {
            ranked
        }

        val winner = pool.first()
        val runnerUp = pool.getOrNull(1)

        // 规则 3/4：只命中支付通道（线下扫码「支付宝付款 25.80 元」）⇒ 取支付通道；
        //           只命下单平台 ⇒ 取下单平台。上面 pool 的取值已同时覆盖这两种情况。
        val platformId = if (winner.score < PlatformResolver.UNKNOWN_THRESHOLD) {
            PlatformCatalog.UNKNOWN_ID
        } else {
            winner.platformId
        }

        // 歧义只在**同一角色内部**比较：淘宝 vs 支付宝是"下单平台 vs 通道"，有确定答案，不算歧义；
        // 抖音 vs 拼多多是两个都说得通的下单平台，才算歧义。
        val ambiguous = runnerUp != null &&
            (winner.score - runnerUp.score) < PlatformResolver.AMBIGUOUS_GAP &&
            runnerUp.score >= PlatformResolver.AMBIGUOUS_TOP_FLOOR

        return PlatformResolution(platformId, winner.score, topCandidates(pool), ambiguous)
    }

    /** 得分达到「真候选」门槛的前 [PlatformResolver.TOP_N] 个，降序。 */
    private fun topCandidates(pool: List<PlatformMatch>): List<PlatformMatch> =
        pool.filter { it.score >= PlatformResolver.AMBIGUOUS_TOP_FLOOR }
            .take(PlatformResolver.TOP_N)

    private fun kindOf(platformId: String): PlatformKind =
        PlatformCatalog.find(platformId)?.kind ?: PlatformKind.OTHER

    private fun keepBest(sink: MutableMap<String, PlatformMatch>, match: PlatformMatch) {
        val current = sink[match.platformId]
        if (current == null || match.score > current.score) sink[match.platformId] = match
    }

    private enum class TextSource(val suffix: String) {
        BODY(""),
        COUNTERPARTY("（商户名）"),
    }

    companion object {
        const val ID: String = "keyword_platform"

        /** 包名：系统给的确认事实，优先级最高。 */
        const val SCORE_PACKAGE: Float = 0.95f

        /** Tier A：平台自付渠道措辞。 */
        const val SCORE_STRONG: Float = 0.90f

        /** Tier B：可能是平台也可能是商户。 */
        const val SCORE_MEDIUM: Float = 0.60f

        /** Tier C：间接线索（如「财付通」是微信持牌主体，但也可能出现在银行短信对手方描述里）。 */
        const val SCORE_WEAK: Float = 0.35f

        /**
         * 「下单平台覆盖支付通道型包名」时的置信度。
         *
         * - 高于 [PlatformResolver.CONFIRM_THRESHOLD]（0.75）⇒ 不打「待确认」角标；
         * - 低于 [SCORE_PACKAGE]（0.95）⇒ 保留「包名仍是更强证据」的层级关系。
         */
        const val SCORE_ORDER_OVERRIDE: Float = 0.85f
    }
}
