package com.autoledger.core.model.platform

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [PlatformCatalog] 的契约护栏。
 *
 * 重点锁两条：
 * 1. **未收录 ID 绝不抛异常**（旧备份导入、远端下发新 ID 都会带来未收录值，崩溃即丢数据）；
 * 2. 目录稳定有序（同分时顺序不随遍历顺序抖动，候选列表才不会每次刷新都跳）。
 */
class PlatformCatalogTest {

    @Test
    fun `unregistered id falls back without throwing`() {
        assertEquals("未知平台", PlatformCatalog.displayNameOf("不存在"))
        assertEquals("未知平台", PlatformCatalog.displayNameOf("jd"))
        assertEquals("未知平台", PlatformCatalog.displayNameOf(""))
        assertNull(PlatformCatalog.find("不存在"))
    }

    @Test
    fun `unknown is a real catalog entry but not a platform guess`() {
        assertNotNull(PlatformCatalog.find(PlatformCatalog.UNKNOWN_ID))
        assertEquals("未知", PlatformCatalog.displayNameOf(PlatformCatalog.UNKNOWN_ID))
        assertNotEquals(
            "未知平台",
            PlatformCatalog.displayNameOf(PlatformCatalog.UNKNOWN_ID),
            "unknown 是平台分布里的一个桶，与「未收录」的兜底文案必须可分",
        )
    }

    @Test
    fun `built in catalog keeps a stable order`() {
        val ids = PlatformCatalog.all().map { it.id }
        assertEquals(
            listOf("wechat", "alipay", "meituan", "pdd", "douyin", "taobao", "unknown"),
            ids,
            "目录顺序 = sortOrder 升序；unknown 必须永远排最后",
        )
        assertEquals(ids, PlatformCatalog.all().map { it.id }, "重复调用必须完全一致")
    }

    @Test
    fun `every built in platform has a distinct non blank display name`() {
        val names = PlatformCatalog.all().map { it.displayName }
        assertEquals(names.size, names.distinct().size, "展示名不得重复")
        names.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `no built in platform other than unknown is left without signals`() {
        for (entry in PlatformCatalog.all()) {
            if (entry.id == PlatformCatalog.UNKNOWN_ID) continue
            assertTrue(
                entry.strongKeywords.isNotEmpty() || entry.mediumKeywords.isNotEmpty() || entry.weakKeywords.isNotEmpty(),
                "${entry.id} 至少要有一个关键词，否则永远识别不出来",
            )
            assertTrue(entry.packageNames.isNotEmpty(), "${entry.id} 至少要映射一个包名")
        }
    }

    @Test
    fun `register adds and overrides by id`() {
        try {
            assertEquals(7, PlatformCatalog.all().size, "前置条件：只有 7 条内置")

            PlatformCatalog.register(
                PlatformEntry(id = "jd", displayName = "京东", mediumKeywords = listOf("京东"), sortOrder = 70),
            )
            assertEquals("京东", PlatformCatalog.displayNameOf("jd"))
            // sortOrder=70 落在 taobao(60) 之后、unknown(MAX) 之前 ⇒ 下标 6
            assertEquals(6, PlatformCatalog.all().indexOfFirst { it.id == "jd" })
            assertEquals(
                PlatformCatalog.UNKNOWN_ID,
                PlatformCatalog.all().last().id,
                "unknown 必须永远排最后",
            )

            PlatformCatalog.register(
                PlatformEntry(id = "jd", displayName = "京东商城", mediumKeywords = listOf("京东"), sortOrder = 70),
            )
            assertEquals("京东商城", PlatformCatalog.displayNameOf("jd"), "同 ID 必须覆盖而不是追加")
            assertEquals(8, PlatformCatalog.all().size, "覆盖后总数不应增加")
        } finally {
            PlatformCatalog.resetExtras()
        }
        assertEquals(7, PlatformCatalog.all().size, "测试结束必须恢复内置目录，避免污染其他用例")
    }

    @Test
    fun `every platform declares whether it is an order platform or a payment channel`() {
        // 「下单平台优先于支付通道」这条判定规则完全建立在 kind 上：
        // 一旦某个平台忘了标 kind，它会落回 OTHER，从而永远赢不了通道 ⇒ 该平台在账单里消失。
        val expected = mapOf(
            "taobao" to PlatformKind.ORDER,
            "meituan" to PlatformKind.ORDER,
            "pdd" to PlatformKind.ORDER,
            "douyin" to PlatformKind.ORDER,
            "alipay" to PlatformKind.PAYMENT,
            "wechat" to PlatformKind.PAYMENT,
            PlatformCatalog.UNKNOWN_ID to PlatformKind.OTHER,
        )
        for (entry in PlatformCatalog.all()) {
            assertEquals(expected[entry.id], entry.kind, "${entry.id} 的角色")
        }
        assertEquals(expected.size, PlatformCatalog.all().size, "新增平台必须同时在这里登记角色")
    }

    @Test
    fun `threshold constants stay in the documented ladder`() {
        assertTrue(PlatformResolver.UNKNOWN_THRESHOLD < PlatformResolver.AMBIGUOUS_TOP_FLOOR)
        assertTrue(PlatformResolver.AMBIGUOUS_TOP_FLOOR < PlatformResolver.CONFIRM_THRESHOLD)
        assertTrue(PlatformResolver.AMBIGUOUS_GAP > 0f)
        assertTrue(PlatformResolver.TOP_N >= 2, "至少要 2 个候选才能判歧义")
    }
}
