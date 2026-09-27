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