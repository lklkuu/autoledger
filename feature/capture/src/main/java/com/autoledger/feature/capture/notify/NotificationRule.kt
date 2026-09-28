package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import com.autoledger.core.model.TxnType

/**
 * 一条「通知 → 流水」的解析规则。
 *
 * 全部规则都是**数据**，没有任何一条被写进 if/else 分支：
 * 想支持新的银行 App，只要在规则包里追加一条 [NotificationRule]（甚至将来改成从服务端下发 JSON），
 * 解析器一行代码都不用动。
 */
data class NotificationRule(
    val id: String,
    val label: String,
    /** null = 通用规则；命中包名优先走更具体的规则 */
    val packageNames: Set<String>? = null,
    val titleMustContainAny: List<String> = emptyList(),
    val bodyMustContainAny: List<String> = emptyList(),
    val bodyRejectAny: List<String> = emptyList(),
    /** 按序尝试，第一个命中的生效；每条必须含且仅含一个捕获组，捕获组=金额（单位：元） */
    val amountPatterns: List<String>,
    /** 商户名提取pattern，捕获组 1 = 商户 */
    val counterpartyPatterns: List<String> = emptyList(),
    val direction: Direction = Direction.OUT,
    /**
     * 采集端已确定的账本类型，会作为 [com.autoledger.core.model.RawEnvelope.explicitType] 下发，
     * 在流水中优先级最高，避免"退款金额是正数 → 被金额正负误判成 INCOME"。
     *
     * 缺省 null = 交由流水线按金额正负推断。**不要用 [direction] 推导**：
     * `wechat_receive` / `sms_bank_in` 同样是 IN，但它们应是 INCOME，只有退款才是 REFUND。
     */
    val ledgerType: TxnType? = null,
)

/** 厂商与各银行的默认值，放在同一个地方便于 review 与增补。 */
object DefaultNotificationRules {

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

