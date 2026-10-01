package com.autoledger.app.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import com.autoledger.app.ui.theme.LedgerIcons

/**
 * 导航目标。
 *
 * 底部六个入口**完全照搬参考仪表盘**（今日 / 时薪 / 记账 / 月结 / 自由 / 发现），
 * 「采集箱」与「设置」是本 App 新增能力，挂在顶栏，不挤占原有底部信息层级。
 */
enum class Destination(
    val label: String,
    val primaryEntry: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    DASHBOARD("今日", true, LedgerIcons.Dashboard),
    HOURLY("时薪", true, LedgerIcons.Clock),
    EXPENSES("记账", true, LedgerIcons.Receipt),
    MONTHLY("账单", true, LedgerIcons.Calendar),
    FREEDOM("自由", true, LedgerIcons.Pig),
    INSIGHTS("发现", true, LedgerIcons.Spark),
    CAPTURE("采集箱", false, LedgerIcons.Notifications),
    SETTINGS("设置", false, LedgerIcons.Settings),
    CATEGORY("分类管理", false, LedgerIcons.Category),
    REFUND("订单退款", false, LedgerIcons.Download),
    PLATFORM("消费平台", false, LedgerIcons.Category),
    ;

    companion object {
        val PRIMARY: List<Destination> = entries.filter { it.primaryEntry }
    }
}

/** 极简返回栈：压栈 / 出栈，够用且不引入 navigation-compose 的版本耦合。 */
class NavState {
    val stack: SnapshotStateList<Destination> = mutableStateListOf(Destination.DASHBOARD)

    val current: Destination get() = stack.last()

    fun navigate(destination: Destination) {
        stack.removeAll { it == destination }
        stack.add(destination)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
