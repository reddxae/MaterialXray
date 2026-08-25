package com.material.xray.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.DatabaseMetadataEntity
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class, DatabaseMetadataEntity::class],
    version = 16,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao

    companion object {
        const val DATABASE_NAME = "material-xray.db"

        val VALUE_VALIDATION_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                DatabaseValueValidator.validateIfNeeded(db)
            }
        }
    }
}
