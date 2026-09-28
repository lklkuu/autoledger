package com.autoledger.core.model.platform

/**
 * 消费平台（业务维度）的类型契约。
 *
 * ## 三个概念必须严格区分（混用是本次改造要解开的死结）
 *
 * | 概念 | 是什么 | 字段 |
 * |---|---|---|
 * | **消费平台** | 这笔消费发生在哪个业务平台：微信 / 支付宝 / 美团… | `platformId` |
 * | **商户** | 具体收款方 / 店名 | `counterparty` |
 * | **采集来源** | 这条记录是**怎么抓到的**（通知 / 短信 / 账单导入） | `sourceId`（技术追溯字段，**不是**业务维度） |
 *
 * ## 为什么 `platformId` 用稳定 String 而不是 enum
 *
 * - 新增平台 = 往 [PlatformCatalog] 加一条数据，不必改 `core:model` + Room TypeConverter；
 * - 未知 ID 有兜底（[PlatformCatalog.displayNameOf] 返回「未知平台」），**绝不抛异常**；
 * - 本项目已踩过「枚举 + 语句位置 `when` 不做穷尽性检查 → 新类型静默渲染空白」的坑，
 *   枚举会把该风险固化下来。
 */

/** 平台取值的来源，决定「自动值能否覆盖用户值」。 */
enum class PlatformSource {
    /** 自动识别结果 —— 后续任何自动流程都可以改写。 */
    AUTO,

    /** 用户手动指定 —— **权威标记**，后续任何自动流程都不得改写。 */
    USER,
}

/**
 * 平台在交易链条中的角色。
 *
 * 区分它的**唯一理由**：一次消费通常同时涉及两个平台 ——
 * 「淘宝下单 + 支付宝付款」「美团下单 + 微信支付」。
 * 若不做区分，支付通道（措辞更强、得分更高）会**恒压**下单平台，
 * 结果是「淘宝 / 美团」这些用户真正关心的消费平台在账单里几乎永不出现。
 */
enum class PlatformKind {
    /** 下单平台：消费发生的场所（淘宝 / 美团 / 拼多多 / 抖音…）。这是业务维度。 */
    ORDER,

    /** 支付通道：钱从哪条通道出去（支付宝 / 微信支付…）。是手段，不是消费场所。 */
    PAYMENT,

    /** 既非下单也非通道（如 unknown）。 */
    OTHER,
}

/**
 * 平台目录条目（纯数据，不进 Room —— 它是常量，不是用户数据）。
 *
 * @property id 稳定 ID 字符串，落库的就是它（不存中文名）
 * @property kind 角色（下单平台 / 支付通道），决定同时命中时谁优先，见 [PlatformKind]
 * @property strongKeywords Tier A（0.90）：平台自付渠道措辞，如「微信支付」「抖音支付」
 * @property mediumKeywords Tier B（0.60）：可能是平台也可能是商户，如「美团」「淘宝」
 * @property weakKeywords Tier C（0.35）：间接线索，如「财付通」（微信持牌主体，但也可能出现在银行短信对手方描述里）
 * @property packageNames 通知来源包名，命中即最强信号（0.95）
 * @property sortOrder 同分时的稳定排序（候选顺序不随 map 遍历顺序抖动）
 */
data class PlatformEntry(
    val id: String,
    val displayName: String,
    val kind: PlatformKind = PlatformKind.OTHER,
    val strongKeywords: List<String> = emptyList(),
    val mediumKeywords: List<String> = emptyList(),
    val weakKeywords: List<String> = emptyList(),
    val packageNames: Set<String> = emptySet(),
    val sortOrder: Int = Int.MAX_VALUE,
)

/**
 * 消费平台目录。
 *
 * 新增平台 = 往 [BUILT_IN] 加一条数据，或运行时调用 [register]（预留给「用户自定义 / 远端下发规则包」，
 * 本期不开放 UI）。
 */
object PlatformCatalog {

    /** 未知平台 ID：识别不出、或历史数据回填时用它。**不是错误状态**。 */
    const val UNKNOWN_ID = "unknown"

