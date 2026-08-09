package com.material.xray.data.repository

import com.material.xray.model.BackupData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class BackupRestoreJournal(val previous: BackupData)

/**
 * Persists the pre-restore snapshot that lets an interrupted backup restore be rolled back.
 *
 * The journal is the only copy of the user's pre-restore data, so it is written atomically and a
 * copy that can no longer be decoded is quarantined instead of deleted.
 */
internal class BackupRestoreJournalStore(
    directory: File,
    private val json: Json,
) {
    private val journalFile = File(directory, FILE_NAME)

    fun exists(): Boolean = journalFile.isFile

    /**
     * Reads the persisted journal, or returns null when none exists.
     *
     * A journal that exists but cannot be decoded is moved aside to [QUARANTINE_FILE_NAME] so it
     * stops blocking every future backup operation while remaining available for manual salvage,
     * and an [IOException] describing the failed recovery is thrown so the interruption is not
     * silently absorbed.
     */
    fun read(): BackupRestoreJournal? {
        if (!journalFile.isFile) return null
        // A transient read failure must not discard the journal, so it propagates unchanged.
        val text = journalFile.readText()
        return try {
            json.decodeFromString<BackupRestoreJournal>(text)
        } catch (error: SerializationException) {
            throw quarantineCorruptJournal(error)
        } catch (error: IllegalArgumentException) {
            throw quarantineCorruptJournal(error)
        }
    }

    fun write(journal: BackupRestoreJournal) {
        val temporary = File(journalFile.parentFile, "$FILE_NAME.tmp")
        val bytes = json.encodeToString(journal).toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            journalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun delete() {
        check(!journalFile.exists() || journalFile.delete()) { "Unable to remove the completed restore journal" }
    }

    private fun quarantineCorruptJournal(cause: Exception): IOException {
        val quarantined = File(journalFile.parentFile, QUARANTINE_FILE_NAME)
        val moved = runCatching {
            Files.move(journalFile.toPath(), quarantined.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        val disposition = if (moved) {
            "it was moved to $QUARANTINE_FILE_NAME for manual inspection"
        } else {
            "it also could not be moved aside"
        }
        return IOException(
            "The journal of an interrupted backup restore could not be replayed; $disposition. " +
                "The current data may still reflect the interrupted restore.",
            cause,
        )
    }

    companion object {
        const val FILE_NAME = "backup-restore-journal.json"
        const val QUARANTINE_FILE_NAME = "$FILE_NAME.corrupt"
    }
}
