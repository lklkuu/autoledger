package com.autoledger.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoledger.app.di.AppContainer
import com.autoledger.app.ui.components.AppCard
import com.autoledger.app.ui.components.EmptyHint
import com.autoledger.app.ui.components.SectionTitle
import com.autoledger.app.ui.components.yuan
import com.autoledger.app.ui.stores.RefundStore
import com.autoledger.app.ui.theme.LedgerPalette
import com.autoledger.core.model.refund.DeductionKind
import com.autoledger.core.model.refund.OrderRefundState
import com.autoledger.core.model.refund.RefundOutcome
import com.autoledger.core.model.refund.RefundStatus

/**
 * 订单与退款 —— 对账视图：看清"原抵扣 → 已回退 → 剩余可退"，并能发起退款。
 * 文案与错误提示全部来自引擎（`RefundRejectCode`），UI 不自行编规则。
 */
@Composable
fun RefundScreen(container: AppContainer) {
    val store = remember(container) { RefundStore(container) }
    LaunchedEffect(container) { store.load() }
    val state by store.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            AppCard {
                SectionTitle("订单与退款", "按原抵扣构成原路回退，金额可对账")
                Button(onClick = { showAdd = true }, Modifier.padding(top = 10.dp)) { Text("登记一笔订单") }
                state.message?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = LedgerPalette.Positive)
                }
                state.hint?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.orders.isEmpty()) {
            item { AppCard { EmptyHint("还没有订单，先「登记一笔订单」") } }
        }

        items(state.orders, key = { it.orderId }) { order ->
            AppCard(onClick = { store.select(order.orderId) }) {
                OrderRow(order)
            }
            if (state.selected?.orderId == order.orderId) {
                OrderDetail(
                    order = order,
                    store = store,
                    allocations = state.allocations,
                )
            }
        }
    }

    if (showAdd) {
        AddOrderDialog(
            onDismiss = { showAdd = false },
            onConfirm = { no, total, balance, points, coupon ->
                store.addOrder(no, total, balance, points, coupon)
                showAdd = false
            },
        )
    }
}

@Composable
private fun OrderRow(order: OrderRefundState) {
    val refunded = order.refunds.filter { it.status != RefundStatus.REJECTED }.sumOf { it.amountMinor }
    val remaining = (order.totalMinor - refunded).coerceAtLeast(0L)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(order.orderNo, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${order.status} · 总额 ¥${order.totalMinor.yuan()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "剩余可退 ¥${remaining.yuan()}",
            style = MaterialTheme.typography.labelMedium,
            color = if (remaining == 0L) MaterialTheme.colorScheme.onSurfaceVariant else LedgerPalette.Positive,
        )
    }
}

@Composable
private fun OrderDetail(order: OrderRefundState, store: RefundStore, allocations: List<com.autoledger.core.database.RefundAllocationEntity>) {
    var amountText by remember(order.orderId) { mutableStateOf("") }

    AppCard {
        SectionTitle("抵扣构成", "原额 / 已回退 / 剩余")
        order.deductions.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(kindLabel(d.kind), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append("¥${d.amountMinor.yuan()} / ¥${d.reversedAmountMinor.yuan()} / ¥${d.remainingAmountMinor.yuan()}")
                        if (d.quantity > 0) append("（${d.reversedQuantity}/${d.quantity} 次）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (order.refunds.isNotEmpty()) {
            SectionTitle("退款记录", "共 ${order.refunds.size} 笔")
            order.refunds.forEach { r ->
                Text(
                    "${r.refundNo} · ¥${r.amountMinor.yuan()} · ${r.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (allocations.isNotEmpty()) {
            SectionTitle("回退明细", "回退方式一目了然")
            allocations.forEach { a ->
                val line = buildString {
                    append(kindLabel(a.kind))
                    append(" ¥${a.amountMinor.yuan()}")
                    if (a.quantity > 0) append("（${a.quantity} 次）")
                    append(" · ")
                    append(outcomeLabel(a.outcome))
                    if (a.fallbackAmountMinor > 0) append("（折现 ¥${a.fallbackAmountMinor.yuan()}）")
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.outcome == RefundOutcome.RETURNED) LedgerPalette.Positive else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("退款金额（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val yuan = amountText.trim().toDoubleOrNull() ?: 0.0
                    store.requestRefund(com.autoledger.core.model.Money.fromYuanDouble(yuan).minor)
                    amountText = ""
                },
                Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LedgerPalette.Positive),
            ) { Text("发起退款") }
        }
    }
}

@Composable
private fun AddOrderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long, Long, Long, Long) -> Unit,
) {
    var no by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var points by remember { mutableStateOf("") }
    var coupon by remember { mutableStateOf("") }

    fun minor(text: String): Long =
        com.autoledger.core.model.Money.fromYuanDouble(text.trim().toDoubleOrNull() ?: 0.0).minor

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登记订单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(no, { no = it }, label = { Text("订单号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(total, { total = it }, label = { Text("订单总额（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(balance, { balance = it }, label = { Text("余额抵扣（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(points, { points = it }, label = { Text("积分抵扣（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(coupon, { coupon = it }, label = { Text("优惠券抵扣（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(no, minor(total), minor(balance), minor(points), minor(coupon)) }) { Text("登记") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun kindLabel(kind: DeductionKind): String = when (kind) {
    DeductionKind.BALANCE -> "余额"
    DeductionKind.POINTS -> "积分"
    DeductionKind.COUPON -> "优惠券"
    DeductionKind.ENTITLEMENT -> "权益次数"
    DeductionKind.THIRD_PARTY -> "第三方支付"
}

private fun outcomeLabel(outcome: RefundOutcome): String = when (outcome) {
    RefundOutcome.RETURNED -> "原路返还"
    RefundOutcome.EXPIRED_FALLBACK -> "券已过期→折现退余额"
    RefundOutcome.USED_FALLBACK -> "资源已用→折现退余额"
    RefundOutcome.SKIPPED -> "已跳过"
}
