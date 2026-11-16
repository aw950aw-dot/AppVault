package com.appvault.android.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** A user-installed package that can be exported by App Vault. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val applicationInfo: ApplicationInfo
)

object InstalledApps {
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
        }

        return packages.mapNotNull { info: PackageInfo ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(info.packageName)
            InstalledApp(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName ?: "—",
                versionCode = info.longVersionCode,
                applicationInfo = appInfo
            )
        }.sortedBy { it.label.lowercase() }
    }
}
