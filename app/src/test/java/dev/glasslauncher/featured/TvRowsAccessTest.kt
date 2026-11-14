package dev.glasslauncher.featured

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P339: the plain APK reads Watch Next with READ_TV_LISTINGS alone; other apps' channels still need the system-app access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvRowsAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun watchNextNeedsEitherPermissionAndChannelsNeedTheFullOne() {
        assertFalse(TvRows.watchNextAvailable(app))
        shadowOf(app).grantPermissions(TvRows.LISTINGS)
        assertTrue("READ_TV_LISTINGS is enough for Continue Watching", TvRows.watchNextAvailable(app))
        assertFalse("but not for other apps' rows", TvRows.available(app))
        shadowOf(app).grantPermissions("com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA")
        assertTrue(TvRows.available(app))
    }
}
