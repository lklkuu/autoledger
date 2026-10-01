package com.autoledger.core.model.platform

import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.toPlatformEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [PlatformCatalog] 的契约护栏。
 *
 * 重点锁四条：
 * 1. **未收录 ID 绝不抛异常**（旧备份导入、远端下发新 ID 都会带来未收录值，崩溃即丢数据）；
 * 2. 目录稳定有序（同分时顺序不随遍历顺序抖动，候选列表才不会每次刷新都跳）；
 * 3. **`all()` 的缓存必须有失效**（否则注册自定义平台后目录不更新；
 *    或反过来说，每次调用都重排会让账单列表每行渲染都排一次序）；
 * 4. **层级映射（`priorityOf`）**：它是「合并后谁留下」的唯一依据，写错会静默吞掉真实消费。
 */
class PlatformCatalogTest {

    /** 内置 ID 的完整名单，按 sortOrder 升序。新增内置平台必须同步改这里。 */
    private val builtInIds = listOf(
        "wechat", "alipay", "meituan", "pdd", "douyin", "taobao",
        "bank", "digital_rmb", "unionpay",
        PlatformCatalog.UNKNOWN_ID,
    )

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
            builtInIds,
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
    fun `every built in platform other than unknown carries at least one keyword`() {
        // 关键词是识别引擎的唯一输入（R12：引擎里不得硬编码关键词）。
        // 没有关键词的平台 = 永远识别不出来，等于没加。
        for (entry in PlatformCatalog.all()) {
            if (entry.id == PlatformCatalog.UNKNOWN_ID) continue
            assertTrue(
                entry.strongKeywords.isNotEmpty() || entry.mediumKeywords.isNotEmpty() || entry.weakKeywords.isNotEmpty(),
                "${entry.id} 至少要有一个关键词，否则永远识别不出来",
            )
        }
    }

    @Test
    fun `only verified package names are mapped`() {
        // 写错包名 = 整类通知被**恒定错判**平台，比暂时不识别危害大得多。
        // 因此「没有映射包名」是一个**被批准的显式状态**，而不是遗漏：
        // 银行 App 包名零散、数字人民币/云闪付包名待核实 ⇒ 一律留空，靠关键词兜底。
        val approvedWithoutPackage = setOf(
            "bank",        // 各银行 App 包名零散，本期不逐个收录
            "digital_rmb", // 待核实
            "unionpay",    // 待核实
        )
        for (entry in PlatformCatalog.all()) {
            if (entry.id == PlatformCatalog.UNKNOWN_ID) continue
            if (entry.id in approvedWithoutPackage) {
                assertTrue(
                    entry.packageNames.isEmpty(),
                    "${entry.id} 的包名未经核实，必须留空（设计文档 §3.2）",
                )
                continue
            }
            assertTrue(entry.packageNames.isNotEmpty(), "${entry.id} 至少要映射一个已核实的包名")
        }
        // 反向护栏：approve 名单不能悄悄过期 —— 一旦某条真的补上了包名，就该把它从名单里删掉。
        for (id in approvedWithoutPackage) {
            assertNotNull(PlatformCatalog.find(id), "$id 必须在目录里（名单过期了？）")
        }
    }

    @Test
    fun `register adds and overrides by id`() {
        try {
            assertEquals(builtInIds.size, PlatformCatalog.all().size, "前置条件：只有内置条目")

            PlatformCatalog.register(
                PlatformEntry(
                    id = "jd", displayName = "京东",
                    mediumKeywords = listOf("京东"),
                    sortOrder = UserPlatform.DEFAULT_SORT_ORDER,
                ),
            )
            assertEquals("京东", PlatformCatalog.displayNameOf("jd"))
            // 1000 落在 unionpay(90) 之后、unknown(MAX) 之前 ⇒ 下标 = 内置条数 - 1
            assertEquals(builtInIds.size - 1, PlatformCatalog.all().indexOfFirst { it.id == "jd" })
            assertEquals(
                PlatformCatalog.UNKNOWN_ID,
                PlatformCatalog.all().last().id,
                "unknown 必须永远排最后",
            )

            PlatformCatalog.register(
                PlatformEntry(
                    id = "jd", displayName = "京东商城",
                    mediumKeywords = listOf("京东"),
                    sortOrder = UserPlatform.DEFAULT_SORT_ORDER,
                ),
            )
            assertEquals("京东商城", PlatformCatalog.displayNameOf("jd"), "同 ID 必须覆盖而不是追加")
            assertEquals(builtInIds.size + 1, PlatformCatalog.all().size, "覆盖后总数不应增加")
        } finally {
            PlatformCatalog.resetExtras()
        }
        assertEquals(builtInIds.size, PlatformCatalog.all().size, "测试结束必须恢复内置目录，避免污染其他用例")
    }