    /** 退款金额提取：优先"退款金额/退款"后的数字，其次带币符、再次"xx元"。 */
    private val REFUND_AMOUNT_PATTERNS = listOf(
        """(?:退款金额|退款)[^\d]{0,8}([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
        """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
        """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
    )

    private val REFUND_COUNTERPARTY_PATTERNS = listOf(
        """(?:商户名称|收款方|商家|商户)[^\S\n]{0,4}[:：]?\s?([^\s,，|]{2,24})""",
        """向\s?([^\s,，|]{2,24})\s?(?:付款|转账)""",
    )

    val PACK: List<NotificationRule> = listOf(
        // ---------------- 退款（必须排在付款规则之前：付款规则会主动排除退款文案） ----------------
        NotificationRule(
            id = "refund_wechat",
            label = "微信退款到账",
            packageNames = setOf(PKG_WECHAT),
            bodyMustContainAny = listOf("退款", "已退款", "退款到账", "退款成功", "退回"),
            // 排除"还没退成功"的文案，避免把申请中的退款记成到账；
            // "退回"系一并排除：付款通知常带"如未收到可申请退回"，不能当成退款到账。
            bodyRejectAny = listOf("退款失败", "退款申请", "正在退款", "退款中", "申请退款", "申请退回", "可退回", "退回申请"),
            amountPatterns = REFUND_AMOUNT_PATTERNS,
            counterpartyPatterns = REFUND_COUNTERPARTY_PATTERNS,
            direction = Direction.IN,
            ledgerType = TxnType.REFUND,
        ),
        NotificationRule(
            id = "refund_alipay",
            label = "支付宝退款到账",
            packageNames = setOf(PKG_ALIPAY),
            bodyMustContainAny = listOf("退款", "已退款", "退款成功", "退回"),
            bodyRejectAny = listOf("退款失败", "退款申请", "正在退款", "退款中", "申请退款", "申请退回", "可退回", "退回申请"),
            amountPatterns = REFUND_AMOUNT_PATTERNS,
            counterpartyPatterns = REFUND_COUNTERPARTY_PATTERNS,
            direction = Direction.IN,
            ledgerType = TxnType.REFUND,
        ),
        NotificationRule(
            id = "refund_generic",
            label = "退款到账（银行 App / 短信）",
            packageNames = null,
            bodyMustContainAny = listOf("退款", "已退款", "退款到账", "退款入账", "原路退回", "冲正"),
            bodyRejectAny = listOf("退款失败", "退款申请", "正在退款", "退款中", "申请退款", "申请退回", "可退回", "退回申请"),
            amountPatterns = REFUND_AMOUNT_PATTERNS,
            counterpartyPatterns = REFUND_COUNTERPARTY_PATTERNS,
            direction = Direction.IN,
            ledgerType = TxnType.REFUND,
        ),
        // ---------------- 微信支付（付款凭证 / 支付成功） ----------------
        NotificationRule(
            id = "wechat_pay",
            label = "微信支付",
            packageNames = setOf(PKG_WECHAT),
            titleMustContainAny = listOf("微信支付", "微信支付凭证", "服务通知"),
            bodyMustContainAny = listOf("付款金额", "支付金额", "微信支付凭证", "已支付", "支付成功"),
            bodyRejectAny = listOf("收款成功", "收款到账", "转账到账通知", "已退款到"),
            amountPatterns = listOf(
                """(?:付款金额|支付金额)[^\d]{0,8}([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
            ),
            counterpartyPatterns = listOf(
                """(?:商户名称|收款方|商家)[^\S\n]{0,4}[:：]?\s?([^\s,，|]{2,24})""",
                """向\s?([^\s,，|]{2,24})\s?(?:付款|转账)""",
            ),
            direction = Direction.OUT,
        ),
        NotificationRule(
            id = "wechat_receive",
            label = "微信收款 / 转账收入",
            packageNames = setOf(PKG_WECHAT),
            bodyMustContainAny = listOf("收款成功", "收款到账", "转账到账通知", "已存入零钱"),
            amountPatterns = listOf(
                """(?:收款金额|转账金额)[^\d]{0,8}([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
            ),
            direction = Direction.IN,
        ),
        // ---------------- 支付宝 ----------------
        NotificationRule(
            id = "alipay_pay",
            label = "支付宝付款",
            packageNames = setOf(PKG_ALIPAY),
            titleMustContainAny = listOf("支付宝", "交易提醒", "账单"),
            bodyMustContainAny = listOf("成功付款", "付款成功", "已付款", "支付成功", "即时到账交易"),
            bodyRejectAny = listOf("收款成功", "退款成功"),
            amountPatterns = listOf(
                """(?:成功付款|付款成功|已付款|支付成功)([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
            ),
            counterpartyPatterns = listOf(
                """(?:收款方|商家|商户)[^\S\n]{0,4}[:：]?\s?([^\s,，|]{2,24})""",
            ),
            direction = Direction.OUT,
        ),
        // ---------------- 银行 App 通用 ----------------
        NotificationRule(
            id = "bank_generic_out",
            label = "银行交易通知（支出）",
            titleMustContainAny = listOf(),
            // 信用卡/银行卡消费通知的措辞五花八门：除了"消费/支出/扣款"，
            // 银行 App 常用"交易成功 / 交易提醒 / 交易支出"（抖音刷信用卡正是这类），
            // 若漏掉这些词，整笔消费通知会被静默丢弃。
            bodyMustContainAny = listOf(
                "消费", "支出", "扣款", "支付成功", "取款", "还款",
                "交易", "交易成功", "交易提醒", "交易支出", "消费成功", "已消费",
            ),
            // 营销 / 推广短信常含"消费"等词（如"消费累计金额达标，有机会抽取 500 元立减金"），
            // 会被误当成一笔消费。命中任一营销词即整条拒绝（宁可漏记一条，也不虚增一笔支出）。
            bodyRejectAny = listOf(
                "工资", "转入", "收入", "退款",
                "活动", "抽奖", "立减金", "红包", "优惠", "领取", "参与方式",
                "公众号", "退订", "达标", "有机会", "礼品", "积分", "权益",
                "福利", "办理", "尽享", "尊享", "敬请",
            ),
            amountPatterns = listOf(
                // 最可靠：数字紧跟"元"（如"39.80元"）。必须放最前——
                // 否则商户名里的数字（如"2zero首饰屋"）会被下面带关键词的正则先匹配到，导致金额记错。
                """([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
                """[¥￥]\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """(?:消费|支出|扣款|支付|取款|还款|交易金额|交易)(?:人民币|CNY)?[^\d]{0,6}([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
            ),
            counterpartyPatterns = listOf(
                // ① 显式前缀（最可靠）：银行 App 常见「商户：星巴克」「收款方 XX」
                """(?:商户|收款方|收款人|对方)[^\S\n]{0,4}[:：]?\s?([^\s,，|]{2,24})""",
                // ② 兜底：银行**短信**常写成「…支出(消费财付通-2zero首饰屋)39.80元」——
                //    没有「商户」前缀，商户紧跟在交易动词之后。规则：
                //      消费/支出/扣款 → 可选通道词（财付通/支付宝…） → 可选分隔符 → 捕获商户串
                //    两个防噪约束：
                //      · 负向断言 `(?!\d[\d,.]*\s?元)` 排除「消费 398.00元」把金额当商户；
                //      · 字符集排除空白/逗号/竖线/币符/**括号**，避免吞进整句或金额尾巴。
                //    抽不到就返回空 —— 宁可让用户手填，也不塞噪声。
                """(?:消费|支出|扣款)\s?(?:财付通|支付宝|微信支付|云闪付|银联|快捷支付)?\s?[-—]?\s?(?!\d[\d,.]*\s?元)([^\s,，|：:¥￥()（）]{2,20})""",
            ),
            direction = Direction.OUT,
        ),
        // ---------------- 短信通道（银行下行短信） ----------------
        NotificationRule(
            id = "sms_bank_in",
            label = "银行短信（收入）",
            bodyMustContainAny = listOf("工资", "转入", "收入", "存入", "报销"),
            // 银行短信里"尾号/卡号/日期"等无关数字极多，金额正则**必须带明确上下文**，
            // 绝不能用"前缀可选 + 元也可选"的松正则 —— 那会把尾号 1234 当成金额。
            // 按可靠性降序：① 数字紧跟"元" ② 带币符 / "人民币" ③ 紧跟收入关键词。
            amountPatterns = listOf(
                """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
                """(?:人民币|¥|￥)\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                """(?:工资|转入|收入|存入|报销|入账|到账|退款)\s?[:：]?\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
            ),
            direction = Direction.IN,
        ),
    )
}
