package com.autoledger.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Room 构建工厂。
 *
 * 未发布期策略：**结构变更即重建**（`fallbackToDestructiveMigration`）——
 * schema 版本不匹配时直接删表重建，省去逐版本手写 Migration。
 * 正式发布后必须改回「逐版本 Migration」，否则升级会丢用户数据。
 */
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
        .fallbackToDestructiveMigration()
        .build()
}
