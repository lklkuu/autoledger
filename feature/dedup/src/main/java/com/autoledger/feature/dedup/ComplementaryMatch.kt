package com.autoledger.feature.dedup

import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf

/**
 * **Tier-2 层级互补匹配的护栏**（设计 §4.2 的判定表，唯一真源）。
 *
 * ## 为什么需要 Tier-2 这条新通道
 * Tier-1 指纹 = `sha256(金额 | 归一化商户)`。而需求场景里两条记录的**商户名天然不同**：
 *
 * | 来源 | 原始商户 | 归一化后 |
 * |---|---|---|
 * | 美团 App 通知 | `美团外卖` | `美团外卖` |
 * | 银行扣款短信 | `财付通` | `财付通` |
 *
 * ⇒ 指纹不同 ⇒ `findByFingerprintNear` 根本查不到对方 ⇒ **平台优先级永远没机会执行**。
 * 必须补一条「同金额 + 时间窗口 + 层级互补」的通道
 * （**不再要求跨 source** —— 同一笔支付的多条通知常来自同一条 `notify` 渠道，见设计文档 §10-⑨）。
 *
 * ## 三态而不是布尔
 * 判定表的第三行「`PAYMENT ↔ BANK`」不是简单的「不合」而是「**证据不足，交给用户**」：
 * 「微信支付 88」+「银行卡扣 88」**可能是同一笔**（微信绑的就是这张卡），
 * **也可能真是两笔**（先充值、再消费）。所以它要作为候选浮出来提示用户，
 * 只是不能由系统**静默**合并。用一个布尔表达不了这个区别。
 *
 * ## 为什么这条判定必须是纯函数
 * 它决定「两条记录会不会被合成一条」。写松一点就是**静默吞掉真实消费**（设计 R2）。
 * 抽成纯函数后，判定表能被逐格单测钉死（含全部 `4 × 4` 组合），
 * 不必为每一格都造一遍完整 ingest 链路。
 */
enum class ComplementaryVerdict {
    /** 层级互补且证据充分 —— 允许**静默自动合并**。 */
    AUTO_MERGE,

    /** 层级互补但证据不足（唯一真实歧义的组合）—— 作为候选浮出，**等用户确认**。 */
    REVIEW,

    /** 层级不互补 —— **连候选都不是**，不得参与合并（防「同店同金额两笔真实消费」被吞）。 */
    REJECT,
}

