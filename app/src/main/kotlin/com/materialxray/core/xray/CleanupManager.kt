package com.materialxray.core.xray

import android.content.Context
import com.materialxray.core.nftables.NftablesManager
import com.materialxray.core.root.RootShell

class CleanupManager(
    context: Context,
    private val shell: RootShell,
) {
    private val stateFile = StateFile(context)
    private val nftables = NftablesManager(shell)
    private val tunManager = TunManager(shell)

    suspend fun ensureCleanState() {
        val state = stateFile.read()

        // 1. Kill orphaned xray process
        if (state != null && state.xrayPid > 0) {
            shell.execute("kill ${state.xrayPid} 2>/dev/null")
        }
        shell.execute("pkill -f 'xray run' 2>/dev/null")

        // 2. Remove nftables table (atomic)
        nftables.remove()

        // 3. Remove ip rules and routes
        val tunName = state?.tunName ?: "xray0"
        val fwmark = state?.fwmark ?: 255
        val routeMark = state?.routeMark ?: 100
        val routeTable = state?.routeTable ?: 100
        val appRouteCount = state?.appProxyServerIds?.size?.takeIf { it > 0 } ?: 64
        tunManager.removeRouting(fwmark, routeMark, routeTable, tunName, appRouteCount)

        // 4. Delete state file
        stateFile.delete()
    }
}
