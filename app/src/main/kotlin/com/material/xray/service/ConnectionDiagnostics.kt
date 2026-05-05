package com.material.xray.service

import com.material.xray.core.root.RootShell
import com.material.xray.core.root.RootShell.NetworkNamespace

internal interface DiagnosticCommandRunner {
    fun defaultNetworkNamespace(): NetworkNamespace

    suspend fun execute(command: String, namespace: NetworkNamespace): RootShell.Result
}

internal class RootShellDiagnosticCommandRunner(
    private val shell: RootShell,
) : DiagnosticCommandRunner {
    override fun defaultNetworkNamespace(): NetworkNamespace = shell.defaultNetworkNamespace()

    override suspend fun execute(command: String, namespace: NetworkNamespace): RootShell.Result =
        shell.execute(command, namespace)
}

internal class ConnectionDiagnostics(
    private val commandRunner: DiagnosticCommandRunner,
    private val log: LogBuffer,
) {
    suspend fun logNamespaceDiagnostics(
        stage: String,
        tunName: String? = null,
        xrayPid: Int? = null,
    ) {
        logCommand(
            "$stage/current-netns",
            "printf 'pid=%s self=%s pid1=%s\\n' \"$$\" \"$(readlink /proc/$$/ns/net 2>/dev/null)\" \"$(readlink /proc/1/ns/net 2>/dev/null)\"",
            NetworkNamespace.CURRENT,
        )

        if (commandRunner.defaultNetworkNamespace() == NetworkNamespace.INIT) {
            logCommand(
                "$stage/init-netns",
                "printf 'pid=%s self=%s pid1=%s\\n' \"$$\" \"$(readlink /proc/$$/ns/net 2>/dev/null)\" \"$(readlink /proc/1/ns/net 2>/dev/null)\"",
                NetworkNamespace.INIT,
            )
        }

        if (tunName != null) {
            logCommand(
                "$stage/current-link",
                "ip link show $tunName 2>/dev/null | head -n 1",
                NetworkNamespace.CURRENT,
            )
            if (commandRunner.defaultNetworkNamespace() == NetworkNamespace.INIT) {
                logCommand(
                    "$stage/init-link",
                    "ip link show $tunName 2>/dev/null | head -n 1",
                    NetworkNamespace.INIT,
                )
            }
        }

        if (xrayPid != null && xrayPid > 0) {
            logCommand(
                "$stage/xray-proc",
                "if [ -d /proc/$xrayPid ]; then printf 'pid=$xrayPid net=%s\\n' \"$(readlink /proc/$xrayPid/ns/net 2>/dev/null)\"; tr '\\0' ' ' </proc/$xrayPid/cmdline 2>/dev/null; else echo 'pid=$xrayPid missing'; fi",
                NetworkNamespace.CURRENT,
            )
            if (tunName != null) {
                logCommand(
                    "$stage/xray-link",
                    "if [ -d /proc/$xrayPid ]; then nsenter -t $xrayPid -n -- ip link show $tunName 2>/dev/null | head -n 1; fi",
                    NetworkNamespace.CURRENT,
                )
            }
        }
    }

    private suspend fun logCommand(
        label: String,
        command: String,
        namespace: NetworkNamespace,
    ) {
        val result = commandRunner.execute(command, namespace)
        val outputLines = result.output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val errorLines = result.error.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

        if (outputLines.isEmpty() && errorLines.isEmpty()) {
            log.append(LogSource.APP, "$label: exit=${result.exitCode}")
            return
        }

        outputLines.forEach { log.append(LogSource.APP, "$label: $it") }
        errorLines.forEach { log.append(LogSource.APP, "$label stderr: $it") }
        if (!result.isSuccess) {
            log.append(LogSource.APP, "$label: exit=${result.exitCode}")
        }
    }
}
