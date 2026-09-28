package com.autoledger.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.platform.PlatformCatalog

/**
 * Room 构建工厂。
 *
 * 历史策略：**结构变更即重建**（`fallbackToDestructiveMigration`）——
 * schema 版本不匹配时直接删表重建，省去逐版本手写 Migration。
 *
 * ⚠️ 该策略在 v5 已**终止**：用户在待确认队列里点「忽略这笔」的流水、以及所有历史账目，
 * 都不该因为一次字段新增而消失。从 v5 起每个结构变更都必须提供显式 Migration，
 * `fallbackToDestructiveMigration()` 仅作为**最后的兜底**保留（正常路径不会再走到）。
 */

/**
 * 消费平台字段落地。
 *
 * 三列均为 NOT NULL + 非空默认值 ⇒ **旧行原样保留，平台列落 `unknown`**
 * （用户口径：「历史数据留未知即可」= 旧行保留，不是把旧数据删光）。
 *
 * 索引名 `index_transactions_platform_id` 必须与 Room 对
 * `Index(value = ["platform_id"])` 的生成规则**完全一致**，
 * 否则 `exportSchema` 校验会报 "migration didn't properly handle"，
 * 进而退回到 destructive fallback ⇒ **全库流水被清空**。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_id TEXT NOT NULL DEFAULT '${PlatformCatalog.UNKNOWN_ID}'",
        )
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_confidence REAL NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN platform_source TEXT NOT NULL DEFAULT 'AUTO'",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_transactions_platform_id ON transactions (platform_id)",
        )
    }
}

object LedgerDatabaseFactory {

    /**
     * @param openHelperFactory SQLCipher 工厂；传 null 表示降级为明文库（仅当用户在设置里明确允许）
     */
    fun create(
        context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory?,
        databaseName: String = LedgerDatabase.DATABASE_NAME,
    ): LedgerDatabase = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName)
        .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
        // 显式迁移优先于破坏性回退；版本号与 [LedgerSchema.DATABASE_VERSION] 必须同步推进。
        .addMigrations(MIGRATION_4_5)
        // 兜底保留，但正常路径不会再走到（任何版本跃迁都必须先补 Migration）
        .fallbackToDestructiveMigration()
        .build()
}
