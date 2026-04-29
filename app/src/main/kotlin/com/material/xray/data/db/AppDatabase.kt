package com.material.xray.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class],
    version = 5,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN contentDisposition TEXT")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN contentType TEXT")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN profileTitle TEXT")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN profileUpdateIntervalHours INTEGER")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionUploadBytes INTEGER")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionDownloadBytes INTEGER")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionTotalBytes INTEGER")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionExpireAt INTEGER")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN profileWebPageUrl TEXT")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN announce TEXT")
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN supportUrl TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE app_bypass ADD COLUMN serverId INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE app_bypass ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE app_bypass SET manual = 1 WHERE excluded = 0 AND serverId IS NOT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE subscriptions ADD COLUMN autoUpdateIntervalHours INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
