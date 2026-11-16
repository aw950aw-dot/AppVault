package com.appvault.android.adb

import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Only exposes the exact ADB operations required by App Vault. There is deliberately
 * no arbitrary shell/terminal UI.
 */
class KadbGateway {
    data class CommandResult(val exitCode: Int, val output: String)

    suspend fun pair(host: String, pairingPort: Int, sixDigitCode: String) {
        validateHost(host)
        require(pairingPort in 1..65535) { "Invalid pairing port" }
        require(sixDigitCode.matches(Regex("[0-9]{6}"))) { "Pairing code must contain six digits" }

        withContext(Dispatchers.IO) {
            Kadb.pair(host, pairingPort, sixDigitCode)
        }
    }

    suspend fun testConnection(host: String, connectPort: Int): CommandResult =
        runShell(host, connectPort, "echo APPVAULT_CONNECTED")

    suspend fun currentUserId(host: String, connectPort: Int): Int {
        val result = runShell(host, connectPort, "am get-current-user")
        require(result.exitCode == 0) { "Could not read the current Android user: ${result.output}" }
        return result.output.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /**
     * Keeps the package's data/cache directories on this device while removing it
     * for the active Android user. The caller must have exported the APK bundle first.
     */
    suspend fun uninstallKeepingData(
        host: String,
        connectPort: Int,
        packageName: String
    ): CommandResult {
        requireValidPackageName(packageName)
        val userId = currentUserId(host, connectPort)
        return runShell(
            host,
            connectPort,
            "pm uninstall -k --user $userId $packageName"
        )
    }

    private suspend fun runShell(host: String, port: Int, command: String): CommandResult {
        validateHost(host)
        require(port in 1..65535) { "Invalid ADB connection port" }
        return withContext(Dispatchers.IO) {
            Kadb.create(host, port).use { client ->
                val response = client.shell(command)
                CommandResult(response.exitCode, response.output)
            }
        }
    }

    private fun validateHost(host: String) {
        require(host.matches(Regex("[A-Za-z0-9.:%-]{1,100}"))) { "Invalid IP address or host" }
    }

    companion object {
        fun requireValidPackageName(packageName: String) {
            require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
                "Invalid package name"
            }
        }
    }
}
