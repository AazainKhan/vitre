package dev.glasslauncher.system

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.data.FeaturedConfig
import dev.glasslauncher.data.FeaturedSourceId
import dev.glasslauncher.featured.FeaturedAuthExpired
import dev.glasslauncher.featured.Sources
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** P214: the quiet failures each say so once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SilentFailuresTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val probe by lazy { NoticeProbe(context) }

    @Before fun clean() {
        context.getSharedPreferences("notices", Context.MODE_PRIVATE).edit().clear().commit()
        probe.reset()
        RemoteNotice.overlayHostForTest = probe.host
    }

    @org.junit.After fun uninstall() { RemoteNotice.overlayHostForTest = null }

    @Test fun aNoticeIsDueTheFirstTimeThenOncePerPeriod() {
        assertTrue(Notices.due(0L, 1_000L, Notices.DAY_MS))
        assertFalse(Notices.due(1_000L, 2_000L, Notices.DAY_MS))
        assertTrue(Notices.due(1_000L, 1_000L + Notices.DAY_MS, Notices.DAY_MS))
        assertTrue("a clock set back does not silence it for a day", Notices.due(5_000L, 1_000L, Notices.DAY_MS))
    }

    @Test fun dueNowRemembersWhatItAnswered() {
        assertTrue(Notices.dueNow(context, "k", Notices.DAY_MS, now = 10_000L))
        assertFalse(Notices.dueNow(context, "k", Notices.DAY_MS, now = 20_000L))
        assertTrue("another key", Notices.dueNow(context, "other", Notices.DAY_MS, now = 20_000L))
    }

    @Test fun storageIsLowBelowHalfAGigabyteAndUnknownIsNeverLow() {
        assertTrue(StorageWatch.low(100L * 1024 * 1024))
        assertFalse(StorageWatch.low(StorageWatch.LOW_BYTES))
        assertFalse(StorageWatch.low(8L * 1024 * 1024 * 1024))
        assertFalse("unreadable (-1)", StorageWatch.low(-1L))
        assertEquals("Storage is almost full: 120 MB left. Remove apps or downloads to free space.", StorageWatch.line(120L * 1024 * 1024))
    }

    @Test fun onlyACrashOrAnAnrThatJustHappenedIsReported() {
        val now = 1_000_000_000L
        assertTrue(CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_CRASH, now - 5_000, lastSeen = 0, now = now))
        assertTrue(CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_ANR, now - 5_000, 0, now))
        assertTrue(CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 5_000, 0, now))
        assertFalse("the user closed it", CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_USER_REQUESTED, now - 5_000, 0, now))
        assertFalse("a cached process the system trimmed", CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_LOW_MEMORY, now - 5_000, 0, now))
        assertFalse("already reported", CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_CRASH, now - 5_000, lastSeen = now - 5_000, now = now))
        assertFalse("long ago (a reboot since)", CrashRecovery.shouldNotify(ApplicationExitInfo.REASON_CRASH, now - 30 * 60_000, 0, now))
    }

    @Test fun anExpiredFeedSignInIsToldOncePerDay() {
        CredentialNotice.expired(context, FeaturedSourceId.Plex)
        ShadowLooper.idleMainLooper()
        assertEquals("Plex rejected its saved sign-in. Sign in again in Settings.", probe.latest())
        probe.reset()
        CredentialNotice.expired(context, FeaturedSourceId.Plex)
        ShadowLooper.idleMainLooper()
        assertNull("the same source again today", probe.latestOrNull())
        ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.SECONDS) // the first card is still up; notices show one at a time
        CredentialNotice.expired(context, FeaturedSourceId.Tmdb)
        ShadowLooper.idleMainLooper()
        assertEquals("TMDB rejected its saved sign-in. Sign in again in Settings.", probe.latest())
    }

    @Test fun aRefusedPlexTokenIsAnAuthFailureNotAnEmptyShelf() {
        val refuse = Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("".toResponseBody(null)).build()
        }
        val http = OkHttpClient.Builder().addInterceptor(refuse).build()
        val result = runCatching { runBlocking { Sources.of(FeaturedSourceId.Plex)!!.load(http, FeaturedConfig(source = FeaturedSourceId.Plex, plexToken = "stale", plexClientId = "x")) } }
        assertTrue("was ${result.exceptionOrNull()}", result.exceptionOrNull() is FeaturedAuthExpired)
    }

    private val seen get() = context.getSharedPreferences("notices", Context.MODE_PRIVATE).getLong("exit-seen", 0L)
    private val pending get() = context.getSharedPreferences("notices", Context.MODE_PRIVATE).getLong("exit-pending", 0L)

    @Test fun aCrashAtStartIsHeldUntilHomeIsUpAndOnlyThenMarkedSeen() {
        val now = System.currentTimeMillis()
        CrashRecovery.record(context, ApplicationExitInfo.REASON_CRASH, now - 3_000, now)
        assertEquals("kept, not shown, not consumed", now - 3_000, pending)
        assertEquals(0L, seen)
        ShadowLooper.idleMainLooper()
        assertNull("nothing shown at start", probe.latestOrNull())
        CrashRecovery.showPending(context)
        ShadowLooper.idleMainLooper()
        assertEquals("VITRE restarted after it stopped unexpectedly.", probe.latest())
        assertEquals("now it is seen", now - 3_000, seen)
        assertEquals(0L, pending)
        probe.reset()
        CrashRecovery.showPending(context)
        ShadowLooper.idleMainLooper()
        assertNull("once", probe.latestOrNull())
    }

    @Test fun aQuietExitOrAnOldCrashIsConsumedAndNeverShown() {
        val now = System.currentTimeMillis()
        CrashRecovery.record(context, ApplicationExitInfo.REASON_USER_REQUESTED, now - 3_000, now)
        assertEquals(now - 3_000, seen)
        assertEquals(0L, pending)
        CrashRecovery.record(context, ApplicationExitInfo.REASON_CRASH, now - 60 * 60_000, now)
        assertEquals("older than what was seen: ignored", now - 3_000, seen)
        CrashRecovery.showPending(context)
        ShadowLooper.idleMainLooper()
        assertNull(probe.latestOrNull())
    }

    private fun doNotDisturb(on: Boolean) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        org.robolectric.Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_NONE else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test fun showSaysWhichPathTookTheNotice() {
        assertEquals("no service up here: the overlay window", RemoteNotice.Outcome.Overlay, RemoteNotice.show(context, "hello"))
        doNotDisturb(true)
        assertEquals("a plain notice under Do Not Disturb", RemoteNotice.Outcome.Suppressed, RemoteNotice.show(context, "hello"))
        assertEquals("an actionable one still shows", RemoteNotice.Outcome.Overlay, RemoteNotice.show(context, "help", actionable = true))
    }

    @Test fun aCrashNoticeHeldBackByDoNotDisturbIsKeptForTheNextResume() {
        val now = System.currentTimeMillis()
        CrashRecovery.record(context, ApplicationExitInfo.REASON_CRASH, now - 3_000, now)
        doNotDisturb(true)
        CrashRecovery.showPending(context)
        assertEquals("not shown, so not seen", 0L, seen)
        assertEquals(now - 3_000, pending)
        doNotDisturb(false)
        CrashRecovery.showPending(context)
        ShadowLooper.idleMainLooper()
        assertEquals("VITRE restarted after it stopped unexpectedly.", probe.latest())
        assertEquals(now - 3_000, seen)
        assertEquals(0L, pending)
    }
}
