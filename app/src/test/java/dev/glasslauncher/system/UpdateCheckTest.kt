package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P340: when the weekly check may run, and what must match before an update is installed. */
class UpdateCheckTest {
    private val week = UpdateCheck.EVERY_MS

    @Test fun aCheckIsDueOnceAWeekHasPassedOrNeverRan() {
        assertTrue(UpdateCheck.due(0, 1_000))
        assertFalse(UpdateCheck.due(1_000, 1_000 + week - 1))
        assertTrue(UpdateCheck.due(1_000, 1_000 + week))
        assertTrue("a clock set back is due, not never", UpdateCheck.due(5_000, 1_000))
    }

    @Test fun theTimerIsTheRemainingWait() {
        assertEquals(0L, UpdateCheck.untilDue(0, 5))
        assertEquals(1_000L, UpdateCheck.untilDue(10, 10 + week - 1_000))
    }

    @Test fun itRunsOnlyOnAQuietHomeWithNoMusic() {
        val q = UpdateCheck.QUIET_MS
        assertTrue(UpdateCheck.mayRun(true, q, false))
        assertFalse("another app is in front", UpdateCheck.mayRun(false, q * 5, false))
        assertFalse("music is playing", UpdateCheck.mayRun(true, q * 5, true))
        assertFalse("a key was just pressed", UpdateCheck.mayRun(true, q - 1, false))
    }

    @Test fun anUpdateMustBeSignedByTheSameKeyAndHaveTheListedSize() {
        assertTrue(UpdateCheck.sameSigner(setOf("a"), setOf("a")))
        assertFalse(UpdateCheck.sameSigner(setOf("a"), setOf("b")))
        assertFalse("an unreadable installed signer never matches", UpdateCheck.sameSigner(emptySet(), emptySet()))
        assertFalse("an extra signer is a different identity", UpdateCheck.sameSigner(setOf("a"), setOf("a", "b")))
        assertTrue(UpdateCheck.sizeOk(100, 100))
        assertFalse(UpdateCheck.sizeOk(100, 99))
        assertTrue("no size listed: nothing to compare", UpdateCheck.sizeOk(null, 99))
    }
}
