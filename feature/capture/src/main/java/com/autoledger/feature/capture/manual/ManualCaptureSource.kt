package com.autoledger.feature.capture.manual

import android.content.Context
import com.autoledger.core.model.RawEnvelope
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState
import java.util.UUID

/**
 * 「手动补记」渠道。
 *
 * 把它也做成 CaptureSource 是有意的：10 秒快记、桌面小组件快捷入口、将来接小爱/Tasker，
 * 走的都是同一条总线、同一套去重与分类逻辑，不存在「手动记录的账另外一套规则」这种割裂。
 */
class ManualCaptureSource : CaptureSource {

    override val id: String = ID
    override val displayName: String = "手动补记"
    override val description: String = "10 秒录一笔：金额、商户、备注，入账后自动换算成工作时间"
    override val requiredPermissions: List<String> = emptyList()
    override val needsSystemToggle: Boolean = false

    override fun permissionState(context: Context): PermissionState = PermissionState.GRANTED

    /**
     * @param explicitType 手动录入时可直接指定类型（如「退款」），为空则按金额正负推断
     */
    fun envelope(
        amountMinor: Long,
        counterparty: String,
        note: String?,
        occurredAtMillis: Long = System.currentTimeMillis(),
        explicitType: com.autoledger.core.model.TxnType? = null,
    ): RawEnvelope =
        RawEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            sourceId = ID,
            sourceRef = "manual:${UUID.randomUUID()}",
            occurredAtMillis = occurredAtMillis,
            rawText = "$counterparty ${' '}${(note.orEmpty())}",
            counterpartyHint = counterparty.takeIf { it.isNotBlank() },
            amountHint = amountMinor,
            explicitType = explicitType,
        )

    companion object { const val ID = "manual" }
}