    @Test
    fun `register and unregister both invalidate the cached catalog`() {
        // 缓存 + 失效是一对：漏了失效，「新增自定义平台后选择器里看不到」这种 bug
        // 会只在「缓存已经被暖起来」的路径上出现（冷启动测试常常测不到）。
        try {
            val before = PlatformCatalog.all().size // 先把缓存暖起来

            PlatformCatalog.register(
                PlatformEntry(id = "jd", displayName = "京东", mediumKeywords = listOf("京东"), sortOrder = 1000),
            )
            assertEquals(before + 1, PlatformCatalog.all().size, "注册后缓存必须失效并重新排序")
            assertEquals("京东", PlatformCatalog.displayNameOf("jd"), "find() 也必须看到新条目")

            PlatformCatalog.unregister("jd")
            assertEquals(before, PlatformCatalog.all().size, "注销后缓存同样必须失效")
            assertNull(PlatformCatalog.find("jd"))
            assertEquals("未知平台", PlatformCatalog.displayNameOf("jd"))
        } finally {
            PlatformCatalog.resetExtras()
        }
    }

    @Test
    fun `unregister on a built in id is a no-op rather than an error`() {
        // 内置条目只能靠 register 覆盖，不能靠 unregister 移除。
        // 做成 no-op（而不是抛异常）是为了让调用方不必先判断「这是不是内置的」。
        try {
            PlatformCatalog.unregister("wechat")
            assertNotNull(PlatformCatalog.find("wechat"), "内置条目不可被 unregister 移除")
            assertEquals("微信", PlatformCatalog.displayNameOf("wechat"))
        } finally {
            PlatformCatalog.resetExtras()
        }
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
            // 云闪付 / 数字人民币是**官方数字通道**（E_WALLET）—— 见设计文档 §10-⑩：
            // 优先级**高于银行卡、低于微信/支付宝**，既不是消费场所、也不是第三方支付通道。
            "unionpay" to PlatformKind.E_WALLET,
            "digital_rmb" to PlatformKind.E_WALLET,
            // 银行卡：最底层资金源；塞进 PAYMENT 会与微信同级、排不出「银行卡最低」
            "bank" to PlatformKind.BANK,
            PlatformCatalog.UNKNOWN_ID to PlatformKind.OTHER,
        )
        for (entry in PlatformCatalog.all()) {
            assertEquals(expected[entry.id], entry.kind, "${entry.id} 的角色")
        }
        assertEquals(expected.size, PlatformCatalog.all().size, "新增平台必须同时在这里登记角色")
    }

    // ------------------------------------------------------------------ 层级映射（去重语义）

    @Test
    fun `role maps to the documented dedup priority ladder`() {
        assertEquals(PlatformPriority.ORDER, PlatformKind.ORDER.toPriority())
        assertEquals(PlatformPriority.PAYMENT, PlatformKind.PAYMENT.toPriority())
        assertEquals(PlatformPriority.E_WALLET, PlatformKind.E_WALLET.toPriority())
        assertEquals(PlatformPriority.BANK, PlatformKind.BANK.toPriority())
        assertEquals(PlatformPriority.NONE, PlatformKind.OTHER.toPriority())

        // 需求四档：美团(下单) > 微信/支付宝(通道) > 数币/云闪付(官方数字通道) > 银行卡
        assertTrue(PlatformPriority.ORDER.rank > PlatformPriority.PAYMENT.rank)
        assertTrue(PlatformPriority.PAYMENT.rank > PlatformPriority.E_WALLET.rank)
        assertTrue(PlatformPriority.E_WALLET.rank > PlatformPriority.BANK.rank)
        assertTrue(PlatformPriority.BANK.rank > PlatformPriority.NONE.rank)
        // rank 是 Int 且新增中间层是靠**重排**（E_WALLET=2 插在 BANK=1 与 PAYMENT=3 之间），
        // 不是插 1.5 —— 这里顺带钉死相对顺序，防止后人改成小数式排布。
        assertEquals(0, PlatformPriority.NONE.rank)
        assertEquals(1, PlatformPriority.BANK.rank)
        assertEquals(2, PlatformPriority.E_WALLET.rank)
        assertEquals(3, PlatformPriority.PAYMENT.rank)
        assertEquals(4, PlatformPriority.ORDER.rank)
    }

    @Test
    fun `priorityOf resolves built in platforms by id`() {
        assertEquals(PlatformPriority.ORDER, priorityOf("meituan"))
        assertEquals(PlatformPriority.PAYMENT, priorityOf("alipay"))
        assertEquals(PlatformPriority.PAYMENT, priorityOf("wechat"))
        assertEquals(PlatformPriority.E_WALLET, priorityOf("digital_rmb"))
        assertEquals(PlatformPriority.E_WALLET, priorityOf("unionpay"))
        assertEquals(PlatformPriority.BANK, priorityOf("bank"))
        assertEquals(PlatformPriority.NONE, priorityOf(PlatformCatalog.UNKNOWN_ID))
    }

    @Test
    fun `priorityOf on an unknown id is NONE and never throws`() {
        // 旧备份 / 远端下发都可能带来未收录 ID；此处抛异常会让「导入备份」直接失败。
        assertEquals(PlatformPriority.NONE, priorityOf("不存在"))
        assertEquals(PlatformPriority.NONE, priorityOf(""))
        assertEquals(0, priorityOf("user:尚未注册").rank)
    }

    @Test
    fun `a user defined order platform outranks a payment channel`() {
        // 自定义平台注入目录后必须**同样参与层级比较**，否则 Tier-2 对它们失效。
        try {
            PlatformCatalog.register(
                PlatformEntry(
                    id = "user:jd", displayName = "京东", kind = PlatformKind.ORDER,
                    mediumKeywords = listOf("京东"), sortOrder = 1000,
                ),
            )
            assertEquals(PlatformPriority.ORDER, priorityOf("user:jd"))
            assertTrue(priorityOf("user:jd").rank > priorityOf("alipay").rank)
        } finally {
            PlatformCatalog.resetExtras()
        }
    }

    @Test
    fun `threshold constants stay in the documented ladder`() {
        assertTrue(PlatformResolver.UNKNOWN_THRESHOLD < PlatformResolver.AMBIGUOUS_TOP_FLOOR)
        assertTrue(PlatformResolver.AMBIGUOUS_TOP_FLOOR < PlatformResolver.CONFIRM_THRESHOLD)
        assertTrue(PlatformResolver.AMBIGUOUS_GAP > 0f)
        assertTrue(PlatformResolver.TOP_N >= 2, "至少要 2 个候选才能判歧义")
    }

    @Test
    fun `bank weak keywords win nothing below the unknown threshold`() {
        // bank 兜底靠 weak(0.35)；它必须**不低于** UNKNOWN_THRESHOLD，否则兜底等于没兜。
        val bank = PlatformCatalog.find(PlatformCatalog.BANK_ID)
        assertNotNull(bank, "bank 必须在目录里，否则银行线索兜底失效")
        assertTrue(bank.weakKeywords.isNotEmpty(), "bank 只有 weak 关键词，不能是空的")
        assertEquals(PlatformKind.BANK, bank.kind)
        assertFalse(
            PlatformResolver.UNKNOWN_THRESHOLD > 0.35f,
            "bank 的 weak 分(0.35)若低于 unknown 阈值，兜底会落回 unknown",
        )
    }

    // ------------------------------------------------------------------ 自定义平台 → 目录条目

    @Test
    fun `a user defined platform round trips into the catalog without losing signals`() {
        try {
            val jd = UserPlatform(
                id = "user:jd", displayName = "京东", kind = PlatformKind.ORDER,
                strongKeywords = listOf("京东支付"), mediumKeywords = listOf("京东"),
                weakKeywords = listOf("京东物流"), packageNames = setOf("com.jingdong.app.mall"),
                sortOrder = 1000, createdAtMillis = 1_700_000_000_000L,
            )
            PlatformCatalog.register(jd.toPlatformEntry())

            val back = PlatformCatalog.find(jd.id)
            assertEquals("京东", back?.displayName)
            assertEquals(PlatformKind.ORDER, back?.kind)
            assertEquals(listOf("京东支付"), back?.strongKeywords)
            assertEquals(setOf("com.jingdong.app.mall"), back?.packageNames)
            assertEquals(PlatformPriority.ORDER, priorityOf(jd.id), "自定义平台必须参与层级比较")
        } finally {
            PlatformCatalog.resetExtras()
        }
    }

    @Test
    fun `a default user platform sorts after every built in platform`() {
        // 「内置永远排在自定义前面」：靠 DEFAULT_SORT_ORDER 实现，不是靠注册顺序。
        val builtInMax = PlatformCatalog.all()
            .filter { it.id != PlatformCatalog.UNKNOWN_ID }
            .maxOf { it.sortOrder }
        assertTrue(
            UserPlatform.DEFAULT_SORT_ORDER > builtInMax,
            "自定义平台默认 sortOrder(${UserPlatform.DEFAULT_SORT_ORDER}) 必须大于全部内置最大值($builtInMax)",
        )
    }

    @Test
    fun `user platform ids cannot collide with built in ids`() {
        val generated = com.autoledger.core.model.newUserPlatformId()
        assertTrue(generated.startsWith(UserPlatform.ID_PREFIX), "生成 ID 必须带 user: 前缀：$generated")
        assertNull(PlatformCatalog.find(generated), "内置目录不可能包含随机 UUID 生成的 ID")
        assertNotEquals(
            com.autoledger.core.model.newUserPlatformId(),
            generated,
            "两次生成必须不同（否则两笔自定义平台会互相覆盖）",
        )
    }
}