    /** 未收录 ID 的兜底展示名。刻意与 [UNKNOWN] 的「未知」区分开，便于排查脏数据。 */
    const val UNKNOWN_DISPLAY_NAME = "未知平台"

    private const val SORT_UNKNOWN = Int.MAX_VALUE

    val UNKNOWN: PlatformEntry = PlatformEntry(
        id = UNKNOWN_ID,
        displayName = "未知",
        sortOrder = SORT_UNKNOWN,
    )

    /**
     * 内置目录。排序即 [sortOrder] 升序，同分时保持这里的声明顺序。
     *
     * 关键词分层的判据见 [PlatformEntry] 的属性文档；同一词出现在多层时**取最高分**。
     */
    val BUILT_IN: List<PlatformEntry> = listOf(
        PlatformEntry(
            id = "wechat",
            displayName = "微信",
            kind = PlatformKind.PAYMENT,
            strongKeywords = listOf("微信支付", "微信支付凭证", "微信零钱"),
            mediumKeywords = listOf("微信"),
            weakKeywords = listOf("财付通", "亲属卡"),
            packageNames = setOf("com.tencent.mm"),
            sortOrder = 10,
        ),
        PlatformEntry(
            id = "alipay",
            displayName = "支付宝",
            kind = PlatformKind.PAYMENT,
            // 拉丁品牌名与中文品牌名是同一个事实，故同列 Tier A；
            // 匹配本身忽略大小写（见 KeywordPlatformResolver），故 Alipay / ALIPAY 一并覆盖。
            strongKeywords = listOf("支付宝", "支付宝付款", "alipay"),
            mediumKeywords = listOf("支付宝"),
            // 注：设计稿里「网商银行」带 (?) 表示存疑。它与支付宝并非同一主体且常出现在银行侧短信，
            // 纳入会抬高误判，故**不收录**；等有真实样本再说。
            weakKeywords = listOf("花呗", "余额宝"),
            packageNames = setOf("com.eg.android.AlipayGphone"),
            sortOrder = 20,
        ),
        PlatformEntry(
            id = "meituan",
            displayName = "美团",
            kind = PlatformKind.ORDER,
            strongKeywords = listOf("美团支付", "美团外卖"),
            mediumKeywords = listOf("美团", "大众点评"),
            weakKeywords = listOf("美团优选"),
            packageNames = setOf("com.sankuai.meituan"),
            sortOrder = 30,
        ),
        PlatformEntry(
            id = "pdd",
            displayName = "拼多多",
            kind = PlatformKind.ORDER,
            strongKeywords = listOf("拼多多支付"),
            mediumKeywords = listOf("拼多多"),
            weakKeywords = listOf("多多钱包"),
            packageNames = setOf("com.xunmeng.pinduoduo"),
            sortOrder = 40,
        ),
        PlatformEntry(
            id = "douyin",
            displayName = "抖音",
            kind = PlatformKind.ORDER,
            strongKeywords = listOf("抖音支付"),
            mediumKeywords = listOf("抖音", "抖音商城"),
            weakKeywords = listOf("巨量引擎"),
            packageNames = setOf("com.ss.android.ugc.aweme"),
            sortOrder = 50,
        ),
        PlatformEntry(
            id = "taobao",
            displayName = "淘宝",
            kind = PlatformKind.ORDER,
            strongKeywords = listOf("淘票票"),
            mediumKeywords = listOf("淘宝", "天猫"),
            weakKeywords = listOf("阿里巴巴"),
            packageNames = setOf("com.taobao.taobao"),
            sortOrder = 60,
        ),
        UNKNOWN,
    )

    /** 运行时注册的额外条目（用户自定义 / 远端下发）。用写时复制列表，读多写少。 */
    private val extra: MutableList<PlatformEntry> = java.util.concurrent.CopyOnWriteArrayList()

    /** 全部条目，按 [PlatformEntry.sortOrder] 稳定排序。 */
    fun all(): List<PlatformEntry> = (BUILT_IN + extra).sortedBy { it.sortOrder }

