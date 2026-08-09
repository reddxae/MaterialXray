package com.material.xray.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A value read from outside the app, which the user can change while the app is in the background.
 *
 * @property value the value as of the last read.
 * @property refresh re-reads the value, for a change that happens without the activity resuming,
 * such as the result of a permission request made from within the app.
 */
data class SystemState<T>(
    val value: T,
    val refresh: () -> Unit,
)

/**
 * Reads [read] and reads it again on every resume, so returning from system settings never leaves
 * the screen acting on a stale answer.
 *
 * [read] is re-invoked whenever the value is refreshed, so it must not close over changing state:
 * which instance of it runs depends on when the refresh happens, not on where it was written.
 */
@Composable
fun <T> rememberSystemState(read: (Context) -> T): SystemState<T> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember(context) { mutableIntStateOf(0) }
    val value = remember(context, revision) { read(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return SystemState(value) { revision++ }
}
