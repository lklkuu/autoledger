package com.autoledger.core.backup

import android.util.Base64

import com.autoledger.core.database.ClassifierRuleDao
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.database.toAppSettings
import com.autoledger.core.database.toEntity
import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.Direction
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.WageProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导出 / 导入 / 本地备份。
 *
 * 两个硬性约定：
 * 1. 导入**永远先走迁移链**再落库，保证老备份可用；迁移失败要抛错，不允许静默丢弃。
 * 2. 加密导出走 PBKDF2 派生的 AES-GCM，明文导出默认不提供（避免用户误以为有保护）。
 */
class BackupManager(
    private val db: LedgerDatabase,
    private val repo: RoomLedgerRepository,
) {

    // Android Base64 与导出格式既有 NO_WRAP 契约一致，避免平台默认编码产生换行。
    enum class MergeStrategy { REPLACE_ALL, MERGE_BY_ID }

    data class ImportOutcome(
        val fileVersion: Int,
        val migratedToVersion: Int,
        val transactionsUpserted: Int,
        val categoriesUpserted: Int,
        val rulesUpserted: Int,
    )

    private val txnDao = db.transactionDao()
    private val ruleDao: ClassifierRuleDao = db.classifierRuleDao()

    // ------------------------------------------------- 导出

    suspend fun exportJson(
        appVersion: String,
        device: String,
        transform: (LedgerTransaction) -> LedgerTransaction = { it },
    ): String {
        val root = BackupEnvelope.wrap(buildPayload(transform), device, appVersion)
        return root.toString(2)
    }

    suspend fun exportEncrypted(appVersion: String, device: String, passphrase: CharArray): String {
        val plainJson = exportJson(appVersion, device)
        val salt = PassphraseKeyDeriver.randomSalt()
        val key = PassphraseKeyDeriver.derive(passphrase, salt)
        val cipher = CryptoBox(key).seal(plainJson.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put(BackupEnvelope.KEY_KIND, BackupEnvelope.KIND)
            put("encrypted", true)
            put("kdf", PassphraseKeyDeriver.KDF)
            put("iterations", PassphraseKeyDeriver.ITERATIONS)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("cipher", Base64.encodeToString(cipher, Base64.NO_WRAP))
        }.toString()
    }

    private suspend fun buildPayload(transform: (LedgerTransaction) -> LedgerTransaction): JSONObject {
        val transactions = JSONArray().apply {
            repo.listAll(includeTransfers = true).forEach { t -> put(transform(t).toJson()) }
        }
        val categories = JSONArray().apply {
            repo.listCategories().forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("name", c.name); put("iconKey", c.iconKey)
                    put("colorHex", c.colorHex); put("parentId", c.parentId)
                    put("sortOrder", c.sortOrder); put("builtIn", c.builtIn)
                    put("kind", c.kind.name); put("archived", c.archived)
                    if (c.monthlyBudgetMinor != null) put("monthlyBudgetMinor", c.monthlyBudgetMinor)
                })
            }
        }
        val accounts = JSONArray().apply {
            repo.listAccounts().forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id); put("name", a.name); put("kind", a.kind.name)
                    put("institution", a.institution)
                    put("identifierHints", JSONArray(a.identifierHints))
                    put("archived", a.archived)
                })
            }
        }
        val rules = JSONArray().apply {
            ruleDao.listAll().forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("kind", r.kind.name); put("pattern", r.pattern)
                    put("categoryId", r.categoryId); put("priority", r.priority)
                    put("learned", r.learned); put("hitCount", r.hitCount)
                    put("createdAtMillis", r.createdAtMillis)
                })
            }
        }
        return JSONObject().apply {
            put("transactions", transactions)
            put("categories", categories)
            put("accounts", accounts)
            put("rules", rules)
            db.settingsDao().global()?.let { put("settings", it.toAppSettings().toJson()) }
        }
    }

    // ------------------------------------------------- 导入

    suspend fun import(
        raw: String,
        strategy: MergeStrategy,
        transform: (LedgerTransaction) -> LedgerTransaction = { it },
    ): ImportOutcome {
        val root = JSONObject(raw)

        // 加密档案先解开
        val effective = if (root.optBoolean("encrypted", false)) {
            val passphrase = currentPassphrase
                ?: throw IllegalStateException("该备份已加密，需要提供口令")
            val salt = Base64.decode(root.getString("salt"), Base64.NO_WRAP)
            val iterations = root.optInt("iterations", PassphraseKeyDeriver.ITERATIONS)
            val key = PassphraseKeyDeriver.derive(passphrase, salt, iterations)
            val cipher = Base64.decode(root.getString("cipher"), Base64.NO_WRAP)
            JSONObject(String(CryptoBox(key).open(cipher), Charsets.UTF_8))
        } else root

        val fileVersion = BackupEnvelope.readVersion(effective)
        val payloadJson = BackupEnvelope.payloadOf(effective)
        val (payload, finalVersion) = BackupMigrator.migrateToCurrent(payloadJson, fileVersion)

        if (strategy == MergeStrategy.REPLACE_ALL) repo.clearAllTransactions()

        val txns = payload.optJSONArray("transactions")?.toTransactions().orEmpty().map(transform)
        repo.upsertAll(txns)

        val cats = payload.optJSONArray("categories")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.autoledger.core.model.Category(
                    id = o.getString("id"), name = o.getString("name"),
                    iconKey = o.optString("iconKey", "receipt"),
                    colorHex = o.optString("colorHex", "#708786"),
                    parentId = o.optString("parentId").takeIf { it.isNotBlank() },
                    sortOrder = o.optInt("sortOrder", 0),
                    builtIn = o.optBoolean("builtIn", true),
                    kind = runCatching { CategoryKind.valueOf(o.optString("kind", "EXPENSE")) }.getOrDefault(CategoryKind.EXPENSE),
                    monthlyBudgetMinor = if (o.has("monthlyBudgetMinor")) o.optLong("monthlyBudgetMinor") else null,
                    archived = o.optBoolean("archived", false),
                )
            }
        }.orEmpty()
        if (cats.isNotEmpty()) repo.upsertCategories(cats)

        val accs = payload.optJSONArray("accounts")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.autoledger.core.model.Account(
                    id = o.getString("id"), name = o.getString("name"),
                    kind = runCatching { AccountKind.valueOf(o.getString("kind")) }.getOrDefault(AccountKind.OTHER),
                    institution = o.optString("institution").takeIf { it.isNotBlank() },
                    identifierHints = o.optJSONArray("identifierHints")?.let { h ->
                        (0 until h.length()).map { h.getString(it) }
                    }.orEmpty(),
                    archived = o.optBoolean("archived", false),
                )
            }
        }.orEmpty()
        if (accs.isNotEmpty()) repo.upsertAccounts(accs)

        val rules = payload.optJSONArray("rules")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.autoledger.core.database.ClassifierRuleEntity(
                    id = o.getString("id"),
                    kind = runCatching { RuleKind.valueOf(o.getString("kind")) }.getOrDefault(RuleKind.KEYWORD),
                    pattern = o.getString("pattern"),
                    categoryId = o.getString("categoryId"),
                    priority = o.optInt("priority", 0),
                    learned = o.optBoolean("learned", false),
                    hitCount = o.optInt("hitCount", 0),
                    createdAtMillis = o.optLong("createdAtMillis", 0L),
                )
            }
        }.orEmpty()
        if (rules.isNotEmpty()) ruleDao.upsertAll(rules)

        payload.optJSONObject("settings")?.let { db.settingsDao().upsert(it.toAppSettingsFromJson().toEntity()) }

        return ImportOutcome(
            fileVersion = fileVersion,
            migratedToVersion = finalVersion,
            transactionsUpserted = txns.size,
            categoriesUpserted = cats.size,
            rulesUpserted = rules.size,
        )
    }

    /** 解密导入时用的口令，UI 通过赋值给它来注入（用完即弃，不落盘） */
    @Volatile var currentPassphrase: CharArray? = null

    private fun JSONArray.toTransactions(): List<LedgerTransaction> = (0 until length()).map { i ->
        val o = getJSONObject(i)
        LedgerTransaction(
            id = o.getString("id"),
            amountMinor = o.getLong("amountMinor"),
            currency = o.optString("currency", "CNY"),
            occurredAtMillis = o.getLong("occurredAtMillis"),
            bookedAtMillis = o.optLong("bookedAtMillis", o.getLong("occurredAtMillis")),
            type = runCatching { TxnType.valueOf(o.getString("type")) }.getOrDefault(TxnType.EXPENSE),
            direction = runCatching { Direction.valueOf(o.optString("direction", "OUT")) }.getOrDefault(Direction.OUT),
            counterparty = o.optString("counterparty", ""),
            note = o.optString("note").takeIf { it.isNotBlank() },
            sourceId = o.optString("sourceId", "manual"),
            sourceRef = o.optString("sourceRef", ""),
            accountId = o.optString("accountId").takeIf { it.isNotBlank() },
            categoryId = o.optString("categoryId").takeIf { it.isNotBlank() },
            transferGroupId = o.optString("transferGroupId").takeIf { it.isNotBlank() },
            fingerprint = o.optString("fingerprint", ""),
            status = runCatching { TxnStatus.valueOf(o.optString("status", "CONFIRMED")) }.getOrDefault(TxnStatus.CONFIRMED),
            confidence = o.optDouble("confidence", 1.0).toFloat(),
            rawTextSealed = o.optString("rawTextSealed").takeIf { it.isNotBlank() },
            extras = o.optString("extras").takeIf { it.isNotBlank() },
            orderId = o.optString("orderId").takeIf { it.isNotBlank() },
            refundId = o.optString("refundId").takeIf { it.isNotBlank() },
            schemaVersion = o.optInt("schemaVersion", com.autoledger.core.model.LedgerSchema.CURRENT),
        )
    }

    private fun LedgerTransaction.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("amountMinor", amountMinor); put("currency", currency)
        put("occurredAtMillis", occurredAtMillis); put("bookedAtMillis", bookedAtMillis)
        put("type", type.name); put("direction", direction.name)
        put("counterparty", counterparty); put("note", note)
        put("sourceId", sourceId); put("sourceRef", sourceRef)
        put("accountId", accountId); put("categoryId", categoryId)
        put("transferGroupId", transferGroupId); put("fingerprint", fingerprint)
        put("status", status.name); put("confidence", confidence)
        put("rawTextSealed", rawTextSealed); put("extras", extras); put("schemaVersion", schemaVersion)
        put("orderId", orderId); put("refundId", refundId)
    }

    private fun AppSettings.toJson(): JSONObject = JSONObject().apply {
        put("wage", JSONObject().apply {
            put("monthlyNetSalaryMinor", wage.monthlyNetSalaryMinor)
            put("payMonthsPerYear", wage.payMonthsPerYear)
            put("monthlyWorkCostMinor", wage.monthlyWorkCostMinor)
            put("workDaysPerMonth", wage.workDaysPerMonth)
            put("dailyOfficeHours", wage.dailyOfficeHours)
            put("dailyCommuteMinutes", wage.dailyCommuteMinutes)
            put("dailyOvertimeHours", wage.dailyOvertimeHours)
        })
        put("goal", JSONObject().apply {
            put("targetMinor", goal.targetMinor)
            put("cushionMinor", goal.cushionMinor)
            put("currentMinor", goal.currentMinor)
        })
        put("autoMerge", autoMerge)
    }

    private fun JSONObject.toAppSettingsFromJson(): AppSettings {
        val w = optJSONObject("wage")
        val g = optJSONObject("goal")
        return AppSettings(
            wage = WageProfile(
                monthlyNetSalaryMinor = w?.optLong("monthlyNetSalaryMinor") ?: WageProfile().monthlyNetSalaryMinor,
                payMonthsPerYear = w?.optInt("payMonthsPerYear") ?: WageProfile().payMonthsPerYear,
                monthlyWorkCostMinor = w?.optLong("monthlyWorkCostMinor") ?: WageProfile().monthlyWorkCostMinor,
                workDaysPerMonth = w?.optDouble("workDaysPerMonth") ?: WageProfile().workDaysPerMonth,
                dailyOfficeHours = w?.optDouble("dailyOfficeHours") ?: WageProfile().dailyOfficeHours,
                dailyCommuteMinutes = w?.optInt("dailyCommuteMinutes") ?: WageProfile().dailyCommuteMinutes,
                dailyOvertimeHours = w?.optDouble("dailyOvertimeHours") ?: WageProfile().dailyOvertimeHours,
            ),
            goal = FreedomGoal(
                targetMinor = g?.optLong("targetMinor") ?: FreedomGoal().targetMinor,
                cushionMinor = g?.optLong("cushionMinor") ?: FreedomGoal().cushionMinor,
                currentMinor = g?.optLong("currentMinor") ?: FreedomGoal().currentMinor,
            ),
            autoMerge = optBoolean("autoMerge", true),
        )
    }
}
