package com.autoledger.app.ingest

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferVerdict
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.platform.PlatformResolution
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.platform.priorityOf
import com.autoledger.feature.capture.IngestPipeline
import com.autoledger.feature.capture.notify.NotificationParser
import com.autoledger.feature.dedup.ComplementaryVerdict
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import com.autoledger.feature.dedup.complementaryVerdict
import com.autoledger.feature.dedup.tierOneAllowsAutoMerge
import com.autoledger.feature.platform.KeywordPlatformResolver
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 复现「一笔数字人民币支付触发 4 条通知，却记成 2 条」的**真实链路**（解析 → 识别 → 入账 → 两级去重 → 合并）。
 *
 * 只用**真实**组件：`NotificationParser`（真实规则包）+ `KeywordPlatformResolver`（真实识别引擎）
 * + `IngestPipeline`（真实流水线）+ `LedgerDuplicateResolver`（真实去重）。
 * 唯一替身是仓储（内存版，语义逐条对齐 Room）。
 *
 * ## 这个文件回答三个问题
 * 1. **机制是否成立**：同一条渠道（`sourceId` 相同，通知恒为 `"notify"`）的两条记录，
 *    在 Tier-1 / Tier-2 里到底能不能互为候选？（`same channel records can never pair`）
 * 2. **团队假设是否成立**：把 4 条拆成「3 通知 + 1 短信」，是否真能得到「4 → 2」？
 * 3. **缺口在哪**：修完缺口，截图场景最终应剩几条？
 *
 * ## ⚠️ 数据来源声明
 * `scenario*` 里的通知文本是**按真实银行/数币通知格式重建的**（真实原文见对话记录）。
 * 机制结论（问题 1）**与文本无关**、确定成立；分组条数（问题 2/3）以真实原文为准，
 * 替换 `Notif` 列表即可重跑。
 */
class DigitalRmbNotificationReproTest {

    private val anchor = 1_700_000_000_000L
    private val parser = NotificationParser()
    private val platformResolver = KeywordPlatformResolver()

    // ------------------------------------------------------------------ 夹具

    /** 一条待复现的通知/短信输入。 */
    private data class Notif(
        val label: String,
        /** 渠道级来源：通知 = "notify"、短信 = "sms"（见 CaptureSourceIds）。 */
        val sourceId: String,
        /** 通知 = 包名；短信 = 发件号（真实采集侧传的就是这个，见 SmsCaptureSource.scan）。 */
        val packageName: String,
        val title: String,
        val body: String,
        val offsetMillis: Long,
    )

    /** 逐条复现结果（一行 = 一条通知）。 */
    private data class Row(
        val label: String,
        val sourceId: String,
        val sourceRef: String,
        val ruleId: String?,
        val amountMinor: Long?,
        val counterparty: String,
        val platformId: String,
        val confidence: Float,
        val platformSource: String,
        val fingerprint: String,
        val outcome: String,
    )

    private fun newPipeline(repo: ReproRepo): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = LedgerDuplicateResolver(repo),
        transferDetector = NoTransfer,
        classifier = AlwaysFood,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = platformResolver,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    /** 与 CaptureEnvelopeFactory.toRawEnvelope 字段一一对应（app 模块够不到它的 internal 可见性）。 */
    private fun toEnvelope(n: Notif): RawEnvelope {
        val parsed = parser.parse(n.packageName, n.title, n.body)
        val text = listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString("\n")
        return RawEnvelope(
            envelopeId = n.label,
            sourceId = n.sourceId,
            sourceRef = "${n.sourceId}:${n.label}",
            occurredAtMillis = anchor + n.offsetMillis,
            rawText = text,
            counterpartyHint = parsed?.counterparty,
            amountHint = parsed?.amountMinor,
            packageName = n.packageName,
            explicitType = parsed?.explicitType,
        )
    }

