package com.material.xray.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the whole migration chain on a JVM SQLite database and compares the result against the
 * schema Room exports for the current version.
 *
 * Room only detects a broken migration at runtime, on a user's device, by comparing identity
 * hashes. This test moves that detection into the build.
 */
class DatabaseMigrationChainTest {

    @Test
    fun migrationsCoverEveryVersionUpToTheCurrentOne() {
        val schema = readCurrentSchema()
        val startVersions = DatabaseMigrations.sqlByStartVersion.keys.sorted()

        assertEquals(
            "Migrations must form a contiguous chain from version 1 to ${schema.database.version}",
            (1 until schema.database.version).toList(),
            startVersions,
        )
    }

    @Test
    fun migratedSchemaMatchesFreshlyCreatedSchema() {
        val schema = readCurrentSchema()

        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                migrated.applyVersionOneSchema()
                DatabaseMigrations.sqlByStartVersion.entries
                    .sortedBy { it.key }
                    .forEach { (_, statements) -> statements.forEach { migrated.runSql(it) } }

                schema.database.entities.forEach { entity ->
                    fresh.runSql(entity.createSql.forTable(entity.tableName))
                    entity.indices.forEach { index -> fresh.runSql(index.createSql.forTable(entity.tableName)) }
                }

                assertEquals(fresh.tableNames(), migrated.tableNames())
                fresh.tableNames().forEach { table ->
                    assertColumnsMatch(table, expected = fresh.columns(table), actual = migrated.columns(table))
                    assertEquals("Indices of $table", fresh.indices(table), migrated.indices(table))
                    assertEquals("Foreign keys of $table", fresh.foreignKeys(table), migrated.foreignKeys(table))
                }
            }
        }
    }

    @Test
    fun subscriptionOrderMigrationKeepsExistingRowsAheadOfNewRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.runSql("CREATE TABLE subscriptions (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            connection.runSql("INSERT INTO subscriptions VALUES (4, 'First'), (9, 'Second')")
            DatabaseMigrations.sqlByStartVersion.getValue(15).forEach { connection.runSql(it) }
            connection.runSql(
                "INSERT INTO subscriptions VALUES (12, 'Third', " +
                    "(SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM subscriptions))",
            )

            val names = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM subscriptions ORDER BY sortOrder, id").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
            }
            assertEquals(listOf("First", "Second", "Third"), names)
        }
    }

    /**
     * Room accepts any database default when the entity declares none, so the chain is only
     * required to match a default that the exported schema actually specifies.
     */
    private fun assertColumnsMatch(table: String, expected: Map<String, Column>, actual: Map<String, Column>) {
        assertEquals("Columns of $table", expected.keys, actual.keys)
        expected.forEach { (name, expectedColumn) ->
            val actualColumn = actual.getValue(name)
            assertEquals("$table.$name type", expectedColumn.type, actualColumn.type)
            assertEquals("$table.$name nullability", expectedColumn.notNull, actualColumn.notNull)
            assertEquals("$table.$name primary key position", expectedColumn.primaryKeyPosition, actualColumn.primaryKeyPosition)
            if (expectedColumn.defaultValue != null) {
                assertEquals("$table.$name default", expectedColumn.defaultValue, actualColumn.defaultValue)
            }
        }
    }

    /**
     * The schema as it existed at version 1.
     *
     * Versions 1 to 3 predate this repository, so `subscriptions` and `app_bypass` are derived by
     * removing from the oldest committed entity definitions exactly the columns that
     * [DatabaseMigrations] adds in steps 1 to 3. No migration has ever touched `servers`, so its
     * baseline is the current definition; this test consequently cannot detect a `servers`
     * migration that was needed but never written.
     */
    private fun Connection.applyVersionOneSchema() {
        runSql(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`lastUpdated` INTEGER NOT NULL)",
        )
        runSql(
            "CREATE TABLE IF NOT EXISTS `servers` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`subscriptionId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`protocol` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, " +
                "`port` INTEGER NOT NULL, " +
                "`configJson` TEXT NOT NULL, " +
                "`latencyMs` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        runSql("CREATE INDEX IF NOT EXISTS `index_servers_subscriptionId` ON `servers` (`subscriptionId`)")
        runSql(
            "CREATE TABLE IF NOT EXISTS `app_bypass` (" +
                "`packageName` TEXT NOT NULL, " +
                "`uid` INTEGER NOT NULL, " +
                "`excluded` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`))",
        )
    }

    private fun readCurrentSchema(): SchemaFile {
        val directory = File(
            requireNotNull(System.getProperty("room.schemaLocation")) {
                "room.schemaLocation is not set; the Gradle test task must provide it"
            },
        )
        val exported = directory.resolve(SCHEMA_SUBDIRECTORY).listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }
        assertTrue(
            "No exported Room schema found in $directory. Run a build so KSP regenerates it.",
            exported.isNotEmpty(),
        )
        val latest = exported.maxBy { it.nameWithoutExtension.toInt() }
        return json.decodeFromString<SchemaFile>(latest.readText())
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.tableNames(): Set<String> = createStatement().use { statement ->
        statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        ).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("name")) }
        }
    }

    private fun Connection.columns(table: String): Map<String, Column> = createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
            buildMap {
                while (rows.next()) {
                    put(
                        rows.getString("name"),
                        Column(
                            type = rows.getString("type").uppercase(),
                            notNull = rows.getInt("notnull") != 0,
                            defaultValue = rows.getString("dflt_value"),
                            primaryKeyPosition = rows.getInt("pk"),
                        ),
                    )
                }
            }
        }
    }

    // Implicit indices are excluded because SQLite names them after the table they were created
    // on; primary keys are compared through table_info instead, which is also what Room does.
    private fun Connection.indices(table: String): Map<String, Index> = createStatement().use { statement ->
        val names = statement.executeQuery("PRAGMA index_list(`$table`)").use { rows ->
            buildList {
                while (rows.next()) {
                    val name = rows.getString("name")
                    if (!name.startsWith("sqlite_autoindex_")) add(name to (rows.getInt("unique") != 0))
                }
            }
        }
        names.associate { (name, unique) ->
            val columns = createStatement().use { indexStatement ->
                indexStatement.executeQuery("PRAGMA index_info(`$name`)").use { rows ->
                    buildList { while (rows.next()) add(rows.getInt("seqno") to rows.getString("name")) }
                }
            }.sortedBy { it.first }.map { it.second }
            name to Index(unique = unique, columns = columns)
        }
    }

    private fun Connection.foreignKeys(table: String): Set<ForeignKey> = createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_list(`$table`)").use { rows ->
            buildSet {
                while (rows.next()) {
                    add(
                        ForeignKey(
                            referencedTable = rows.getString("table"),
                            column = rows.getString("from"),
                            referencedColumn = rows.getString("to"),
                            onUpdate = rows.getString("on_update"),
                            onDelete = rows.getString("on_delete"),
                        ),
                    )
                }
            }
        }
    }

    private fun String.forTable(tableName: String) = replace("\${TABLE_NAME}", tableName)

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private data class Index(val unique: Boolean, val columns: List<String>)

    private data class ForeignKey(
        val referencedTable: String,
        val column: String,
        val referencedColumn: String,
        val onUpdate: String,
        val onDelete: String,
    )

    @Serializable
    private data class SchemaFile(val database: SchemaDatabase)

    @Serializable
    private data class SchemaDatabase(val version: Int, val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaEntity(
        val tableName: String,
        val createSql: String,
        val indices: List<SchemaIndex> = emptyList(),
    )

    @Serializable
    private data class SchemaIndex(val createSql: String)

    private companion object {
        const val SCHEMA_SUBDIRECTORY = "com.material.xray.data.db.AppDatabase"
        val json = Json { ignoreUnknownKeys = true }
    }
}
