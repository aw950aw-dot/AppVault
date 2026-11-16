package com.appvault.android.backup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates and validates .avbackup archives. Archives contain APK/split APK files and
 * metadata only. They do NOT contain another app's private data directory.
 */
object BackupBundleManager {
    private const val FORMAT_VERSION = 1
    private const val MANIFEST_NAME = "appvault-manifest.json"
    private const val MAX_ARCHIVE_ENTRIES = 64
    private const val MAX_UNCOMPRESSED_BYTES = 3L * 1024L * 1024L * 1024L

    data class ApkFile(
        val entryName: String,
        val file: File,
        val kind: String,
        val splitName: String?
    )

    data class RestoredBundle(
        val packageName: String,
        val appLabel: String,
        val versionName: String,
        val versionCode: Long,
        val signerSha256: String,
        val directory: File,
        val apkFiles: List<ApkFile>
    )

    fun createBundle(context: Context, packageName: String): File {
        requirePackageName(packageName)
        val pm = context.packageManager
        val packageInfo = getPackageInfo(pm, packageName)
        val appInfo = packageInfo.applicationInfo ?: error("לא נמצאו פרטי אפליקציה עבור החבילה")

        val baseApk = File(appInfo.sourceDir)
        require(baseApk.isFile && baseApk.canRead()) {
            "Android לא אפשר לקרוא את קובץ ה־APK הראשי. ייתכן שהמכשיר מגביל גישה לקובצי התקנה."
        }

        val entries = mutableListOf<Triple<String, File, Pair<String, String?>>>()
        entries += Triple("base.apk", baseApk, "base" to null)

        val splitPaths = appInfo.splitSourceDirs ?: emptyArray()
        val splitNames = packageInfo.splitNames ?: emptyArray()
        splitPaths.forEachIndexed { index, path ->
            val splitFile = File(path)
            require(splitFile.isFile && splitFile.canRead()) {
                "לא ניתן לקרוא קובץ APK מפוצל ${splitFile.name}"
            }
            val splitName = splitNames.getOrNull(index) ?: "split_$index"
            val originalName = splitFile.name
            val safeName = if (isAllowedApkEntryName(originalName) && originalName != "base.apk") {
                originalName
            } else {
                val safeSplit = splitName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                "split_${index}_$safeSplit.apk"
            }
            require(entries.none { it.first == safeName }) { "שם קובץ APK מפוצל כפול: $safeName" }
            entries += Triple(safeName, splitFile, "split" to splitName)
        }

        val manifestFiles = JSONArray()
        entries.forEach { (entryName, file, role) ->
            manifestFiles.put(
                JSONObject()
                    .put("entry", entryName)
                    .put("kind", role.first)
                    .put("splitName", role.second ?: JSONObject.NULL)
                    .put("size", file.length())
                    .put("sha256", sha256(file))
            )
        }

        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        val manifest = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("packageName", packageName)
            .put("appLabel", appLabel)
            .put("versionName", packageInfo.versionName ?: "")
            .put("versionCode", packageInfo.longVersionCode)
            .put("signerSha256", signerFingerprint(packageInfo))
            .put("createdAt", System.currentTimeMillis())
            .put("files", manifestFiles)
            .put("dataIncluded", false)
            .put("dataNote", "Private app data is not contained in this archive. It can only be retained on the original device using pm uninstall -k before reinstalling the same package/signature.")

        val outputDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
        val output = File(outputDirectory, "$packageName-${System.currentTimeMillis()}.avbackup")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            entries.forEach { (entryName, sourceFile, _) ->
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(sourceFile).use { input -> input.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
        return output
    }

    fun extractAndValidate(context: Context, input: InputStream): RestoredBundle {
        val restoreRoot = File(context.filesDir, "restored-bundles").apply { mkdirs() }
        val directory = File(restoreRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            var manifestText: String? = null
            var totalBytes = 0L
            var count = 0
            val seenEntries = mutableSetOf<String>()
            val extracted = linkedMapOf<String, File>()

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ARCHIVE_ENTRIES) { "קובץ הגיבוי מכיל מספר גדול מדי של פריטים" }
                    require(!entry.isDirectory) { "קובץ הגיבוי מכיל תיקיות שאינן נתמכות" }
                    require(isAllowedEntryName(entry.name)) { "פריט לא בטוח בקובץ הגיבוי: ${entry.name}" }
                    require(seenEntries.add(entry.name)) { "פריט כפול בקובץ הגיבוי" }

                    if (entry.name == MANIFEST_NAME) {
                        val bytes = zip.readBounded(128 * 1024 + 1)
                        require(bytes.size <= 128 * 1024) { "קובץ פרטי הגיבוי גדול מהצפוי" }
                        manifestText = bytes.toString(Charsets.UTF_8)
                    } else {
                        val destination = File(directory, entry.name)
                        var written = 0L
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                written += read
                                totalBytes += read
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "קובץ הגיבוי גדול מדי" }
                                output.write(buffer, 0, read)
                            }
                        }
                        extracted[entry.name] = destination
                    }
                    zip.closeEntry()
                }
            }

            val manifest = JSONObject(manifestText ?: error("קובץ פרטי הגיבוי חסר"))
            require(manifest.optInt("formatVersion") == FORMAT_VERSION) { "תבנית קובץ הגיבוי אינה נתמכת" }
            val packageName = manifest.getString("packageName")
            requirePackageName(packageName)
            val expectedFiles = manifest.getJSONArray("files")
            require(expectedFiles.length() in 1..MAX_ARCHIVE_ENTRIES) { "רשימת קובצי APK אינה תקינה" }

            val apkFiles = mutableListOf<ApkFile>()
            val listedEntries = mutableSetOf<String>()
            for (index in 0 until expectedFiles.length()) {
                val item = expectedFiles.getJSONObject(index)
                val entryName = item.getString("entry")
                require(isAllowedApkEntryName(entryName)) { "שם קובץ APK אינו תקין" }
                require(listedEntries.add(entryName)) { "קובץ APK מופיע יותר מפעם אחת בפרטי הגיבוי" }
                val file = extracted[entryName] ?: error("קובץ APK חסר: $entryName")
                require(file.length() == item.getLong("size")) { "בדיקת גודל קובץ APK נכשלה: $entryName" }
                require(sha256(file).equals(item.getString("sha256"), ignoreCase = true)) {
                    "בדיקת תקינות קובץ APK נכשלה: $entryName"
                }
                val kind = item.getString("kind")
                require(kind == "base" || kind == "split") { "סוג קובץ APK אינו תקין" }
                val splitName = if (item.isNull("splitName")) null else item.getString("splitName")
                if (kind == "base") {
                    require(entryName == "base.apk" && splitName == null) { "פרטי קובץ ה־APK הראשי אינם תקינים" }
                } else {
                    require(splitName != null) { "שם קובץ APK מפוצל חסר" }
                }
                apkFiles += ApkFile(entryName, file, kind, splitName)
            }

            require(apkFiles.count { it.kind == "base" } == 1 && apkFiles.any { it.entryName == "base.apk" }) {
                "נדרש קובץ APK ראשי אחד בדיוק"
            }
            require(apkFiles.size == extracted.size && listedEntries == extracted.keys) {
                "קובץ הגיבוי מכיל קובצי APK שלא מופיעים ברשימה"
            }

            val base = apkFiles.first { it.kind == "base" }.file
            val archiveInfo = getArchivePackageInfo(context.packageManager, base)
                ?: error("Android לא הצליח לקרוא את קובץ ה־APK הראשי")
            require(archiveInfo.packageName == packageName) { "שם החבילה בפרטי הגיבוי אינו תואם לקובץ ה־APK" }

            val signerSha256 = manifest.getString("signerSha256")
            require(signerFingerprint(archiveInfo).equals(signerSha256, ignoreCase = true)) {
                "חתימת קובץ ה־APK אינה תואמת לפרטי הגיבוי"
            }

            return RestoredBundle(
                packageName = packageName,
                appLabel = manifest.optString("appLabel", packageName),
                versionName = manifest.optString("versionName", ""),
                versionCode = manifest.optLong("versionCode"),
                signerSha256 = signerSha256,
                directory = directory,
                apkFiles = apkFiles.sortedWith(compareBy<ApkFile> { if (it.kind == "base") 0 else 1 }.thenBy { it.entryName })
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun copyBundleToUri(context: Context, bundle: File, uri: android.net.Uri) {
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("לא ניתן לפתוח את יעד שמירת הגיבוי שנבחר")
        output.use { stream -> FileInputStream(bundle).use { it.copyTo(stream) } }
    }

    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun getArchivePackageInfo(pm: PackageManager, apk: File): PackageInfo? {
        @Suppress("DEPRECATION")
        return pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun signerFingerprint(info: PackageInfo): String {
        val signers = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }.orEmpty()
        require(signers.isNotEmpty()) { "לא ניתן לקרוא את תעודת החתימה של קובץ ה־APK" }
        return signers.sorted().joinToString(":")
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun isAllowedEntryName(name: String): Boolean =
        name == MANIFEST_NAME || isAllowedApkEntryName(name)

    internal fun isAllowedApkEntryName(name: String): Boolean =
        name.matches(Regex("(?:base\\.apk|split_[A-Za-z0-9_.-]+\\.apk)"))

    private fun requirePackageName(packageName: String) {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "שם חבילה לא תקין"
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > maxBytes) break
        }
        return output.toByteArray()
    }
}
