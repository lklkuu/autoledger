package com.autoledger.core.backup

import android.util.Base64

import com.autoledger.core.database.ClassifierRuleDao
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.database.toAppSettings
import com.autoledger.core.database.toEntity
import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.AccountKind
import com.autoledger.core.model.CategoryKind
import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RuleKind
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.toPlatformEntry
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
        /** v6：导入的自定义消费平台数量（旧档案没有这一节时为 0）。 */
        val userPlatformsUpserted: Int = 0,
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
        // 自定义消费平台。**含归档条目**：归档只是「不再参与识别候选」，
        // 引用它的历史流水仍需在换机后显示原名，否则会塌成「未知平台」。
        val platforms = JSONArray().apply {
            repo.listUserPlatforms(includeArchived = true).forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("displayName", p.displayName)
                    put("kind", p.kind.name)
                    put("strongKeywords", JSONArray(p.strongKeywords))
                    put("mediumKeywords", JSONArray(p.mediumKeywords))
                    put("weakKeywords", JSONArray(p.weakKeywords))
                    put("packageNames", JSONArray(p.packageNames.toList()))
                    put("sortOrder", p.sortOrder); put("archived", p.archived)
                    put("createdAtMillis", p.createdAtMillis)
                    put("schemaVersion", p.schemaVersion)
                })
            }
        }
        return JSONObject().apply {
            put("transactions", transactions)
            put("categories", categories)
            put("accounts", accounts)
            put("rules", rules)
            put("platforms", platforms)
            db.settingsDao().global()?.let { put("settings", it.toAppSettings().toSettingsJson()) }
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

        payload.optJSONObject("settings")?.let { db.settingsDao().upsert(it.parseAppSettings().toEntity()) }

        // 自定义消费平台。旧档案没有 `platforms` 一节 ⇒ optJSONArray 返回 null ⇒ 跳过，导入不报错。
        val platforms = payload.optJSONArray("platforms")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserPlatform(
                    id = o.getString("id"),
                    displayName = o.optString("displayName", ""),
                    kind = runCatching { PlatformKind.valueOf(o.optString("kind", "ORDER")) }
                        .getOrDefault(PlatformKind.ORDER),
                    strongKeywords = o.optJSONArray("strongKeywords").stringList(),
                    mediumKeywords = o.optJSONArray("mediumKeywords").stringList(),
                    weakKeywords = o.optJSONArray("weakKeywords").stringList(),
                    packageNames = o.optJSONArray("packageNames").stringList().toSet(),
                    sortOrder = o.optInt("sortOrder", UserPlatform.DEFAULT_SORT_ORDER),
                    archived = o.optBoolean("archived", false),
                    createdAtMillis = o.optLong("createdAtMillis", 0L),
                    schemaVersion = o.optInt("schemaVersion", LedgerSchema.CURRENT),
                )
            }
        }.orEmpty()
        platforms.forEach { repo.upsertUserPlatform(it) }

        // 落库后**重建进程内目录**：否则导入的自定义平台要等下次冷启动才生效，
        // 本次会话里编辑流水的平台选择器还是旧的。
        // 用 replaceExtras 整体替换（原子），而不是「先清空再逐个注册」——
        // 后者存在一个「自定义平台全部消失」的窗口，采集循环可能正在并发识别。
        PlatformCatalog.replaceExtras(
            repo.listUserPlatforms(includeArchived = true).map { it.toPlatformEntry() },
        )

        return ImportOutcome(
            fileVersion = fileVersion,
            migratedToVersion = finalVersion,
            transactionsUpserted = txns.size,
            categoriesUpserted = cats.size,
            rulesUpserted = rules.size,
            userPlatformsUpserted = platforms.size,
        )
    }

    /** 解密导入时用的口令，UI 通过赋值给它来注入（用完即弃，不落盘） */
    @Volatile var currentPassphrase: CharArray? = null

    // 流水的备份 JSON 编解码已抽到顶层纯函数（见 TxnJson.kt）：便于不经 Room 直接单测。
    // 设置的编解码同理抽到 SettingsJson.kt（toSettingsJson / parseAppSettings），
    // 这样「旧档案里多出来的字段（如已下线的 cushionMinor）能不能导进来」可以直接单测，
    // 不必为了两行 JSON 先造一个 LedgerDatabase。
}

/**
 * `JSONArray` → `List<String>`，**字段缺失时返回空列表**。
 *
 * 冗余存一份「平台列表」而不是复用别处的解析：它与 [UserPlatform] 的字段一一对应，
 * 单测要能直接喂老档案（缺 `platforms` 一节）验证不抛异常。
 */
private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }
}
