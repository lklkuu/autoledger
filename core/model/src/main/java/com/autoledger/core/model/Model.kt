package com.autoledger.core.model

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource

/** 一条（已经入账的）资金流水。 */
enum class TxnType { EXPENSE, INCOME, TRANSFER, REFUND }

/** 流水的处理状态：自动采集进来的默认是 RAW，需人工确认或规则自动放行后转为 CONFIRMED。 */
enum class TxnStatus { RAW, CONFIRMED, MERGED, IGNORED }

enum class Direction { OUT, IN }

/**
 * 资金流水领域模型。
 *
 * 结构版本号随实体一起落盘（[schemaVersion]），用于备份导入时选择迁移链路；
 * 旧版本数据不会因为字段新增而读不出来。
 */
data class LedgerTransaction(
    val id: String,
    /** 有符号，单位「分」；负数为流出。 */
    val amountMinor: Long,
    val currency: String = Money.DEFAULT_CURRENCY,
    val occurredAtMillis: Long,
    val bookedAtMillis: Long = occurredAtMillis,
    val type: TxnType,
    /**
     * 资金流向，**由 [type] 决定**，不由金额符号推断。
     *
     * 为什么必须看类型：采集端在金额解析失败时用 `0` 占位（`IngestPipeline` 的
     * `amountMinor = amount ?: 0L`），而 `0 < 0` 不成立 —— 按金额推断会把这类流水**恒判为 IN**，
     * 于是一笔「金额没认出来的支出」在数据层显示为流入，与它自己的 `type = EXPENSE` 直接矛盾。
     *
     * [TxnType.TRANSFER] / [TxnType.REFUND] 没有固定方向（转出/转入、退款入账/冲正都可能），
     * 这两类仍按 [amountMinor] 符号推断。
     */
    val direction: Direction = when (type) {
        TxnType.EXPENSE -> Direction.OUT
        TxnType.INCOME -> Direction.IN
        TxnType.TRANSFER, TxnType.REFUND -> if (amountMinor < 0) Direction.OUT else Direction.IN
    },
    val counterparty: String = "",
    /**
     * 消费平台（业务维度）：这笔消费发生在微信 / 支付宝 / 美团… 存**稳定 ID**，不存中文名。
     * 展示名一律走 [com.autoledger.core.model.platform.PlatformCatalog.displayNameOf]。
     */
    val platformId: String = PlatformCatalog.UNKNOWN_ID,
    /** 平台识别置信度 0~1，驱动 UI 的「不确定」角标。用户手选时置 1f。 */
    val platformConfidence: Float = 0f,
    /**
     * 平台取值来源。[PlatformSource.USER] 是权威标记：
     * 后续任何自动流程（重解析、合并继承、再次 ingest）**都不得改写 platformId**。
     */
    val platformSource: PlatformSource = PlatformSource.AUTO,
    val note: String? = null,
    /** 来源采集插件 ID，见 CaptureSource.id
     *
     * ⚠️ **这是「采集来源」技术追溯字段，不是消费平台（业务字段），勿混用。**
     * 它回答的是「这条记录是怎么抓到的」（通知 / 短信 / 账单导入），
     * 而「这笔钱花在哪个平台上」请看 [platformId]。
     * 它只应在「排查为什么没记录」这类审计场景展示，不作为统计维度。
     */
    val sourceId: String,
    /** 渠道侧标识（通知 key / 短信 _id / CSV 行号），用于追溯与审计 */
    val sourceRef: String,
    val accountId: String? = null,
    val categoryId: String? = null,
    /** 被判定为内部划转时，成对的两笔共享同一个组 ID */
    val transferGroupId: String? = null,
    /** 跨渠道去重指纹 */
    val fingerprint: String = "",
    val status: TxnStatus = TxnStatus.CONFIRMED,
    /** 命中分类规则的置信度，供「待确认」队列排序 */
    val confidence: Float = 1f,
    /**
     * 原始通知/短信正文，**入库前必须用 core-crypto 的 AES-GCM 加密**（base64 密文）。
     * 之所以单独再加密一次：整库已有 SQLCipher，这一层是防止「一旦有人把 db 文件导出 + 拿到口令」
     * 后原文直接暴露，同时也是「日志/截图里不会误泄原文」的第二道保险。
     */
    val rawTextSealed: String? = null,
    /**
     * 扩展属性（JSON 字符串）。用于装标签、地点、票据、分期、报销状态等非索引维度，
     * 避免每加一个属性都要改表结构。不参与 SQL 过滤/聚合。
     */
    val extras: String? = null,
    /** 该流水归属的订单 ID（订单/退款场景使用） */
    val orderId: String? = null,
    /** 由退款产生时，指向退款单 ID */
    val refundId: String? = null,
    /**
     * 合并溯源：本行被吸收进哪条主记录（`null` = 未被合并）。
     *
     * 与 `status == MERGED` **成对**出现，由 `setMergeState` 一次写入 —— 分成两次调用
     * 会出现「置了 MERGED 但没记主记录」的中间态（那一行既不在账单里，也查不出被谁吸收）。
     *
     * 有了它，「这笔记了两次，分别来自微信和银行卡」变成**可查数据**：
     * `mergeGroupOf(primaryId)` 反查 + 每行自己的 `platformId` 仍在，
     * 不需要把「微信/银行卡」拼成字符串塞进主记录（那样会污染主记录口径）。
     */
    val mergedIntoId: String? = null,
    val schemaVersion: Int = LedgerSchema.CURRENT,
)

