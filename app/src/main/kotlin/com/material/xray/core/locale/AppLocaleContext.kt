package com.material.xray.core.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val localeChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val appLocaleChanges = localeChangeEvents.asSharedFlow()

fun initializeAppLocales(context: Context) {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return
    val storedLocales = LocaleManagerCompat.getApplicationLocales(context)
    if (!storedLocales.isEmpty) {
        AppCompatDelegate.setApplicationLocales(storedLocales)
    }
}

fun Context.forAppLanguage(): Context {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) {
        AppLocaleContextCache.clear()
        return this
    }
    return AppLocaleContextCache.get(locales.toLanguageTags()) {
        val configuration = Configuration(resources.configuration)
        ConfigurationCompat.setLocales(configuration, locales)
        applicationContext.createConfigurationContext(configuration)
    }
}

fun Context.localizedString(@StringRes resourceId: Int, vararg arguments: Any): String = forAppLanguage().getString(resourceId, *arguments)

fun setAppLocales(locales: LocaleListCompat) {
    AppCompatDelegate.setApplicationLocales(locales)
    notifyAppLocaleChanged()
}

fun notifyAppLocaleChanged() {
    AppLocaleContextCache.clear()
    localeChangeEvents.tryEmit(Unit)
}

private object AppLocaleContextCache {
    @Volatile private var cached: CachedContext? = null

    fun get(currentLanguageTags: String, create: () -> Context): Context {
        cached?.takeIf { it.languageTags == currentLanguageTags }?.let { return it.context }
        return synchronized(this) {
            cached?.takeIf { it.languageTags == currentLanguageTags }?.context
                ?: create().also { cached = CachedContext(currentLanguageTags, it) }
        }
    }

    fun clear() {
        if (cached == null) return
        synchronized(this) {
            cached = null
        }
    }

    private data class CachedContext(
        val languageTags: String,
        val context: Context,
    )
}
