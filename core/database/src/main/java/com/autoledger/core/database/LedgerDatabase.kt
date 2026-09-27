package com.autoledger.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autoledger.core.model.LedgerSchema

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        ClassifierRuleEntity::class,
        SyncOutboxEntity::class,
        SettingsEntity::class,
        OrderEntity::class,
        OrderDeductionEntity::class,
        RefundEntity::class,
        RefundAllocationEntity::class,
        ResourceBalanceEntity::class,
    ],
    version = LedgerSchema.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun classifierRuleDao(): ClassifierRuleDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun settingsDao(): SettingsDao
    abstract fun orderDao(): OrderDao
    abstract fun refundDao(): RefundDao
    abstract fun resourceBalanceDao(): ResourceBalanceDao

    companion object {
        const val DATABASE_NAME = "autoledger.db"
    }
}
