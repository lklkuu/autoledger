package com.autoledger.core.backup

import android.util.Base64

import com.autoledger.core.database.ClassifierRuleDao
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.database.toAppSettings
import com.autoledger.core.database.toEntity
import com.autoledger.core.database.toWageVersion
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
import com.autoledger.core.model.WageLedger
import com.autoledger.core.model.WageProfile
import com.autoledger.core.model.WageVersion
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.toPlatformEntry
import java.time.ZoneId
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
        /** v8：导入的时薪版本行数（旧档案播种单版本时记 1，无相关数据时 0）。 */
        val wageVersionsUpserted: Int = 0,
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
            // ⚠️ 用 listAllForBackup() 而**不是** repo.listAll(true)：后者会排除 MERGED / IGNORED，
            // 导致被吸收的行不导出 ⇒ 导出 JSON 里 mergedIntoId 恒为 null ⇒ 换机后合并链丢失。
            // 备份要"把整库搬走"，隐藏状态（被合并 / 被忽略）必须原样带上。
            repo.listAllForBackup().forEach { t -> put(transform(t).toJson()) }
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
            // 时薪按月版本（v8）。**必须放在根级**（与 platforms 同级）而不是 settings 段：
            // 下一行的 `settings` 是 `?.let`，设置行不存在时整段不写，
            // 版本数组会被一起丢掉；而版本表完全可能非空（导入过新档案 / 迁移播种过）。
            // 旧版本 App 用 optJSONArray 取不到就当没有 ⇒ 双向兼容，故 BACKUP_VERSION 不必 +1。
            put("wageVersions", db.wageVersionDao().listAll().map { it.toWageVersion() }.toWageVersionsJson())
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

        // ---------------------------------------------------------- 时薪按月版本（v8）
        // 三种情形必须区分（设计文档 D6）：
        //  ① 档案里有 `wageVersions`（新档案） ⇒ **以档案为准**：REPLACE_ALL 先清表再逐条 upsert。
        //     用户导入新档案的意图就是"恢复这份快照"，同月行必须被覆盖。
        //  ② 档案里没有 `wageVersions`（v7 及更早导出的旧档案）但 `settings.wage` 存在
        //     ⇒ 播种**单版本**，月份 = 本档案最早流水所在月（无流水 ⇒ 当月）。用 insertIfAbsent：
        //     本地已有的版本更权威，绝不能被一个"最近一次编辑的快照"覆盖。
        //  ③ 两者都没有 ⇒ 不动本地版本表（没有信息可用于推断）。
        val wageVersionDao = db.wageVersionDao()
        val wageVersionArray = payload.optJSONArray("wageVersions")
        val wageVersionsUpserted: Int = if (wageVersionArray != null) {
            val restored = wageVersionArray.parseWageVersions()
            if (strategy == MergeStrategy.REPLACE_ALL) wageVersionDao.clear()
            wageVersionDao.upsertAll(restored.map { it.toEntity() })
            restored.size
        } else {
            val seedProfile = payload.optJSONObject("settings")?.parseAppSettings()?.wage
            // ⚠️ 与 `MIGRATION_7_8` **同一判据**（那道门关了，这道门也必须关）：
            // `app_settings` 是整行 upsert —— 用户只改过「自动合并」或存过自由基金目标，
            // 档案里的 `settings.wage` 就是出厂默认 `WageProfile()`。那不是"用户填过的时薪"，
            // 播种它会让收入变回 ¥4333.33/月，而且档案播种用的是真实时间戳（旧实现），
            // 连「请确认」三态提示都不会出现 —— 比迁移那条路更隐蔽。
            // `WageProfile` 是 data class，`==` 逐字段比较 7 个值，等价于迁移 SQL 的 7 个 `<>`。
            if (seedProfile == null || seedProfile == WageProfile()) {
                0
            } else {
                val seedMonth = earliestMonthKeyOf(txns)
                // `nowMillis = 0` ⇒ `createdAtMillis == 0`：旧档案播种也是**预填、未经用户确认**，
                // 与迁移播种共用 `WageLedger.isMigrationSeeded` 判据，UI 才会提示「请确认」。
                val seed = WageVersion.of(
                    month = seedMonth,
                    profile = seedProfile,
                    nowMillis = 0L,
                )
                // 先查再插：`insertIfAbsent` 在"本地已有同月"时是空操作，
                // 但 ImportOutcome 里的计数必须是**真实落库行数**（UI 的导入提示依赖它）。
                val willInsert = wageVersionDao.find(seedMonth) == null
                wageVersionDao.insertIfAbsent(seed.toEntity())
                if (willInsert) 1 else 0
            }
        }

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
        //
        // 「upsertUserPlatform 循环 → listUserPlatforms → replaceExtras」是一个三步序列，
        // 必须整体套 PlatformCatalog.withRebuildGate：否则与启动注入/用户编辑平台的重建入口
        // 并发交错时，先读库者的旧快照会覆盖后落库者（丢失更新）。
        // （锁顺序 ingestGate → rebuildGate：此处持门期间不再回调采集总线。）
        PlatformCatalog.withRebuildGate {
            platforms.forEach { repo.upsertUserPlatform(it) }
            PlatformCatalog.replaceExtras(
                repo.listUserPlatforms(includeArchived = true).map { it.toPlatformEntry() },
            )
        }

        // 导入是一次性批量写入（最多 upsert 数千条流水）⇒ outbox 会被一口气塞满；
        // 导入完成后淘汰一次，封住 outbox 无限膨胀。淘汰是运维动作，**绝不**放进单笔写事务。
        repo.trimOutbox()

        return ImportOutcome(
            fileVersion = fileVersion,
            migratedToVersion = finalVersion,
            transactionsUpserted = txns.size,
            categoriesUpserted = cats.size,
            rulesUpserted = rules.size,
            userPlatformsUpserted = platforms.size,
            wageVersionsUpserted = wageVersionsUpserted,
        )
    }

    /** 解密导入时用的口令，UI 通过赋值给它来注入（用完即弃，不落盘） */
    @Volatile var currentPassphrase: CharArray? = null

    /**
     * 旧档案播种用的月份：**本档案里最早流水所在月**；没有流水 ⇒ 当月。
     *
     * 与迁移（`MIGRATION_7_8`）选同一个月份口径，理由也相同：
     * 功能上 `1970-01` 与"最早流水月"等价（更早的月份会回落到最早版本），
     * 但后者在 UI 的版本列表里语义自洽，不会让用户以为"数据坏了"。
     *
     * 直接算内存里的流水（`txns` 已解析好）而不查库：导入是"把档案搬进来"，
     * 插进库的流水与内存里的是同一批，没必要多一次全表扫描。
     */
    private fun earliestMonthKeyOf(txns: List<LedgerTransaction>): String {
        val zone = ZoneId.systemDefault()
        val earliest = txns.minByOrNull { it.occurredAtMillis }?.occurredAtMillis
        return if (earliest == null) {
            WageLedger.monthKeyOf(System.currentTimeMillis(), zone)
        } else {
            WageLedger.monthKeyOf(earliest, zone)
        }
    }

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
