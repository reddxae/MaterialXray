package com.material.xray.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.material.xray.model.BackupData

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

private const val CURRENT_SETTINGS_DEFAULTS_REVISION = 2
private const val PREVIOUS_XRAY_BUFFER_SIZE_KIB = 512
private const val PREVIOUS_TUN_NAME = "xray0"

// Increment the revision and record the previous value whenever a compiled default changes.
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
)
