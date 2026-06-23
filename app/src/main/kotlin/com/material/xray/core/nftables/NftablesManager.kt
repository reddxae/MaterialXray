package com.material.xray.core.nftables

import com.material.xray.core.root.RootShell

class NftablesManager(private val shell: RootShell) {

    suspend fun apply(fwmark: Int, routeMark: Int, bypassUids: Set<Int>) {
        val uidElements = if (bypassUids.isNotEmpty()) {
            "elements = { ${bypassUids.joinToString(", ")} }"
        } else {
            ""
        }

        val ruleset = buildString {
            appendLine("table inet xray {")
            appendLine("    set bypass_uids {")
            appendLine("        type uid_t")
            if (uidElements.isNotEmpty()) appendLine("        $uidElements")
            appendLine("    }")
            appendLine()
            appendLine("    chain output {")
            appendLine("        type route hook output priority 0; policy accept;")
            appendLine("        meta mark $fwmark accept")
            appendLine("        oifname \"lo\" accept")
            if (bypassUids.isNotEmpty()) {
                appendLine("        meta skuid @bypass_uids accept")
            }
            appendLine("        ip protocol icmp accept")
            appendLine("        ip6 nexthdr icmpv6 accept")
            appendLine("        meta mark set $routeMark")
            appendLine("    }")
            appendLine("}")
        }

        shell.execute("nft delete table inet xray 2>/dev/null; printf '%s' '${ruleset.replace("'", "'\\''")}' | nft -f -")
    }

    suspend fun remove() {
        shell.execute("nft delete table inet xray 2>/dev/null")
    }

    suspend fun exists(): Boolean {
        val result = shell.execute("nft list tables 2>/dev/null")
        return result.output.contains("inet xray")
    }
}