    /** 按 ID 查条目；未收录返回 null。 */
    fun find(id: String): PlatformEntry? = all().firstOrNull { it.id == id }

    /**
     * ID → 展示名。**未收录的 ID 返回 [UNKNOWN_DISPLAY_NAME]，绝不抛异常**
     * （导入旧备份、远端下发新 ID 都可能带来未收录值，崩溃即丢数据）。
     */
    fun displayNameOf(id: String): String = find(id)?.displayName ?: UNKNOWN_DISPLAY_NAME

    /**
     * 注册额外条目。同 ID 覆盖旧值。
     *
     * 本期无调用方（预留给「用户自定义平台」），但目录的可扩展性是本设计的既定目标，
     * 故按契约实现；未开放 UI 前不会被触发。
     */
    fun register(entry: PlatformEntry) {
        extra.removeAll { it.id == entry.id }
        extra.add(entry)
    }

    /** 仅供测试：清空 [register] 进来的条目，恢复内置目录。 */
    internal fun resetExtras() {
        extra.clear()
    }
}

/**
 * 平台识别的输入上下文。
 *
 * @property rawText 解密后的原文（通知正文 / 短信正文）
 * @property counterparty 已有的商户名，可作弱线索（如商户恰为「美团外卖」）
 * @property packageName 通知来源包名（最强线索）。注意短信渠道传的是 `"sms:inbox"`，
 *           它**不会**命中任何平台包名映射 —— 这是正确的，银行短信本就该走关键词或落 unknown
 * @property sourceId 采集来源（**技术字段**）。仅用于降级策略，**不参与平台判定**
 */
data class PlatformContext(
    val rawText: String? = null,
    val counterparty: String? = null,
    val packageName: String? = null,
    val sourceId: String = "",
)

/** 单个平台的命中结果。 */
data class PlatformMatch(
    val platformId: String,
    /** 0~1 */
    val score: Float,
    /** 给人看的判定依据，如「包名 com.tencent.mm」「命中强词「微信支付」」 */
    val evidence: String,
)

/**
 * 平台识别结果。
 *
 * @property platformId 最终取值（可能是 [PlatformCatalog.UNKNOWN_ID]）
 * @property confidence top1 得分
 * @property candidates 候选，按 score 降序，最多 [PlatformResolver.TOP_N] 个
 * @property ambiguous true ⇒ UI 必须打「不确定」角标（即便 top1 很高）
 */
data class PlatformResolution(
    val platformId: String = PlatformCatalog.UNKNOWN_ID,
    val confidence: Float = 0f,
    val candidates: List<PlatformMatch> = emptyList(),
    val ambiguous: Boolean = false,
)

/**
 * 平台识别器契约。
 *
 * 实现放在 `feature:platform`（纯 JVM），**不依赖 `core:database`**。
 * 串在 `NotificationParser` **之后**，不改动后者（职责正交：一个管「是不是一笔钱」，一个管「在哪个平台花的」）。
 */
interface PlatformResolver {
    val id: String

    fun resolve(ctx: PlatformContext): PlatformResolution

    companion object {
        /** 候选列表最多几个。 */
        const val TOP_N: Int = 3

        /** top1 低于此分 ⇒ 判为 unknown。 */
        const val UNKNOWN_THRESHOLD: Float = 0.35f

        /** top1 ≥ 此分且非歧义 ⇒ 直接采用，不打角标。 */
        const val CONFIRM_THRESHOLD: Float = 0.75f

        /**
         * top1 − top2 小于此差 ⇒ 判为歧义。
         *
         * 独立于 [CONFIRM_THRESHOLD] 的理由：典型「淘宝 + 支付宝」场景两者都超过 0.75，
         * **任何单个阈值来看都「很有把握」，但它们互相打架**。只有 gap 能识别
         * 「两个都高 ⇒ 说明有冲突」这个语义。
         */
        const val AMBIGUOUS_GAP: Float = 0.15f

        /** top2 至少达到此分才算「真候选」，避免把噪声当成第二选择。 */
        const val AMBIGUOUS_TOP_FLOOR: Float = 0.50f
    }
}
