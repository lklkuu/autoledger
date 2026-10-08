package com.autoledger.feature.capture.manual

import android.content.Context
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.platform.PlatformCatalog
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
     * @param platformId 用户在录入界面手选的消费平台 ID；
     *                   **没选（null / 空白 / [PlatformCatalog.UNKNOWN_ID]）一律归一成 null** ——
     *                   unknown 的语义是「没识别出来」，若塞进 platformHint 会被 IngestPipeline
     *                   当成权威值标成 `PlatformSource.USER`：**既盖掉自动识别的结果，又让这行
     *                   以后永远不会被重解析 / 去重继承修正**。留 null 才是「请你自动识别」。
     * @param categoryId 用户手选的分类 ID；非空时 IngestPipeline 不再跑分类器。
     */
    fun envelope(
        amountMinor: Long,
        counterparty: String,
        note: String?,
        occurredAtMillis: Long = System.currentTimeMillis(),
        explicitType: com.autoledger.core.model.TxnType? = null,
        platformId: String? = null,
        categoryId: String? = null,
    ): RawEnvelope {
        // 「未选择」的三种形态统一归一成 null，让自动识别路径保持原样。
        val manualPlatform = platformId
            ?.takeIf { it.isNotBlank() && it != PlatformCatalog.UNKNOWN_ID }
        return RawEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            sourceId = ID,
            sourceRef = "manual:${UUID.randomUUID()}",
            occurredAtMillis = occurredAtMillis,
            // 备注仍然拼进原文：分类器要靠它做关键词匹配（此行为不得改动）。
            rawText = "$counterparty ${' '}${(note.orEmpty())}",
            counterpartyHint = counterparty.takeIf { it.isNotBlank() },
            amountHint = amountMinor,
            explicitType = explicitType,
            platformHint = manualPlatform,
            categoryHint = categoryId?.takeIf { it.isNotBlank() },
            // 以前备注**只**活在 rawText 里，落库后 note 恒为 null ⇒ 用户填的备注保存即消失。
            noteHint = note?.takeIf { it.isNotBlank() },
        )
    }

    // ID 的单一真源在 core:model（去重护栏也要按它识别「权威来源」，见 CaptureSourceIds）。
    companion object { const val ID = CaptureSourceIds.MANUAL }
}
