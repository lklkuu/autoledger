package com.autoledger.feature.capture.bill

import android.content.Context
import android.net.Uri
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.feature.capture.CaptureAction
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「账单文件导入」渠道：把支付宝 / 微信官方导出的账单 CSV 一次性转成流水。
 *
 * 这是**覆盖率和确定性最高**的补充渠道——官方账单是最权威的对账来源，
 * 用来校准平时自动抓的结果最合适，也用来做初次安装的冷启动。
 *
 * 文件通过系统文件选择器（SAF）读取，**不需要任何存储权限**。
 */
class BillImportCaptureSource : CaptureSource {

    override val id: String = ID
    override val displayName: String = "账单文件导入"
    override val description: String = "导入支付宝 / 微信官方导出的账单 CSV，一次性补录历史流水"
    override val requiredPermissions: List<String> = emptyList()
    override val needsSystemToggle: Boolean = false

    override val actions: List<CaptureAction> = listOf(CaptureAction.PickFile("text/csv", "选择账单 CSV"))

    override fun permissionState(context: Context): PermissionState = PermissionState.NOT_APPLICABLE

    override fun statusHint(state: PermissionState): String = "随时可导入官方账单 CSV"

    /** args: uri(Uri) */
    override suspend fun pullBacklog(context: Context, args: Map<String, Any?>): List<RawEnvelope> {
        val uri = args["uri"] as? Uri ?: return emptyList()
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream -> parse(stream) } ?: emptyList()
        }
    }

    fun parse(stream: InputStream): List<RawEnvelope> = CsvBillReader.read(stream)
        .mapNotNull { row -> rowToEnvelope(row) }

    /**
     * 只保留「真实成交」的行：
     * - 交易成功 / 支付成功 / 已存入 之类的才算数，退款 / 关闭 / 冻结的行也进来（由后续退款规则识别）
     * - 金额缺失或为 0 的行直接丢掉，它们通常是标题行或合计行
     */
    private fun rowToEnvelope(row: Map<String, String>): RawEnvelope? {
        val amountRaw = row["amount"].orEmpty()
        if (amountRaw.isBlank() || amountRaw == "0" || amountRaw == "0.00") return null

        val status = row["status"].orEmpty()
        if (status.isNotBlank() && REJECT_STATUS.any { status.contains(it) }) return null

        val product = row["product"].orEmpty()
        val directionRaw = row["direction"].orEmpty()

        // 退款：CSV 的"收/支"列常为"不计收支"，必须靠状态/商品说明识别，否则会被当成支出（金额符号也会错）。
        val isRefund = REFUND_HINTS.any {
            status.contains(it) || product.contains(it) || directionRaw.contains(it)
        }
        // "不计收支"= 既非收入也非支出（提现/还款/余额宝划转），必须单独识别，
        // 否则会被下面 `contains("支")` 误判成支出，整体虚增支出。
        val isNeutral = directionRaw.contains("不计收支")
        val isOut = !isRefund && !isNeutral && (directionRaw.contains("支出") || directionRaw.contains("支"))

        val magnitude = amountRaw.trimStart('¥', '￥').replace(",", "").trim().toBigDecimalOrNull()
            ?.times(java.math.BigDecimal(100))?.toLong() ?: return null
        val amountMinor = if (isOut) -kotlin.math.abs(magnitude) else kotlin.math.abs(magnitude)

        // 显式下发类型：退款是资金流入（且必须显式标注，否则正数会被误判成收入）；
        // "不计收支"按内部划转处理，绝不虚增支出/收入。
        val explicitType = when {
            isRefund -> TxnType.REFUND
            isNeutral -> TxnType.TRANSFER
            else -> null
        }

        val occurredAt = row["time"]?.let { tryParseDateTime(it) } ?: System.currentTimeMillis()
        val counterparty = row["counterparty"].orEmpty()
        val method = row["method"].orEmpty()
        val rawLine = listOf(occurredAt.toString(), counterparty, product, amountRaw, status, method)
            .filter { it.isNotBlank() }.joinToString(" | ")

        return RawEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            sourceId = ID,
            sourceRef = "csv:${row["orderNo"].orEmpty()}:${occurredAt}:${amountMinor}",
            occurredAtMillis = occurredAt,
            rawText = rawLine,
            counterpartyHint = counterparty.takeIf { it.isNotBlank() },
            amountHint = amountMinor,
            packageName = row["platform"],
            explicitType = explicitType,
        )
    }

    private val REJECT_STATUS = listOf("关闭", "已取消", "解冻成功", "冻结成功", "充值中", "提现中", "交易关闭")

    // 与 feature:dedup 的 TransferRulePack.REFUND 保持一致；capture 模块不依赖 dedup，故本地定义。
    private val REFUND_HINTS = listOf("退款", "退回", "冲正", "撤销交易")

    // 支付宝/微信导出的账单日期格式不统一（如 2024/1/2 13:45、2024-01-02 09:30:15），
    // 且月/日常有前导零缺失，必须多模式尝试，否则整批时间戳会退化、月度统计全错。
    private val DATE_TIME_PATTERNS = listOf(
        java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
    )

    private val DATE_PATTERNS = listOf(
        java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d"),
    )

    private fun tryParseDateTime(value: String): Long? {
        val zone = java.time.ZoneId.systemDefault()
        for (fmt in DATE_TIME_PATTERNS) {
            val parsed = runCatching {
                java.time.LocalDateTime.parse(value.trim(), fmt)
                    .atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        for (fmt in DATE_PATTERNS) {
            val parsed = runCatching {
                java.time.LocalDate.parse(value.trim(), fmt)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        // 兜底：ISO 标准形态（如 2024-01-02T13:45:00）
        return runCatching {
            java.time.LocalDateTime.parse(value.trim().replace("/", "-").replace(" ", "T"))
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    // ID 的单一真源在 core:model（去重护栏也要按它识别「权威来源」，见 CaptureSourceIds）。
    companion object { const val ID = CaptureSourceIds.BILL_IMPORT }
}

/**
 * 极简 CSV 读取器。
 *
 * 之所以自己写：支付宝账单含多行前言（账号、导出时间），表头位置不固定；
 * 微信账单又有 GBK / UTF-8 两种编码。通用 CSV 库要么强依赖表头在第一行，
 * 要么在 GBK 上直接乱码。这里做三件事：
 * 1) 自动嗅探编码并去掉 BOM；
 * 2) 扫描到「包含 ≥3 个已知列名」的那一行才认作表头；
 * 3) 处理带引号、含逗号的字段。
 */
private object CsvBillReader {

    private val ALIASES = mapOf(
        "time" to listOf("交易时间", "交易日期", "时间", "日期"),
        "counterparty" to listOf("交易对方", "对方", "商户", "收款方", "付款方"),
        "product" to listOf("商品说明", "商品", "商品名称", "备注", "说明"),
        "direction" to listOf("收/支", "收支", "收支类型", "交易类型", "类型"),
        "amount" to listOf("金额(元)", "金额（元）", "金额", "发生金额", "发生额"),
        "status" to listOf("交易状态", "当前状态", "状态"),
        "method" to listOf("收/付款方式", "收付款方式", "支付方式", "付款方式"),
        "orderNo" to listOf("交易订单号", "交易单号", "订单号"),
    )

    fun read(stream: InputStream): List<Map<String, String>> {
        val bytes = stream.readBytes()
        val text = decode(bytes)
        val rows = parseRows(text)
        val headerIdx = rows.indexOfFirst { row ->
            ALIASES.values.flatten().count { alias -> row.any { it.contains(alias) } } >= 3
        }
        if (headerIdx < 0) return emptyList()
        val header = rows[headerIdx]
        val indexOf = ALIASES.mapValues { (_, aliases) ->
            header.indexOfFirst { h -> aliases.any { alias -> h.contains(alias) } }
        }
        return rows.drop(headerIdx + 1).mapNotNull { row ->
            val map = indexOf.mapNotNull { (key, idx) ->
                if (idx >= 0 && idx < row.size) key to row[idx].trim() else null
            }.toMap()
            map.takeIf { it.containsKey("amount") }
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // 大量替换字符 => 大概率是 GBK（支付宝 PC 端老导出）
        return if (utf8.count { it == '\uFFFD' } > 2) {
            runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
        } else utf8.trimStart('\uFEFF')
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cell = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                (c == ',' && !inQuotes) -> { row += cell.toString(); cell = StringBuilder() }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell = StringBuilder()
                    if (row.any { it.isNotBlank() }) rows += row.toList()
                    row.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row.toList()
        return rows
    }
}
