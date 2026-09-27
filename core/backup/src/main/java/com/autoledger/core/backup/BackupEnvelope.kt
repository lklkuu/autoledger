package com.autoledger.core.backup

import com.autoledger.core.model.LedgerSchema
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/**
 * 备份档案的信封（工程要求 2：数据结构带版本号）。
 *
 * 三层保险：
 * - `version`：档案本身的格式版本，导入时决定走哪条迁移链；
 * - 每条交易自己的 `schemaVersion`：即便档案版本丢失也能按行自救；
 * - Room 的 DATABASE_VERSION：库结构升级由 Migration 负责。
 *
 * 只有三者都带上，才能做到「一年前的备份今天还能导入」。
 */
object BackupEnvelope {

    const val KIND = "autoledger-backup"
    const val KEY_KIND = "kind"
    const val KEY_VERSION = "version"
    const val KEY_CREATED_AT = "createdAtMillis"
    const val KEY_APP = "app"
    const val KEY_DEVICE = "device"
    const val KEY_PAYLOAD = "payload"

    /** 导出档案时调用 */
    fun wrap(payload: JSONObject, device: String, appVersion: String): JSONObject = JSONObject().apply {
        put(KEY_KIND, KIND)
        put(KEY_VERSION, LedgerSchema.BACKUP_VERSION)
        put(KEY_CREATED_AT, System.currentTimeMillis())
        put(KEY_APP, appVersion)
        put(KEY_DEVICE, device)
        put(KEY_PAYLOAD, payload)
    }

    @Throws(IllegalArgumentException::class)
    fun readVersion(root: JSONObject): Int {
        require(root.optString(KEY_KIND) == KIND) { "不是 AutoLedger 备份文件" }
        return root.optInt(KEY_VERSION, 1)
    }

    fun payloadOf(root: JSONObject): JSONObject = root.getJSONObject(KEY_PAYLOAD)

    fun createdAtOf(root: JSONObject): Long = root.optLong(KEY_CREATED_AT, 0L)
}

/** 一次迁移：从小到大逐级执行，直到追上当前版本。 */
interface BackupMigration {
    val from: Int
    val to: Int
    fun migrate(payload: JSONObject): JSONObject
}

/** 出厂默认不加密时的导出体积：约每条流水 220 字节 */
internal object BackupMigrations {

    /**
     * v1 -> v2：
     * 1) 金额表示从「元的 Double」改为「分的 Long」（修掉浮点误差导致的对账错位）；
     * 2) 新增 confidence / rawTextSealed 字段，老记录补默认值；
     * 3) 分类规则表新增 learned 标记。
     */
    val V1_TO_V2 = object : BackupMigration {
        override val from = 1
        override val to = 2

        override fun migrate(payload: JSONObject): JSONObject {
            val txns = payload.optJSONArray("transactions") ?: return payload
            for (i in 0 until txns.length()) {
                val t = txns.getJSONObject(i)
                if (t.has("amount")) {
                    val yuan = t.getDouble("amount")
                    // Double 必须先按十进制定标，否则 0.29 等金额会系统性少一分。
                    val amountMinor = BigDecimal.valueOf(yuan)
                        .setScale(2, RoundingMode.HALF_UP)
                        .movePointRight(2)
                        .longValueExact()
                    t.put("amountMinor", amountMinor)
                    t.remove("amount")
                }
                if (!t.has("confidence")) t.put("confidence", 1.0)
                if (!t.has("currency")) t.put("currency", "CNY")
                if (!t.has("schemaVersion")) t.put("schemaVersion", 2)
                if (!t.has("fingerprint")) t.put("fingerprint", "")
            }
            val rules = payload.optJSONArray("rules")
            if (rules != null) {
                for (i in 0 until rules.length()) {
                    val r = rules.getJSONObject(i)
                    if (!r.has("learned")) r.put("learned", false)
                }
            }
            return payload
        }
    }

    val ALL: List<BackupMigration> = listOf(V1_TO_V2)
}

/**
 * 版本迁移执行器。
 *
 * 未发布期：不维护历史迁移链，任何版本都按当前结构直接处理。
 * 正式发布后需恢复「逐版本迁移」，否则老备份升级会丢字段。
 */
object BackupMigrator {

    fun migrateToCurrent(payload: JSONObject, fromVersion: Int): Pair<JSONObject, Int> =
        payload to LedgerSchema.BACKUP_VERSION
}
