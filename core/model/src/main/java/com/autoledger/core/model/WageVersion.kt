package com.autoledger.core.model

/**
 * **时薪按月版本**（v8 新增）—— 收入口径从「流水聚合」改为「版本化的月度应计值」的载体。
 *
 * ## 为什么需要它
 * 改造前 `app_settings` 只有一行 `wage`，用户改一次时薪就把上一版**覆盖**掉，
 * 于是「历史月份的收入」只能靠"用今天的时薪重算"来展示 —— 这正是用户明令禁止的回溯覆盖。
 * 把「某月的时薪」落成独立行之后，"改时薪不回写历史"从**约定**变成了**数据结构的性质**。
 *
 * ## 为什么整行存全量快照而不是「只存变动字段」
 * - 历史月份必须能**独立复现**当时的口径：差量存储会让「某字段从哪版开始生效」
 *   变成需要回溯推导的问题，一旦漏一个字段就静默算错；
 * - 行数极少（用户一年改几次时薪），存储成本可忽略。
 *
 * ## [monthlyIncomeMinor] 为什么落库、而不是每次现算
 * 1. **审计**：用户能对账「我这个月为什么是 ¥4333.33」；
 * 2. **公式演进不得改写历史**：将来若调整年化方式或舍入策略，已落库的月度收入必须原样保留
 *    —— 这是"历史月份保留当时收入结果"的唯一实现方式。
 *
 * @property month 生效月，`YYYY-MM`（当月及之后生效，见 [WageLedger.effectiveVersion]）
 * @property profile 该版本的全量时薪快照（工时参数也随版本走，历史月份的工时换算才对得上）
 * @property monthlyIncomeMinor 落库的折算月度收入（分）＝ 写入时的 [WageProfile.effectiveMonthlySalaryMinor]
 * @property createdAtMillis 该月**首次**写入时间（同月二次保存必须保留，见 `WageBook.save`）。
 *   **特殊值 `0`** = **预填、用户尚未在 v8 里确认**（即：**迁移或旧档案导入**时的预填），
 *   来源有两种，语义相同：
 *   ① `MIGRATION_7_8` 从升级前的 `app_settings` 预填；
 *   ② 导入旧档案（无 `wageVersions` 键）时用档案里的 `settings.wage` 预填。
 *   两者都是"从别处推断出来的值"，不是用户在 v8 里亲手保存的。UI 据此提示
 *   「时薪为升级前保存的值，请确认」，判据见 [WageLedger.isMigrationSeeded]。
 *   真实保存用的都是真实时间戳。选 `0` 而不是加一个额外列：迁移里本来就写 0，
 *   不需要为一条纯展示语义改 schema。
 * @property updatedAtMillis 最近一次修改时间（迁移预填 / 旧档案播种时同样为 `0`）
 */
data class WageVersion(
    /** 生效月，`YYYY-MM`（当月及之后生效）。 */
    val month: String,
    /** 该版本的全量时薪快照（工时参数也随版本走）。 */
    val profile: WageProfile,
    /** 落库的折算月度收入（分）＝ 写入时的 profile.effectiveMonthlySalaryMinor。 */
    val monthlyIncomeMinor: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {

    companion object {

        /**
         * 用工时参数快照构造一个版本，并把月度收入**锁定**为当时的
         * [WageProfile.effectiveMonthlySalaryMinor]。
         *
         * 一律通过本工厂构造（而不是直接 `WageVersion(...)` + 手算月度收入），
         * 才能保证「落库的月度收入 = 当时公式的结果」这条不变量不被某处的临时算错破坏。
         *
         * @param nowMillis 本次写入时间，同时作为 [updatedAtMillis]
         * @param createdAtMillis 首次写入时间；同月二次保存由调用方传入旧的 `createdAtMillis`
         */
        fun of(
            month: String,
            profile: WageProfile,
            nowMillis: Long,
            createdAtMillis: Long = nowMillis,
        ): WageVersion = WageVersion(
            month = month,
            profile = profile,
            monthlyIncomeMinor = profile.effectiveMonthlySalaryMinor,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = nowMillis,
        )
    }
}
