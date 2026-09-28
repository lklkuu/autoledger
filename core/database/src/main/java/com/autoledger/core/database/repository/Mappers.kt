package com.autoledger.core.database.repository

import com.autoledger.core.database.AccountEntity
import com.autoledger.core.database.CategoryEntity
import com.autoledger.core.database.TransactionEntity
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformSource

private const val HINT_SEP = "\u0001"

internal fun LedgerTransaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    occurredAtMillis = occurredAtMillis,
    bookedAtMillis = bookedAtMillis,
    type = type,
    direction = if (amountMinor < 0) Direction.OUT else Direction.IN,
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

/** 供 finance 层复用的类型判定：被合并的、或被用户「忽略」的流水都不算消费。 */
internal fun LedgerTransaction.isConsumption(): Boolean =
    type == TxnType.EXPENSE &&
        status != com.autoledger.core.model.TxnStatus.MERGED &&
        status != com.autoledger.core.model.TxnStatus.IGNORED
