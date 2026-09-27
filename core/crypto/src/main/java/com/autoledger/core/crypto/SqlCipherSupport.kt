package com.autoledger.core.crypto

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher 工厂（工程要求 3：默认本地加密存储）。
 *
 * **必须显式加载 native 库**：核实过 sqlcipher-android 4.5.5 的 API jar ——
 * 里面**没有任何一处 `System.loadLibrary` 调用**（旧版的 `SQLiteDatabase.loadLibs()` 已被移除），
 * 也就是说这个版本**不会自动加载**。若不先 `System.loadLibrary("sqlcipher")`，
 * 首次打开数据库就会抛
 * `UnsatisfiedLinkError: No implementation found for ...SQLiteConnection.nativeOpen`。
 * 编译期与纯 JVM 测试都发现不了，**只能真机暴露**（Android 14 已复现）。
 * `System.loadLibrary` 对已加载的库是幂等的，重复调用无副作用。
 *
 * 把加密细节收敛在 core-crypto：换 SQLCipher 版本/类名时只改本文件。
 */
object SqlCipherSupport {

    /** 降级开关：加密不可用时是否退化为明文库（仍需调用方显式决策并提示用户）。 */
    data class Config(val encrypted: Boolean = true)

    /**
     * 创建加密库的 OpenHelper 工厂。**内部会先加载 native 库**。
     *
     * @param context 仅用于表达"该调用需要运行在 Android 环境"；native 库由系统按 APK 中的
     *                `lib/<abi>/libsqlcipher.so` 加载（需保持 AGP 默认的未压缩 + 页对齐打包）
     * @return 加密工厂；[Config.encrypted] 为 false 时返回 null（表示用明文库）
     */
    fun openHelperFactory(
        context: Context,
        passphrase: ByteArray,
        config: Config = Config(),
    ): SupportSQLiteOpenHelper.Factory? {
        if (!config.encrypted) return null
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(passphrase)
    }

    /** 便捷重载：默认加密，返回非空工厂。 */
    fun openHelperFactory(context: Context, passphrase: ByteArray): SupportSQLiteOpenHelper.Factory =
        openHelperFactory(context, passphrase, Config())!!

    /**
     * **防回归护栏**：判断数据库文件是否**实际为明文**。
     *
     * 只靠"调用 SQLCipher 没抛异常"是不够的 —— 曾出现过"看似走了加密、文件其实是明文"的静默降级。
     * 这里直接读文件头判定（见 [SqliteHeader]）。
     *
     * @return true = 该文件**没有加密**；文件不存在时返回 false（交给调用方按"尚未建库"处理）
     */
    fun isPlaintextDatabase(context: Context, databaseName: String = "autoledger.db"): Boolean {
        val file = context.getDatabasePath(databaseName) ?: return false
        if (!file.exists()) return false
        return try {
            file.inputStream().use { input ->
                val prefix = ByteArray(SqliteHeader.PLAINTEXT_SIGNATURE.size)
                val read = input.read(prefix)
                read == prefix.size && SqliteHeader.isPlaintext(prefix)
            }
        } catch (_: Exception) {
            false
        }
    }
}
