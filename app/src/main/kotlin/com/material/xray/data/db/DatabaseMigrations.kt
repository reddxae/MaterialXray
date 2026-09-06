package com.material.xray.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema step of [AppDatabase], expressed as plain SQL.
 *
 * Keeping the statements as data rather than as behaviour lets `DatabaseMigrationChainTest` replay
 * the whole chain on a JVM SQLite database and compare the result against the schema Room exports
 * for the current version, so a migration that drifts from the entities fails the build instead of
 * failing on a user's device.
 *
 * Each entry migrates from its key to the following version. The keys must stay contiguous and end
 * at the version before the one declared on [AppDatabase]; the test enforces both.
 */
internal object DatabaseMigrations {
    val sqlByStartVersion: Map<Int, List<String>> = mapOf(
        1 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN contentDisposition TEXT",
            "ALTER TABLE subscriptions ADD COLUMN contentType TEXT",
            "ALTER TABLE subscriptions ADD COLUMN profileTitle TEXT",
            "ALTER TABLE subscriptions ADD COLUMN profileUpdateIntervalHours INTEGER",
            "ALTER TABLE subscriptions ADD COLUMN subscriptionUploadBytes INTEGER",
            "ALTER TABLE subscriptions ADD COLUMN subscriptionDownloadBytes INTEGER",
            "ALTER TABLE subscriptions ADD COLUMN subscriptionTotalBytes INTEGER",
            "ALTER TABLE subscriptions ADD COLUMN subscriptionExpireAt INTEGER",
            "ALTER TABLE subscriptions ADD COLUMN profileWebPageUrl TEXT",
            "ALTER TABLE subscriptions ADD COLUMN announce TEXT",
            "ALTER TABLE subscriptions ADD COLUMN supportUrl TEXT",
        ),
        2 to listOf(
            "ALTER TABLE app_bypass ADD COLUMN serverId INTEGER",
        ),
        3 to listOf(
            "ALTER TABLE app_bypass ADD COLUMN manual INTEGER NOT NULL DEFAULT 0",
            "UPDATE app_bypass SET manual = 1 WHERE excluded = 0 AND serverId IS NOT NULL",
        ),
        4 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN autoUpdateIntervalHours INTEGER NOT NULL DEFAULT 1",
        ),
        5 to listOf(
            "ALTER TABLE app_bypass ADD COLUMN routeMode TEXT",
        ),
        6 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN descriptionHidden INTEGER NOT NULL DEFAULT 0",
        ),
        7 to listOf(
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
            "DROP TABLE app_bypass",
            "ALTER TABLE app_bypass_new RENAME TO app_bypass",
        ),
        8 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN userAgentMode TEXT",
            "ALTER TABLE subscriptions ADD COLUMN customUserAgent TEXT",
            "ALTER TABLE subscriptions ADD COLUMN customHeaders TEXT",
        ),
        9 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN appRoutingPackages TEXT",
            "ALTER TABLE subscriptions ADD COLUMN appRoutingMode TEXT",
        ),
        10 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN preferJson INTEGER",
        ),
        11 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN providerRouting TEXT",
        ),
        12 to listOf(
            """
            UPDATE subscriptions
            SET subscriptionTotalBytes = NULL, lastUpdated = 0
            WHERE subscriptionTotalBytes > 0
            """.trimIndent(),
        ),
        13 to listOf(
            """
            CREATE TABLE IF NOT EXISTS database_metadata (
                id INTEGER NOT NULL,
                valueValidationRevision INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        ),
        14 to listOf(
            "ALTER TABLE servers ADD COLUMN edited INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE servers ADD COLUMN guarded INTEGER NOT NULL DEFAULT 0",
        ),
        15 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0",
            "UPDATE subscriptions SET sortOrder = id",
        ),
        16 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN fallbackUrl TEXT",
            "ALTER TABLE subscriptions ADD COLUMN useFallbackUrl INTEGER NOT NULL DEFAULT 0",
        ),
        17 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN appRoutingInverted INTEGER NOT NULL DEFAULT 0",
        ),
        18 to listOf(
            "ALTER TABLE subscriptions ADD COLUMN requiresHardwareId INTEGER NOT NULL DEFAULT 0",
        ),
        19 to listOf(
            """
            CREATE TABLE subscriptions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                preferJson INTEGER,
                lastUpdated INTEGER NOT NULL,
                contentDisposition TEXT,
                contentType TEXT,
                profileTitle TEXT,
                profileUpdateIntervalHours INTEGER,
                autoUpdateIntervalHours INTEGER NOT NULL,
                subscriptionUploadBytes INTEGER,
                subscriptionDownloadBytes INTEGER,
                subscriptionTotalBytes INTEGER,
                subscriptionExpireAt INTEGER,
                profileWebPageUrl TEXT,
                announce TEXT,
                supportUrl TEXT,
                fallbackUrl TEXT,
                requiresHardwareId INTEGER NOT NULL,
                descriptionHidden INTEGER NOT NULL,
                userAgentMode TEXT,
                customUserAgent TEXT,
                customHeaders TEXT,
                appRoutingPackages TEXT,
                appRoutingMode TEXT,
                appRoutingInverted INTEGER NOT NULL,
                providerRouting TEXT,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO subscriptions_new (
                id, name, url, preferJson, lastUpdated, contentDisposition, contentType,
                profileTitle, profileUpdateIntervalHours, autoUpdateIntervalHours,
                subscriptionUploadBytes, subscriptionDownloadBytes, subscriptionTotalBytes,
                subscriptionExpireAt, profileWebPageUrl, announce, supportUrl, fallbackUrl,
                requiresHardwareId, descriptionHidden, userAgentMode, customUserAgent,
                customHeaders, appRoutingPackages, appRoutingMode, appRoutingInverted,
                providerRouting, sortOrder
            )
            SELECT id, name, url, preferJson, lastUpdated, contentDisposition, contentType,
                profileTitle, profileUpdateIntervalHours, autoUpdateIntervalHours,
                subscriptionUploadBytes, subscriptionDownloadBytes, subscriptionTotalBytes,
                subscriptionExpireAt, profileWebPageUrl, announce, supportUrl, fallbackUrl,
                requiresHardwareId, descriptionHidden, userAgentMode, customUserAgent,
                customHeaders, appRoutingPackages, appRoutingMode, appRoutingInverted,
                providerRouting, sortOrder
            FROM subscriptions
            """.trimIndent(),
            "DROP TABLE subscriptions",
            "ALTER TABLE subscriptions_new RENAME TO subscriptions",
        ),
    )

    val all: Array<Migration> = sqlByStartVersion.entries
        .sortedBy { it.key }
        .map { (startVersion, statements) -> SqlMigration(startVersion, startVersion + 1, statements) }
        .toTypedArray()

    private class SqlMigration(
        startVersion: Int,
        endVersion: Int,
        private val statements: List<String>,
    ) : Migration(startVersion, endVersion) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach(db::execSQL)
        }
    }
}
