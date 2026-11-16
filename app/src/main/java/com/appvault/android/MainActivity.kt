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
            "המכשיר שויך בעבר. לחץ על התחברות כדי להתחבר מחדש."
        } else {
            "אין חיבור למכשיר. בצע שיוך חד־פעמי דרך ניפוי באגים אלחוטי."
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.rgb(11, 18, 32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(text("כספת אפליקציות", 12, Color.rgb(96, 165, 250), true))
        content.addView(text("גיבוי אפליקציות ושמירת נתונים", 26, Color.WHITE, true).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        content.addView(text(
            "ייצא את קובצי ההתקנה, ואז הסר את האפליקציה תוך שמירת הנתונים במכשיר. " +
                "הנתונים הפרטיים נשארים בטלפון ואינם נכללים בקובץ הגיבוי.",
            14, Color.rgb(203, 213, 225), false
        ).apply { setPadding(0, 0, 0, dp(16)) })

        statusText = text("אין חיבור למכשיר", 14, Color.rgb(147, 197, 253), true).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(22, 38, 64), dp(12))
        }
        content.addView(statusText, matchWrap())

        val adbButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        adbButtons.addView(actionButton("שיוך מכשיר") { showPairDialog() }, weightedParams())
        adbButtons.addView(actionButton("התחברות") { showConnectDialog() }, weightedParams())
        content.addView(adbButtons)
        content.addView(actionButton("פתח הגדרות ניפוי באגים אלחוטי") {
            openWirelessDebuggingSettings()
        }, matchWrap())

        content.addView(text("איך החיבור עובד", 17, Color.WHITE, true).apply {
            setPadding(0, dp(16), 0, dp(5))
        })
        content.addView(text(
            "אין צורך ב־Root או ב־Shizuku. לקוח ADB מובנה באפליקציה. " +
                "יש להפעיל אפשרויות למפתחים וניפוי באגים אלחוטי, לפתוח במסך ההגדרות את ״שיוך באמצעות קוד״ ולהזין את הקוד בן שש הספרות. " +
                "האפליקציה תאתר אוטומטית את פורט השיוך והחיבור. נדרש Android 11 ומעלה.",
            13, Color.rgb(148, 163, 184), false
        ))

        val appHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(8))
        }
        appHeading.addView(text("אפליקציות מותקנות", 20, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appHeading.addView(actionButton("רענון") { renderApps() })
        content.addView(appHeading)

        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appList)

        content.addView(actionButton("שחזור מקובץ ‎.avbackup") { chooseBackupToRestore() }.apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, matchWrap())
        content.addView(text(
            "חשוב: קובץ ‎.avbackup מכיל את קובצי ההתקנה בלבד ולא את המידע הפרטי של האפליקציה. " +
                "כדי לשמור את המידע לאחר ההסרה, יש להשתמש בפעולת ״שמירת נתונים והסרה״ מתוך כספת אפליקציות באותו מכשיר. " +
                "אין להסיר את האפליקציה דרך הגדרות Android.",
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
            appList.addView(text("לא ניתן לטעון את רשימת האפליקציות: ${it.message}", 13, Color.RED, false))
            return
        }
        if (apps.isEmpty()) {
            appList.addView(text("לא נמצאו אפליקציות שהותקנו על ידי המשתמש.", 14, Color.LTGRAY, false))
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
        details.addView(text("גרסה ${app.versionName} · ${app.versionCode}", 11, Color.rgb(148, 163, 184), false))
        top.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(actionButton("ייצוא קובצי התקנה") { exportApp(app) }, weightedParams())
        actions.addView(actionButton("שמירת נתונים והסרה") { confirmPreservingUninstall(app) }, weightedParams())
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
                        Toast.makeText(this@MainActivity, "קובץ הגיבוי נשמר בהצלחה", Toast.LENGTH_LONG).show()
                        val app = InstalledApps.load(this@MainActivity).firstOrNull { it.packageName == packageName }
                        if (app != null) confirmPreservingUninstall(app, exportJustCompleted = true)
                    } catch (error: Throwable) {
                        showError("ייצוא נכשל", error.message ?: "שגיאה לא ידועה")
                    }
                }
            }
            REQUEST_RESTORE -> {
                scope.launch {
                    try {
                        statusText.text = "בודק את קובץ הגיבוי…"
                        val bundle = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use {
                                BackupBundleManager.extractAndValidate(this@MainActivity, it)
                            } ?: error("לא ניתן לפתוח את קובץ הגיבוי שנבחר")
                        }
                        pendingRestore = bundle
                        installPendingBundle()
                    } catch (error: Throwable) {
                        showError("השחזור נכשל", error.message ?: "קובץ הגיבוי אינו תקין")
                        statusText.text = "בדיקת קובץ הגיבוי נכשלה"
                    }
                }
            }
        }
    }

    private fun confirmPreservingUninstall(app: InstalledApp, exportJustCompleted: Boolean = false) {
        if (!exportJustCompleted) {
            AlertDialog.Builder(this)
                .setTitle("יש לייצא תחילה את קובצי ההתקנה")
                .setMessage("כספת אפליקציות חייבת לשמור את קובצי ההתקנה לפני הסרת האפליקציה. הנתונים הפרטיים יישארו בטלפון ולא ייכללו בקובץ שיוצא.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("ייצוא קובצי התקנה") { _, _ -> exportApp(app) }
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
            .setTitle("לשמור את נתוני האפליקציה במכשיר?")
            .setMessage("קובצי ההתקנה נשמרו. כעת תישלח ל־Android בקשה להסיר את האפליקציה מהמשתמש הנוכחי תוך השארת תיקיות הנתונים והמטמון בטלפון. הנתונים הפרטיים אינם בקובץ הגיבוי. כדי להשתמש בהם שוב, יש להתקין את אותה חבילה עם אותה חתימת מפתח.")
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שמירה והסרה") { _, _ ->
                performPreservingUninstall(app.packageName)
            }
            .show()
    }

    private fun performPreservingUninstall(packageName: String) {
        val host = prefs.getString(KEY_HOST, null) ?: return showConnectDialog()
        val port = prefs.getInt(KEY_CONNECT_PORT, -1)
        if (port !in 1..65535) return showConnectDialog()
        scope.launch {
            statusText.text = "מבקש מ־Android להסיר את $packageName תוך שמירת הנתונים…"
            try {
                val result = adbGateway.uninstallKeepingData(host, port, packageName)
                if (result.exitCode == 0 && result.output.contains("Success", ignoreCase = true)) {
                    Toast.makeText(this@MainActivity,
                        "האפליקציה הוסרה. נשלחה ל־Android בקשה לשמור את הנתונים במכשיר.", Toast.LENGTH_LONG).show()
                    statusText.text = "ההסרה תוך שמירת נתונים הושלמה עבור $packageName"
                    adbConnected = true
                    renderApps()
                } else {
                    showError("ההסרה נכשלה", result.output.ifBlank { "קוד יציאה ${result.exitCode}" })
                    statusText.text = "ההסרה תוך שמירת נתונים נכשלה"
                }
            } catch (error: Throwable) {
                adbConnected = false
                statusText.text = "החיבור למכשיר נכשל"
                showError("לא ניתן לשמור את נתוני האפליקציה", error.message ?: "שגיאת ADB")
            }
        }
    }

    private fun showPairDialog() {
        val form = formLayout()
        val discoveryText = text("מאתר אוטומטית את שירות השיוך…", 13, Color.DKGRAY, false)
        val codeInput = input("קוד שיוך בן 6 ספרות", "", number = true)
        val hostInput = input("כתובת IP (לגיבוי בלבד)", prefs.getString(KEY_HOST, "") ?: "")
        val portInput = input("פורט שיוך (לגיבוי בלבד)", "", number = true)
        hostInput.visibility = View.GONE
        portInput.visibility = View.GONE
        val manualButton = actionButton("אין זיהוי אוטומטי? הזנה ידנית") {
            val show = hostInput.visibility != View.VISIBLE
            hostInput.visibility = if (show) View.VISIBLE else View.GONE
            portInput.visibility = if (show) View.VISIBLE else View.GONE
            (it as? Button)?.text = if (show) "הסתרת הזנה ידנית" else "אין זיהוי אוטומטי? הזנה ידנית"
        }
        form.addView(discoveryText)
        form.addView(codeInput)
        form.addView(manualButton)
        form.addView(hostInput)
        form.addView(portInput)

        var discovered: KadbGateway.DiscoveredEndpoint? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("שיוך אלחוטי")
            .setView(form)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("בצע שיוך", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = codeInput.text.toString().trim()
                if (!code.matches(Regex("[0-9]{6}"))) {
                    Toast.makeText(this, "יש להזין קוד בן שש ספרות.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val endpoint = discovered ?: run {
                    val host = hostInput.text.toString().trim()
                    val port = portInput.text.toString().toIntOrNull()
                    if (host.isBlank() || port == null || port !in 1..65535) {
                        Toast.makeText(this, "לא נמצא פורט אוטומטית. הזן כתובת IP ופורט שיוך.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    KadbGateway.DiscoveredEndpoint(host, port, "הזנה ידנית")
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                discoveryText.text = "מתחבר לפורט הזיווג ומאמת את הקוד…"
                scope.launch {
                    try {
                        adbGateway.pair(endpoint.host, endpoint.port, code)
                        prefs.edit().putString(KEY_HOST, endpoint.host).apply()
                        adbConnected = false
                        dialog.dismiss()
                        statusText.text = "השיוך הצליח. מחפש את פורט החיבור…"
                        Toast.makeText(this@MainActivity, "השיוך הצליח. מנסה להתחבר אוטומטית.", Toast.LENGTH_LONG).show()
                        connectAfterPairing(endpoint.host)
                    } catch (error: Throwable) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        discoveryText.text = "השיוך נכשל. בדוק שהקוד עדיין בתוקף וששני השירותים באותה רשת Wi‑Fi."
                        showError("השיוך נכשל", error.message ?: "בדוק כתובת, פורט וקוד שיוך.")
                    }
                }
            }
        }
        dialog.show()

        scope.launch {
            discoveryText.text = "מחפש את שירות השיוך ברשת המקומית…"
            discovered = runCatching {
                adbGateway.discoverPairingEndpoint(this@MainActivity, timeoutMs = 8_000)
            }.getOrNull()
            if (discovered != null) {
                discoveryText.text = "נמצא מכשיר: ${discovered!!.name}\nכתובת: ${discovered!!.host}\nהזן את הקוד שמופיע בהגדרות."
                hostInput.setText(discovered!!.host)
                portInput.setText(discovered!!.port.toString())
                manualButton.text = "הצג פרטי חיבור ידניים"
            } else {
                discoveryText.text = "לא נמצא שירות אוטומטית. ודא שפתחת ״שיוך באמצעות קוד״ בהגדרות, או בחר הזנה ידנית."
                hostInput.visibility = View.VISIBLE
                portInput.visibility = View.VISIBLE
            }
        }
    }

    private fun connectAfterPairing(preferredHost: String?) {
        scope.launch {
            statusText.text = "מחפש את שירות החיבור האלחוטי…"
            val endpoint = runCatching {
                adbGateway.discoverConnectEndpoint(this@MainActivity, preferredHost, timeoutMs = 10_000)
            }.getOrNull()
            if (endpoint != null) {
                connectToEndpoint(endpoint.host, endpoint.port)
            } else {
                statusText.text = "השיוך הצליח, אך הזיהוי האוטומטי לא מצא פורט חיבור. אפשר להזין אותו ידנית."
                showConnectDialog()
            }
        }
    }

    private fun showConnectDialog() {
        val form = formLayout()
        val discoveryText = text("מאתר את פורט החיבור ברשת המקומית…", 13, Color.DKGRAY, false)
        val hostInput = input("כתובת IP", prefs.getString(KEY_HOST, "") ?: "")
        val portInput = input(
            "פורט חיבור (לא פורט השיוך)",
            prefs.getInt(KEY_CONNECT_PORT, -1).takeIf { it > 0 }?.toString() ?: "",
            number = true
        )
        hostInput.visibility = View.GONE
        portInput.visibility = View.GONE
        val manualButton = actionButton("הזנה ידנית") {
            hostInput.visibility = View.VISIBLE
            portInput.visibility = View.VISIBLE
        }
        form.addView(discoveryText)
        form.addView(manualButton)
        form.addView(hostInput)
        form.addView(portInput)

        var discovered: KadbGateway.DiscoveredEndpoint? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle("התחברות למכשיר")
            .setView(form)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("התחבר", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val endpoint = discovered ?: run {
                    val host = hostInput.text.toString().trim()
                    val port = portInput.text.toString().toIntOrNull()
                    if (host.isBlank() || port == null || port !in 1..65535) {
                        Toast.makeText(this, "לא נמצא פורט. הזן כתובת IP ופורט חיבור תקין.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    KadbGateway.DiscoveredEndpoint(host, port, "הזנה ידנית")
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                discoveryText.text = "בודק חיבור והרשאת ADB…"
                scope.launch {
                    val success = connectToEndpoint(endpoint.host, endpoint.port, showErrors = false)
                    if (success) {
                        dialog.dismiss()
                        completePendingUninstall()
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        discoveryText.text = "החיבור נכשל. ודא שניפוי באגים אלחוטי פעיל ושהמכשיר מאשר את מפתח ADB."
                        showError("ההתחברות נכשלה", "בדוק שהמכשיר והאפליקציה באותה רשת Wi‑Fi ונסה לרענן את פורט החיבור.")
                    }
                }
            }
        }
        dialog.show()

        scope.launch {
            discovered = runCatching {
                adbGateway.discoverConnectEndpoint(
                    this@MainActivity,
                    prefs.getString(KEY_HOST, null),
                    timeoutMs = 8_000
                )
            }.getOrNull()
            if (discovered != null) {
                hostInput.setText(discovered!!.host)
                portInput.setText(discovered!!.port.toString())
                discoveryText.text = "נמצא פורט חיבור אוטומטית. לחץ על ״התחבר״."
            } else {
                discoveryText.text = "לא נמצא פורט אוטומטית. פתח את ניפוי הבאגים האלחוטי או הזן פרטים ידנית."
                hostInput.visibility = View.VISIBLE
                portInput.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun connectToEndpoint(
        host: String,
        port: Int,
        showErrors: Boolean = true
    ): Boolean {
        return try {
            val result = adbGateway.testConnection(host, port)
            check(result.exitCode == 0 && result.output.contains("APPVAULT_CONNECTED")) {
                result.output.ifBlank { "התקן Android לא החזיר תשובת חיבור." }
            }
            prefs.edit().putString(KEY_HOST, host).putInt(KEY_CONNECT_PORT, port).apply()
            adbConnected = true
            statusText.text = "מחובר לניפוי באגים אלחוטי · $host:$port"
            Toast.makeText(this, "החיבור למכשיר הצליח.", Toast.LENGTH_SHORT).show()
            true
        } catch (error: Throwable) {
            adbConnected = false
            statusText.text = "החיבור נכשל"
            if (showErrors) showError("ההתחברות נכשלה", error.message ?: "בדוק את הגדרות ניפוי הבאגים.")
            false
        }
    }

    private fun completePendingUninstall() {
        pendingUninstallPackage?.let { packageName ->
            pendingUninstallPackage = null
            val hadExport = pendingUninstallHasExport
            pendingUninstallHasExport = false
            val app = InstalledApps.load(this@MainActivity).firstOrNull { it.packageName == packageName }
            if (app != null) confirmPreservingUninstall(app, exportJustCompleted = hadExport)
        }
    }

    private fun openWirelessDebuggingSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (error: Throwable) {
            showError("לא ניתן לפתוח את הגדרות המפתחים", "פתח ידנית: הגדרות > מערכת > אפשרויות למפתחים > ניפוי באגים אלחוטי.")
        }
    }

    private fun installPendingBundle() {
        val bundle = pendingRestore ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("אישור התקנת אפליקציות")
                .setMessage("Android דורש לאשר לכספת אפליקציות להתקין קובצי APK. זהו אישור מערכת רגיל, ללא Root או Shizuku.")
                .setNegativeButton("ביטול") { _, _ ->
                    pendingRestore?.directory?.deleteRecursively()
                    pendingRestore = null
                }
                .setPositiveButton("פתיחת הגדרות Android") { _, _ ->
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
            statusText.text = "בקשת ההתקנה נשלחה ל־Android. יש לאשר את ההתקנה בחלון המערכת אם יוצג."
        } catch (error: InstallPermissionRequiredException) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (error: Throwable) {
            showError("לא ניתן להתחיל בהתקנה", error.message ?: "שגיאת מתקין החבילות")
            statusText.text = "לא ניתן להתחיל את ההתקנה"
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
        textDirection = View.TEXT_DIRECTION_LTR
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
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
            .setPositiveButton("אישור", null).show()
    }

    companion object {
        private const val REQUEST_EXPORT = 101
        private const val REQUEST_RESTORE = 102
        private const val KEY_HOST = "adb_host"
        private const val KEY_CONNECT_PORT = "adb_connect_port"
    }
}