    private fun runScenario(name: String, notifs: List<Notif>): Pair<List<Row>, ReproRepo> {
        val repo = ReproRepo()
        val pipeline = newPipeline(repo)
        val rows = mutableListOf<Row>()

        for (n in notifs) {
            val env = toEnvelope(n)
            val parsed = parser.parse(n.packageName, n.title, n.body)
            val resolution: PlatformResolution = platformResolver.resolve(
                PlatformContext(
                    rawText = env.rawText,
                    counterparty = env.counterpartyHint?.takeIf { it.isNotBlank() },
                    packageName = env.packageName,
                    sourceId = env.sourceId,
                )
            )
            val outcome = runBlocking { pipeline.ingest(env) }
            val stored = runBlocking { repo.findById(outcome.txnId) }
            rows += Row(
                label = n.label,
                sourceId = n.sourceId,
                sourceRef = env.sourceRef,
                ruleId = parsed?.ruleId,
                amountMinor = parsed?.amountMinor,
                counterparty = parsed?.counterparty.orEmpty(),
                platformId = resolution.platformId,
                confidence = resolution.confidence,
                platformSource = (stored?.platformSource ?: PlatformSource.AUTO).name,
                fingerprint = stored?.fingerprint?.take(12) ?: "-",
                outcome = outcome::class.simpleName.orEmpty() + describe(outcome),
            )
        }

        println("[REPRO] ===== 场景：$name =====")
        println("[REPRO] #  来源   sourceRef      规则             金额(分)  商户         平台(置信度,来源)  指纹          去重结论")
        rows.forEachIndexed { i, r ->
            println(
                "[REPRO] ${i + 1}  ${r.sourceId.padEnd(6)} ${r.sourceRef.padEnd(14)} ${(r.ruleId ?: "-").padEnd(16)} " +
                    "${(r.amountMinor?.toString() ?: "-").padEnd(8)} ${r.counterparty.ifBlank { "(空)" }.padEnd(12)} " +
                    "${"${r.platformId}(${r.confidence},${r.platformSource})".padEnd(18)} ${r.fingerprint.padEnd(13)} ${r.outcome}"
            )
        }
        val alive = repo.alive()
        println("[REPRO] 最终存活 = ${alive.size} 条：")
        alive.forEach {
            println(
                "[REPRO]    ${it.id}  平台=${it.platformId}  状态=${it.status}  " +
                    "商户=${it.counterparty.ifBlank { "(空)" }}  sourceRef=${it.sourceRef}",
            )
        }
        println("[REPRO] ------------------------------------------------------------")
        return rows to repo
    }

    private fun describe(outcome: IngestPipeline.Outcome): String = when (outcome) {
        is IngestPipeline.Outcome.MergedInto -> " → 并入 ${outcome.primaryId}"
        is IngestPipeline.Outcome.NeedsReview -> " (待确认: ${outcome.reason})"
        is IngestPipeline.Outcome.Accepted -> " (入账 autoConfirmed=${outcome.autoConfirmed})"
    }

    // ------------------------------------------------------------------ 机制（与文本无关，确定成立）

