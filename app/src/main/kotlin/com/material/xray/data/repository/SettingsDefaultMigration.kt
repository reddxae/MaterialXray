package com.material.xray.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.material.xray.model.BackupData
import com.material.xray.model.canonicalDnsServers

internal val SETTINGS_DEFAULTS_REVISION = intPreferencesKey("__settings_defaults_revision")

internal interface SettingDefaultChange {
    val revision: Int
    fun apply(preferences: MutablePreferences)
}

internal fun <T> settingDefaultChange(
    revision: Int,
    key: Preferences.Key<T>,
    previousDefault: T,
): SettingDefaultChange = object : SettingDefaultChange {
    override val revision = revision

    override fun apply(preferences: MutablePreferences) {
        if (preferences[key] == previousDefault) preferences.remove(key)
    }
}

internal fun <T> removedSettingChange(
    revision: Int,
    key: Preferences.Key<T>,
): SettingDefaultChange = object : SettingDefaultChange {
    override val revision = revision

    override fun apply(preferences: MutablePreferences) {
        preferences.remove(key)
    }
}

internal class SettingsDefaultMigration(
    private val currentRevision: Int = CURRENT_SETTINGS_DEFAULTS_REVISION,
    private val changes: List<SettingDefaultChange> = SETTINGS_DEFAULT_CHANGES,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val sourceRevision = currentData[SETTINGS_DEFAULTS_REVISION] ?: 0
        validateSettingsDefaultsRevision(sourceRevision, currentRevision)
        return sourceRevision < currentRevision
    }

    override suspend fun migrate(currentData: Preferences): Preferences = mutablePreferencesOf()
        .apply {
            this += currentData
            applySettingsDefaultChanges(
                preferences = this,
                sourceRevision = currentData[SETTINGS_DEFAULTS_REVISION] ?: 0,
                currentRevision = currentRevision,
                changes = changes,
            )
        }

    override suspend fun cleanUp() = Unit
}

internal fun applySettingsDefaultChanges(
    preferences: MutablePreferences,
    sourceRevision: Int,
    currentRevision: Int = CURRENT_SETTINGS_DEFAULTS_REVISION,
    changes: List<SettingDefaultChange> = SETTINGS_DEFAULT_CHANGES,
) {
    validateSettingsDefaultsRevision(sourceRevision, currentRevision)
    changes
        .asSequence()
        .filter { it.revision > sourceRevision && it.revision <= currentRevision }
        .sortedBy { it.revision }
        .forEach { it.apply(preferences) }
    preferences[SETTINGS_DEFAULTS_REVISION] = currentRevision
}

internal fun settingsDefaultsRevisionFromBackup(
    settings: Map<String, String>,
    backupVersion: Int?,
): Int = if (backupVersion != null && backupVersion >= BackupData.SPARSE_SETTINGS_VERSION) {
    val encodedRevision = settings[SETTINGS_DEFAULTS_REVISION.name]
    val revision = encodedRevision?.toIntOrNull()
    require(encodedRevision == null || revision != null) { "Invalid settings defaults revision" }
    (revision ?: 0).also { restoredRevision ->
        validateSettingsDefaultsRevision(restoredRevision)
    }
} else {
    0
}

private fun validateSettingsDefaultsRevision(
    sourceRevision: Int,
    currentRevision: Int = CURRENT_SETTINGS_DEFAULTS_REVISION,
) {
    require(sourceRevision in 0..currentRevision) {
        "Unsupported settings defaults revision $sourceRevision"
    }
}

private const val CURRENT_SETTINGS_DEFAULTS_REVISION = 6
private const val PREVIOUS_XRAY_BUFFER_SIZE_KIB = 512
private const val PREVIOUS_TUN_NAME = "xray0"
private const val PREVIOUS_DNS_SERVERS = "1.1.1.1,1.0.0.1"
private const val PREVIOUS_IPV6_DNS_SERVERS =
    "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001"

// Increment the revision and append a change whenever a compiled default changes or a setting is retired.
private val SETTINGS_DEFAULT_CHANGES = listOf(
    settingDefaultChange(
        revision = 1,
        key = SettingsRepository.XRAY_BUFFER_SIZE_KIB,
        previousDefault = PREVIOUS_XRAY_BUFFER_SIZE_KIB,
    ),
    settingDefaultChange(
        revision = 2,
        key = SettingsRepository.TUN_NAME,
        previousDefault = PREVIOUS_TUN_NAME,
    ),
    removedSettingChange(
        revision = 3,
        key = stringPreferencesKey("latency_dns_servers"),
    ),
    dnsPresetCanonicalisationChange(revision = 4),
    object : SettingDefaultChange {
        override val revision = 5

        override fun apply(preferences: MutablePreferences) {
            val storedServers = preferences[SettingsRepository.DNS_SERVERS]
            if (storedServers == PREVIOUS_DNS_SERVERS || storedServers == PREVIOUS_IPV6_DNS_SERVERS) {
                preferences.remove(SettingsRepository.DNS_SERVERS)
            }
        }
    },
    dnsPresetCanonicalisationChange(revision = 6),
)

/**
 * Rewrites a stored DNS setting to the current form of the preset it came from.
 *
 * Earlier builds kept IPv6 resolvers out of these settings whenever IPv6 connections were off, and
 * appended guessed ones when they were on. Both addresses of a provider now live in its preset, so
 * a value that names a provider is replaced with that provider's full list. A list that matches no
 * provider is the user's own and is left alone.
 *
 * It runs at two revisions because ordering against revision 5 matters: canonicalising at 4 turns a
 * half-populated Cloudflare list back into the retired default, which 5 then clears so the shipped
 * default applies. Revision 6 is what reaches installs that already passed 5.
 */
private fun dnsPresetCanonicalisationChange(revision: Int): SettingDefaultChange = object : SettingDefaultChange {
    override val revision = revision

    override fun apply(preferences: MutablePreferences) {
        preferences.canonicaliseDnsSetting(SettingsRepository.DNS_SERVERS)
        preferences.canonicaliseDnsSetting(SettingsRepository.DOMESTIC_DNS_SERVERS)
    }
}

private fun MutablePreferences.canonicaliseDnsSetting(key: Preferences.Key<String>) {
    val storedValue = this[key] ?: return
    canonicalDnsServers(storedValue)?.let { this[key] = it }
}
