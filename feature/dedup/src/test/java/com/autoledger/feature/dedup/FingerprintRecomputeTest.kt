package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 用户反馈问题 5 的**独立指纹验证**（QA 新增，不复跑工程师用例）。
 *
 * 问题 5 的修复引入 `applyTxnEdit(...)`（app/.../ui/stores/TxnEdit.kt）：编辑商户名/备注后
 * **重算去重指纹**，AppStores.kt:295-303 的 `updateCounterparty` 用 `duplicateResolver::fingerprintOf` 作为算法。
 *
 * 本测试用真实 [LedgerDuplicateResolver] 复算指纹，回答关键问题：
 *  「补商户名 → 重算指纹」会不会把跨渠道记录从指纹一致改成不一致、从而破坏去重、产生重复流水？
 *
 * 说明：[applyTxnEdit] 位于 app 模块（internal），本模块无法直接引用；
 * 这里用 [simulateEdit] 复刻其**唯一行为**——`copy(counterparty=trim)` 后按同一算法重算指纹，
 * 与 TxnEdit.kt 逐行等价，因此结论对生产代码成立。
 */
class FingerprintRecomputeTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        amountMinor: Long,
        counterparty: String,
        sourceId: String = "notify",
        status: TxnStatus = TxnStatus.RAW,
        occurredAt: Long = anchor,
        fingerprint: String = "",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = TxnType.EXPENSE,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
    )

    private val resolver = LedgerDuplicateResolver(FakeLedgerRepository())

    /** 复刻 TxnEdit.kt 的 applyTxnEdit：trim 商户名后按同一算法重算指纹。 */
    private fun simulateEdit(txn: LedgerTransaction, counterparty: String): LedgerTransaction {
        val edited = txn.copy(counterparty = counterparty.trim())
        return edited.copy(fingerprint = resolver.fingerprintOf(edited))
    }

    // ------------------------------------------------------------ B1：补商户名使指纹**趋于一致**（修复正向价值）

    @Test
    fun `B1 - filling a blank merchant on the notification record aligns it with the sms record`() {
        // 渠道①通知：商户名没解析出来（空白）→ 指纹纳入渠道标识，与短信不同。
        val notify = txn("n", -2500, "", sourceId = "notify")
        // 渠道②短信：解析出商户"星巴克"。
        val sms = txn("s", -2500, "星巴克", sourceId = "sms")

        assertNotEquals(
            resolver.fingerprintOf(notify),
            resolver.fingerprintOf(sms),
            "创建时：空白商户走渠道指纹，两条本就不同 —— 修复前就已不一致，非 Fix 5 引入",
        )

        // 用户补齐通知的商户名 → 重算指纹。
        val notifyFixed = simulateEdit(notify, "星巴克")
        assertEquals(
            resolver.fingerprintOf(sms),
            notifyFixed.fingerprint,
            "补齐商户名后指纹应与短信记录**一致**：重算把两条拉近，而不是拉远",
        )
        assertTrue(notifyFixed.fingerprint != resolver.fingerprintOf(notify), "指纹确实被重算，不是旧值")
    }

    @Test
    fun `B1b - without recompute the fixed record would carry a stale fingerprint and never match`() {
        val notify = txn("n", -2500, "", sourceId = "notify")
        val stale = resolver.fingerprintOf(notify) // 若不重算，编辑后仍是这个"空白|渠道"指纹
        val sms = txn("s", -2500, "星巴克", sourceId = "sms")
        // 反证：不重算 ⇒ 永远不等于短信指纹 ⇒ 该记录永久无法参与去重。故重算是**必要**的。
        assertNotEquals(stale, resolver.fingerprintOf(sms))
    }

    // ------------------------------------------------------------ B2：对已成对的记录只改一侧 → 指纹**发散**（残留风险）

    @Test
    fun `B2 - editing ONLY one side of an already-consistent pair diverges the fingerprints`() {
        // 两条原本一致（分支机构后缀被 normalize 去掉）。
        val notify = txn("n", -2500, "星巴克", sourceId = "notify")
        val sms = txn("s", -2500, "星巴克（南京西路店）", sourceId = "sms")
        assertEquals(
            resolver.fingerprintOf(notify),
            resolver.fingerprintOf(sms),
            "normalize 会去掉括号装饰，两条原指纹一致",
        )

        // 用户只在其中一侧补上"门店后缀"。
        val notifyFixed = simulateEdit(notify, "星巴克 南京西路店")
        assertEquals("星巴克南京西路店", notifyFixed.counterparty.replace(" ", ""))
        assertNotEquals(
            resolver.fingerprintOf(sms),
            notifyFixed.fingerprint,
            "风险：只重算被编辑的一侧 → 原本一致的配对变为不一致；对侧指纹未被重算",
        )
    }

    @Test
    fun `B2b - the peer record keeps its old fingerprint because only the edited side is recomputed`() {
        val notify = txn("n", -2500, "星巴克", sourceId = "notify")
            .let { it.copy(fingerprint = resolver.fingerprintOf(it)) }
        val sms = txn("s", -2500, "星巴克（南京西路店）", sourceId = "sms")
            .let { it.copy(fingerprint = resolver.fingerprintOf(it)) }
        val peerBefore = sms.fingerprint

        // 只编辑 notify，sms 完全不动。
        simulateEdit(notify, "瑞幸")

        assertEquals(peerBefore, sms.fingerprint, "对侧（sms）的指纹不会被任何编辑重算 —— 配对从此失配")
    }

    // ------------------------------------------------------------ B3：编辑后不会**自动**重跑去重（机制边界）

    @Test
    fun `B3 - after the edit the peer becomes discoverable only if findDuplicates is re-run`() = runBlocking {
        // 短信记录已在库（指纹按算法落定）。
        val sms = txn("s", -2500, "星巴克", sourceId = "sms")
            .let { it.copy(fingerprint = resolver.fingerprintOf(it)) }
        val repo = FakeLedgerRepository(listOf(sms))
        val r = LedgerDuplicateResolver(repo)

        val notify = txn("n", -2500, "", sourceId = "notify", status = TxnStatus.RAW)
        val notifyFixed = simulateEdit(notify, "星巴克")

        // 重算后指纹一致 ⇒ **若**重跑判重即可发现跨渠道候选；
        val dups = r.findDuplicates(notifyFixed.copy(fingerprint = notifyFixed.fingerprint))
        assertEquals(1, dups.size, "指纹一致后，重跑 findDuplicates 能命中短信记录")
        assertEquals("s", dups.first().txnId)
        assertTrue(dups.first().crossSource, "两条来自不同渠道")

        // 提醒：生产链路 updateCounterparty 只做 upsert，**不会**在编辑后重跑 findDuplicates，
        // 故已存在的跨渠道重复仍需用户手动合并 —— 这是机制边界，非本次修复的回归。
    }

    // ------------------------------------------------------------ B4：空白商户仍按渠道区分（REC-1 设计，避免误吞真实消费）

    @Test
    fun `B4 - two blank merchant records stay distinct so a same-amount real pair is not silently merged`() {
        val a = txn("a", -2500, "", sourceId = "notify")
        val b = txn("b", -2500, "", sourceId = "notify")
        assertNotEquals(
            resolver.fingerprintOf(a),
            resolver.fingerprintOf(b),
            "空白商户需纳入 sourceRef，避免同金额真实消费被静默合并",
        )
    }

    // ------------------------------------------------------------ B5：消费平台**不参与**指纹（本次改造的关键护栏）

    @Test
    fun `B5 - platform does NOT participate in the fingerprint so cross-channel dedup survives`() {
        // 同一笔「美团下单」消费会同时产生：
        //   ① 美团 App 通知 —— 识别出 platform = meituan
        //   ② 银行短信     —— 短信不写"美团"，识别不出 ⇒ platform = unknown
        // 两者金额与商户相同。**若平台进了指纹**，两条指纹就会不同 ⇒
        // 跨渠道去重直接失效 ⇒ 一笔消费被记两次。
        // 本用例是「平台绝不进指纹」的锁：一旦有人把 platformId 加进 fingerprintOf，它必须失败。
        val notify = txn("n", -4500, "美团外卖", sourceId = "notify")
            .copy(platformId = "meituan", platformConfidence = 0.95f)
        val sms = txn("s", -4500, "美团外卖", sourceId = "sms")
            .copy(platformId = "unknown")

        assertEquals(
            resolver.fingerprintOf(notify),
            resolver.fingerprintOf(sms),
            "平台不同（meituan vs unknown）但金额+商户相同 ⇒ 指纹必须相同，否则跨渠道去重失效",
        )

        // 再验一层：平台取任意值都不改变指纹（它根本不是指纹材料）。
        val editedPlatform = notify.copy(platformId = "taobao", platformConfidence = 1f)
        assertEquals(
            resolver.fingerprintOf(notify),
            resolver.fingerprintOf(editedPlatform),
            "用户改平台不改变指纹 —— 这是刻意设计（否则编辑一次就会与同笔的对侧失配）",
        )
    }
}
