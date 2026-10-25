package com.autoledger.core.database.repository

import com.autoledger.core.database.AccountEntity
import com.autoledger.core.database.CategoryEntity
import com.autoledger.core.database.TransactionEntity
import com.autoledger.core.database.UserPlatformEntity
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.countsInLedger
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.platform.PlatformSource

private const val HINT_SEP = "\u0001"

/**
 * 关键词组 / 包名组的**换行分隔符**（实体用 String、领域用 List）。
 *
 * 与 [HINT_SEP]（账号的 `\u0001`）刻意不同：那几个字段是机器生成的提示词，
 * 而平台关键词是**用户手输的多行文本**（一行一个），用 `\n` 才符合直觉；
 * `\u0001` 是控制字符，用户既看不见也敲不出来，一旦需要人工修库就无从下手。
 */
internal const val KEYWORD_SEP = "\n"

internal fun LedgerTransaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    occurredAtMillis = occurredAtMillis,
    bookedAtMillis = bookedAtMillis,
    type = type,
    // 直接搬运领域对象的方向，**不要**在这里按金额重算：重算会丢掉「类型决定方向」的语义
    // （金额缺失时 amountMinor 是 0，重算会把一笔支出写成 IN），也让 toDomain 的往返回不到原值。
    direction = direction,
    counterparty = counterparty,
    platformId = platformId,
    platformConfidence = platformConfidence,
    platformSource = platformSource.name,
    note = note,
    sourceId = sourceId,
    sourceRef = sourceRef,
    accountId = accountId,
    categoryId = categoryId,
    transferGroupId = transferGroupId,
    fingerprint = fingerprint,
    status = status,
    confidence = confidence,
    rawTextSealed = rawTextSealed,
    extras = extras,
    orderId = orderId,
    refundId = refundId,
    mergedIntoId = mergedIntoId,
    schemaVersion = schemaVersion,
)

internal fun TransactionEntity.toDomain(): LedgerTransaction = LedgerTransaction(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    occurredAtMillis = occurredAtMillis,
    bookedAtMillis = bookedAtMillis,
    type = type,
    direction = direction,
    counterparty = counterparty,
    platformId = platformId,
    platformConfidence = platformConfidence,
    platformSource = runCatching { PlatformSource.valueOf(platformSource) }.getOrDefault(PlatformSource.AUTO),
    note = note,
    sourceId = sourceId,
    sourceRef = sourceRef,
    accountId = accountId,
    categoryId = categoryId,
    transferGroupId = transferGroupId,
    fingerprint = fingerprint,
    status = status,
    confidence = confidence,
    rawTextSealed = rawTextSealed,
    extras = extras,
    orderId = orderId,
    refundId = refundId,
    mergedIntoId = mergedIntoId,
    schemaVersion = schemaVersion,
)

// ---------------------------------------------------------------- 用户自定义消费平台

/** 换行分隔 ↔ List，**逐项去空白并丢弃空行**（用户在编辑框里多敲几个回车不该产生空关键词）。 */
private fun String.toKeywordList(): List<String> =
    split(KEYWORD_SEP).map { it.trim() }.filter { it.isNotEmpty() }

private fun List<String>.toKeywordText(): String =
    joinToString(KEYWORD_SEP) { it.trim() }

internal fun UserPlatformEntity.toDomain(): UserPlatform = UserPlatform(
    id = id,
    displayName = displayName,
    kind = runCatching { PlatformKind.valueOf(kind) }.getOrDefault(PlatformKind.ORDER),
    strongKeywords = strongKeywords.toKeywordList(),
    mediumKeywords = mediumKeywords.toKeywordList(),
    weakKeywords = weakKeywords.toKeywordList(),
    packageNames = packageNames.toKeywordList().toSet(),
    sortOrder = sortOrder,
    archived = archived,
    createdAtMillis = createdAtMillis,
    schemaVersion = schemaVersion,
)

internal fun UserPlatform.toEntity(): UserPlatformEntity = UserPlatformEntity(
    id = id,
    displayName = displayName,
    // 存 name() 而不是 ordinal：ordinal 会随枚举新增取值而整体错位（BANK 插在中间就会写坏历史行）。
    kind = kind.name,
    strongKeywords = strongKeywords.toKeywordText(),
    mediumKeywords = mediumKeywords.toKeywordText(),
    weakKeywords = weakKeywords.toKeywordText(),
    // Set → 有序 List：Set 的迭代顺序不稳定，直接 join 会让同一份数据每次落库得到不同的字符串，
    // 从而在「导入 → 导出」对拍里出现无意义的差异。
    packageNames = packageNames.toList().toKeywordText(),
    sortOrder = sortOrder,
    archived = archived,
    createdAtMillis = createdAtMillis,
    schemaVersion = schemaVersion,
)

internal fun CategoryEntity.toDomain(): Category = Category(
    id = id, name = name, iconKey = iconKey, colorHex = colorHex,
    parentId = parentId, sortOrder = sortOrder, builtIn = builtIn,
    kind = kind, monthlyBudgetMinor = monthlyBudgetMinor, archived = archived,
)

internal fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id, name = name, iconKey = iconKey, colorHex = colorHex,
    parentId = parentId, sortOrder = sortOrder, builtIn = builtIn,
    kind = kind, monthlyBudgetMinor = monthlyBudgetMinor, archived = archived,
)

internal fun AccountEntity.toDomain(): Account = Account(
    id = id, name = name, kind = kind, institution = institution,
    identifierHints = identifierHints.split(HINT_SEP).filter { it.isNotBlank() },
    archived = archived,
)

internal fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id, name = name, kind = kind, institution = institution,
    identifierHints = identifierHints.joinToString(HINT_SEP),
    archived = archived,
)

/**
 * 供 finance 层复用的类型判定：只有「待确认但默认成立（RAW）」与「已确认（CONFIRMED）」的支出算消费。
 *
 * 口径统一走 [countsInLedger] 白名单 —— 被合并（MERGED）、被忽略（IGNORED）、
 * 疑似重复未确认（DUPLICATE）的流水都不算消费。与 [com.autoledger.core.model.ExpenseMath]
 * 必须逐格一致（见 MappersTest 的交叉断言）。
 */
internal fun LedgerTransaction.isConsumption(): Boolean =
    type == TxnType.EXPENSE && status.countsInLedger
