package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 独立复核（QA「3fix」）：**编辑账单不得改动原始记录日期**。
 *
 * ## 为什么另开一个文件
 * 工程师的 `TxnEditTest` 补了两条用例，但**只覆盖了「只改备注」**（`amountMinor = null`）。
 * 用户原话是「修改**备注或金额**后，日期被自动覆盖」—— 改**金额**那一支恰好是更危险的一支：
 * `applyFullEdit` 里 `amountMinor != null` 会进入
 * `if (amountMinor != null || occurredAtMillis != null || type != txn.type)` 分支，
 * 分支里有一句 `occurredAtMillis = occurredAtMillis ?: txn.occurredAtMillis`。
 * 那句 `?:` 是「改金额不碰日期」的**唯一**保障，此前没有任何用例覆盖。
 *
 * 另外补一条**结构性**约束：`app/src/main` 里不得再出现 `LocalDate.ofInstant` 调用
 * （它是 API 34 才有的方法，本项目 `minSdk = 26` 且未开 desugaring ⇒ 低版本设备抛
 * `NoSuchMethodError`，被 `runCatching` 吞掉后静默降级）。注释里可以提到它，**调用**不行 ——
 * 这正是本次缺陷的形态，值得用可执行约束钉住。
 */
class TxnDatePreservationIndependentAuditTest {

    /** 2026-10-17 23:59:59.999 +08:00 —— 挑一天最末尾的时刻，「抹成 0 点」或「顺延一天」都藏不住。 */
    private val lateNight = 1_792_252_799_999L

    /** 2026-10-17 00:00:00 +08:00 */
    private val startOfDay = 1_792_166_400_000L

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun txn(occurredAt: Long = lateNight) = LedgerTransaction(
        id = "t1",
        amountMinor = -1_350L,
        occurredAtMillis = occurredAt,
        bookedAtMillis = 1_700_000_000_000L,
        type = TxnType.EXPENSE,
        counterparty = "星巴克",
        note = "旧备注",
        sourceId = "notify",
        sourceRef = "notify:t1",
        fingerprint = "old",
        status = TxnStatus.CONFIRMED,
    )

    // ------------------------------------------------------------ 用户报障的主路径

    @Test
    fun `changing only the amount keeps the original instant (user-reported path)`() {
        val edited = applyFullEdit(
            txn = txn(),
            counterparty = "星巴克",
            note = null,
            platformId = "unknown",
            amountMinor = 5_000L,
            occurredAtMillis = null,
            fingerprintOf = { "fp" },
        )

        assertEquals(-5_000L, edited.amountMinor, "金额必须按用户输入写回（支出记负）")
        assertEquals(
            lateNight, edited.occurredAtMillis,
            "★ 只改金额时日期必须原样保留 —— 用户报障的正是这条路：改完金额，日期被挪到修改当天",
        )
        assertTrue(
            edited.occurredAtMillis != startOfDay,
            "时刻不得被抹成当天 00:00（实际 ${edited.occurredAtMillis}）",
        )
        assertEquals(1_700_000_000_000L, edited.bookedAtMillis, "入账时间不动")
    }

    @Test
    fun `changing only the note keeps the original instant`() {
        val edited = applyFullEdit(
            txn = txn(),
            counterparty = "星巴克",
            note = "新备注",
            platformId = "unknown",
            amountMinor = null,
            occurredAtMillis = null,
            fingerprintOf = { "fp" },
        )
        assertEquals("新备注", edited.note)
        assertEquals(lateNight, edited.occurredAtMillis, "只改备注不得改动日期")
    }

    @Test
    fun `switching the type without touching the date keeps the original instant`() {
        val edited = applyFullEdit(
            txn = txn(),
            counterparty = "星巴克",
            note = null,
            platformId = "unknown",
            amountMinor = null,
            occurredAtMillis = null,
            type = TxnType.INCOME,
            fingerprintOf = { "fp" },
        )
        assertEquals(TxnType.INCOME, edited.type)
        assertEquals(1_350L, edited.amountMinor, "类型切换只翻符号，金额绝对值不变")
        assertEquals(lateNight, edited.occurredAtMillis, "只改类型不得改动日期")
    }

