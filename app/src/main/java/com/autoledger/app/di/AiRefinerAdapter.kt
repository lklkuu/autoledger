package com.autoledger.app.di

import com.autoledger.core.model.TxnType
import com.autoledger.feature.ai.AiTypeRefiner
import com.autoledger.feature.capture.TypeRefineRequest
import com.autoledger.feature.capture.TypeRefiner

/**
 * 把 [feature:ai] 的判定器适配到采集侧的 [TypeRefiner] 端口。
 *
 * **为什么需要这一层薄适配**：端口 `TypeRefiner` 按依赖倒置定义在调用方
 * （`feature:capture`），而实现在 `feature:ai`。若让 `feature:ai` 直接实现该端口，
 * 就会产生 `feature:ai → feature:capture` 的**横向依赖**，`tools/static_check.py`
 * 会直接判「feature 层不允许横向依赖」并红。
 *
 * 放在 app 层（唯一同时依赖两侧的地方）注入，feature 之间保持零横向依赖。
 *
 * 契约映射：`feature:ai` 用「返回 null = 不采纳」表达超时/异常/低置信/非法输出，
 * 与端口语义完全一致，因此这里只是一层直通。
 */
internal class AiRefinerAdapter(
    private val delegate: AiTypeRefiner,
) : TypeRefiner {

    override suspend fun refine(request: TypeRefineRequest): TxnType? =
        delegate.decide(
            text = request.text,
            amountMinor = request.amountMinor,
            localGuess = request.localGuess,
        )
}