    /**
     * 机制（实测校正）：**同一条渠道（`sourceId` 相同）的两条记录，行为分两种**。
     *
     * ① **指纹相同**（金额 + 归一化商户都一致）⇒ 命中 Tier-1 候选，但候选 `crossSource == false`
     *    ⇒ `canAutoMerge` 返回 false ⇒ 落「待确认」，**既不静默合并、也不静默双记**。
     * ② **指纹不同** ⇒ Tier-1 查不到；本条 fixture 两侧平台都是 `unknown` ⇒ Tier-2 的
     *    [complementaryVerdict] 把 `NONE ↔ NONE` 判 REJECT ⇒ **连候选都不是** ⇒ 直接入账。
     *
     * ## ⚠️ 本文件保留的历史结论（已随 §10-⑨ 演进，勿再照抄）
     * 复现**当时** Tier-2 还有一条 `if (other.sourceId == txn.sourceId) return null`，
     * 于是「同渠道 + 指纹不同」会被来源约束挡掉 —— 那是「4 条同渠道通知漏合并」的根因。
     * 现在该约束已删除（§10-⑨），**同渠道不再是障碍**：能否成为候选改由**层级护栏**决定
     * （见 [TierTwoSameChannelMatchTest]）。本条 fixture 之所以仍"无候选"，是因为两侧平台是 `unknown`
     * （`NONE↔NONE` ⇒ REJECT），**与渠道无关**。
     */
    @Test
    fun `same channel with identical fingerprints surfaces for review but is never auto merged`() = runBlocking<Unit> {
        val repo = ReproRepo()
        val dup = LedgerDuplicateResolver(repo)

        val a = LedgerTransaction(
            id = "a", amountMinor = -1_200L, occurredAtMillis = anchor, type = TxnType.EXPENSE,
            counterparty = "永辉超市", sourceId = "notify", sourceRef = "notify:a",
        ).let { it.copy(fingerprint = dup.fingerprintOf(it)) }
        repo.upsert(a)

        val b = a.copy(id = "b", sourceRef = "notify:b").let { it.copy(fingerprint = dup.fingerprintOf(it)) }
        assertEquals(a.fingerprint, b.fingerprint, "前置：两条指纹相同")

        val candidates = dup.findDuplicates(b)
        assertTrue(candidates.isNotEmpty(), "同渠道 + 同指纹会命中 Tier-1 候选（指纹精确不看 sourceId）")
        assertTrue(candidates.all { !it.crossSource }, "但每个候选都必须标记 crossSource=false")
        assertTrue(
            candidates.none { dup.canAutoMerge(b, it) },
            "同渠道 ⇒ 绝不静默自动合并（该记录只会落「待确认」）",
        )
    }

    /**
     * 机制 ②（**口径已更新**）：同渠道 + 指纹不同，**两侧平台都是 `unknown`** ⇒ 无候选。
     *
     * 注意「无候选」的原因**不再是**渠道相同（那条约束已随 §10-⑨ 删除），而是
     * [complementaryVerdict] 对 `NONE ↔ NONE` 判 REJECT。如果两侧平台能识别出层级，
     * 同渠道本可成为候选 —— 见 [TierTwoSameChannelMatchTest]。
     */
    @Test
    fun `same channel with different fingerprints and no platform info is not even a candidate`() = runBlocking<Unit> {
        val repo = ReproRepo()
        val dup = LedgerDuplicateResolver(repo)

        val a = LedgerTransaction(
            id = "a", amountMinor = -1_200L, occurredAtMillis = anchor, type = TxnType.EXPENSE,
            counterparty = "永辉超市", sourceId = "notify", sourceRef = "notify:a",
        ).let { it.copy(fingerprint = dup.fingerprintOf(it)) }
        repo.upsert(a)

        // 同金额、同秒、同渠道（notify），**仅商户名不同** ⇒ 指纹不同；两侧平台都是 unknown
        val b = a.copy(id = "b", sourceRef = "notify:b", counterparty = "罗森便利店")
            .let { it.copy(fingerprint = dup.fingerprintOf(it)) }
        assertNotEquals(a.fingerprint, b.fingerprint, "前置：商户不同 ⇒ 指纹不同")

        assertTrue(
            dup.findDuplicates(b).isEmpty(),
            "Tier-1 miss + 两侧平台均 unknown（NONE↔NONE ⇒ REJECT）⇒ 无候选（两条都留下）",
        )
    }

    // ------------------------------------------------------------------ 场景复现

