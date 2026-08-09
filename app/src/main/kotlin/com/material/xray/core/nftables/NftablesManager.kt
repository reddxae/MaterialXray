package com.material.xray.core.nftables

import com.material.xray.core.root.RootShell

/**
 * Removes the nftables table that older releases of the app created. The current runtime routes
 * with ip rules and fwmarks only, so this exists purely as defensive cleanup of leftover state.
 */
class NftablesManager(private val shell: RootShell) {

    suspend fun remove(): Boolean = shell.execute(nftablesRemovalCommand()).isSuccess
}

internal fun nftablesRemovalCommand(): String = "if ! command -v nft >/dev/null 2>&1; then exit 0; fi; " +
    "tables=\$(nft list tables 2>/dev/null) || exit 1; " +
    "printf '%s\\n' \"\$tables\" | grep -Fqx 'table inet xray'; status=\$?; " +
    "if [ \$status -eq 0 ]; then nft delete table inet xray; " +
    "elif [ \$status -eq 1 ]; then true; else exit \$status; fi"
