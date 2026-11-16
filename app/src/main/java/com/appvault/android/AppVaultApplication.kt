package com.appvault.android

import android.app.Application
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File

/**
 * Configures a persistent ADB host identity. The private key is stored only in this
 * app's internal files directory; Android's app sandbox protects it from other apps.
 * Android backup is disabled in the manifest so the ADB identity is not copied to cloud backup.
 */
class AppVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val adbDirectory = File(filesDir, "adb").apply { mkdirs() }
        val privateKeyFile = File(adbDirectory, "host_private_key.pem")

        KadbCert.configure(
            store = OkioFilePrivateKeyStore(privateKeyPath = privateKeyFile.absolutePath.toPath()),
            policy = KadbCertPolicy(),
            additionalPrivateKeysPem = emptyList()
        )
        KadbCert.ensureReady()
    }
}
