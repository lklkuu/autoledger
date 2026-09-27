package com.autoledger.core.model

/**
 * **结构版本号契约**
 *
 * - [DATABASE_VERSION]：Room/SQLite schema 版本。**未发布期**改结构只需 +1 并用
 *   destructive fallback 重建（见 core:database 的 LedgerDatabaseFactory），暂不维护迁移链。
 * - [BACKUP_VERSION]：导出档案信封版本。未发布期改结构直接 +1，暂不维护历史迁移。
 * - [LedgerTransaction.schemaVersion]：行级结构标记。
 *
 * 三者独立演进；正式发布后需恢复「逐版本 Migration」约定，避免升级丢数据。
 */
object LedgerSchema {
    /** Room database version */
    const val DATABASE_VERSION = 4

    /** 导出／备份信封版本 */
    const val BACKUP_VERSION = 4

    /** 行级结构版本 */
    const val CURRENT = BACKUP_VERSION

    /** 导出文件后缀 */
    const val BACKUP_EXTENSION = "ledgerbak"
}
