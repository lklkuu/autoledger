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
    /**
     * **正则版**拒绝条件：命中任一即整条拒绝。
     *
     * 与 [bodyRejectAny] 的区别：后者是「正文里出现了这个词」就拒绝，无法表达上下文；
     * 这里可以写「某个词**主导**金额才拒绝」这类带上下文的条件，
     * 用于避免「为了让位给收入规则而误伤真实支出」。
     */
    val bodyRejectPatterns: List<String> = emptyList(),
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

    /**
     * 银行「资金流入」关键词：正文命中任一，说明这条**可能是**收入（是否真是，要看 [INCOME_GOVERNS_AMOUNT]）。
     *
     * 供两处使用（同一份表，避免两处漂移）：
     *  - `sms_bank_in.bodyMustContainAny`：收入规则的准入条件；
     *  - [INCOME_GOVERNS_AMOUNT]：判断入账词是否**主导本次金额**。
     *
     * ⚠️ **绝不能**直接塞进 `bank_generic_out.bodyRejectAny`：
     * 支出规则一旦因为「正文里出现了某个入账词」而让位，收入规则就会顺势认领这条文本，
     * 后果不是「静默丢弃」，而是更严重的**方向反转** ——
     * 「您尾号1234信用卡本期消费5,000元，将于10月25日入账」会被记成 +5000 的收入。
     * （QA 的 BankExpenseRegressionGuardTest A/C/E 组就是钉死这条的。）
     *
     * 刻意**不含**的词（宁可漏记，也不把真实支出判成收入）：
     *  - 「收款」：支出短信常写「收款方：XX」，整词命中会误杀真实消费；
     *    只认「收款成功 / 收款到账 / 已收款」这类自带方向的表述。
     *  - 「利息」：存款结息是收入、信用卡利息是支出，单词无法区分，只认无歧义的「结息」。
     *  - 「贷记」：会被「贷记卡」（信用卡，典型支出场景）命中。
     */
    private val BANK_INCOME_KEYWORDS = listOf(
        "工资", "转入", "收入", "存入", "报销", "入账", "到账", "汇入",
        "结息", "代发", "补贴", "奖金", "返现", "退还",
        "收款成功", "收款到账", "已收款",
    )

    /** 供金额正则复用的「或」表达式：长词在前，避免「到账」先吃掉「收款到账」。 */
    private val BANK_INCOME_ALT = BANK_INCOME_KEYWORDS.sortedByDescending { it.length }.joinToString("|")

    /** 金额本体（元）—— 与 [INCOME_GOVERNS_AMOUNT] / `sms_bank_in` 的第三条金额正则共用。 */
    private const val AMOUNT_BODY = """(\d+(?:,\d{3})*(?:\.\d{1,2})?)(?!\d)(?!\s?[月日年号])"""

    /**
     * 「入账词**主导**本次金额」判定：入账词后面（可隔着注记括号或连接词）紧跟的数字，
     * 就是正文里的**第一处**「…元」金额。
     *
     * 只有这个条件成立，支出规则才让位给收入规则。它是「收入不能被记成支出」与
     * 「支出不能被记成收入」两条诉求之间的唯一分界线：
     *
     * | 文本 | 主导金额？ | 结论 |
     * |---|---|---|
     * | `…交易入账5,055元` | ✅ 入账→5,055 是第一处金额 | 收入 |
     * | `…交易成功，存入5,055元` | ✅ | 收入 |
     * | `…消费500元，该笔交易将于次日入账。` | ❌ 入账后面没有数字 | 支出 |
     * | `…信用卡本期消费5,000元，将于10月25日入账。` | ❌ 同上 | 支出 |
     * | `…消费500元，其中政府补贴100元，实付400元。` | ❌ 补贴→100 不是第一处金额 | 支出 |
     *
     * `\A(?:(?!元).)*?` 这一段是「第一处金额」的实现：前缀里不允许出现「元」，
     * 因此入账词只能主导**最前面**那个金额，而不是后文某个子金额。
     */
    private val INCOME_GOVERNS_AMOUNT =
        """(?s)\A(?:(?!元).)*?(?:$BANK_INCOME_ALT)\s*(?:[（(][^）)]{0,12}[)）]|入账|到账|发放)?\s*[:：]?\s*(?<![\d.,])(?<!尾号)(?<!卡号)$AMOUNT_BODY\s?元"""

    /**
     * 无歧义的**流出**动词。收入规则见到它们就整条拒绝 ——
     * 防止「支出规则因营销词被拒 → 收入规则顺势认领 → 方向反转」这条链路。
     *
     * 刻意**不含**「交易 / 交易成功 / 交易提醒」：这三个词在真实的银行**入账**短信里同样常见
     * （「交易成功，存入5,055元」），把它列进来会把真实收入整条丢掉。
     */
    private val BANK_STRONG_OUTFLOW_VERBS = listOf(
        "消费", "支出", "扣款", "取款", "还款", "交易支出", "消费成功", "已消费", "支付成功",
    )

    /** 营销 / 推广词：命中即整条拒绝（宁可漏记一条，也不把营销文案记成一笔账）。 */
    private val BANK_MARKETING_REJECT = listOf(
        "活动", "抽奖", "立减金", "立减", "红包", "优惠", "领取", "参与方式",
        "公众号", "退订", "达标", "有机会", "礼品", "积分", "权益",
        "福利", "办理", "尽享", "尊享", "敬请",
        // QA 二轮实测的营销尾缀（BankExpenseRegressionGuardTest B1/B2/B3）
        "先到先得", "首绑", "有礼", "奖励金",
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
            // ⚠️ 「元的支出」是支付宝**新版交易提醒**句式「你有一笔9.90元的支出」的触发词
            // （2026-10 真机实测：标题「交易提醒」/ 正文「你有一笔9.90元的支出，领2元小荷包支付红包。」）。
            // 此前只认「成功付款 / 付款成功 / 已付款 / 支付成功 / 即时到账交易」这类措辞，
            // 该句式一个都不含 ⇒ **未命中任何规则、整条被丢弃**（App 采集诊断里显示「未命中规则」）。
            //
            // ⚠️ 刻意**不用裸「支出」**：裸词会把「你的花呗本月账单：本月支出1,280.00元，请于10日还款」
            // 这类**账单汇总文案**一并吸进来、记成一笔支出。已用同一套判据模拟验证：
            // 裸「支出」会误命中该账单文案，而「元的支出」不会（账单文案里「元」后面跟的是「，」）。
            bodyMustContainAny = listOf("成功付款", "付款成功", "已付款", "支付成功", "即时到账交易", "元的支出"),
            bodyRejectAny = listOf("收款成功", "退款成功"),
            amountPatterns = listOf(
                // 把金额**锚定在「的支出」前面那个数**，避免营销尾缀里的数字（如「领2元…红包」）被抢走。
                // 取不到时自然回落到下面三条。
                // ⚠️ 作用范围（QA 复核实测，**勿**简化成「加固而非必需」）：
                //   · 真机样本「你有一笔9.90元的支出，领2元小荷包支付红包」上它**非必需** ——
                //     回退的 `(\d+…)\s?元` 左起第一个金额就是支出额；
                //   · 但只要正文**更靠前处**出现别的「数字+元」，它即成**必需**：
                //     如「本月已支出500元，你有一笔9.90元的支出」，去掉本正则后金额会被 500 元抢走（实测 50000 分）。
                // ⚠️ 每条金额正则必须**含且仅含一个捕获组**（见 NotificationRule.amountPatterns 的约定）。
                """一笔\s?([¥￥]?\s?\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元的支出""",
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
            //
            // ⚠️ 末尾四条是**数字人民币 / 钱包支付**类措辞的组合词，**不能**直接用裸「支付」代替 ——
            // 「支付」太宽泛，会把「还款提醒 / 营销 / 账单到期」这类短信一并吸进来
            // （见 BANK_MARKETING_REJECT 在营销词上踩过的坑）。真实样本：
            //  · 「您尾号为4793的**数字钱包**支付给中电联京东共管钱包（0098）¥17.45」
            //      → 命中「数字钱包」/「钱包支付」
            //  · 「您的我的钱包**数字人民币钱包**在京东平台支付¥17.45」
            //      → 命中「数字人民币钱包」
            //  ⚠️ 注意：「数字人民币钱包」**并不包含**子串「数字钱包」（中间隔着"人民币"）——
            //     二者是两个不同的字符串，必须各自列出，否则后一条会漏（实测踩过这个坑）。
            // 这两条此前**无任何规则命中** ⇒ 落「未解析出金额」的半残状态。金额正则本就认 `¥`，
            // 缺的只是触发词 —— 所以在准入表补词，而不是改金额正则。
            //
            // ⚠️⚠️ **「支付给」被刻意移除**（QA 二轮实测反例）：
            // 「对方**支付给**您的500元已转入」这类**收入文本**同样含「支付给」，
            // 会让支出规则抢在收入规则之前命中 ⇒ **收入被记成支出**（方向反转）。
            // 移除后 [3][4] 仍能靠其余组合词命中（零损失）。
            // ⚠️ 教训：将来新增任何宽词前，**必须先在收入文本里验证**（QA 的
            // `TriggerWordSideEffectAuditTest` 已钉死此类反例，见 BankExpenseRegressionGuardTest）。
            bodyMustContainAny = listOf(
                "消费", "支出", "扣款", "支付成功", "取款", "还款",
                "交易", "交易成功", "交易提醒", "交易支出", "消费成功", "已消费",
                // 数字人民币 / 钱包支付（组合词）。⚠️ 「支付给」已移除——见上。
                "钱包支付", "数字人民币支付", "数字钱包", "数字人民币钱包",
            ),
            // ① 营销 / 推广词：命中任一即整条拒绝（宁可漏记一条，也不虚增一笔支出）。
            bodyRejectAny = BANK_MARKETING_REJECT + listOf("退款"),
            // ② **带上下文**的让位条件：只有当某个入账词**主导本次金额**时
            //    （见 [INCOME_GOVERNS_AMOUNT]），支出规则才让位给收入规则。
            //    绝不能把入账词整表塞进 bodyRejectAny —— 那会让
            //    「消费500元，…将于次日入账」这类真实消费被收入规则认领，方向反转。
            bodyRejectPatterns = listOf(INCOME_GOVERNS_AMOUNT),
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
            bodyMustContainAny = BANK_INCOME_KEYWORDS,
            // 三道拒绝，缺一不可：
            //  a) 营销词与「退款」——「工资理财，有机会领取红包」不得记成一笔收入；
            //  b) 无歧义的流出动词 —— 堵死「支出规则被营销词拒绝 → 收入规则顺势认领」的反转链路；
            //     （这里**不含**「交易 / 交易成功 / 交易提醒」，否则真实的
            //      「交易成功，存入5,055元」会被整条丢掉。）
            bodyRejectAny = BANK_MARKETING_REJECT + BANK_STRONG_OUTFLOW_VERBS + listOf("退款"),
            // 银行短信里"尾号/卡号/日期"等无关数字极多，金额正则**必须带明确上下文**，
            // 绝不能用"前缀可选 + 元也可选"的松正则 —— 那会把尾号 1234 当成金额。
            // 按可靠性降序：① 数字紧跟"元" ② 带币符 / "人民币" ③ 紧跟收入关键词。
            amountPatterns = listOf(
                """(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s?元""",
                """(?:人民币|¥|￥)\s?(\d+(?:,\d{3})*(?:\.\d{1,2})?)""",
                // ③ 紧跟收入关键词：中间允许夹「注记」括号或「入账 / 到账 / 发放」连接词，
                //    用于覆盖「收入(整整到期)5,055」这种**没写「元」**的形态
                //    （取不到金额时 IngestPipeline 会把类型兜底成 EXPENSE，等于记反方向）。
                //    四道护栏，缺一不可：
                //      · (?<![\d.,])      不从数字中间起跳（避免 9,783 被截成 783）
                //      · (?<!尾号)(?<!卡号) 绝不取卡号 / 尾号
                //      · (?!\d)           不许只吃日期的高位（「10月」不得退化成 1）
                //      · (?!\s?[月日年号])  绝不取日期（「入账9月30日」不得变成 9 元）
                """(?:$BANK_INCOME_ALT)\s*(?:[（(][^）)]{0,12}[)）]|入账|到账|发放)?\s*[:：]?\s*(?<![\d.,])(?<!尾号)(?<!卡号)$AMOUNT_BODY""",
            ),
            counterpartyPatterns = listOf(
                // 银行收入没有商户：抽**银行名**（「工商银行」「中国建设银行」）作为对手方。
                // 两个收益：① UI 有可读主体，而不是空白；
                //          ② 「银行短信」与「动账通知」两个渠道归一化出同一个实体，
                //             从而算出同一个去重指纹 —— 否则指纹退化成
                //             `金额|blank|sourceId|sourceRef`，跨渠道永远不可能相等。
                """([一-龥]{2,8}银行)""",
            ),
            direction = Direction.IN,
        ),
    )
}