/** 分类归属：支出类目 / 收入类目。 */
enum class CategoryKind { EXPENSE, INCOME }

data class Category(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val builtIn: Boolean = true,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    /** 月度预算（分）；null 表示不设预算 */
    val monthlyBudgetMinor: Long? = null,
    val archived: Boolean = false,
)

enum class AccountKind { BANK_CARD, CREDIT_CARD, WECHAT, ALIPAY, CASH, OTHER }

data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val institution: String? = null,
    /** 用于内部转账识别：卡号后四位 / 手机号 / 微信昵称 / 支付宝账号 */
    val identifierHints: List<String> = emptyList(),
    val archived: Boolean = false,
)

enum class RuleKind { MERCHANT_EXACT, KEYWORD, REGEX, AMOUNT_RANGE }

/** 分类规则。learned=true 表示由用户纠正回流生成，优先级最高。 */
data class ClassifierRule(
    val id: String,
    val kind: RuleKind,
    val pattern: String,
    val categoryId: String,
    val priority: Int = 0,
    val learned: Boolean = false,
    val hitCount: Int = 0,
    val createdAtMillis: Long = 0L,
)

/** 内部划转的类型。非 NONE 的一律不计入消费统计。 */
enum class TransferKind { NONE, TOPUP, WITHDRAW, CREDIT_REPAYMENT, SELF_TRANSFER, REFUND }

/**
 * 接入采集渠道的原始数据信封。
 * 归一化流水线把它解析成 [LedgerTransaction]，解析失败也不丢，进入待确认队列。
 */
data class RawEnvelope(
    val envelopeId: String,
    val sourceId: String,
    val sourceRef: String,
    val occurredAtMillis: Long,
    val rawText: String,
    val counterpartyHint: String? = null,
    val amountHint: Long? = null,
    val packageName: String? = null,
    /**
     * 显式类型提示：采集方已经知道这笔是什么类型时给出（手动录入退款、账单里带明确类型列）。
     * 为空则按金额正负推断。优先级最高，但仍会被"内部划转识别"覆盖为 TRANSFER/REFUND。
     */
    val explicitType: TxnType? = null,
    /**
     * 采集端由规则已知的收支方向（`NotificationParser.ParseResult.direction` 透传而来）。
     *
     * 用途**仅限**在 [amountHint] 缺失时为初始账本类型判定提供信号：金额存在时一律以金额符号为准，
     * 方向不得推翻金额。**不得**用它推导 `explicitType`（`Direction.IN` 既可能是收入也可能是退款）。
     */
    val directionHint: Direction? = null,
    /**
     * 采集端**已知**的消费平台 ID（手动录入让用户手选时给出）。
     *
     * 非空 ⇒ 权威：见 [LedgerTransaction.platformSource] —— 落 [PlatformSource.USER]
     * 后，去重继承 / 重新解析等任何自动流程都不得改写 platformId。
     *
     * ⚠️ 因此**不得**用 [PlatformCatalog.UNKNOWN_ID] 填它：unknown 的语义是「没识别出来」，
     * 一旦标成 USER 就等于把「不确定」钉成永久事实，以后再也补不上。
     * 构造方负责把 unknown / 空白归一成 null（见 ManualCaptureSource.envelope）。
     */
    val platformHint: String? = null,
    /** 采集端已知的分类 ID（手动录入让用户手选时给出）。非空时不跑分类器。 */
    val categoryHint: String? = null,
    /** 采集端已知的备注（手动录入）。此前备注只被拼进 rawText，落库后 LedgerTransaction.note 恒为 null。 */
    val noteHint: String? = null,
)
