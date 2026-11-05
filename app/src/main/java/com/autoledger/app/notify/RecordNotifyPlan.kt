package com.autoledger.app.notify

import com.autoledger.app.ui.nav.Destination
import com.autoledger.core.model.TxnStatus
import java.util.Locale

/**
 * 一条「记账提醒」该怎么显示；返回 null 表示**这条不该弹通知**。
 *
 * 抽成纯数据 + 纯函数（不碰 `Context` / `NotificationManager` / 数据库）的原因：
 * 文案与「弹不弹」是本次报障的全部内容，而它们逐条都是**可枚举的判定规则**；
 * 埋在 `AppContainer` 的私有方法里就只能靠跑 Robolectric + 伪造通知来验证，
 * 拿到 `plan` 之后即可逐格单测（见 `RecordNotifyPlanTest`）。
 *
 * @property title 通知标题（必须在语义上如实：已入账 / 待确认 是两件事）
 * @property body 通知正文，形如 `商户 · 金额 · 点按查看`
 * @property openDestination 点按通知后应落到的页面（待确认 ⇒ 采集箱，已入账 ⇒ 账单）
 */
internal data class RecordNotifyPlan(
    val title: String,
    val body: String,
    val openDestination: Destination,
)

/** 「这条不弹通知」的标题占位（仅用于文档可读性，实际用 null 表达）。 */
private const val REVIEW_TITLE = "记了一笔，待你确认"

/** 已入账（自动确认）时的标题；刻意**不带笔数**——通知是单槽覆盖的，笔数会失真。 */
private const val CONFIRMED_TITLE = "已自动记账"

/** 抽不出商户时的兜底主体。 */
private const val UNKNOWN_COUNTERPARTY = "一笔新流水"

/**
 * 金额缺失时的说法。
 *
 * **不是**「金额待确认」：`待确认` 描述的是**用户要做的动作**（这条流水需要人看一眼），
 * 而 `未识别` 描述的是**解析结果**（原文里没抽到金额）。两者可以同时成立、也可以各自成立 ——
 * 混成一个词会让用户以为"金额有了只是没确认"，于是不去采集箱补，只等着系统自己确认。
 */
private const val UNKNOWN_AMOUNT = "金额未识别"

/**
 * 决定「记账提醒」的标题 / 正文 / 点击去向。
 *
 * ## 判定规则（顺序即优先级）
 *
 * 1. **不入账的流水一律不提醒**：`storedStatus` 为 [TxnStatus.DUPLICATE] / [TxnStatus.MERGED] /
 *    [TxnStatus.IGNORED] 时返回 `null`。
 *    这三类的共同点是「这笔钱没有作为独立一笔进账单」：
 *    - `DUPLICATE` 是同一笔的疑似重复（默认不成立，不进账单）；
 *    - `MERGED` 已被主记录吸收（金额由主记录承担）；
 *    - `IGNORED` 是用户/规则判定不算账。
 *    给它们弹「已自动记账」是**事实错误**；而且一次消费常常同时触发
 *    银行短信 + 银行 App + 数币 App 等多条通知，落成 N 条流水，
 *    若每条都弹一条通知，通知栏就会被这一笔消费刷屏（本次用户报障：6 条）。
 *
 * 2. **需要人看一眼 ⇒ 引导到采集箱**：`needsReview == true` **或** `storedStatus == RAW`
 *    （`RAW` = 待确认但默认成立，同样在待确认队列里）时，
 *    标题为 [REVIEW_TITLE]、去向 [Destination.CAPTURE]。
 *    反过来，只有「既没有 review 信号、也不是 RAW」才敢说「已自动记账」并去 [Destination.MONTHLY]。
 *
 * 3. **金额**：非 null 且非 0 才敢报数字（`¥%.2f`，取绝对值——收支方向由账单页的符号表达，
 *    通知里写 `¥-843.51` 只会让用户以为记错了）；`null` 或 `0` 一律 [UNKNOWN_AMOUNT]。
 *    `0` 与 `null` 同待遇：`IngestPipeline` 在解析不到金额时落的就是 `amountMinor = 0`，
 *    对用户来说「0 元」和「没抽到」是同一件事 —— 都不能冒充一个真实金额。
 *
 * 4. **主体**：`counterparty` 去空白后非空则用它，否则 [UNKNOWN_COUNTERPARTY]。
 *
 * 5. **正文**：`"$who · $amountText · 点按查看"`（与既有格式逐字一致，只是各段来源变准）。
 *
 * @param amountMinor 落库后的金额（分，有符号）；解析不到时传 null
 * @param counterparty 落库后的商户；为空/空白时走兜底文案
 * @param storedStatus 落库后这条流水的真实状态；查不到（理论上不会）时为 null，
 *   此时按「没有否定证据」处理，不因此抑制通知
 * @param needsReview 流水线给出的"需要人工确认"信号
 *   （`Outcome.NeedsReview`，或 `Accepted` 但 `autoConfirmed == false`）
 * @return 该弹的通知方案；`null` = 这条不该弹
 */
internal fun planRecordNotify(
    amountMinor: Long?,
    counterparty: String?,
    storedStatus: TxnStatus?,
    needsReview: Boolean,
): RecordNotifyPlan? {
    // 1) 没作为独立一笔入账的，不提醒（也天然避免同一笔消费刷屏）。
    when (storedStatus) {
        TxnStatus.DUPLICATE, TxnStatus.MERGED, TxnStatus.IGNORED -> return null
        // RAW：待确认但默认成立，需要用户核对；CONFIRMED / null：继续往下判。
        TxnStatus.RAW, TxnStatus.CONFIRMED, null -> Unit
    }

    // 2) RAW 本身就是"待确认"，与流水线给的 review 信号等价，两者取或。
    val review = needsReview || storedStatus == TxnStatus.RAW
    val title = if (review) REVIEW_TITLE else CONFIRMED_TITLE
    val destination = if (review) Destination.CAPTURE else Destination.MONTHLY

    // 3) 金额：只有"确有其数"才报；0 与 null 同待遇（见 KDoc 第 3 条）。
    val amountText = if (amountMinor != null && amountMinor != 0L) {
        // 用绝对值：方向交给账单页表达，通知里不出现负号。
        val yuan = kotlin.math.abs(amountMinor.toDouble()) / 100.0
        String.format(Locale.US, "¥%.2f", yuan)
    } else {
        UNKNOWN_AMOUNT
    }

    // 4) 主体：空白商户不算"有商户"。
    val who = counterparty?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_COUNTERPARTY

    return RecordNotifyPlan(
        title = title,
        body = "$who · $amountText · 点按查看",
        openDestination = destination,
    )
}
