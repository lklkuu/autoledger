package com.autoledger.feature.dedup

import com.autoledger.core.model.Account
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferKind
import com.autoledger.core.model.TransferVerdict

/**
 * 内部划转识别规则包（同理全是数据）。
 *
 * 这块的准确性直接决定账单有没有意义：**把「银行卡充值微信」算成消费，月度支出就凭空翻倍**，
 * 这是自动记账类 App 最常见也最伤用户信任的错误。
 */
object TransferRulePack {

    val TOPUP = listOf("零钱充值", "余额充值", "充值", "转入零钱", "转入余额", "钱包充值", "绑卡充值")
    val WITHDRAW = listOf("提现", "余额提现", "转出到账户", "转出到卡", "提现到银行卡", "取出")
    val REPAYMENT = listOf("信用卡还款", "还信用卡", "还款", "账单还款", "主动还款", "自动还款")
    val SELF_TRANSFER = listOf("账户互转", "内部转账", "同名互转", "转到本人", "给自己转账", "二类户", "资金归集")
    val REFUND = listOf("退款", "退回", "冲正", "撤销交易", "原路退回", "已退款")

    /** 这些商户名出现在"账单"里，几乎一定是资金在自己口袋之间挪动 */
    val SELF_MERCHANT_HINTS = listOf(
        "零钱", "余额宝", "余额", "微信零钱", "我的银行卡", "我的储蓄卡", "活期", "定期", "基金赎回",
    )

    fun matchKindOf(ctx: TransferContext): Pair<TransferKind, String>? {
        val haystack = buildList {
            add(ctx.counterparty)
            ctx.note?.let(::add)
        }.joinToString(" ")

        if (REFUND.any { haystack.contains(it) }) return TransferKind.REFUND to "命中退款关键词"
        if (TOPUP.any { haystack.contains(it) }) return TransferKind.TOPUP to "命中充值关键词"
        if (WITHDRAW.any { haystack.contains(it) }) return TransferKind.WITHDRAW to "命中提现关键词"
        if (REPAYMENT.any { haystack.contains(it) }) return TransferKind.CREDIT_REPAYMENT to "命中还款关键词"
        if (SELF_TRANSFER.any { haystack.contains(it) }) return TransferKind.SELF_TRANSFER to "命中互转关键词"
        if (SELF_MERCHANT_HINTS.any { ctx.counterparty.contains(it) }) return TransferKind.SELF_TRANSFER to "收款方是自己的钱包"
        return null
    }

    /** 对方是不是自己的另一个账户（按卡号尾号 / 手机号 / 昵称等标识匹配） */
    fun ownAccount(ctx: TransferContext): Account? = ctx.accounts.firstOrNull { account ->
        // 账户名命中要求长度 ≥3，避免「微信/钱包/现金」这类 2 字短名把整渠道误判成内部划转。
        val nameHit = account.name.trim().let { it.length >= 3 && ctx.counterparty.contains(it) }
        val hintHit = account.identifierHints.any { hint ->
            hint.length >= 4 && (ctx.counterparty.contains(hint) || ctx.note?.contains(hint) == true)
        }
        nameHit || hintHit
    }
}

/**
 * 转账 / 退款识别器。
 *
 * 判定优先级：账户归属 > 关键词 > 金额对称配对。
 * 「转入账户归属名」证据最强 —— 转账给张三（自己的招行卡）比任何关键词都可靠。
 */
class DefaultTransferDetector : com.autoledger.core.model.TransferDetector {

    override val id: String = DETECTOR_ID

    override suspend fun detect(ctx: TransferContext): TransferVerdict {
        // 1) 对方是自己名下的另一个账户
        TransferRulePack.ownAccount(ctx)?.let { account ->
            val kind = TransferRulePack.matchKindOf(ctx)?.first ?: TransferKind.SELF_TRANSFER
            return TransferVerdict(kind, 0.95f, "收款方是你的「${account.name}」", account.id)
        }

        // 2) 关键词
        TransferRulePack.matchKindOf(ctx)?.let { (kind, reason) ->
            return TransferVerdict(kind, 0.85f, reason)
        }

        return TransferVerdict.none()
    }

    companion object { const val DETECTOR_ID = "default_transfer" }
}

/**
 * 成对配账：把「招行卡扣 1000」和「微信零钱 +1000」这两笔独立记录关联起来。
 *
 * 单看任何一笔都不像转账，但**金额相反、时间相近、且一方口袋是我们的账户**，
 * 三者同时成立时几乎可以确定是同一笔资金搬家。
 */
object TransferPairMatcher {

    data class PairResult(val groupId: String, val outId: String, val inId: String)

    fun match(txn: com.autoledger.core.model.LedgerTransaction, candidates: List<com.autoledger.core.model.LedgerTransaction>): PairResult? {
        val target = candidates.firstOrNull { other ->
            other.id != txn.id &&
                other.amountMinor == -txn.amountMinor &&
                kotlin.math.abs(other.occurredAtMillis - txn.occurredAtMillis) <= WINDOW &&
                // 拒绝「类型与方向矛盾」的畸形候选：EXPENSE 却为正数，不能作为流入方配对。
                !(other.type == com.autoledger.core.model.TxnType.EXPENSE && other.amountMinor > 0L)
        } ?: return null
        val out = if (txn.amountMinor < 0) txn else target
        val inn = if (txn.amountMinor < 0) target else txn
        return PairResult("tr-${out.id.take(8)}-${inn.id.take(8)}", out.id, inn.id)
    }

    const val WINDOW = 10L * 60 * 1000L
}
