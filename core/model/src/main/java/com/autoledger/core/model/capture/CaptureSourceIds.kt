package com.autoledger.core.model.capture

/**
 * 采集来源（`CaptureSource.id`）的**单一真源**。
 *
 * ## 为什么这些 ID 要提到 `core:model`
 * 各 `CaptureSource` 实现分散在 `feature:capture`，而**消费它们的领域规则却在别的模块**：
 * 去重护栏要能区分「这条记录是**用户亲手录入 / 官方账单导入**的，还是**自动抓来的**」——
 * 前者是**权威数据**，不得被同金额的另一渠道静默吸收（见 `feature:dedup` 的
 * `ComplementaryMatch.noneSideIsAuthoritative`）。
 *
 * `feature:dedup` **不依赖** `feature:capture`（依赖方向反过来会污染纯 JVM 模块），
 * 所以这些字符串不能各写一遍字面量 —— 那样「采集侧改了 ID、护栏侧不知道」会静默失配。
 * 放在 `core:model`（两侧都依赖的最底层契约模块）即成为唯一真源。
 *
 * ## 语义边界
 * 这是**技术追溯字段**，回答「这条记录是怎么抓到的」，**不是**消费平台（业务维度）。
 * 两者混淆正是本次平台改造要解开的死结，详见 [com.autoledger.core.model.platform.PlatformKind] 的文档。
 */
object CaptureSourceIds {

    /** 系统通知监听（微信 / 支付宝 / 银行 App 的支付通知）。 */
    const val NOTIFY = "notify"

    /** 银行短信扫描。 */
    const val SMS = "sms"

    /**
     * 手动补记。
     *
     * **权威来源**：金额、商户、时间都是用户亲手填的，是「已经发生的事实」，
     * 不是靠正则从文本里猜出来的。任何自动流程都不得把它当作「某笔自动记录的银行侧」而吸收。
     */
    const val MANUAL = "manual"

    /**
     * 账单文件导入（支付宝 / 微信官方导出 CSV）。
     *
     * **权威来源**：官方账单是**对账基准**，比任何正则抓取都可靠。同上，不得被静默吸收。
     */
    const val BILL_IMPORT = "bill_import"
}
