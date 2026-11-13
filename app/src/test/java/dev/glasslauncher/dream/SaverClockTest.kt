package dev.glasslauncher.dream

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.data.ScreensaverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** P338: every screensaver shows the Aerials clock, flat, top right; P337: the Now Playing card keeps clear of it and of the Aerials location. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SaverClockTest {
    @get:org.junit.Rule val pinnedClock = dev.glasslauncher.shots.PinnedClockRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun clockOf(v: FrameLayout) = (0 until v.childCount).map { v.getChildAt(it) }.filterIsInstance<SaverClock>().single()

    @Test fun anotherSaverGetsTheSameClockWithNoPill() {
        dev.glasslauncher.shots.TvHarness.pinClockForTest()
        val saver = ClockOverSaver(context, View(context), ScreensaverConfig())
        saver.start(); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val clock = clockOf(saver)
        assertEquals(View.VISIBLE, clock.visibility)
        assertEquals("9:41 AM", clock.text.toString())
        assertNull("flat text: no pill behind it", clock.background)
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.END, (clock.layoutParams as FrameLayout.LayoutParams).gravity)
        saver.stop()
        assertEquals("stopped: gone and silent", View.GONE, clock.visibility)
    }

    @Test fun everySaverFollowsTheLaunchersTwentyFourHourSetting() {
        dev.glasslauncher.shots.TvHarness.pinClockForTest()
        val saver = ClockOverSaver(context, View(context), ScreensaverConfig(), h24 = true)
        saver.start(); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals("9:41", clockOf(saver).text.toString())
        saver.stop()
    }

    @Test fun showClockOffHidesIt() {
        val saver = ClockOverSaver(context, View(context), ScreensaverConfig(showClock = false))
        saver.start(); shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(View.GONE, clockOf(saver).visibility)
        saver.stop()
    }

    @Test fun theCardLivesInTheBottomRightAndMovesOnlyToTheTopLeft() {
        val card = NowPlayingCardView(context)
        val inner = card.getChildAt(0)
        val first = (inner.layoutParams as FrameLayout.LayoutParams).gravity
        assertEquals("bottom right: the clock is top right, the Aerials location bottom left", android.view.Gravity.BOTTOM or android.view.Gravity.END, first)
        card.start()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(6, TimeUnit.MINUTES)
        assertEquals("the burn-in move goes to the opposite corner", android.view.Gravity.TOP or android.view.Gravity.START, (inner.layoutParams as FrameLayout.LayoutParams).gravity)
        card.stop()
    }
}