/**
 * 按**平台层级**判定两条同金额记录能否互补合并。
 *
 * 记 `tier(p) = priorityOf(p)`：`ORDER(4) > PAYMENT(3) > E_WALLET(2) > BANK(1) > NONE(0)`。
 *
 * | 组合 | 判定 | 理由 |
 * |---|---|---|
 * | 恰好一侧 `ORDER`（`ORDER↔PAYMENT` / `ORDER↔E_WALLET` / `ORDER↔BANK` / `ORDER↔NONE`） | [AUTO_MERGE] | 消费场所 + 资金通道，一笔消费的上下游 —— **正是需求要的场景** |
 * | `PAYMENT ↔ E_WALLET` | [REVIEW] | 一笔消费只走一个支付通道（两者互斥），但保守起见浮出给用户 |
 * | `PAYMENT ↔ BANK` | [REVIEW] | 唯一真实歧义组合（微信绑的就是这张卡？还是先充值再消费？），交用户 |
 * | `E_WALLET ↔ BANK` | [REVIEW] | **用户拍板**：数币 / 云闪付与银行卡边界模糊，宁可保守，也不静默吞掉真实消费 |
 * | `PAYMENT ↔ NONE` / `E_WALLET ↔ NONE` / `BANK ↔ NONE` | [REJECT] | 保守：unknown 那条可能什么都没识别出，没有互补证据 |
 * | 同层级 + **同 id**（`wechat↔wechat` / `digital_rmb↔digital_rmb` / `bank↔bank`） | [REVIEW] | 同一类通道被重复抓取 ⇒ 疑似同一笔，但无法排除「同金额两笔」⇒ 交用户 |
 * | 同层级 + **不同 id**（`wechat↔alipay` / `digital_rmb↔unionpay` / `meituan↔taobao`） | [REJECT] | 一次消费只有一个通道 / 两个消费场所 = 两笔真实消费 ⇒ **连候选都不是** |
 * | `NONE ↔ NONE` | [REJECT] | 无层级信息，不合并 |
 *
 * ## 判定方针：「**能 REVIEW 就别 REJECT**」（用户拍板）
 * REJECT = 连候选都不给，用户**根本看不到**这条重复；REVIEW = 至少浮出来让用户裁决。
 * 因此凡「可能是同一笔、也可能是两笔」的格子一律取 REVIEW —— 即使理论上互斥：
 * `PAYMENT ↔ E_WALLET`（两者都是"钱从某通道出去"）与 `E_WALLET ↔ BANK`（本次需求的核心一格）
 * 都取 REVIEW 而非 REJECT，让用户有机会发现漏网。与项目既有取舍一致
 * （「宁可多一步确认，也不静默吞掉真实消费」）。
 *
 * ## 为什么这条判定必须是纯函数
 * 它决定「两条记录会不会被合成一条」。写松一点就是**静默吞掉真实消费**（设计 R2）。
 * 抽成纯函数后，判定表能被逐格单测钉死（[com.autoledger.feature.dedup.TierTwoVerdictMatrixTest]），
 * 不必为每一格都造一遍完整 ingest 链路。
 *
 * ## 实现结构（4 条分支覆盖全表，无"某一格被写反"的空间）
 * ① 恰好一侧 `ORDER` ⇒ [AUTO_MERGE]；② 任一侧 `NONE` ⇒ [REJECT]；
 * ③ 两侧不同层级（都在 `{PAYMENT, E_WALLET, BANK}`）⇒ [REVIEW]；
 * ④ 同层级：同 id ⇒ [REVIEW]，不同 id ⇒ [REJECT]。
 *
 * ## ⚠️ 与 Tier-1 [tierOneAllowsAutoMerge] 对**同一组合**的判定**可以不同**（有意为之，勿"统一"）
 * 分层的**唯一尺度是证据强度**：Tier-1 要求商户名**完全相同**（证据强，同一条通道的两条记录
 * 商户名一致）⇒ 可以自动合并；Tier-2 的前提是商户名**不同或为空**（证据弱）⇒ 保守浮出候选。
 * 例：`E_WALLET ↔ BANK` 在 Tier-1 放行（同商户名 = 同一条支付的两面），在 Tier-2 为 [REVIEW]。
 * 把两者"统一"要么丢能力（Tier-1 退化），要么吞账（Tier-2 变静默合并）。**这不是 bug。**
 */
fun complementaryVerdict(incomingPlatformId: String, existingPlatformId: String): ComplementaryVerdict {
    val incoming = priorityOf(incomingPlatformId)
    val existing = priorityOf(existingPlatformId)

    // ① 恰好一侧是下单平台 ⇒ 层级互补：一侧说「在哪个平台花」，另一侧说「钱从哪出」。
    if ((incoming == PlatformPriority.ORDER) != (existing == PlatformPriority.ORDER)) {
        return ComplementaryVerdict.AUTO_MERGE
    }

    // ② 任一侧无层级信息（unknown / 未收录）⇒ 没有互补证据（unknown 那条可能什么都没识别出）。
    //    `NONE ↔ NONE` 也在此拒（无层级信息，不合并）。
    if (incoming == PlatformPriority.NONE || existing == PlatformPriority.NONE) {
        return ComplementaryVerdict.REJECT
    }

    // 走到这里：两侧都在 `{PAYMENT, E_WALLET, BANK}`（都不是 ORDER、不是 NONE）。
    // ③ 不同层级（支付通道 ↔ 数币/云闪付 ↔ 银行卡）：两侧都只说「钱从哪条通道 / 哪张卡出去」，
    //    可能是同一笔的两个侧面，也可能是两笔（先充值再消费）⇒ 证据不足，浮出候选交用户。
    //    用户方针「能 REVIEW 就别 REJECT」⇒ 一律 REVIEW。
    if (incoming != existing) return ComplementaryVerdict.REVIEW

    // ④ 同层级：
    //    · 同 id（如 `bank↔bank`、`digital_rmb↔digital_rmb`）⇒ 同一类通道被重复抓取 ⇒ 疑似同一笔，交用户；
    //    · 不同 id（`wechat↔alipay` / `digital_rmb↔unionpay`）⇒ 一次消费只走一个通道 ⇒ 两笔真实消费 ⇒ 拒绝。
    return if (incomingPlatformId == existingPlatformId) {
        ComplementaryVerdict.REVIEW
    } else {
        ComplementaryVerdict.REJECT
    }
}

