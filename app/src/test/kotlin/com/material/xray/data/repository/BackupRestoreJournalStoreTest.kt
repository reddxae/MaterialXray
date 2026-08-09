package com.material.xray.data.repository

import com.material.xray.model.BackupData
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestoreJournalStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `read returns null when no journal exists`() {
        assertNull(store().read())
    }

    @Test
    fun `write then read round-trips the journal`() {
        val store = store()
        val journal = BackupRestoreJournal(previous = backupData())

        store.write(journal)

        assertTrue(store.exists())
        assertEquals(journal, store.read())
    }

    @Test
    fun `delete removes the journal`() {
        val store = store()
        store.write(BackupRestoreJournal(previous = backupData()))

        store.delete()

        assertFalse(store.exists())
        assertNull(store.read())
    }

    @Test
    fun `a corrupt journal is quarantined instead of blocking every operation`() {
        val store = store()
        journalFile().writeText("{ not json")

        val error = assertThrows(IOException::class.java) { store.read() }

        assertTrue(error.message.orEmpty().contains(BackupRestoreJournalStore.QUARANTINE_FILE_NAME))
        assertFalse(journalFile().exists())
        assertEquals("{ not json", quarantineFile().readText())
        // The blocking copy was moved aside, so the next operation proceeds.
        assertNull(store.read())
    }

    @Test
    fun `quarantining preserves the corrupt payload for manual salvage`() {
        val store = store()
        journalFile().writeText("""{"unexpected":"shape"}""")

        assertThrows(IOException::class.java) { store.read() }

        assertEquals("""{"unexpected":"shape"}""", quarantineFile().readText())
    }

    private fun store() = BackupRestoreJournalStore(temporaryFolder.root, json)

    private fun journalFile() = File(temporaryFolder.root, BackupRestoreJournalStore.FILE_NAME)

    private fun quarantineFile() = File(temporaryFolder.root, BackupRestoreJournalStore.QUARANTINE_FILE_NAME)

    private fun backupData() = BackupData(
        version = BackupData.CURRENT_VERSION,
        subscriptions = listOf(
            BackupData.BackupSubscription(
                key = "subscription-1",
                name = "My Subscription",
                url = "https://example.com/sub",
            ),
        ),
        bypassedApps = listOf("0:com.example.app"),
        settings = mapOf("last_server_id" to "1"),
    )
}
