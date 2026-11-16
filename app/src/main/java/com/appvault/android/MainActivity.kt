package com.appvault.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.appvault.android.adb.KadbGateway
import com.appvault.android.apps.InstalledApp
import com.appvault.android.apps.InstalledApps
import com.appvault.android.backup.ApkInstaller
import com.appvault.android.backup.BackupBundleManager
import com.appvault.android.backup.InstallPermissionRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adbGateway = KadbGateway()
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }

    private lateinit var statusText: TextView
    private lateinit var appList: LinearLayout
    private lateinit var root: LinearLayout
    private var pendingExportPackage: String? = null
    private var pendingRestore: BackupBundleManager.RestoredBundle? = null
    private var pendingUninstallPackage: String? = null
    private var pendingUninstallHasExport = false
    private var adbConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderApps()
        val savedHost = prefs.getString(KEY_HOST, null)
        val savedPort = prefs.getInt(KEY_CONNECT_PORT, -1)
        statusText.text = if (savedHost != null && savedPort in 1..65535) {
            "ADB paired previously. Tap Connect to reconnect."
        } else {
            "ADB is not connected. Pair once using Wireless Debugging."
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestore != null && packageManager.canRequestPackageInstalls()) {
            installPendingBundle()
        }
        renderApps()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.rgb(11, 18, 32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(text("APP VAULT", 12, Color.rgb(96, 165, 250), true))
        content.addView(text("App backup & data preservation", 26, Color.WHITE, true).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        content.addView(text(
            "Export the install package, then use Android's keep-data uninstall option. " +
                "Private app data stays on this phone; it is not inside the backup archive.",
            14, Color.rgb(203, 213, 225), false
        ).apply { setPadding(0, 0, 0, dp(16)) })

        statusText = text("ADB not connected", 14, Color.rgb(147, 197, 253), true).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(22, 38, 64), dp(12))
        }
        content.addView(statusText, matchWrap())

        val adbButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        adbButtons.addView(actionButton("Pair ADB") { showPairDialog() }, weightedParams())
        adbButtons.addView(actionButton("Connect") { showConnectDialog() }, weightedParams())
        content.addView(adbButtons)

        content.addView(text("How the connection works", 17, Color.WHITE, true).apply {
            setPadding(0, dp(16), 0, dp(5))
        })
        content.addView(text(
            "No Root and no Shizuku. The ADB client is built into this APK. " +
                "One-time setup: enable Developer options and Wireless debugging, pair with the six-digit code, " +
                "then enter the current connection port. Android 11 or newer is required.",
            13, Color.rgb(148, 163, 184), false
        ))

        val appHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(8))
        }
        appHeading.addView(text("Installed apps", 20, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appHeading.addView(actionButton("Refresh") { renderApps() })
        content.addView(appHeading)

        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appList)

        content.addView(actionButton("Restore a .avbackup file") { chooseBackupToRestore() }.apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, matchWrap())
        content.addView(text(
            "Important: the .avbackup file contains the APK and split APKs, not private data. " +
                "To retain private data after removal, use App Vault's Preserve data & uninstall action " +
                "on this same device. Do not use Android Settings to uninstall the app normally.",
            12, Color.rgb(251, 191, 36), false
        ).apply { setPadding(0, dp(14), 0, dp(12)) })

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val apps = runCatching { InstalledApps.load(this) }.getOrElse {
            appList.addView(text("Could not enumerate apps: ${it.message}", 13, Color.RED, false))
            return
        }
        if (apps.isEmpty()) {
            appList.addView(text("No user-installed apps found.", 14, Color.LTGRAY, false))
            return
        }
        apps.forEach { app -> appList.addView(appCard(app), matchWrap()) }
    }

    private fun appCard(app: InstalledApp): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(20, 31, 51), dp(14))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(app.applicationInfo))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        details.addView(text(app.label, 16, Color.WHITE, true))
        details.addView(text(app.packageName, 11, Color.rgb(148, 163, 184), false))
        details.addView(text("Version ${app.versionName} · ${app.versionCode}", 11, Color.rgb(148, 163, 184), false))
        top.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("Export APK bundle") { exportApp(app) }, weightedParams())
        actions.addView(actionButton("Preserve data & uninstall") { confirmPreservingUninstall(app) }, weightedParams())
        card.addView(actions)
        return card.apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
    }

    private fun exportApp(app: InstalledApp) {
        pendingExportPackage = app.packageName
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${app.packageName}.avbackup")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun chooseBackupToRestore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQUEST_RESTORE)
    }

    @Deprecated("Activity Result API is not required by this minimal platform-only UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> {
                val packageName = pendingExportPackage ?: return
                pendingExportPackage = null
                scope.launch {
                    try {
                        val bundle = withContext(Dispatchers.IO) {
                            BackupBundleManager.createBundle(this@MainActivity, packageName)
                        }
                        withContext(Dispatchers.IO) {
                            BackupBundleManager.copyBundleToUri(this@MainActivity, bundle, uri)
                        }
                        bundle.delete()
                        Toast.makeText(this@MainActivity, "Backup bundle exported", Toast.LENGTH_LONG).show()
                        val app = InstalledApps.load(this@MainActivity).firstOrNull { it.packageName == packageName }
                        if (app != null) confirmPreservingUninstall(app, exportJustCompleted = true)
                    } catch (error: Throwable) {
                        showError("Export failed", error.message ?: "Unknown error")
                    }
                }
            }
            REQUEST_RESTORE -> {
                scope.launch {
                    try {
                        statusText.text = "Validating backup archive…"
                        val bundle = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use {
                                BackupBundleManager.extractAndValidate(this@MainActivity, it)
                            } ?: error("Could not open the selected archive")
                        }
                        pendingRestore = bundle
                        installPendingBundle()
                    } catch (error: Throwable) {
                        showError("Restore failed", error.message ?: "Invalid backup archive")
                        statusText.text = "Restore validation failed"
                    }
                }
            }
        }
    }

    private fun confirmPreservingUninstall(app: InstalledApp, exportJustCompleted: Boolean = false) {
        if (!exportJustCompleted) {
            AlertDialog.Builder(this)
                .setTitle("Export the APK first")
                .setMessage("App Vault must save the APK bundle before it removes the app. The private data will remain on this phone and will not be inside the exported file.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Export APK bundle") { _, _ -> exportApp(app) }
                .show()
            return
        }
        if (!adbConnected) {
            pendingUninstallPackage = app.packageName
            pendingUninstallHasExport = true
            showConnectDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Preserve app data on this phone?")
            .setMessage("The APK bundle was saved. App Vault will ask Android to remove this app for the current user while keeping its data/cache directories on this phone. The private data is NOT in the exported file. Reinstall the same package signed with the same signing key to reuse the retained data.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Preserve & uninstall") { _, _ ->
                performPreservingUninstall(app.packageName)
            }
            .show()
    }

    private fun performPreservingUninstall(packageName: String) {
        val host = prefs.getString(KEY_HOST, null) ?: return showConnectDialog()
        val port = prefs.getInt(KEY_CONNECT_PORT, -1)
        if (port !in 1..65535) return showConnectDialog()
        scope.launch {
            statusText.text = "Asking Android to keep data while removing $packageName…"
            try {
                val result = adbGateway.uninstallKeepingData(host, port, packageName)
                if (result.exitCode == 0 && result.output.contains("Success", ignoreCase = true)) {
                    Toast.makeText(this@MainActivity,
                        "App removed; Android was asked to retain its data on this device.", Toast.LENGTH_LONG).show()
                    statusText.text = "Data-preserving uninstall completed for $packageName"
                    adbConnected = true
                    renderApps()
                } else {
                    showError("Uninstall failed", result.output.ifBlank { "Exit code ${result.exitCode}" })
                    statusText.text = "Preserve-data uninstall failed"
                }
            } catch (error: Throwable) {
                adbConnected = false
                statusText.text = "ADB connection failed"
                showError("Could not preserve data", error.message ?: "ADB error")
            }
        }
    }

    private fun showPairDialog() {
        val form = formLayout()
        val hostInput = input("Device IP address", prefs.getString(KEY_HOST, "") ?: "")
        val portInput = input("Pairing port shown in the pairing dialog", "")
        val codeInput = input("Six-digit pairing code", "", number = true)
        form.addView(hostInput)
        form.addView(portInput)
        form.addView(codeInput)
        form.addView(text(
            "Keep the Android Wireless Debugging pairing-code dialog open while pairing. Use split-screen if necessary.",
            12, Color.DKGRAY, false
        ))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pair built-in ADB")
            .setMessage("Android 11+ · No Root · No Shizuku")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull()
                val code = codeInput.text.toString().trim()
                if (port == null || host.isBlank() || !code.matches(Regex("[0-9]{6}"))) {
                    Toast.makeText(this, "Enter the IP, pairing port and six-digit code.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                scope.launch {
                    try {
                        adbGateway.pair(host, port, code)
                        prefs.edit().putString(KEY_HOST, host).apply()
                        adbConnected = false
                        statusText.text = "Paired. Enter the connection port shown on the Wireless Debugging page."
                        dialog.dismiss()
                        showConnectDialog()
                    } catch (error: Throwable) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        showError("Pairing failed", error.message ?: "Check the IP, port and code")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showConnectDialog() {
        val form = formLayout()
        val hostInput = input("Device IP address", prefs.getString(KEY_HOST, "") ?: "")
        val portInput = input(
            "Wireless Debugging connection port",
            prefs.getInt(KEY_CONNECT_PORT, -1).takeIf { it > 0 }?.toString() ?: ""
        )
        form.addView(hostInput)
        form.addView(portInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect to this device")
            .setMessage("Use the IP address and connection port shown on the main Wireless Debugging screen, not the temporary pairing port.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535) {
                    Toast.makeText(this, "Enter a valid IP address and connection port.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                scope.launch {
                    try {
                        val result = adbGateway.testConnection(host, port)
                        check(result.exitCode == 0 && result.output.contains("APPVAULT_CONNECTED")) {
                            result.output.ifBlank { "ADB did not return the connection test response." }
                        }
                        prefs.edit().putString(KEY_HOST, host).putInt(KEY_CONNECT_PORT, port).apply()
                        adbConnected = true
                        statusText.text = "ADB connected · $host:$port · shell authorization active"
                        dialog.dismiss()
                        pendingUninstallPackage?.let { packageName ->
                            pendingUninstallPackage = null
                            val hadExport = pendingUninstallHasExport
                            pendingUninstallHasExport = false
                            val app = InstalledApps.load(this@MainActivity).firstOrNull { it.packageName == packageName }
                            if (app != null) confirmPreservingUninstall(app, exportJustCompleted = hadExport)
                        }
                    } catch (error: Throwable) {
                        adbConnected = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        showError("Connection failed", error.message ?: "Check Wireless Debugging and the connection port")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun installPendingBundle() {
        val bundle = pendingRestore ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow app installation")
                .setMessage("Android requires you to allow App Vault to install APK files. This is an Android system permission, not Root or Shizuku.")
                .setNegativeButton("Cancel") { _, _ ->
                    pendingRestore?.directory?.deleteRecursively()
                    pendingRestore = null
                }
                .setPositiveButton("Open Android Settings") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ))
                }
                .show()
            return
        }
        try {
            ApkInstaller.install(this, bundle)
            pendingRestore = null
            statusText.text = "Install session sent to Android. Complete the system confirmation if shown."
        } catch (error: InstallPermissionRequiredException) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            showError("Could not start installation", error.message ?: "PackageInstaller error")
            statusText.text = "Install could not start"
        }
    }

    private fun formLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun input(hintText: String, value: String, number: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(value)
        textSize = 14f
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_NEXT
        if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(Color.BLACK)
        setHintTextColor(Color.DKGRAY)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { action() }
        setPadding(dp(6), dp(5), dp(6), dp(5))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }

    private fun weightedParams() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { marginEnd = dp(5) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    companion object {
        private const val REQUEST_EXPORT = 101
        private const val REQUEST_RESTORE = 102
        private const val KEY_HOST = "adb_host"
        private const val KEY_CONNECT_PORT = "adb_connect_port"
    }
}
