package com.material.xray.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultMigrationTest {
    @Test
    fun `settings migration preserves both profile DNS choices`() = runTest {
        assertEquals("prefer_profile_dns", SettingsRepository.PREFER_PROFILE_DNS.name)

        for (enabled in listOf(false, true)) {
            val preferences = mutablePreferencesOf(SettingsRepository.PREFER_PROFILE_DNS to enabled)

            val migrated = SettingsDefaultMigration().migrate(preferences)

            assertEquals(enabled, migrated[SettingsRepository.PREFER_PROFILE_DNS])
        }
    }

    @Test
    fun `known previous default is removed so current default can apply`() = runTest {
        val preferences = mutablePreferencesOf(SettingsRepository.XRAY_BUFFER_SIZE_KIB to 512)
        val migration = SettingsDefaultMigration()

        assertTrue(migration.shouldMigrate(preferences))
        val migrated = migration.migrate(preferences)

        assertNull(migrated[SettingsRepository.XRAY_BUFFER_SIZE_KIB])
        assertEquals(6, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `non-default value survives migration`() = runTest {
        val preferences = mutablePreferencesOf(SettingsRepository.XRAY_BUFFER_SIZE_KIB to 1024)

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals(1024, migrated[SettingsRepository.XRAY_BUFFER_SIZE_KIB])
    }

    @Test
    fun `previous TUN name default is removed for automatic selection`() = runTest {
        val preferences = mutablePreferencesOf(SettingsRepository.TUN_NAME to "xray0")

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertNull(migrated[SettingsRepository.TUN_NAME])
        assertEquals(6, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `removed latency DNS setting is deleted`() = runTest {
        val latencyDnsServers = stringPreferencesKey("latency_dns_servers")
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 2,
            latencyDnsServers to "9.9.9.9",
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertNull(migrated[latencyDnsServers])
        assertEquals(6, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `future boolean default change migrates matching stored value`() = runTest {
        val change = settingDefaultChange(
            revision = 4,
            key = SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED,
            previousDefault = true,
        )
        val migration = SettingsDefaultMigration(currentRevision = 4, changes = listOf(change))
        val matching = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 3,
            SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED to true,
        )
        val different = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 3,
            SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED to false,
        )

        val migratedMatching = migration.migrate(matching)
        val migratedDifferent = migration.migrate(different)

        assertNull(migratedMatching[SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED])
        assertFalse(requireNotNull(migratedDifferent[SettingsRepository.PASSIVE_HEALTH_MONITORING_ENABLED]))
        assertEquals(4, migratedMatching[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `migration leaves a resolver list that matches no provider alone`() = runTest {
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 3,
            SettingsRepository.ALLOW_IPV6 to true,
            SettingsRepository.DNS_SERVERS to "8.8.8.8,9.9.9.9",
            SettingsRepository.DOMESTIC_DNS_SERVERS to "192.0.2.53",
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals("8.8.8.8,9.9.9.9", migrated[SettingsRepository.DNS_SERVERS])
        assertEquals("192.0.2.53", migrated[SettingsRepository.DOMESTIC_DNS_SERVERS])
    }

    @Test
    fun `migration gives a provider list the IPv6 addresses an earlier build left out`() = runTest {
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 5,
            SettingsRepository.DNS_SERVERS to "https://8.8.8.8/dns-query,https://8.8.4.4/dns-query",
            SettingsRepository.DOMESTIC_DNS_SERVERS to "77.88.8.8,77.88.8.1",
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals(
            "https://8.8.8.8/dns-query,https://8.8.4.4/dns-query," +
                "https://[2001:4860:4860::8888]/dns-query,https://[2001:4860:4860::8844]/dns-query",
            migrated[SettingsRepository.DNS_SERVERS],
        )
        assertEquals(
            "77.88.8.8,77.88.8.1,2a02:6b8::feed:0ff,2a02:6b8:0:1::feed:0ff",
            migrated[SettingsRepository.DOMESTIC_DNS_SERVERS],
        )
        assertEquals(6, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `pre-IPv6 revision Cloudflare defaults fall back to the current default`() = runTest {
        val ipv4Preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 3,
            SettingsRepository.DNS_SERVERS to "1.1.1.1,1.0.0.1",
        )
        val dualStackPreferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 3,
            SettingsRepository.ALLOW_IPV6 to true,
            SettingsRepository.DNS_SERVERS to "1.1.1.1,1.0.0.1",
        )

        val migratedIpv4 = SettingsDefaultMigration().migrate(ipv4Preferences)
        val migratedDualStack = SettingsDefaultMigration().migrate(dualStackPreferences)

        assertNull(migratedIpv4[SettingsRepository.DNS_SERVERS])
        assertNull(migratedDualStack[SettingsRepository.DNS_SERVERS])
    }

    @Test
    fun `migration leaves a provider list that is already current`() = runTest {
        val current = "8.8.8.8,8.8.4.4,2001:4860:4860::8888,2001:4860:4860::8844"
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 5,
            SettingsRepository.DNS_SERVERS to current,
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals(current, migrated[SettingsRepository.DNS_SERVERS])
        assertEquals(6, migrated[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `migration keeps a hand-mixed list that borrows one address from a provider`() = runTest {
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 5,
            SettingsRepository.DNS_SERVERS to "8.8.8.8,2606:4700:4700::1111",
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertEquals("8.8.8.8,2606:4700:4700::1111", migrated[SettingsRepository.DNS_SERVERS])
    }

    @Test
    fun `the retired Cloudflare default is cleared from the revision it was stored at`() = runTest {
        val preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 4,
            SettingsRepository.ALLOW_IPV6 to true,
            SettingsRepository.DNS_SERVERS to "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001",
        )

        val migrated = SettingsDefaultMigration().migrate(preferences)

        assertNull(migrated[SettingsRepository.DNS_SERVERS])
    }

    @Test
    fun `previous DNS defaults are removed so explicit endpoint defaults can apply`() = runTest {
        val ipv4Preferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 4,
            SettingsRepository.DNS_SERVERS to "1.1.1.1,1.0.0.1",
        )
        val dualStackPreferences = mutablePreferencesOf(
            SETTINGS_DEFAULTS_REVISION to 4,
            SettingsRepository.ALLOW_IPV6 to true,
            SettingsRepository.DNS_SERVERS to
                "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001",
        )

        val migratedIpv4 = SettingsDefaultMigration().migrate(ipv4Preferences)
        val migratedDualStack = SettingsDefaultMigration().migrate(dualStackPreferences)

        assertNull(migratedIpv4[SettingsRepository.DNS_SERVERS])
        assertNull(migratedDualStack[SettingsRepository.DNS_SERVERS])
        assertEquals(6, migratedIpv4[SETTINGS_DEFAULTS_REVISION])
    }

    @Test
    fun `current revision does not rerun migrations`() = runTest {
        val preferences = mutablePreferencesOf(SETTINGS_DEFAULTS_REVISION to 6)

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
            mutablePreferencesOf(SETTINGS_DEFAULTS_REVISION to 7),
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
