package com.appvault.android.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.FileInputStream

/** Installs all APKs in a bundle through Android's own package installer UI. */
object ApkInstaller {
    fun install(context: Context, bundle: BackupBundleManager.RestoredBundle) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            throw InstallPermissionRequiredException()
        }

        val installer = context.packageManager.packageInstaller
        val totalSize = bundle.apkFiles.sumOf { it.file.length() }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(bundle.packageName)
                setSize(totalSize)
            }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                bundle.apkFiles.forEach { apk ->
                    FileInputStream(apk.file).use { input ->
                        session.openWrite(apk.entryName, 0, apk.file.length()).use { output ->
                            input.copyTo(output, 64 * 1024)
                            session.fsync(output)
                        }
                    }
                }

                val callbackIntent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction(InstallStatusReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, bundle.packageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val callback = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
                session.commit(callback.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }

        // PackageInstaller has copied the session data; this temporary extraction is no longer needed.
        bundle.directory.deleteRecursively()
    }
}

class InstallPermissionRequiredException : Exception(
    "יש לאשר לכספת אפליקציות להתקין אפליקציות בהגדרות Android, ואז לנסות שחזור שוב."
)