    @Test
    fun `a genuinely new date is applied and only the date changes`() {
        val edited = applyFullEdit(
            txn = txn(),
            counterparty = "星巴克",
            note = null,
            platformId = "unknown",
            amountMinor = null,
            occurredAtMillis = startOfDay,
            fingerprintOf = { "fp" },
        )
        assertEquals(startOfDay, edited.occurredAtMillis, "用户真改了日期就必须写回")
        assertEquals(1_700_000_000_000L, edited.bookedAtMillis, "改日期不得连带改入账时间")
        assertEquals(-1_350L, edited.amountMinor, "改日期不得连带改金额")
    }

    // ------------------------------------------------------------ 日期初值（isoDateOf）

    @Test
    fun `the initial date text never drifts a day at the end of the day`() {
        assertEquals("2026-10-17", isoDateOf(lateNight, shanghai), "23:59:59.999 仍是 10-17")
        assertEquals("2026-10-18", isoDateOf(startOfDay + 86_400_000L, shanghai), "次日 00:00 才是 10-18")
    }

    @Test
    fun `a dirty timestamp yields empty text which must block saving instead of meaning today`() {
        // 「读不出来」与「用户想改成今天」必须可区分：前者 → null → UI 留空 + 保存禁用。
        assertNull(isoDateOf(Long.MIN_VALUE, shanghai), "脏时间戳必须返回 null")
        assertNull(isoDateOf(Long.MAX_VALUE, shanghai), "脏时间戳必须返回 null（不得抛异常）")
        assertEquals("", isoDateOf(Long.MIN_VALUE, shanghai).orEmpty(), "UI 拿到的是空串，不是「今天」")
        // 反向：合法时间戳不得被当成脏数据
        assertNotNull(isoDateOf(0L, shanghai), "epoch 0 是合法日期（1970-01-01），不能被判脏")
    }

    // ------------------------------------------------------------ 结构性约束（可执行）

    @Test
    fun `production sources must not call the API-34-only LocalDate_ofInstant`() {
        val mainRoot = locateAppMainSourceRoot()
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    // 只查「调用」，注释里提到它（例如解释为什么不能用）是允许的
                    if (!isComment && code.contains("LocalDate.ofInstant")) "${file.relativeTo(mainRoot)}:${index + 1}: $code"
                    else null
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "app/src/main 里出现了 LocalDate.ofInstant 调用：它是 API 34 才新增的方法，" +
                "本项目 minSdk = 26 且未开 core library desugaring ⇒ Android 13 及以下抛 " +
                "NoSuchMethodError（继承自 Error，runCatching 也会吞掉）。" +
                "统一走 isoDateOf（Java 8 API）。命中：\n  ${offenders.joinToString("\n  ")}",
        )
    }

    @Test
    fun `the source scanner actually finds the main source root (it must not silently pass)`() {
        // 若源码树定位失败，上面那条用例会因为「扫描了 0 个文件」而恒绿 —— 那是假绿。
        val root = locateAppMainSourceRoot()
        val ktFiles = root.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue(ktFiles > 20, "扫描到的主源码文件数异常（$ktFiles），定位可能错了：$root")
        assertTrue(
            File(root, "com/autoledger/app/ui/stores/TxnDateText.kt").exists(),
            "主源码根应包含 ui/stores/TxnDateText.kt（被钉的那个纯函数所在文件）",
        )
        assertFalse(ktFiles == 0, "不得扫到 0 个文件")
    }

    /** 定位单测所在模块的 `src/main/java`（兼容从模块目录或仓库根目录运行）。 */
    private fun locateAppMainSourceRoot(): File {
        val candidates = listOf(
            "src/main/java",
            "app/src/main/java",
            "../app/src/main/java",
        ).map { File(it).absoluteFile }
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "无法定位 app 模块主源码目录（cwd=${File(".").absolutePath}）。" +
                    "本用例依赖「扫描主源码」，定位失败必须显式失败而不是跳过。",
            )
    }
}
