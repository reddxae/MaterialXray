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
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN contentDisposition TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN contentType TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN profileTitle TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN profileUpdateIntervalHours INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionUploadBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionDownloadBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionTotalBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionExpireAt INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN profileWebPageUrl TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN announce TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN supportUrl TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_bypass ADD COLUMN serverId INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_bypass ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE app_bypass SET manual = 1 WHERE excluded = 0 AND serverId IS NOT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN autoUpdateIntervalHours INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_bypass ADD COLUMN routeMode TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN descriptionHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE app_bypass_new (
                        packageName TEXT NOT NULL,
                        profileId INTEGER NOT NULL,
                        uid INTEGER NOT NULL,
                        excluded INTEGER NOT NULL,
                        serverId INTEGER,
                        manual INTEGER NOT NULL,
                        routeMode TEXT,
                        PRIMARY KEY(profileId, packageName)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO app_bypass_new (packageName, profileId, uid, excluded, serverId, manual, routeMode)
                    SELECT packageName,
                        CASE WHEN uid >= 100000 THEN uid / 100000 ELSE 0 END,
                        uid,
                        excluded,
                        serverId,
                        manual,
                        routeMode
                    FROM app_bypass
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE app_bypass")
                db.execSQL("ALTER TABLE app_bypass_new RENAME TO app_bypass")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN userAgentMode TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN customUserAgent TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN customHeaders TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN appRoutingPackages TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN appRoutingMode TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN preferJson INTEGER")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN providerRouting TEXT")
            }
        }
    }
}
