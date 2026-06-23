package com.material.xray.core.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.material.xray.model.LauncherIcon
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LauncherIconManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun apply(icon: LauncherIcon) {
        val packageManager = context.packageManager
        packageManager.setComponentEnabledSetting(
            icon.componentName(),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        LauncherIcon.entries
            .filterNot { it == icon }
            .forEach { disabledIcon ->
                packageManager.setComponentEnabledSetting(
                    disabledIcon.componentName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private fun LauncherIcon.componentName(): ComponentName = ComponentName(context, "${context.packageName}.$aliasClassName")
}
