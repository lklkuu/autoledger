package com.autoledger.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * **收入口径的唯一入口**（纯 JVM，无 Android / Room 依赖，可直接 JVM 单测）。
 *
 * ## 口径定义（唯一真源）
 * ```
 * 某月收入 = 该月生效版本的 monthlyIncomeMinor
 *          = 版本写入时的 WageProfile.effectiveMonthlySalaryMinor
 *          = round(monthlyNetSalaryMinor × payMonthsPerYear / 12)
 * ```
 * - **不扣** `monthlyWorkCostMinor`：工作成本属于**支出侧**，用户若真花了这笔钱会另记一笔支出，
 *   从收入里再扣一次就是双计。
 * - **真实/名义时薪公式完全不变**（见 [WageProfile]）：时薪页观感不变，
 *   只有"收入汇总"改用本对象。这条差异有专门的单测钉子。
 * - 账本里的 `INCOME` 流水**不参与**收入汇总（见 [ExpenseMath.actualReceivedMinor]）。
 *
 * ## 版本解析规则（[effectiveVersion]）
 * 1. `month` 格式非法 ⇒ `null`；
 * 2. 取「`version.month <= month`」中**最大**的一条（同一月份出现多行时取列表**靠后**的一条，
 *    兼容手改库等脏数据，绝不抛异常）；
 * 3. 不存在这样的行（该月**早于最早版本**）⇒ `null`（收入 0）—— 见下节「只向后沿用」；
 * 4. `versions` 为空 ⇒ `null`。
 *
 * ## 只向后沿用，**不向前回填**
 * 版本从生效月起**向后**沿用（改一次时薪，后续月份自动跟着变）；
 * 但**早于最早版本**的月份**没有收入记录**，一律记 0，不拿最早那条去"猜"。
 *
 * 为什么不做向前回填：账本始于 2025-03 的用户点开 2024 年度，会被算出
 * 「12 × 月薪收入、0 支出、结余 +5 万」这类**凭空长出来的年度数据** ——
 * 那时用户还没开始记账，甚至还没有这份工资。需求是「以生效月份为界，当月及之后」，
 * 向前回填与它相反。代价是"补录一笔早于首版时薪的历史流水"时，那个月只有支出没有收入
 * （结余为负）—— 这是**诚实**的：我们确实不知道那时的时薪，宁可显示 0 也不编一个。
 *
 * ## 月份比较一律用字符串比较
 * `YYYY-MM` 是定长的，字典序即时间序（`"2026-03" < "2026-04"`、`"2026-12" < "2027-01"`），
 * 不需要解析成数字再比，也就不存在进位 / 时区问题。
 *
 * ## 一律不抛异常
 * 版本行可能来自手改库或第三方导入的档案；脏数据（`month = "abc"`、`"2026-13"`）
 * 一律当作「不生效」处理并跳过，**不崩 App**。
 */
object WageLedger {

    /** 严格 `YYYY-MM`：四位年 + `-` + `01..12`。 */
    private val MONTH_PATTERN = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

    /** 逐月累加的理论上限（100 年），用于兜住脏入参导致的超长循环。 */
    private const val MAX_MONTHS_SPAN = 1_200

    // ------------------------------------------------------------------ 月份键

    /**
     * 把一个日期折算成月份键 `YYYY-MM`（业务时区由调用方决定，见 `TimeRange.ZONE`）。
     *
     * 手工补零而不是 `String.format("%04d-%02d", …)`：后者走默认 `Locale`，
     * 在阿拉伯语等区域会输出**非 ASCII 数字**，让 [isValidMonth] 与字符串比较同时失效。
     */
    fun monthKeyOf(date: LocalDate): String =
        "${date.year.toString().padStart(4, '0')}-${date.monthValue.toString().padStart(2, '0')}"