/**
 * **Tier-1（指纹精确）的层级护栏**：`true` = 层级上允许**静默自动合并**。
 *
 * ## 为什么 Tier-1 也需要护栏（本批 P0 修复）
 * `findDuplicates` 的 Tier-1 分支原先对候选**完全不看层级**（只判「跨渠道 + 商户非空」），
 * 于是一笔微信通知 + 一笔支付宝通知（同一家店、同金额、3 分钟内、商户名恰好一致）
 * 会被**静默合并** —— 而一次消费不可能同时走两个支付通道，这只能是**两笔真实消费**，
 * 合并即「吞账」。这与用户需求直接冲突，是本批最严重的问题。
 *
 * ## 为什么不能直接套用 [complementaryVerdict]
 * 它会把 `PAYMENT ↔ BANK` 判成 [ComplementaryVerdict.REVIEW]（交给用户），
 * 但「**微信消费通知 + 银行扣款短信自动合并**」是 v1.0 就有的核心能力，
 * 降级成待确认等于**功能倒退**。Tier-1 的商户名**完全相同**（指纹相同）⇒ 证据比 Tier-2 更强，
 * 护栏可以放宽。
 *
 * ## 判定（只拒绝「同层级 **且不同 id**」；跨层级一律放行）
 * | 组合 | 是否允许自动合并 | 理由 |
 * |---|---|---|
 * | 层级不同（`ORDER↔PAYMENT` / `ORDER↔E_WALLET` / `ORDER↔BANK` / `ORDER↔NONE` / `PAYMENT↔E_WALLET` / `PAYMENT↔BANK` / `E_WALLET↔BANK` …） | ✅ 允许 | 层级互补 ⇒ 同一笔的两个侧面 |
 * | 同层级 **同 id**（`bank↔bank` / `digital_rmb↔digital_rmb` / `wechat↔wechat`） | ✅ 允许 | **同一条**通道被重复抓取（银行短信 + 银行 App 动账通知）⇒ 就是同一笔（Bug 2 的修复点） |
 * | `PAYMENT ↔ PAYMENT`（**不同** id：微信 vs 支付宝） | ❌ 拒绝 | 一次消费只有一个支付通道 ⇒ 只能是两笔 |
 * | `ORDER ↔ ORDER`（**不同** id：美团 vs 淘宝） | ❌ 拒绝 | 两个消费场所 ⇒ 两笔消费 |
 * | `E_WALLET ↔ E_WALLET`（**不同** id：数币 vs 云闪付） | ❌ 拒绝 | 两个官方数字通道 ⇒ 只能是两笔 |
 * | `NONE ↔ NONE`（两边都没识别出平台） | ❌ 拒绝 | 没有层级信息可依据，保守交给用户 |
 *
 * ## ⚠️ 不要改成「同层级一律拒绝」（防后人照旧设计稿"修"回去）
 * 正确的判据是「**同层级 且 不同 id**」，**不是**「同层级」。笼统地改成"同层级一律拒绝"会误伤
 * 「银行短信与银行 App 通知**都是 `bank`**（同层级、同 id）」—— 它们是**重复抓取**，拒掉会
 * **同一笔银行流水记两次**（正是 1.1.2 修过的 Bug 2）。这条能力**只有跑完整链路才测得到** ——
 * `CrossChannelBankMergeTest` 明确走 `findDuplicates → canAutoMerge → merge`（见该文件的端到端用例），
 * 改错了会红。（教训：该能力原先只被"直接调 `merge()`、绕过 `canAutoMerge`"的用例覆盖，
 *  字面版实现即使回退 v1.0 能力，**全量测试仍然全绿** —— 变异验证才发现这份盲区。）
 *
 * ## ⚠️ 与 Tier-2 [complementaryVerdict] 对同一组合的判定**不同**（有意为之，勿"统一"）
 * 本函数（Tier-1）护栏**只看层级**，`E_WALLET ↔ BANK` 属"层级不同" ⇒ 放行（自动合并）；
 * 而 [complementaryVerdict]（Tier-2）对同组合返回 [ComplementaryVerdict.REVIEW]。**这不是 bug**：
 * 分层尺度是**证据强度** —— Tier-1 要求商户名**完全相同**（证据强），Tier-2 前提是商户名**不同或为空**（证据弱）。
 * 详见 [complementaryVerdict] 的「与 Tier-1 对同一组合的判定可以不同」一节。
 */