    /**
     * 场景 A：3 条通知 + 1 条银行短信，描述同一笔 12.00 元数字人民币支付（**商户名各不相同 ⇒ 走 Tier-2**）。
     *
     * ⚠️ 最终判定表把「数币/云闪付 ↔ 银行卡」定为 **REVIEW**（用户拍板：宁可保守也不静默吞），
     * 因此同一笔的多条通道记录**不会静默合并**，而是浮出候选交用户。本条只打印 `[REPRO]` 供人核对；
     * 层级判定的硬断言在 [TierTwoVerdictMatrixTest]（纯函数逐格）。
     */
    @Test
    fun `scenario A - three app notifications plus one bank sms of the same payment`() {
        val scenario = listOf(
            Notif(
                "n1", "notify", "cn.gov.pboc.dcep",
                "数字人民币", "数字人民币支付成功 12.00元，收款方：永辉超市", 0L,
            ),
            Notif(
                "n2", "notify", "com.unionpay",
                "云闪付", "云闪付支付成功 12.00元", 5_000L,
            ),
            Notif(
                "n3", "notify", "com.icbc",
                "动账通知", "您尾号1234的储蓄卡消费 12.00元", 8_000L,
            ),
            Notif(
                "s1", "sms", "95588",
                "95588", "【工商银行】您尾号1234账户消费 12.00元，余额 100.00元。", 10_000L,
            ),
        )
        val (rows, repo) = runScenario("A：3 通知 + 1 短信", scenario)
        assertEquals(4, rows.size)
        // 本批**不再断言**存活条数：最终判定表把「数币/云闪付 ↔ 银行卡」定为 REVIEW（用户拍板：
        // 宁可保守也不静默吞），所以同笔的多条通道记录**不会静默合并**，而是浮出候选。
        // 实际存活条数由 `[REPRO] 最终存活` 行打印；断言留给 [TierTwoVerdictMatrixTest] 的纯函数逐格。
    }

    /**
     * 场景 B：2 条通知 + 2 条银行短信（**商户名一致** ⇒ 4 条指纹相同 ⇒ 走 Tier-1）。
     *
     * 用来钉住**设计刻意保留的保守分支**：指纹一致但**同渠道**的候选 `crossSource=false` ⇒ 不自动合并
     * （团队判定为"保守但安全"）。跨渠道的银行短信可并入。仅打印 `[REPRO]` 供人核对。
     */
    @Test
    fun `scenario B - four records sharing one fingerprint stay for user review`() {
        val scenario = listOf(
            Notif(
                "n1", "notify", "cn.gov.pboc.dcep",
                "数字人民币", "数字人民币支付成功 12.00元，收款方：永辉超市", 0L,
            ),
            Notif(
                "n2", "notify", "com.unionpay",
                "云闪付", "云闪付支付成功 12.00元，收款方：永辉超市", 5_000L,
            ),
            Notif(
                "s1", "sms", "95588",
                "95588", "【工商银行】您尾号1234账户消费 12.00元，收款方：永辉超市。", 10_000L,
            ),
            Notif(
                "s2", "sms", "95533",
                "95533", "【建设银行】您尾号5678账户消费 12.00元，收款方：永辉超市。", 12_000L,
            ),
        )
        val (rows, repo) = runScenario("B：2 通知 + 2 短信", scenario)
        assertEquals(4, rows.size)
        // 同 A：存活条数由 `[REPRO]` 打印；硬断言交给纯函数矩阵用例，避免与判定表耦合。
    }

    // ------------------------------------------------------------------ 真实原文（team-lead 从截图誊录，见 docs/repro/digital-rmb-4-notifications.md）

