package com.autoledger.core.backup

import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份 JSON 的**字段兼容**护栏（§7 #10 / #11）。
 *
 * 为什么必须跑 Robolectric：`org.json` 与 `android.util.Base64` 在纯 JVM 的 android.jar 里是 stub，
 * 不跑真实实现的话 `put` / `optString` 全是空操作 —— 测试会通过，但**什么都没验证**。
 *
 * 用户原始诉求里明确有「保证存量数据不丢失」，备份导入正是这条的落点：
 * 旧版本（v4，无平台字段）的档案必须能导进来，不能报错、不能丢条数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCompatTest {

    private fun v4Row(id: String, counterparty: String): String = """
        {
          "id": "$id",
          "amountMinor": -2500,
          "currency": "CNY",
          "occurredAtMillis": 1700000000000,
          "bookedAtMillis": 1700000000000,
          "type": "EXPENSE",
          "direction": "OUT",
          "counterparty": "$counterparty",
          "sourceId": "notify",
          "sourceRef": "$id",
          "fingerprint": "fp-$id",
          "status": "CONFIRMED",
          "confidence": 1.0,
          "schemaVersion": 4
        }
    """.trimIndent()

    @Test
    fun `v4 backup without platform fields imports without losing rows`() {
        // v4 档案：完全没有 platformId / platformConfidence / platformSource 三个字段
        val json = JSONArray().apply {
            put(org.json.JSONObject(v4Row("old-1", "楼下面馆")))
            put(org.json.JSONObject(v4Row("old-2", "沃尔玛")))
            put(org.json.JSONObject(v4Row("old-3", "便利店")))
        }

        val txns = json.toTransactions()

        assertEquals(3, txns.size, "旧档案一条都不能丢")
        assertEquals(setOf("old-1", "old-2", "old-3"), txns.map { it.id }.toSet())
        assertTrue(
            txns.all { it.platformId == PlatformCatalog.UNKNOWN_ID },
            "缺失的平台字段一律兜底为 unknown，而不是崩溃或猜一个",
        )
        assertTrue(txns.all { it.platformConfidence == 0f })
        assertTrue(txns.all { it.platformSource == PlatformSource.AUTO })
        // 其余字段必须原样保留 —— 兼容的是"新字段缺失"，不是"把旧数据抹平"
        assertEquals(setOf("楼下面馆", "沃尔玛", "便利店"), txns.map { it.counterparty }.toSet())
        assertTrue(txns.all { it.amountMinor == -2500L })
        assertTrue(txns.all { it.schemaVersion == 4 })
    }

    @Test
    fun `v5 backup round trips platform fields exactly`() {
        val original = LedgerTransaction(
            id = "new-1",
            amountMinor = -4_500L,
            occurredAtMillis = 1_700_000_000_000L,
            type = TxnType.EXPENSE,
            direction = Direction.OUT,
            counterparty = "肯德基",
            platformId = "wechat",
            platformConfidence = 0.95f,
            platformSource = PlatformSource.USER,
            sourceId = "notify",
            sourceRef = "new-1",
            status = TxnStatus.CONFIRMED,
            schemaVersion = 5,
        )

        val json = JSONArray().put(original.toJson())
        val back = json.toTransactions().single()

        assertEquals("wechat", back.platformId, "平台 ID 必须原样往返")
        assertEquals(0.95f, back.platformConfidence, "置信度必须原样往返")
        assertEquals(PlatformSource.USER, back.platformSource, "USER 标记必须原样往返 —— 丢了用户手选就会被自动值覆盖")
        assertEquals("肯德基", back.counterparty)
        assertEquals(-4_500L, back.amountMinor)
    }

    @Test
    fun `unknown platform id survives a round trip instead of throwing`() {
        // 远端下发 / 用户自定义可能带来未收录的 ID，导入时不得抛异常、不得丢数据
        val original = LedgerTransaction(
            id = "x-1",
            amountMinor = -100L,
            occurredAtMillis = 1L,
            type = TxnType.EXPENSE,
            counterparty = "某店",
            platformId = "not-in-catalog",
            platformConfidence = 0.5f,
            platformSource = PlatformSource.AUTO,
            sourceId = "manual",
            sourceRef = "x-1",
            schemaVersion = 5,
        )
        val json = JSONArray().put(original.toJson())
        val back = json.toTransactions().single()
        assertEquals("not-in-catalog", back.platformId)
        assertEquals("未知平台", PlatformCatalog.displayNameOf(back.platformId))
    }

    @Test
    fun `corrupt platformSource falls back to AUTO`() {
        val json = JSONArray().apply {
            put(
                org.json.JSONObject(v4Row("old-1", "楼下面馆")).apply {
                    put("platformSource", "NOT_A_REAL_ENUM")
                    put("platformId", "meituan")
                },
            )
        }
        val back = json.toTransactions().single()
        assertEquals("meituan", back.platformId, "平台 ID 仍在")
        assertEquals(PlatformSource.AUTO, back.platformSource, "脏枚举值兜底为 AUTO，不得抛异常")
    }

    // ------------------------------------------------------------ sourceId 兜底（单一真源常量）

    /** 缺 sourceId 的最小行（比 v4 还老：连 sourceId / sourceRef 都没有）。 */
    private fun minimalRow(id: String): String = buildString {
        appendLine("{")
        appendLine("""  "id": "$id",""")
        appendLine("""  "amountMinor": -100,""")
        appendLine("""  "currency": "CNY",""")
        appendLine("""  "occurredAtMillis": 1700000000000,""")
        appendLine("""  "type": "EXPENSE",""")
        appendLine("""  "direction": "OUT",""")
        appendLine("""  "counterparty": "某店",""")
        appendLine("""  "status": "CONFIRMED",""")
        appendLine("""  "confidence": 1.0,""")
        appendLine("""  "schemaVersion": 4""")
        append("}")
    }

    @Test
    fun `missing sourceId falls back to the single source of truth MANUAL constant`() {
        // 断言用常量而不用字面量：若哪天 MANUAL 常量被改动，这条会立刻暴露「兜底值与真源脱钩」
        val back = JSONArray().put(org.json.JSONObject(minimalRow("no-src"))).toTransactions().single()
        assertEquals(CaptureSourceIds.MANUAL, back.sourceId, "缺失 sourceId 兜底为 CaptureSourceIds.MANUAL")
    }

    @Test
    fun `a present sourceId value is preserved verbatim`() {
        // 历史档案里已有的任何 sourceId 值必须原样保留 —— 兜底只针对「缺失」，不「统一」旧值
        val row = org.json.JSONObject(minimalRow("with-src")).put("sourceId", CaptureSourceIds.SMS)
        val back = JSONArray().put(row).toTransactions().single()
        assertEquals(CaptureSourceIds.SMS, back.sourceId, "已有的 sourceId 必须原样保留")
    }
}
