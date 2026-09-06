package com.material.xray.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseValueValidatorTest {
    @Test
    fun validationRunsOnlyForMissingOrOlderRevisions() {
        assertTrue(DatabaseValueValidator.shouldValidate(null))
        assertTrue(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION - 1))
        assertFalse(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION))
        assertFalse(DatabaseValueValidator.shouldValidate(DatabaseValueValidator.CURRENT_REVISION + 1))
    }

    @Test
    fun validationSanitizesRowsWithoutDeletingThemAndIsIdempotent() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createSchema()
            connection.seedInvalidRows()

            assertTrue(connection.executeValidation() > 0)
            connection.assertSanitizedRows()
            assertEquals(0, connection.executeValidation())
        }
    }

    private fun Connection.createSchema() {
        createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE subscriptions (
                    id INTEGER PRIMARY KEY,
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
                    useFallbackUrl INTEGER NOT NULL DEFAULT 0,
                    descriptionHidden INTEGER NOT NULL,
                    userAgentMode TEXT,
                    customUserAgent TEXT,
                    customHeaders TEXT,
                    appRoutingPackages TEXT,
                    appRoutingMode TEXT,
                    appRoutingInverted INTEGER NOT NULL DEFAULT 0,
                    providerRouting TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE servers (
                    id INTEGER PRIMARY KEY,
                    subscriptionId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    address TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    configJson TEXT NOT NULL,
                    latencyMs INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE app_bypass (
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
        }
    }

    private fun Connection.seedInvalidRows() {
        createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO subscriptions (
                    id, name, url, preferJson, lastUpdated, autoUpdateIntervalHours,
                    subscriptionTotalBytes, subscriptionExpireAt, descriptionHidden,
                    userAgentMode, appRoutingPackages, appRoutingMode, appRoutingInverted,
                    fallbackUrl, useFallbackUrl
                ) VALUES (
                    1, '  ', ' https://example.com ', 2, -1, -6,
                    -1, 0, 4, ' CUSTOM ', ' ["com.example"] ', ' bypass ', 7,
                    '  https://backup.example.com  ', 5
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO servers (
                    id, subscriptionId, name, protocol, address, port,
                    configJson, latencyMs, sortOrder
                ) VALUES (
                    10, 1, ' ', 'vless', ' example.com ', 70000,
                    '{}', -5, -1
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO app_bypass (
                    packageName, profileId, uid, excluded, serverId, manual, routeMode
                ) VALUES (' ', -1, 1234, 1, 10, 1, ' SERVER ')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO app_bypass (
                    packageName, profileId, uid, excluded, serverId, manual, routeMode
                ) VALUES ('com.example.app', 0, 1234, 1, NULL, 1, 'bypass')
                """.trimIndent(),
            )
        }
    }

    private fun Connection.executeValidation(): Int = DatabaseValueValidator.statements.sumOf { sql ->
        createStatement().use { statement -> statement.executeUpdate(sql) }
    }

    private fun Connection.assertSanitizedRows() {
        createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM subscriptions").use { row ->
                assertTrue(row.next())
                assertEquals("Subscription 1", row.getString("name"))
                assertEquals("https://example.com", row.getString("url"))
                assertNull(row.getObject("preferJson"))
                assertEquals(0, row.getLong("lastUpdated"))
                assertEquals(0, row.getInt("autoUpdateIntervalHours"))
                assertNull(row.getObject("subscriptionTotalBytes"))
                assertNull(row.getObject("subscriptionExpireAt"))
                assertEquals(1, row.getInt("descriptionHidden"))
                assertEquals("custom", row.getString("userAgentMode"))
                assertEquals("[\"com.example\"]", row.getString("appRoutingPackages"))
                assertEquals("direct", row.getString("appRoutingMode"))
                assertEquals(1, row.getInt("appRoutingInverted"))
                assertEquals("https://backup.example.com", row.getString("fallbackUrl"))
                assertEquals(1, row.getInt("useFallbackUrl"))
            }
            statement.executeQuery("SELECT * FROM servers").use { row ->
                assertTrue(row.next())
                assertEquals("Server 10", row.getString("name"))
                assertEquals("VLESS", row.getString("protocol"))
                assertEquals("example.com", row.getString("address"))
                assertEquals(0, row.getInt("port"))
                assertEquals(-1, row.getInt("latencyMs"))
                assertEquals(0, row.getInt("sortOrder"))
            }
            statement.executeQuery("SELECT * FROM app_bypass ORDER BY profileId").use { row ->
                assertTrue(row.next())
                assertEquals(0, row.getInt("uid"))
                assertEquals(0, row.getInt("excluded"))
                assertNull(row.getObject("serverId"))
                assertEquals(0, row.getInt("manual"))
                assertNull(row.getObject("routeMode"))
                assertTrue(row.next())
                assertEquals("bypass", row.getString("routeMode"))
            }
        }
    }
}
