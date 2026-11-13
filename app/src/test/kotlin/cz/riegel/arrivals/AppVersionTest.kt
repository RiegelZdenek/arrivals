package cz.riegel.arrivals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.riegel.arrivals.domain.ReleaseVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppVersionTest {
    @Test
    fun packageInfo_containsValidVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertNotNull(packageInfo.versionName)
        assertTrue("versionName should not be empty", !packageInfo.versionName.isNullOrEmpty())
        @Suppress("DEPRECATION")
        assertTrue("versionCode should be positive", packageInfo.versionCode > 0)
    }

    @Test
    fun calculateVersionCode_computesCorrectFormula() {
        // (MAJOR * 10000) + (MINOR * 100) + PATCH
        assertEquals(10203, ReleaseVersion.calculateVersionCode(1, 2, 3))
        assertEquals(100, ReleaseVersion.calculateVersionCode(0, 1, 0))
        assertEquals(0, ReleaseVersion.calculateVersionCode(0, 0, 0))
        assertEquals(21542, ReleaseVersion.calculateVersionCode(2, 15, 42))
        assertEquals(109999, ReleaseVersion.calculateVersionCode(10, 99, 99))
    }

    @Test
    fun calculateVersionCode_validatesBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.calculateVersionCode(-1, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.calculateVersionCode(1, 100, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.calculateVersionCode(1, 0, 100)
        }
    }

    @Test
    fun parseSemVer_handlesStandardAndPrefixedTags() {
        assertEquals(Triple(1, 2, 3), ReleaseVersion.parseSemVer("v1.2.3"))
        assertEquals(Triple(1, 2, 3), ReleaseVersion.parseSemVer("1.2.3"))
        assertEquals(Triple(0, 1, 0), ReleaseVersion.parseSemVer("v0.1.0"))
        assertEquals(Triple(10, 5, 12), ReleaseVersion.parseSemVer("v10.5.12"))
    }

    @Test
    fun parseSemVer_rejectsInvalidFormats() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.parseSemVer("invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.parseSemVer("v1.2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.parseSemVer("v1.2.3.4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVersion.parseSemVer("v1.2.beta")
        }
    }

    @Test
    fun deriveVersionMetadata_producesConsistentNameAndCode() {
        val (versionName, versionCode) = ReleaseVersion.deriveVersionMetadata("v1.2.3")
        assertEquals("1.2.3", versionName)
        assertEquals(10203, versionCode)
    }
}
