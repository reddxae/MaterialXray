package com.material.xray.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.material.xray.data.db.entity.DatabaseMetadataEntity

internal object DatabaseValueValidator {
    internal const val CURRENT_REVISION = 1

    fun validateIfNeeded(db: SupportSQLiteDatabase): Boolean {
        if (!shouldValidate(readRevision(db))) return false
        repair(db)
        return true
    }

    fun repair(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            statements.forEach(db::execSQL)
            db.execSQL(
                """
                INSERT OR REPLACE INTO database_metadata (id, valueValidationRevision)
                VALUES (${DatabaseMetadataEntity.SINGLETON_ID}, $CURRENT_REVISION)
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun shouldValidate(revision: Int?): Boolean = revision == null || revision < CURRENT_REVISION

    private fun readRevision(db: SupportSQLiteDatabase): Int? = db.query(
        "SELECT valueValidationRevision FROM database_metadata WHERE id = ${DatabaseMetadataEntity.SINGLETON_ID}",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else null
    }

    internal val statements = listOf(
        """
        UPDATE subscriptions
        SET name = CASE
                WHEN TRIM(name) = '' THEN 'Subscription ' || id
                ELSE TRIM(name)
            END,
            url = TRIM(url),
            preferJson = CASE
                WHEN preferJson IS NULL OR preferJson IN (0, 1) THEN preferJson
                ELSE NULL
            END,
            lastUpdated = MAX(lastUpdated, 0),
            contentDisposition = NULLIF(TRIM(contentDisposition), ''),
            contentType = NULLIF(TRIM(contentType), ''),
            profileTitle = NULLIF(TRIM(profileTitle), ''),
            profileUpdateIntervalHours = CASE
                WHEN profileUpdateIntervalHours > 0 THEN profileUpdateIntervalHours
                ELSE NULL
            END,
            autoUpdateIntervalHours = MAX(autoUpdateIntervalHours, 0),
            subscriptionUploadBytes = CASE
                WHEN subscriptionUploadBytes >= 0 THEN subscriptionUploadBytes
                ELSE NULL
            END,
            subscriptionDownloadBytes = CASE
                WHEN subscriptionDownloadBytes >= 0 THEN subscriptionDownloadBytes
                ELSE NULL
            END,
            subscriptionTotalBytes = CASE
                WHEN subscriptionTotalBytes >= 0 THEN subscriptionTotalBytes
                ELSE NULL
            END,
            subscriptionExpireAt = CASE
                WHEN subscriptionExpireAt > 0 THEN subscriptionExpireAt
                ELSE NULL
            END,
            profileWebPageUrl = NULLIF(TRIM(profileWebPageUrl), ''),
            announce = NULLIF(TRIM(announce), ''),
            supportUrl = NULLIF(TRIM(supportUrl), ''),
            fallbackUrl = NULLIF(TRIM(fallbackUrl), ''),
            useFallbackUrl = CASE WHEN useFallbackUrl = 0 THEN 0 ELSE 1 END,
            requiresHardwareId = CASE WHEN requiresHardwareId = 0 THEN 0 ELSE 1 END,
            descriptionHidden = CASE WHEN descriptionHidden = 0 THEN 0 ELSE 1 END,
            userAgentMode = CASE LOWER(TRIM(userAgentMode))
                WHEN 'auto' THEN 'auto'
                WHEN 'happ' THEN 'happ'
                WHEN 'custom' THEN 'custom'
                ELSE NULL
            END,
            customUserAgent = NULLIF(TRIM(customUserAgent), ''),
            customHeaders = NULLIF(TRIM(customHeaders), ''),
            appRoutingPackages = CASE
                WHEN LOWER(TRIM(appRoutingMode)) IN ('direct', 'default_selected', 'default_outbound', 'bypass')
                    THEN NULLIF(TRIM(appRoutingPackages), '')
                ELSE NULL
            END,
            appRoutingMode = CASE LOWER(TRIM(appRoutingMode))
                WHEN 'direct' THEN 'direct'
                WHEN 'bypass' THEN 'direct'
                WHEN 'default_selected' THEN 'default_selected'
                WHEN 'default_outbound' THEN 'default_outbound'
                ELSE NULL
            END,
            appRoutingInverted = CASE WHEN appRoutingInverted = 0 THEN 0 ELSE 1 END,
            providerRouting = NULLIF(TRIM(providerRouting), '')
        WHERE TRIM(name) = ''
            OR name != TRIM(name)
            OR url != TRIM(url)
            OR (preferJson IS NOT NULL AND preferJson NOT IN (0, 1))
            OR lastUpdated < 0
            OR (contentDisposition IS NOT NULL AND (TRIM(contentDisposition) = '' OR contentDisposition != TRIM(contentDisposition)))
            OR (contentType IS NOT NULL AND (TRIM(contentType) = '' OR contentType != TRIM(contentType)))
            OR (profileTitle IS NOT NULL AND (TRIM(profileTitle) = '' OR profileTitle != TRIM(profileTitle)))
            OR (profileUpdateIntervalHours IS NOT NULL AND profileUpdateIntervalHours <= 0)
            OR autoUpdateIntervalHours < 0
            OR (subscriptionUploadBytes IS NOT NULL AND subscriptionUploadBytes < 0)
            OR (subscriptionDownloadBytes IS NOT NULL AND subscriptionDownloadBytes < 0)
            OR (subscriptionTotalBytes IS NOT NULL AND subscriptionTotalBytes < 0)
            OR (subscriptionExpireAt IS NOT NULL AND subscriptionExpireAt <= 0)
            OR (profileWebPageUrl IS NOT NULL AND (TRIM(profileWebPageUrl) = '' OR profileWebPageUrl != TRIM(profileWebPageUrl)))
            OR (announce IS NOT NULL AND (TRIM(announce) = '' OR announce != TRIM(announce)))
            OR (supportUrl IS NOT NULL AND (TRIM(supportUrl) = '' OR supportUrl != TRIM(supportUrl)))
            OR (fallbackUrl IS NOT NULL AND (TRIM(fallbackUrl) = '' OR fallbackUrl != TRIM(fallbackUrl)))
            OR useFallbackUrl NOT IN (0, 1)
            OR requiresHardwareId NOT IN (0, 1)
            OR descriptionHidden NOT IN (0, 1)
            OR (userAgentMode IS NOT NULL AND userAgentMode != LOWER(TRIM(userAgentMode)))
            OR (userAgentMode IS NOT NULL AND LOWER(TRIM(userAgentMode)) NOT IN ('auto', 'happ', 'custom'))
            OR (customUserAgent IS NOT NULL AND (TRIM(customUserAgent) = '' OR customUserAgent != TRIM(customUserAgent)))
            OR (customHeaders IS NOT NULL AND (TRIM(customHeaders) = '' OR customHeaders != TRIM(customHeaders)))
            OR LOWER(TRIM(appRoutingMode)) = 'bypass'
            OR (appRoutingMode IS NOT NULL AND LOWER(TRIM(appRoutingMode)) NOT IN ('direct', 'default_selected', 'default_outbound', 'bypass'))
            OR appRoutingInverted NOT IN (0, 1)
            OR (appRoutingPackages IS NOT NULL AND (TRIM(appRoutingPackages) = '' OR appRoutingPackages != TRIM(appRoutingPackages)))
            OR (appRoutingPackages IS NOT NULL AND appRoutingMode IS NULL)
            OR (providerRouting IS NOT NULL AND (TRIM(providerRouting) = '' OR providerRouting != TRIM(providerRouting)))
        """.trimIndent(),
        """
        UPDATE servers
        SET name = CASE
                WHEN TRIM(name) = '' THEN 'Server ' || id
                ELSE TRIM(name)
            END,
            protocol = UPPER(TRIM(protocol)),
            address = TRIM(address),
            port = CASE WHEN port BETWEEN 0 AND 65535 THEN port ELSE 0 END,
            latencyMs = CASE WHEN latencyMs >= -1 THEN latencyMs ELSE -1 END,
            sortOrder = MAX(sortOrder, 0)
        WHERE TRIM(name) = ''
            OR name != TRIM(name)
            OR protocol != UPPER(TRIM(protocol))
            OR address != TRIM(address)
            OR port NOT BETWEEN 0 AND 65535
            OR latencyMs < -1
            OR sortOrder < 0
        """.trimIndent(),
        """
        UPDATE app_bypass
        SET uid = CASE
                WHEN TRIM(packageName) = '' OR profileId < 0 THEN 0
                ELSE MAX(uid, 0)
            END,
            excluded = CASE
                WHEN TRIM(packageName) = '' OR profileId < 0 THEN 0
                WHEN excluded != 0 OR LOWER(TRIM(routeMode)) = 'bypass' THEN 1
                ELSE 0
            END,
            serverId = CASE
                WHEN TRIM(packageName) != ''
                    AND profileId >= 0
                    AND excluded = 0
                    AND (routeMode IS NULL OR TRIM(routeMode) = '' OR LOWER(TRIM(routeMode)) = 'server')
                    AND serverId IN (SELECT id FROM servers)
                    THEN serverId
                ELSE NULL
            END,
            manual = CASE
                WHEN TRIM(packageName) = '' OR profileId < 0 THEN 0
                WHEN manual = 0 THEN 0
                ELSE 1
            END,
            routeMode = CASE
                WHEN TRIM(packageName) = '' OR profileId < 0 THEN NULL
                WHEN excluded != 0 OR LOWER(TRIM(routeMode)) = 'bypass' THEN 'bypass'
                WHEN LOWER(TRIM(routeMode)) = 'direct' THEN 'direct'
                WHEN LOWER(TRIM(routeMode)) = 'default_outbound' THEN 'default_outbound'
                WHEN LOWER(TRIM(routeMode)) = 'default_selected' THEN 'default_selected'
                WHEN serverId IN (SELECT id FROM servers) THEN 'server'
                ELSE NULL
            END
        WHERE ((TRIM(packageName) = '' OR profileId < 0)
                AND (uid != 0 OR excluded != 0 OR serverId IS NOT NULL OR manual != 0 OR routeMode IS NOT NULL))
            OR uid < 0
            OR excluded NOT IN (0, 1)
            OR manual NOT IN (0, 1)
            OR (LOWER(TRIM(routeMode)) = 'bypass' AND excluded = 0)
            OR (routeMode IS NOT NULL AND routeMode != LOWER(TRIM(routeMode)))
            OR (routeMode IS NOT NULL AND LOWER(TRIM(routeMode)) NOT IN ('bypass', 'direct', 'default_outbound', 'default_selected', 'server'))
            OR (serverId IS NOT NULL AND serverId NOT IN (SELECT id FROM servers))
            OR (serverId IS NOT NULL AND (excluded != 0 OR LOWER(TRIM(routeMode)) IN ('bypass', 'direct', 'default_outbound', 'default_selected')))
        """.trimIndent(),
    )
}
