package com.material.xray.service

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.material.xray.core.root.RootShell
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OemAutostartGuidance(
    val required: Boolean,
    val granted: Boolean,
    val directSettingsAvailable: Boolean,
)

@Singleton
class OemAutostartManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val rootShell: RootShell,
) {
    private val targets = oemAutostartTargets(Build.MANUFACTURER)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _guidance = MutableStateFlow(readGuidance())
    val guidance: StateFlow<OemAutostartGuidance> = _guidance.asStateFlow()

    fun refresh() {
        _guidance.value = readGuidance()
    }

    suspend fun grantWithRoot(force: Boolean = false): Boolean {
        if (!isXiaomiManufacturer(Build.MANUFACTURER)) return false
        if (!force &&
            preferences.getBoolean(KEY_ROOT_GRANT_ATTEMPTED, false) &&
            !preferences.getBoolean(KEY_ROOT_GRANT_SUCCEEDED, false)
        ) {
            return false
        }
        preferences.edit().putBoolean(KEY_ROOT_GRANT_ATTEMPTED, true).apply()
        if (!rootShell.open(RootShell.NetworkNamespace.CURRENT)) return false
        val result = rootShell.execute(
            command = xiaomiAutostartGrantCommand(context.packageName),
            namespace = RootShell.NetworkNamespace.CURRENT,
        )
        val granted = result.isSuccess && parseXiaomiAutostartMode(result.output) == AppOpsManager.MODE_ALLOWED
        preferences.edit().putBoolean(KEY_ROOT_GRANT_SUCCEEDED, granted).apply()
        _guidance.value = readGuidance(granted)
        return granted
    }

    suspend fun restoreRootGrant() {
        grantWithRoot()
    }

    suspend fun prepareForRootUpdate(): Boolean = !isXiaomiManufacturer(Build.MANUFACTURER) || grantWithRoot(force = true)

    fun clearRootGrantAttempt() {
        preferences.edit()
            .remove(KEY_ROOT_GRANT_ATTEMPTED)
            .remove(KEY_ROOT_GRANT_SUCCEEDED)
            .apply()
        refresh()
    }

    suspend fun refreshWithRoot() {
        if (!isXiaomiManufacturer(Build.MANUFACTURER)) {
            refresh()
            return
        }
        if (!rootShell.open(RootShell.NetworkNamespace.CURRENT)) {
            refresh()
            return
        }
        val result = rootShell.execute(
            command = xiaomiAutostartCheckCommand(context.packageName),
            namespace = RootShell.NetworkNamespace.CURRENT,
        )
        _guidance.value = readGuidance(
            result.isSuccess && parseXiaomiAutostartMode(result.output) == AppOpsManager.MODE_ALLOWED,
        )
    }

    fun openSettings() {
        for (target in targets) {
            val intent = target.toIntent()
            if (!canLaunch(intent)) continue
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun readGuidance(
        granted: Boolean = readGrantedState(),
    ): OemAutostartGuidance {
        if (targets.isEmpty()) return OemAutostartGuidance(false, true, false)
        return OemAutostartGuidance(
            required = true,
            granted = granted,
            directSettingsAvailable = targets.any { canLaunch(it.toIntent()) },
        )
    }

    private fun readGrantedState(): Boolean {
        if (!isXiaomiManufacturer(Build.MANUFACTURER)) return false
        return readXiaomiAutostartMode(context)?.let { it == AppOpsManager.MODE_ALLOWED }
            ?: preferences.getBoolean(KEY_ROOT_GRANT_SUCCEEDED, false)
    }

    private fun canLaunch(intent: Intent): Boolean {
        val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        return activity.exported &&
            (
                activity.permission == null ||
                    context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED
                )
    }
}

internal data class OemAutostartTarget(
    val packageName: String,
    val className: String? = null,
    val action: String? = null,
    val dataUri: String? = null,
    val intExtras: Map<String, Int> = emptyMap(),
) {
    init {
        require((className == null) != (action == null))
    }

    fun toIntent(): Intent = Intent(action)
        .setPackage(packageName)
        .apply { className?.let { setComponent(ComponentName(packageName, it)) } }
        .apply { dataUri?.let { setData(Uri.parse(it)) } }
        .apply { intExtras.forEach { (name, value) -> putExtra(name, value) } }
}

@Suppress("CyclomaticComplexMethod")
internal fun oemAutostartTargets(manufacturer: String): List<OemAutostartTarget> = when {
    isXiaomiManufacturer(manufacturer) -> listOf(
        OemAutostartTarget(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
    )
    manufacturer.matchesOem("oppo", "realme", "oneplus") -> listOf(
        OemAutostartTarget("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
        OemAutostartTarget(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        OemAutostartTarget(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        OemAutostartTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        OemAutostartTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    )
    manufacturer.matchesOem("vivo", "iqoo") -> listOf(
        OemAutostartTarget(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        OemAutostartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        OemAutostartTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    )
    manufacturer.matchesOem("huawei", "honor") -> listOf(
        OemAutostartTarget(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        OemAutostartTarget(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        OemAutostartTarget(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        OemAutostartTarget(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    )
    manufacturer.matchesOem("asus") -> listOf(
        OemAutostartTarget("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        OemAutostartTarget(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.entry.FunctionActivity",
            "mobilemanager://function/entry/AutoStart",
        ),
    )
    manufacturer.matchesOem("samsung") -> listOf(
        OemAutostartTarget(
            packageName = "com.samsung.android.lool",
            action = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
            intExtras = mapOf("activity_type" to 2),
        ),
        OemAutostartTarget(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
        ),
        OemAutostartTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        OemAutostartTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        OemAutostartTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )
    manufacturer.matchesOem("tecno", "infinix", "itel") -> listOf(
        OemAutostartTarget("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        OemAutostartTarget(
            "com.transsion.phonemanager",
            "com.itel.autobootmanager.activity.AutoBootMgrActivity",
        ),
    )
    manufacturer.matchesOem("nokia", "hmd global") -> listOf(
        OemAutostartTarget(
            "com.evenwell.powersaving.g3",
            "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
        ),
    )
    manufacturer.matchesOem("letv", "leeco", "lemobile") -> listOf(
        OemAutostartTarget("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
    )
    manufacturer.matchesOem("meizu") -> listOf(
        OemAutostartTarget(
            packageName = "com.meizu.safe",
            action = "com.meizu.safe.security.SHOW_APPSEC",
        ),
    )
    else -> emptyList()
}

internal fun isXiaomiManufacturer(manufacturer: String): Boolean = manufacturer.matchesOem("xiaomi", "redmi", "poco")

private fun String.matchesOem(vararg names: String): Boolean {
    val normalized = lowercase().trim()
    return names.any { normalized.contains(it) }
}

@Suppress("PrivateApi")
private fun readXiaomiAutostartMode(context: Context): Int? = runCatching {
    val method = AppOpsManager::class.java.getMethod(
        "checkOpNoThrow",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
    )
    method.invoke(
        context.getSystemService(AppOpsManager::class.java),
        XIAOMI_AUTOSTART_APP_OP,
        Process.myUid(),
        context.packageName,
    ) as Int
}.getOrNull()

internal fun xiaomiAutostartGrantCommand(packageName: String): String {
    validatePackageName(packageName)
    val where = shellQuote("pkgName='$packageName'")
    return "row=\$(content query --uri $XIAOMI_PERMISSION_URI " +
        "--projection userAccept:userPrompt:userReject --where $where 2>/dev/null) || exit 1; " +
        "accept=\${row#*userAccept=}; accept=\${accept%%,*}; " +
        "prompt=\${row#*userPrompt=}; prompt=\${prompt%%,*}; " +
        "reject=\${row#*userReject=}; reject=\${reject%%,*}; " +
        "case \"\$accept:\$prompt:\$reject\" in ''|*[!0-9:]*) exit 1;; esac; " +
        "content update --uri $XIAOMI_PERMISSION_URI " +
        "--bind userAccept:l:\$((accept | $XIAOMI_AUTOSTART_PERMISSION_BIT)) " +
        "--bind userPrompt:l:\$((prompt & ~$XIAOMI_AUTOSTART_PERMISSION_BIT)) " +
        "--bind userReject:l:\$((reject & ~$XIAOMI_AUTOSTART_PERMISSION_BIT)) " +
        "--where $where && " +
        "cmd appops set $packageName $XIAOMI_AUTOSTART_APP_OP allow && " +
        xiaomiAutostartCheckCommand(packageName)
}

internal fun xiaomiAutostartCheckCommand(packageName: String): String {
    validatePackageName(packageName)
    return "cmd appops get $packageName $XIAOMI_AUTOSTART_APP_OP"
}

private fun validatePackageName(packageName: String) {
    require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
}

internal fun parseXiaomiAutostartMode(output: String): Int? = when {
    output.contains("MIUIOP($XIAOMI_AUTOSTART_APP_OP): allow") -> AppOpsManager.MODE_ALLOWED
    output.contains("MIUIOP($XIAOMI_AUTOSTART_APP_OP): ignore") -> AppOpsManager.MODE_IGNORED
    output.contains("MIUIOP($XIAOMI_AUTOSTART_APP_OP): deny") -> AppOpsManager.MODE_ERRORED
    else -> null
}

private const val XIAOMI_AUTOSTART_APP_OP = 10008
private const val XIAOMI_AUTOSTART_PERMISSION_BIT = 16384
private const val XIAOMI_PERMISSION_URI = "content://com.lbe.security.miui.permmgr/active"
private const val PREFERENCES_NAME = "oem_autostart"
private const val KEY_ROOT_GRANT_ATTEMPTED = "root_grant_attempted"
private const val KEY_ROOT_GRANT_SUCCEEDED = "root_grant_succeeded"