    /** [monthKeyOf] 的时间戳版本（便于 UI 直接传 `System.currentTimeMillis()`）。 */
    fun monthKeyOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        monthKeyOf(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    /** [monthKeyOf] 的年月整数版本（`year` 可越界，只做补零，不做合法性校验）。 */
    fun monthKeyOf(year: Int, month: Int): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"

    /**
     * 月份键是否合法。**非法只返回 `false`，不抛异常**（脏数据不能崩 App）。
     *
     * 刻意比 `LocalDate.parse` 更严格：`2026-1` / `2026-13` / `2026-00` / `""` 全部拒绝，
     * 因为容忍它们会让「月份字典序」这条前提失效。
     */
    fun isValidMonth(key: String): Boolean = MONTH_PATTERN.matches(key)

    // ------------------------------------------------------------------ 版本解析

    /**
     * 解析某月生效的版本，规则见文件头。空列表 / 月份非法 / **早于最早版本** ⇒ `null`。
     *
     * @return 生效版本；`versions` 为空、月份非法、或该月早于最早版本时 `null`
     */
    fun effectiveVersion(versions: List<WageVersion>, month: String): WageVersion? {
        if (versions.isEmpty()) return null
        if (!isValidMonth(month)) return null
        // 先剔除脏行再排序：排序键是 month 字符串，脏行（如 "abc"）会污染"最早一条"的判定。
        val clean = versions.filter { isValidMonth(it.month) }.sortedBy { it.month }
        if (clean.isEmpty()) return null

        var chosen: WageVersion? = null
        for (version in clean) {
            if (version.month <= month) {
                // 同月多行时靠后者覆盖（列表顺序 = 调用方给的顺序，通常是 createdAt 递增）。
                chosen = version
            } else {
                break
            }
        }
        // 该月早于最早版本 ⇒ 没有收入记录 ⇒ null（收入 0）。
        // **刻意不回落到最早一条**：那会让账本开始前的年份/月份凭空长出收入（见文件头）。
        return chosen
    }

    /**
     * 某月收入（分）。**没有可用的版本 ⇒ `0`**（无版本就没有可推算的收入，
     * 展示层负责提示「未设置时薪」，绝不回落到 INCOME 流水求和 —— 那会引入第二口径）。
     */
    fun incomeForMonth(versions: List<WageVersion>, month: String): Long =
        effectiveVersion(versions, month)?.monthlyIncomeMinor ?: 0L

    /**
     * 某月生效的时薪参数快照（含工时参数）。
     *
     * 用于「花掉的时间」这类需要把金额换算成工时的卡片：切到历史月份时应当用**当时**的参数，
     * 而不是今天的。无版本时返回 `null`，由调用方决定回落到 `settings.wage`。
     */
    fun profileForMonth(versions: List<WageVersion>, month: String): WageProfile? =
        effectiveVersion(versions, month)?.profile

    // ------------------------------------------------------------------ 迁移预填标记

    /**
     * 该版本是否由 `MIGRATION_7_8` 从升级前的 `app_settings` **预填**、用户尚未在 v8 里确认。
     *
     * 判据是 `createdAtMillis == 0L`：迁移是唯一会写 0 的地方，真实保存
     * （`WageBook.save` / 旧备份档案播种）用的都是真实时间戳。
     *
     * 为什么需要它：预填值来自用户在 v7 里存的时薪（可能只是"进过时薪页随手留下"的值），
     * 而 v8 改了口径（13 薪摊平后数字会变）。把"预填"与"用户确认过"区分开，
     * UI 才能提示「这是升级前保存的时薪，请确认」，而不是让用户以为哪儿算错了。
     */
    fun isMigrationSeeded(version: WageVersion): Boolean = version.createdAtMillis == 0L

    /**
     * 所有版本都还是迁移预填的（= 用户升级后从未保存过时薪）。
     *
     * 空列表返回 `false`：那是「完全没设过时薪」，与「有预填值待确认」是两种不同的提示。
     */
    fun allMigrationSeeded(versions: List<WageVersion>): Boolean =
        versions.isNotEmpty() && versions.all { isMigrationSeeded(it) }

    // ------------------------------------------------------------------ 区间 / 年度 / 累计

    /**
     * 任意区间的应计收入（分）。
     *
     * **只累加「该月 1 日 00:00 落在 `[from, to]` 内」的月份**：
     * - 对月对齐区间（本月 / 某月）等价于「该月收入」；
     * - 对非对齐区间（如 3/15–4/14）只算 4 月，不会把首尾两个整月都算进去
     *   （否则一个月跨度会拿到两个月收入）。
     *
     * **收入是月度应计值，不得按天摊分**：按天摊会引入浮点与舍入争议，
     * 且与"月度收入落库"的审计口径冲突。
     *
     * @return `from > to` 时返回 `0`
     */
    fun incomeForRange(versions: List<WageVersion>, from: LocalDate, to: LocalDate): Long {
        if (from.isAfter(to)) return 0L
        // 从「第一个月首 >= from」开始：本月首早于 from 说明本月不计（只按整月计）。
        var cursor: LocalDate = from.withDayOfMonth(1)
        if (cursor.isBefore(from)) cursor = cursor.plusMonths(1)

        var sum = 0L
        // 循环守卫与 cumulativeIncomeMinor 同源：本方法是 public（`WageBook.incomeForRange` 直出），
        // 一个退化入参（如 from = LocalDate.MIN、to = now）会让这个 while 在主线程上跑上百万轮，
        // 每轮都要 filter + sort 一遍版本列表。超出 100 年就停 —— 真实账本不可能跨这么久，
        // 而"停"比"卡死"安全。
        var guard = 0
        while (!cursor.isAfter(to) && guard < MAX_MONTHS_SPAN) {
            sum += incomeForMonth(versions, monthKeyOf(cursor))
            cursor = cursor.plusMonths(1)
            guard += 1
        }
        return sum
    }

    /**
     * 某年的应计收入（分）。
     *
     * - 往年：12 个月之和（**按各月版本逐月求和**，不因当年改薪而重算往年）；
     * - 当年：`1..currentMonth 的月份` 之和 —— 未来月份不显示 ¥0 假数据；
     * - 未来年：`0`。
     *
     * @param currentMonth 「现在」所在的月份键 `YYYY-MM`。
     *   它在生产代码里就是 `monthKeyOf(now, zone)`。若它本身非法（脏入参），
     *   则退化为「不截断」：任何年份都按 12 个月求和 —— 宁可多算也不静默清零。
     */
    fun incomeForYear(versions: List<WageVersion>, year: Int, currentMonth: String): Long {
        val months: Int = if (!isValidMonth(currentMonth)) {
            12
        } else {
            val currentYear = currentMonth.substring(0, 4).toInt()
            when {
                year < currentYear -> 12
                year == currentYear -> currentMonth.substring(5, 7).toInt()
                else -> return 0L
            }
        }

        var sum = 0L
        for (month in 1..months) {
            sum += incomeForMonth(versions, monthKeyOf(year, month))
        }
        return sum
    }

    /**
     * 跨月累计收入（分）：从 [fromMonth] 到 [toMonth] **逐月**求和（含首尾）。
     *
     * 之所以必须逐月而不是「月数 × 月薪」：一旦时薪变化，
     * 乘法压缩会把历史月份**整体重算**，跨版本累加才是正确口径。
     *
     * @return 任一月份键非法、或 `fromMonth > toMonth` 时返回 `0`
     */
    fun cumulativeIncomeMinor(
        versions: List<WageVersion>,
        fromMonth: String,
        toMonth: String,
    ): Long {
        if (!isValidMonth(fromMonth) || !isValidMonth(toMonth)) return 0L
        if (fromMonth > toMonth) return 0L

        var year = fromMonth.substring(0, 4).toInt()
        var month = fromMonth.substring(5, 7).toInt()
        var sum = 0L
        var guard = 0
        while (guard < MAX_MONTHS_SPAN) {
            val key = monthKeyOf(year, month)
            if (key > toMonth) break
            sum += incomeForMonth(versions, key)
            if (month == 12) {
                year += 1
                month = 1
            } else {
                month += 1
            }
            guard += 1
        }
        return sum
    }
}
