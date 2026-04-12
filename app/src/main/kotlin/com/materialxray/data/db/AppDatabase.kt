package com.materialxray.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class],
    version = 2,
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
    }
}
