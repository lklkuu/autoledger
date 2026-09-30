package com.autoledger.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 图标字典。
 *
 * 参考仪表盘用的是一套内联 SVG（dashboard / clock / receipt / calendar / pig / spark），
 * 这里用 Material 图标一一对应：影像占位不重要，**入口、层级、位置完全相同**才是重点。
 * 想 100% 还原成原版 SVG，把这些替换成 [androidx.compose.ui.graphics.vector.ImageVector]
 * 即可（原版面 pace 已在 README 中列出）。
 */
object LedgerIcons {

    fun forCategory(iconKey: String): ImageVector = when (iconKey) {
        "receipt" -> Icons.Outlined.ReceiptLong
        "wallet" -> Icons.Outlined.AccountBalanceWallet
        "pig" -> Icons.Outlined.Savings
        "dashboard" -> Icons.Outlined.SpaceDashboard
        "clock" -> Icons.Outlined.Schedule
        "spark" -> Icons.Outlined.AutoAwesome
        "restaurant" -> Icons.Outlined.Restaurant
        "bus" -> Icons.Outlined.DirectionsBus
        "bag" -> Icons.Outlined.ShoppingBag
        "home" -> Icons.Outlined.Home
        "cafe" -> Icons.Outlined.LocalCafe
        "medical" -> Icons.Outlined.MedicalServices
        "school" -> Icons.Outlined.School
        "gift" -> Icons.Outlined.CardGiftcard
        "subscription" -> Icons.Outlined.Subscriptions
        else -> Icons.Outlined.MoreHoriz
    }

    val Dashboard = Icons.Outlined.SpaceDashboard
    val Clock = Icons.Outlined.Schedule
    val Receipt = Icons.Outlined.ReceiptLong
    val Calendar = Icons.Outlined.CalendarMonth
    val Pig = Icons.Outlined.Savings
    val Spark = Icons.Outlined.AutoAwesome
    val Settings = Icons.Outlined.Settings
    val Category = Icons.Outlined.Category
    val Notifications = Icons.Outlined.Notifications
    val Add = Icons.Outlined.MoreHoriz
    val Delete = Icons.Outlined.Delete
    val Edit = Icons.Outlined.Edit
    val Close = Icons.Outlined.Close
    val Check = Icons.Outlined.Check
    val Refresh = Icons.Outlined.Refresh
    val Lock = Icons.Outlined.Lock
    val Upload = Icons.Outlined.Upload
    val Download = Icons.Outlined.Download
    val Search = Icons.Outlined.Search
    val Merge = Icons.Outlined.MergeType
    // AutoMirrored 版本：RTL 语言下箭头会自动镜像（普通 Outlined 版本不会）
    val ChevronLeft = Icons.AutoMirrored.Outlined.KeyboardArrowLeft
    val ChevronRight = Icons.AutoMirrored.Outlined.KeyboardArrowRight
}
