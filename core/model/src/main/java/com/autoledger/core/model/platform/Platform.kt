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

    /**
     * 银行：钱从**哪张卡**出去（结算侧）。
     *
     * 为什么要独立成一个 kind，而不是塞进 [PAYMENT] 或 [OTHER]：
     * - 塞进 [PAYMENT] ⇒ 它会与微信/支付宝同级，而需求要求「银行卡优先级最低」
     *   （美团 > 微信/支付宝 > 银行卡），同级就**排不出序**；
     * - 塞进 [OTHER] ⇒ [toPriority] 会得到 [PlatformPriority.NONE]，**无法参与层级比较**，
     *   跨渠道互补匹配直接失效。
     *
     * 新增本值是**源码兼容**的：全仓对 [PlatformKind] 只有 `== ORDER` / `== PAYMENT` 形式的判断，
     * 没有穷尽 `when`（否则会因缺分支而编译失败）。
     */
    BANK,

    /** 既非下单也非通道（如 unknown）。 */
    OTHER,
}

/**
 * 去重层级 —— 「合并后谁留下」的排序。
 *
 * **刻意与 [PlatformKind]（角色）分开**：kind 描述业务角色，priority 描述合并语义。
 * 二者今天一一对应，但将来可能分叉（例如把 [PlatformKind.OTHER] 也纳入层级），
 * 那时只需改这一处派生函数，不必动 kind 枚举。
 *
 * `rank` 只在**[DedupPriority] 的裁决**里比大小，绝不用于识别打分。
 */
enum class PlatformPriority(val rank: Int) {
    /** unknown / OTHER —— 不参与层级比较。 */
    NONE(0),

    /** 银行卡：层级最低（钱从哪张卡出去，信息量最少）。 */
    BANK(1),

    /** 微信 / 支付宝 / 云闪付 / 数字人民币。 */
    PAYMENT(2),

    /** 美团 / 淘宝 / 拼多多 / 抖音：层级最高（用户真正关心的消费场所）。 */
    ORDER(3),
}

/** 角色 → 去重层级。 */
fun PlatformKind.toPriority(): PlatformPriority = when (this) {
    PlatformKind.ORDER -> PlatformPriority.ORDER
    PlatformKind.PAYMENT -> PlatformPriority.PAYMENT
    PlatformKind.BANK -> PlatformPriority.BANK
    PlatformKind.OTHER -> PlatformPriority.NONE
}

/**
 * 平台 ID → 去重层级。
 *
 * 用**纯函数派生**而不是在 [PlatformEntry] 上加一个 `priority` 字段：
 * 少一个字段就少一处「自定义平台忘了填 priority」的出错可能，且天然与 [PlatformKind] 一致。
 *
 * 未收录 ID 一律 [PlatformPriority.NONE]，**绝不抛异常**（旧备份 / 远端下发都可能是新 ID）。
 */
