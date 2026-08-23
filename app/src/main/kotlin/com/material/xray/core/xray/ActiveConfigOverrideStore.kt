package com.material.xray.core.xray

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores a hand-edited runtime config that replaces config generation on every connect.
 *
 * Only the keys this connect owns are patched back in before the core sees it, so settings changed
 * after the edit was saved are not reflected in it. The override belongs to the server that was
 * selected when it was written, which is why every path that changes the selected server clears it.
 */
@Singleton
class ActiveConfigOverrideStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val file = context.filesDir.resolve(ACTIVE_CONFIG_OVERRIDE_FILE)

    /** False when the override could not be written, so the caller does not report a phantom save. */
    suspend fun save(configJson: String): Boolean = withContext(Dispatchers.IO) {
        // Written via a temporary file so a connect reading it concurrently sees either the old
        // document or the new one, never a half-written one.
        val temp = File(file.parentFile, "$ACTIVE_CONFIG_OVERRIDE_FILE.tmp")
        runCatching {
            temp.writeText(configJson)
            check(temp.renameTo(file)) { "Could not replace $ACTIVE_CONFIG_OVERRIDE_FILE" }
        }.onFailure { temp.delete() }.isSuccess
    }

    /** Matches what the connect path treats as an override, so the UI never disagrees with it. */
    suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.isNotBlank() == true
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }
}
