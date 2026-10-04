package com.autoledger.feature.capture.notify

import com.autoledger.core.model.RawEnvelope

/**
 * 把「解析结果 + 采集元数据」映射成统一的原始信封。
 *
 * 抽成不依赖 Android 的纯函数，是为了让「解析出的显式类型确实被下发」这件事能被单测钉死 ——
 * 此前该映射只写在 Listener / SmsSource 的内联代码里，删掉 `explicitType` 那一行不会有任何测试变红，
 * 退款会悄悄退化回「靠关键词二次命中」。
 */
internal fun toRawEnvelope(
    sourceId: String,
    sourceRef: String,
    occurredAtMillis: Long,
    rawText: String,
    packageName: String?,
    parsed: NotificationParser.ParseResult,
): RawEnvelope = RawEnvelope(
    envelopeId = java.util.UUID.randomUUID().toString(),
    sourceId = sourceId,
    sourceRef = sourceRef,
    occurredAtMillis = occurredAtMillis,
    rawText = rawText,
    counterpartyHint = parsed.counterparty,
    amountHint = parsed.amountMinor,
    packageName = packageName,
    explicitType = parsed.explicitType,
    directionHint = parsed.direction,
)