fun tierOneAllowsAutoMerge(incomingPlatformId: String, existingPlatformId: String): Boolean {
    val incoming = priorityOf(incomingPlatformId)
    val existing = priorityOf(existingPlatformId)

    // 层级不同 ⇒ 互补 ⇒ 放行（含 ORDER↔NONE 这类"一侧说了场所、一侧什么都没说"，
    // 以及 E_WALLET↔BANK 这类"钱从不同层级通道出去"）。
    if (incoming != existing) return true

    // 双方都无层级信息（unknown / 未收录）⇒ 没有可依据的层级，保守拒绝。
    if (incoming == PlatformPriority.NONE) return false

    // 同层级：只有「同一条通道被重复抓取」（同 id）才允许；不同 id = 两笔真实消费（拒）。
    return incomingPlatformId == existingPlatformId
}

// =====================================================================================
// 必修③【P0-2】不同门店护栏：normalize() 抹括号后不能再靠市场区分同品牌不同门店
// =====================================================================================

/**
 * 从原始商户名里抽取**括号中的门店信息**（可能多段，**排序后**用 `|` 连接），已去空白 + 小写。
 *
 * 例：`中石化(朝阳站)` → `朝阳站`；`星巴克（国贸店）(2F)` → `2f|国贸店`；`星巴克` → `""`。
 *
 * 半/全角括号都可识别；**统一小写**与 [com.autoledger.feature.dedup.LedgerDuplicateResolver]
 * 的 `normalize()` 口径一致；**多段排序**使比较与括号出现顺序无关
 * （`星巴克（国贸店）(2F)` 与 `星巴克(2F)(国贸店)` 视为同一门店，避免假冲突）。
 */
private val PARENTHETICAL: Regex = Regex("""[（(](.*?)[)）]""")

private fun branchHintsOf(counterparty: String): String = PARENTHETICAL
    .findAll(counterparty)
    .map { it.groupValues[1].trim().lowercase() }
    .filter { it.isNotEmpty() }
    .sorted()
    .joinToString("|")

/**
 * **门店护栏**：`true` = 两条记录的原始商户名**指向不同门店**，Tier-1 不得自动合并。
 *
 * ## 为什么必须加这条（本批 P0-2）
 * Tier-1 的指纹 = `sha256(金额 | normalize(商户))`，而 `normalize()` 会**抹掉括号内容**
 * （这是有意的：让「星巴克(国贸店)」与「星巴克」能匹配上，见 `normalize()` 的 KDoc）。
 *
 * 但抹括号的副作用是：「`中石化(朝阳站)`」与「`中石化(海淀站)`」归一化后**都变成「中石化」**，
 * 指纹相同 ⇒ 被当成同一条。若两侧平台**层级不同**（例如微信支付通知 = `PAYMENT`、
 * 银行 POS 短信 = `BANK`），[tierOneAllowsAutoMerge] 会**放行**（它只拒绝「同层级且不同通道」）
 * ⇒ 两笔不同加油站、不同车的真实消费被**静默合并**，吞掉一笔。
 *
 * 团队原先的兜底理由是「这种情形两侧平台通常同层级或都是 unknown，护栏能拦住」——
 * 该理由**不成立**（上例就是跨层级）。
 *
 * ## 为什么不能靠「不抹括号」解决
 * 抹括号是 Tier-1 的**核心能力**：银行短信写「星巴克(国贸店)」、微信通知写「星巴克」，
 * 不抹就会算成两个指纹 ⇒ **同一笔永远合并不了**。所以 `normalize()` **一个字都不能改**；
 * 只能在自动合并出口处，用**原始串**再补一道判据。
 *
 * ## 判据（刻意保守，只拒绝最强的情形）
 * | incoming 括号 | existing 括号 | 冲突? | 理由 |
 * |---|---|---|---|
 * | `朝阳站` | `海淀站` | ✅ 冲突 | 两侧都指明门店，且不同 ⇒ 两个门店的两笔真实消费 |
 * | `国贸店` | （无） | ❌ 不冲突 | 一侧没带门店 ⇒ 很可能是同一笔的两渠道描述（保住 Tier-1 的核心价值） |
 * | （无） | （无） | ❌ 不冲突 | 没有门店信息可比 |
 * | `国贸店` | `国贸店` | ❌ 不冲突 | 同一门店 |
 *
 * 即：**只有当两侧原始商户名的括号内容都非空且不同时**才判为冲突 —— 这是「抹括号会出错」
 * 的**唯一确定证据**。判为冲突时，该对记录**降级为待确认**（候选仍浮出，交用户裁决），
 * 而不是静默合并，也不是直接丢弃候选：宁可多一步确认，也不吞真实消费。
 */
