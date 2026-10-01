package com.autoledger.feature.capture.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.feature.capture.CaptureAction
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState
import com.autoledger.feature.capture.notify.NotificationParser
import com.autoledger.feature.capture.notify.toRawEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「银行短信」采集渠道。
 *
 * 通知监管不到的场景（部分国产 ROM 会拦掉 App 通知）由短信兜底。
 *
 * 政策提醒：READ_SMS 在 Google Play 属于受限制权限，上架需申请并通过审核；
 * 侧载自用不受限制。App 在设计上把短信做成**可关闭的选装渠道**，关掉后其余功能完全可用。
 */
class SmsCaptureSource(
    private val parser: NotificationParser = NotificationParser(),
) : CaptureSource {

    override val id: String = ID
    override val displayName: String = "银行短信识别"
    override val description: String = "扫描短信收件箱中的银行交易通知，补齐通知抓不到的部分"
    override val requiredPermissions: List<String> = listOf(Manifest.permission.READ_SMS)
    override val needsSystemToggle: Boolean = false

    override val actions: List<CaptureAction> = listOf(
        CaptureAction.RequestPermission(Manifest.permission.READ_SMS, "授权短信读取"),
        CaptureAction.ScanBacklog("扫描历史短信"),
    )

    override fun permissionState(context: Context): PermissionState =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }

    override fun statusHint(state: PermissionState): String = when (state) {
        PermissionState.GRANTED -> "已授权，可扫描历史短信补录"
        PermissionState.NOT_GRANTED -> "需要短信读取权限才能扫描银行通知"
        PermissionState.NOT_APPLICABLE -> "无需权限"
    }

    /** args: sinceMillis(Long), limit(Int) */
    override suspend fun pullBacklog(context: Context, args: Map<String, Any?>): List<RawEnvelope> {
        if (permissionState(context) != PermissionState.GRANTED) return emptyList()
        val since = args["sinceMillis"] as? Long ?: (System.currentTimeMillis() - DEFAULT_LOOKBACK)
        val limit = args["limit"] as? Int ?: DEFAULT_LIMIT
        return withContext(Dispatchers.IO) { scan(context, since, limit) }
    }

    private fun scan(context: Context, sinceMillis: Long, limit: Int): List<RawEnvelope> {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ?"
        val selectionArgs = arrayOf(sinceMillis.toString())
        val out = mutableListOf<RawEnvelope>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val addr = cursor.getString(addrIdx).orEmpty()
                val body = cursor.getString(bodyIdx).orEmpty()
                if (body.isBlank()) continue
                val parsed = parser.parse(SOURCE_SMS_KEY, addr, body) ?: continue
                out += toRawEnvelope(
                    sourceId = ID,
                    sourceRef = "sms:${cursor.getLong(idIdx)}",
                    occurredAtMillis = cursor.getLong(dateIdx),
                    rawText = "$addr\n$body",
                    packageName = addr,
                    parsed = parsed,
                )
            }
        }
        return out
    }

    companion object {
        const val ID = CaptureSourceIds.SMS
        /** 短信解析时传这个作为「包名」，让它落到通用 / 短信规则而不是微信专用规则上 */
        const val SOURCE_SMS_KEY = "sms:inbox"
        const val DEFAULT_LIMIT = 500
        const val DEFAULT_LOOKBACK = 90L * 24 * 60 * 60 * 1000L
    }
}
