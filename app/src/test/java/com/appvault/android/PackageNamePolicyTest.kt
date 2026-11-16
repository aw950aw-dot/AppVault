package com.appvault.android

import com.appvault.android.adb.KadbGateway
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageNamePolicyTest {
    @Test fun acceptsOrdinaryPackageNames() {
        KadbGateway.requireValidPackageName("com.example.notes")
        KadbGateway.requireValidPackageName("org.mozilla.firefox")
    }

    @Test fun rejectsShellMetacharactersAndPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            KadbGateway.requireValidPackageName("com.example.app; rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KadbGateway.requireValidPackageName("../example")
        }
    }
}
