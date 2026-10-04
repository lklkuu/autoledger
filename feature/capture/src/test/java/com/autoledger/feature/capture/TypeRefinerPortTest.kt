package com.autoledger.feature.capture

import com.autoledger.core.model.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 采集管线上「AI 二次判定端口」的行为护栏。
 *
 * 三条不变式：
 *  1. **采集端已确定类型（`explicitType != null`）时，AI 根本不会被调用** ——
 *     采集端已经判成 REFUND 的流水，绝不允许被 AI 改写；
 *  2. `typeRefiner == null`（默认）⇒ 行为与本参数出现之前**逐位一致**；
 *  3. 实现方返回 null（超时/异常/低置信/非法）⇒ 静默用本地结论。
 *
 * 用假实现，不碰网络也不碰 Room。
 */
class TypeRefinerPortTest {

    private class RecordingRefiner(private val answer: TxnType?) : TypeRefiner {
        var calls = 0
        var lastRequest: TypeRefineRequest? = null

        override suspend fun refine(request: TypeRefineRequest): TxnType? {
            calls++
            lastRequest = request
            return answer
        }
    }

    @Test
    fun `explicit type is never overridden and the refiner is not even called`() = runBlocking {
        val refiner = RecordingRefiner(TxnType.INCOME)

        val result = resolveInitialTypeWithRefiner(
            explicitType = TxnType.REFUND,
            amount = 5_000L,
            rawText = "退款到账 50.00",
            typeRefiner = refiner,
        )

        assertEquals(TxnType.REFUND, result, "采集端已判定为退款，AI 不得改写")
        assertEquals(0, refiner.calls, "显式类型存在时，AI 端口不该被调用")
    }

    @Test
    fun `a null refiner reproduces today's behaviour exactly`() = runBlocking {
        val cases = listOf(
            Triple(null, null, TxnType.EXPENSE),
            Triple(null, -1_470L, TxnType.EXPENSE),
            Triple(null, 5_000L, TxnType.INCOME),
            Triple(TxnType.REFUND, -5_000L, TxnType.REFUND),
        )
        for ((explicit, amount, expected) in cases) {
            assertEquals(
                expected,
                resolveInitialTypeWithRefiner(explicit, amount, "原文", typeRefiner = null),
                "默认（不接线）时必须与 resolveInitialType 完全一致：explicit=$explicit amount=$amount",
            )
        }
    }

    @Test
    fun `an adopted answer replaces the local guess`() = runBlocking {
        val refiner = RecordingRefiner(TxnType.INCOME)

        val result = resolveInitialTypeWithRefiner(
            explicitType = null,
            amount = null, // 本地判不出 ⇒ 才会问
            rawText = "花呗还款",
            typeRefiner = refiner,
        )

        assertEquals(TxnType.INCOME, result)
        assertEquals(1, refiner.calls)
        assertEquals("花呗还款", refiner.lastRequest?.text, "原文必须透传给实现方")
    }

    @Test
    fun `a null answer silently falls back to the local guess`() = runBlocking {
        val refiner = RecordingRefiner(null)

        val result = resolveInitialTypeWithRefiner(
            explicitType = null,
            amount = null,
            rawText = "某笔扣款",
            typeRefiner = refiner,
        )

        assertEquals(TxnType.EXPENSE, result, "AI 不采纳时必须用本地结论（金额缺失兜底为支出）")
        assertEquals(1, refiner.calls)
    }

    @Test
    fun `the request carries exactly the fields the refiner contract promises`() = runBlocking {
        val refiner = RecordingRefiner(TxnType.EXPENSE)

        resolveInitialTypeWithRefiner(
            explicitType = null,
            amount = 1_470L,
            rawText = "郑思强麻辣烫 14.70",
            typeRefiner = refiner,
        )

        val request = refiner.lastRequest!!
        assertEquals("郑思强麻辣烫 14.70", request.text)
        assertEquals(1_470L, request.amountMinor)
        // 金额为正 ⇒ 本地按正负判成 INCOME，这份结论要作为参考传给 AI（AI 采纳后可能改写它）。
        assertEquals(TxnType.INCOME, request.localGuess, "本地结论要作为参考传给 AI")
        assertTrue(request.text.isNotBlank())
    }
}