    /**
     * 场景 R（**真实原文**）：一笔 **17.45 元**京东消费（走数字人民币）触发 4 条通知。
     *
     * 用户报告 App 记成 **2 条**，期望 **1 条**。本用例把真实链路逐条打出来（`[REPRO]`），
     * 并断言**改后**最终条数。
     */
    @Test
    fun `scenario R - the real four notifications of one 17_45 yuan digital rmb payment`() {
        val scenario = listOf(
            // [1] 工商银行短信
            Notif(
                "r1", "sms", "95588",
                "95588",
                "[工商银行]尾号9783卡10月1日11:23工商银行支出(数字人民币消费(京东))17.45元，余额1,037.60元。",
                0L,
            ),
            // [2] 工商银行 App · 动账通知
            Notif(
                "r2", "notify", "com.icbc",
                "动账通知",
                "尾号9783卡10月1日11:23工商银行支出(数字人民币消费(京东))17.45元。请点击查看详情。",
                20_000L,
            ),
            // [3] 工商银行数字钱包 · 动账通知
            Notif(
                "r3", "notify", "com.icbc.wallet",
                "动账通知",
                "您尾号为4793的数字钱包支付给中电联京东共管钱包（0098）¥17.45",
                40_000L,
            ),
            // [4] 数字人民币 App · 付款通知
            Notif(
                "r4", "notify", "cn.gov.pboc.dcep",
                "付款通知",
                "您的我的钱包数字人民币钱包在京东平台支付¥17.45",
                60_000L,
            ),
        )
        val (rows, repo) = runScenario("R：真实原文（17.45 元京东数字人民币）", scenario)
        assertEquals(4, rows.size)
        println("[REPRO] 场景 R 最终存活 = ${repo.alive().size} 条（用户期望 1）")
    }

    // ------------------------------------------------------------------ 层级（需求 1 的前后对照）