fun branchSuffixesConflict(incomingCounterparty: String, existingCounterparty: String): Boolean {
    val incomingBranch = branchHintsOf(incomingCounterparty)
    val existingBranch = branchHintsOf(existingCounterparty)
    // 只有「两侧都带了门店信息 且 不同」才算冲突；任一侧为空都不是确定证据（见判据表）。
    return incomingBranch.isNotEmpty() && existingBranch.isNotEmpty() && incomingBranch != existingBranch
}

// =====================================================================================
// 必修④【P0-4】权威来源护栏：unknown 一侧若是手工录入 / 账单导入，不得被静默吸收
// =====================================================================================

/**
 * **权威 / 导入来源**：记录的是**既有事实**（用户亲手录入、官方账单导出），
 * 而非「靠正则从文本里猜出来」的自动结果。
 *
 * 这些来源的 `platformId` 常常就是 `unknown`（没识别出平台），但 `unknown` 在这里的含义是
 * **「不知道在哪花的」**，绝不是「某笔订单的银行侧」—— 两者不可等同。
 *
 * 取值来自 [CaptureSourceIds]（单一真源，见其 KDoc 为何不能写字面量）。
 */
val AUTHORITATIVE_PLATFORM_SOURCES: Set<String> = setOf(
    CaptureSourceIds.MANUAL,
    CaptureSourceIds.BILL_IMPORT,
)

/**
 * **Tier-2 的权威来源护栏**：`true` = 该对记录中**处于 [PlatformPriority.NONE]（unknown）一侧**
 * 的那条来自权威 / 导入来源 ⇒ 不得静默自动合并。
 *
 * ## 反例（本批 P0-4）
 * 12:00 用户**手工**记「菜市场 现金 25 元」（`sourceId=manual`、平台 `unknown`）；
 * 12:01 美团外卖通知 25 元（`ORDER`）。两条**商户不同** ⇒ 指纹不同 ⇒ Tier-1 不命中；
 * 但符合 Tier-2 的「同金额 + 3 分钟 + 跨 source + 层级互补（`ORDER↔NONE`）」⇒
 * [complementaryVerdict] 判 [ComplementaryVerdict.AUTO_MERGE] ⇒ **用户的现金消费被静默吸收并隐藏**。
 *
 * 用户录的那笔是**独立发生的事实**，不能被同金额的外卖订单吞掉。
 * 因此：`ORDER↔NONE` 这条通道上，**unknown 一侧必须排除权威 / 导入来源**。
 *
 * ## 为什么只排除「NONE 一侧」
 * 权威来源若**自己也带了确定平台**（用户手选了微信 / 银行），那它就不是 unknown 一侧，
 * 走的是正常的层级互补判定（例如 `PAYMENT↔BANK` 本就只能 [ComplementaryVerdict.REVIEW]），
 * 不需要额外收紧。本护栏只堵「unknown 冒充银行侧」这一个缝隙。
 *
 * ## 为什么只对 Tier-2，不扩大到 Tier-1
 * Tier-1 要求**指纹（含商户名）完全相同**，证据比 Tier-2 强得多；而且 Tier-1 已由
 * [branchSuffixesConflict] + [tierOneAllowsAutoMerge] 两道闸守着。此处不叠加，避免过度收紧。
 */
fun noneSideIsAuthoritative(
    incomingPlatformId: String,
    incomingSourceId: String,
    existingPlatformId: String,
    existingSourceId: String,
): Boolean {
    val incomingIsNone = priorityOf(incomingPlatformId) == PlatformPriority.NONE
    val existingIsNone = priorityOf(existingPlatformId) == PlatformPriority.NONE
    // 任一侧「是 unknown 且来自权威来源」即阻断。
    // （两侧同为 unknown 的场景轮不到这里 —— Tier-2 的 [complementaryVerdict] 已把 `NONE↔NONE` 判为 REJECT。）
    return (incomingIsNone && incomingSourceId in AUTHORITATIVE_PLATFORM_SOURCES) ||
        (existingIsNone && existingSourceId in AUTHORITATIVE_PLATFORM_SOURCES)
}
