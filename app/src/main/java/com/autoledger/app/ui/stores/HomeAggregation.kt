package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TimeRange
import com.autoledger.core.model.TxnType

/**
 * 首页聚合的纯逻辑（不依赖 Android / Room，可直接 JVM 单测）。
 *
 * 为什么单独抽出来：
 * 1) [com.autoledger.core.model.LedgerRepository.observeSince] 只有左边界
 *    （`occurredAtMillis >= 月初`），必须在这里补上右边界，否则未来日期的流水
 *    （预授权、跨时区账单）会混进本月，造成「跨月多算」；
 * 2) 边界裁剪是纯数据变换，抽成纯函数后可以用单测把「左闭右闭」和「跨月排除」
 *    这两个容易回退的点钉死。
 */
internal fun clipToMonthSpending(
    list: List<LedgerTransaction>,
    month: TimeRange,
): List<LedgerTransaction> =
    list.asSequence()
        // 只剔除内部划转。**退款(REFUND)必须保留**：汇总里的「退款总额 / 净支出」统一由 ExpenseMath
        // 计算，若在这里把 REFUND 过滤掉，HomeStore.monthRefundMinor 会恒为 0、退款永远显示不出来。
        .filter { it.type != TxnType.TRANSFER }
        // 左闭右闭：`in start..end` 同时保证下边界（>= 月初）与上边界（<= 当前时刻）。
        .filter { it.occurredAtMillis in month.startMillis..month.endInclusiveMillis }
        .toList()

/**
 * 首页聚合的时间窗：左端 = 月初锚点（订阅时定死，本订阅生命周期内不变），
 * 右端 = **发射时的实时 now**。
 *
 * 为什么右端不能在订阅时定死：HomeStore 的数据库订阅是长驻的，
 * 订阅时刻取的 now 只对第一帧正确；此后每一条新发射仍用旧右端的话，
 * 「今天刚落库的一笔」会被裁掉，直到下次整订阅重建（切页/重试）才消失 ——
 * 用户会看到"刚记的账首页不出现"。右端随每次发射实时取 now，窗口才与用户看到的这一刻一致。
 */
internal fun spendingWindow(monthStartMillis: Long, nowMillis: Long): TimeRange =
    TimeRange(monthStartMillis, nowMillis)