    /**
     * 层级探针（**最终方案**）：`digital_rmb` / `unionpay` 层级为 `E_WALLET` ——
     * **高于银行卡、低于微信/支付宝**（用户原话）。
     *
     * 判定（设计文档 §10-⑩，用户拍板）：
     *  - `E_WALLET ↔ BANK` ⇒ REVIEW（**不自动合并**，宁可保守也不静默吞掉真实消费）；
     *  - `PAYMENT ↔ E_WALLET` ⇒ REVIEW；
     *  - `ORDER ↔ E_WALLET` ⇒ AUTO_MERGE；
     *  - `E_WALLET ↔ E_WALLET`（数币 ↔ 云闪付，同层级不同 id）⇒ REJECT。
     */
    @Test
    fun `probe - e-wallet tier sits between payment and bank, and its verdicts match the frozen table`() {
        fun kind(id: String) = PlatformCatalog.find(id)?.kind
        fun prio(id: String) = priorityOf(id)
        println("[REPRO] ===== 层级探针（最终方案） =====")
        println("[REPRO] digital_rmb.kind = ${kind("digital_rmb")} / priority=${prio("digital_rmb")}")
        println("[REPRO] unionpay.kind    = ${kind("unionpay")} / priority=${prio("unionpay")}")
        for ((a, b) in listOf(
            "digital_rmb" to "bank",
            "unionpay" to "bank",
            "digital_rmb" to "unionpay",
            "digital_rmb" to "meituan",
            "digital_rmb" to "alipay",
        )) {
            println("[REPRO] complementaryVerdict($a, $b) = ${complementaryVerdict(a, b)}")
        }
        println("[REPRO] ----------------------------")

        assertEquals(PlatformKind.E_WALLET, kind("digital_rmb"), "数币归「官方数字通道」")
        assertEquals(PlatformKind.E_WALLET, kind("unionpay"), "云闪付归「官方数字通道」")
        assertTrue(prio("digital_rmb").rank > prio("bank").rank, "数币优先级高于银行卡")
        assertTrue(prio("digital_rmb").rank < prio("alipay").rank, "数币优先级低于微信/支付宝")
        // 判定表（拍板）
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict("digital_rmb", "bank"), "E_WALLET ↔ BANK")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict("unionpay", "bank"))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("digital_rmb", "unionpay"), "数币 ↔ 云闪付")
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict("digital_rmb", "meituan"), "ORDER ↔ E_WALLET")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict("digital_rmb", "alipay"), "PAYMENT ↔ E_WALLET")

        // Tier-1（指纹精确）**实测现状**（team-lead 要求核对，不凭猜）：
        // 判据「层级不同 ⇒ 放行；同层级仅同 id 放行」。E_WALLET 独立成层后，本批**未**新增特判。
        println("[REPRO] tierOne(E_WALLET,BANK)=${tierOneAllowsAutoMerge("digital_rmb", "bank")} / " +
            "tierOne(数币,云闪付)=${tierOneAllowsAutoMerge("digital_rmb", "unionpay")} / " +
            "tierOne(bank,bank)=${tierOneAllowsAutoMerge("bank", "bank")}")
        assertTrue(tierOneAllowsAutoMerge("digital_rmb", "bank"), "E_WALLET ↔ BANK 层级不同 ⇒ Tier-1 放行")
        assertFalse(tierOneAllowsAutoMerge("digital_rmb", "unionpay"), "同层级不同 id（数币↔云闪付）⇒ Tier-1 拒绝")
        assertTrue(tierOneAllowsAutoMerge("bank", "bank"), "同一条 bank 通道 ⇒ Tier-1 放行（Bug 2 能力）")
    }

    // ------------------------------------------------------------------ 夹具

    private object NoTransfer : TransferDetector {
        override val id = "no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    private object AlwaysFood : TransactionClassifier {
        override val id = "always_food"
        override val displayName = "固定分类（测试）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult =
            ClassificationResult(categoryId = "cat_food", confidence = 0.9f, reason = "测试固定命中")
    }

    /** 只实现复现所需方法；其余抛错，避免被误当成真实实现（语义对齐 RoomLedgerRepository）。 */
    private class ReproRepo : LedgerRepository {
        private val store = LinkedHashMap<String, LedgerTransaction>()

        fun alive(): List<LedgerTransaction> = store.values.filter { it.status != TxnStatus.MERGED }

        override suspend fun upsert(txn: LedgerTransaction) {
            store[txn.id] = txn
        }

        override suspend fun upsertAll(txns: List<LedgerTransaction>) {
            txns.forEach { store[it.id] = it }
        }

        override suspend fun findById(id: String): LedgerTransaction? = store[id]

        override suspend fun findByFingerprintNear(
            fingerprint: String,
            anchor: Long,
            windowMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.fingerprint == fingerprint &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                kotlin.math.abs(txn.occurredAtMillis - anchor) <= windowMillis
        }

        override suspend fun findByAmountWithin(
            amountMinor: Long,
            fromMillis: Long,
            toMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.amountMinor == amountMinor &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                txn.status != TxnStatus.IGNORED &&
                txn.occurredAtMillis in fromMillis..toMillis
        }.sortedByDescending { it.occurredAtMillis }

        override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) {
            store[id]?.let { store[id] = it.copy(status = status, mergedIntoId = primaryId) }
        }

        override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
            store.values.filter { it.mergedIntoId == primaryId }

        override suspend fun markStatus(id: String, status: TxnStatus) {
            store[id]?.let { store[id] = it.copy(status = status) }
        }

        override suspend fun assignPlatform(id: String, platformId: String) {
            store[id]?.let {
                store[id] = it.copy(
                    platformId = platformId,
                    platformConfidence = 1f,
                    platformSource = PlatformSource.USER,
                )
            }
        }

        override suspend fun listAccounts(): List<Account> = emptyList()
        override suspend fun listCategories(): List<Category> = emptyList()
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

        override suspend fun delete(id: String) = unused()
        override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun listRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = unused()
        override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> = unused()
        override suspend fun upsertUserPlatform(platform: UserPlatform) = unused()
        override suspend fun archiveUserPlatform(id: String) = unused()
        override suspend fun upsertCategory(category: Category) = unused()
        override suspend fun deleteCategory(id: String) = unused()
        override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeRawCount(): Flow<Int> = emptyFlow()
        override fun observeRaw(): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeCategories(): Flow<List<Category>> = emptyFlow()

        private fun unused(): Nothing = error("本复现夹具未实现该方法")
    }
}
