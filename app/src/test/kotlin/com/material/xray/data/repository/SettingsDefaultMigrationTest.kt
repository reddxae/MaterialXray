package com.material.xray.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultMigrationTest {
    @Test
    fun `known previous default is removed so current default can apply`() = runTest {
        val preferences = mutablePreferencesOf(SettingsRepository.XRAY_BUFFER_SIZE_KIB to 512)
        val migration = SettingsDefaultMigration()

        assertTrue(migration.shouldMigrate(preferences))
        val migrated = migration.migrate(preferences)

        assertNull(migrated[SettingsRepository.XRAY_BUFFER_SIZE_KIB])
        assertEquals(1, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `non-default value survives migration`() = runTest {
        val preferences = mutablePreferencesOf(SettingsRepository.XRAY_BUFFER_SIZE_KIB to 1024)

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals(1024, migrated[SettingsRepository.XRAY_BUFFER_SIZE_KIB])
    }

    @Test
    fun `future boolean default change migrates matching stored value`() = runTest {
        val change = settingDefaultChange(
            revision = 2,
            key = SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED,
            previousDefault = true,
        )
        val migration = SettingsDefaultMigration(currentRevision = 2, changes = listOf(change))
        val matching = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 1,
            SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED to true,
        )
        val different = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 1,
            SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED to false,
        )

        val migratedMatching = migration.migrate(matching)
        val migratedDifferent = migration.migrate(different)

        assertNull(migratedMatching[SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED])
        assertFalse(requireNotNull(migratedDifferent[SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED]))
        assertEquals(2, migratedMatching[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `current revision does not rerun migrations`() = runTest {
        val preferences = mutablePreferencesOf(SETTINGS_DEFAULTS_REVISION to 1)

        assertFalse(SettingsDefaultMigration().shouldMigrate(preferences))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `newer settings revision is rejected instead of downgraded`() {
        applySettingsDefaultChanges(
            preferences = mutablePreferencesOf(),
            sourceRevision = 2,
            currentRevision = 1,
            changes = emptyList(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `DataStore lifecycle rejects newer settings revision`() = runTest {
        SettingsDefaultMigration().shouldMigrate(
            mutablePreferencesOf(SETTINGS_DEFAULTS_REVISION to 2),
        )
    }

    @Test
    fun `legacy backup cannot inject internal defaults revision`() {
        val settings = mapOf(SETTINGS_DEFAULTS_REVISION.name to "99")

        assertEquals(0, settingsDefaultsRevisionFromBackup(settings, backupVersion = 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sparse backup rejects newer settings revision`() {
        settingsDefaultsRevisionFromBackup(
            settings = mapOf(SETTINGS_DEFAULTS_REVISION.name to "99"),
            backupVersion = 4,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative settings revision is rejected`() {
        applySettingsDefaultChanges(
            preferences = mutablePreferencesOf(),
            sourceRevision = -1,
            currentRevision = 1,
            changes = emptyList(),
        )
    }
}