fun priorityOf(platformId: String): PlatformPriority =
    PlatformCatalog.find(platformId)?.kind?.toPriority() ?: PlatformPriority.NONE

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
 * @property archived 软删除标记。**只对用户自定义平台有意义**（内置条目恒为 false）。
 *   语义是「不再参与**识别与指派**」，但**仍然可被 [PlatformCatalog.find]/[PlatformCatalog.displayNameOf] 查到**
 *   —— 历史流水的 `platformId` 指向这一条，若彻底查不到，那些流水的平台名会塌成「未知平台」，
 *   用户会以为数据坏了（见设计风险 R7）。因此识别层必须**显式跳过** [archived] 条目，
 *   而不是靠"不注册它"来实现停用。
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
    val archived: Boolean = false,
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

    /**
     * 银行卡 ID。
     *
     * 语义边界：`bank` = **知道钱从银行卡出去，但不知道花在哪**；`unknown` = 连支付通道都不知道。
     * 二者**不可合并** —— 前者是「信息不足但有方向」，后者是「完全没有线索」。
     *
     * ⚠️ 刻意**不**用 `sourceId == "sms"` 判银行：[PlatformContext] 已明确「sourceId 不参与平台判定」，
     * 银行判定只依据文本关键词，以保住这条架构不变量。
     */
    const val BANK_ID = "bank"

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
        PlatformEntry(
            id = BANK_ID,
            displayName = "银行卡",
            kind = PlatformKind.BANK,
            // ⚠️ 刻意**只给 weak（0.35）**：「银行 / 尾号 / 储蓄卡」是极高频词，
            // 几乎每条银行短信都有；放到 strong(0.90) 会把大量本该 unknown 的记录吸成 bank。
            // 0.35 ⇒ 低于 CONFIRM_THRESHOLD，UI 自动打「待确认」角标，用户可一键改成真实平台。
            weakKeywords = listOf("储蓄卡", "信用卡", "借记卡", "尾号", "银行"),
            // 不按包名匹配：各银行 App 包名零散且多为「待核实」，且银行短信没有包名。
            // 写错包名 = 整类通知被恒定错判，比暂时不识别危害大得多。
            packageNames = emptySet(),
            sortOrder = 70,
        ),
        PlatformEntry(
            id = "digital_rmb",
            displayName = "数字人民币",
            kind = PlatformKind.PAYMENT,
            strongKeywords = listOf("数字人民币", "数币支付"),
            mediumKeywords = listOf("数字人民币钱包", "e-CNY"),
            weakKeywords = listOf("试点版"),
            packageNames = emptySet(), // 待核实（设计文档 §3.2）：包名不确定则留空，靠关键词兜底
            sortOrder = 80,
        ),
        PlatformEntry(
            id = "unionpay",
            displayName = "云闪付",
            kind = PlatformKind.PAYMENT,
            strongKeywords = listOf("云闪付"),
            mediumKeywords = listOf("银联", "UnionPay"),
            weakKeywords = listOf("银联商务", "云闪付支付"),
            packageNames = emptySet(), // 待核实（设计文档 §3.2）
            sortOrder = 90,
        ),
        UNKNOWN,
    )

    /** 运行时注册的额外条目（用户自定义 / 远端下发）。用写时复制列表，读多写少。 */
    private val extra: MutableList<PlatformEntry> = java.util.concurrent.CopyOnWriteArrayList()

    /**
     * [all] 的结果缓存。写操作（register / unregister / resetExtras）时置空失效。
     *
     * 为什么必须缓存：`find()` → `all()`、`displayNameOf()` → `find()` → `all()`，
     * 而 `all()` 每次都做 `(BUILT_IN + extra).sortedBy{}`。账单列表里**每一行渲染**
     * 都会调 `displayNameOf`，等于每帧重排一遍整个目录；接入用户自定义平台后规模还会增长。
     */
    @Volatile
    private var cached: List<PlatformEntry>? = null

    /** 全部条目，按 [PlatformEntry.sortOrder] 稳定排序。结果被缓存，见 [cached]。 */
    fun all(): List<PlatformEntry> =
        cached ?: (BUILT_IN + extra).sortedBy { it.sortOrder }.also { cached = it }

    /** 按 ID 查条目；未收录返回 null。**包含归档条目**（历史流水靠它显示原名）。 */
    fun find(id: String): PlatformEntry? = all().firstOrNull { it.id == id }

    /**
     * 可以「指派给新流水」的条目 = 排除已归档的。
     *
     * 与 [all] 的分工必须分清：
     * - **展示**用 [find] / [displayNameOf] / [all]：要能看到归档条目，否则历史流水的平台名会塌成「未知平台」（R7）；
     * - **选择器与识别**用本方法：已停用的平台不该再被选到、也不该再被识别出来。
     */
    fun selectable(): List<PlatformEntry> = all().filter { !it.archived }

    /**
     * ID → 展示名。**未收录的 ID 返回 [UNKNOWN_DISPLAY_NAME]，绝不抛异常**
     * （导入旧备份、远端下发新 ID 都可能带来未收录值，崩溃即丢数据）。
     */
    fun displayNameOf(id: String): String = find(id)?.displayName ?: UNKNOWN_DISPLAY_NAME

    /**
     * 注册额外条目。同 ID 覆盖旧值。
     *
     * 由「用户自定义平台」在启动注入与写成功后调用（见 AppContainer / UserPlatformStore）。
     */
    fun register(entry: PlatformEntry) {
        extra.removeAll { it.id == entry.id }
        extra.add(entry)
        cached = null
    }

    /**
     * 移除运行时条目（用户自定义平台被停用 / 删除时调用）。
     *
     * [BUILT_IN] 不可移除：`extra` 只装 [register] 进来的条目，
     * 所以对内置 ID 调用它是 **no-op**（而不是抛异常）—— 调用方不必先判断「这是不是内置的」。
     *
     * ⚠️ 移除只是「不再参与**识别**」；展示仍走 [displayNameOf]，
     * 而历史流水引用的 `platformId` 若来自别处的目录快照仍能查到。
     * 用户自定义平台的真正保留靠 DB 的软删除（`archived`），不是靠这里。
     */
    fun unregister(id: String) {
        extra.removeAll { it.id == id }
        cached = null
    }

    /** 仅供测试：清空 [register] 进来的条目，恢复内置目录。 */
    internal fun resetExtras() {
        extra.clear()
        cached = null
    }

    /**
     * 用给定条目**整体替换**运行时条目（备份导入后重建目录用）。
     *
     * 为什么不暴露「先 [resetExtras] 再逐个 [register]」给 `core:backup`：
     * ① `resetExtras` 是 `internal`，跨模块本来就调不到；
     * ② 更本质的原因 —— 那样在两步之间存在一个「自定义平台全部消失」的窗口，
     * 而采集循环可能正在并发识别，会把刚落地的流水判定成 `unknown`。
     * 整体替换是**原子**的，要么旧目录、要么新目录，不存在中间态。
     */
    fun replaceExtras(entries: List<PlatformEntry>) {
        extra.clear()
        extra.addAll(entries)
        cached = null
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
