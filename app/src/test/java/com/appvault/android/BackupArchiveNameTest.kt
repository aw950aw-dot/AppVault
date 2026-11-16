package com.appvault.android

import com.appvault.android.backup.BackupBundleManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveNameTest {
    @Test fun acceptsOnlyExpectedApkNames() {
        assertTrue(BackupBundleManager.isAllowedEntryName("appvault-manifest.json"))
        assertTrue(BackupBundleManager.isAllowedEntryName("base.apk"))
        assertTrue(BackupBundleManager.isAllowedEntryName("split_config.arm64_v8a.apk"))
        assertFalse(BackupBundleManager.isAllowedEntryName("../base.apk"))
        assertFalse(BackupBundleManager.isAllowedEntryName("/data/user/0/app/db"))
        assertFalse(BackupBundleManager.isAllowedEntryName("metadata.json"))
        assertFalse(BackupBundleManager.isAllowedEntryName("split_config.apk/../bad"))
    }
}
