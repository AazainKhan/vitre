package dev.glasslauncher.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home switch against Fire OS 8's HOME list (P341 probe #2): the HOME intent belongs to com.amazon.firehomestarter, which starts the launcher; the
 * launcher itself has no HOME activity; the only other candidate is the settings app's FallbackHome at priority -1000. A fake shell models that list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeSwitchFireTest {
    private val vitre = "dev.glasslauncher"
    private val starter = "com.amazon.firehomestarter"
    private val launcher = "com.amazon.tv.launcher"
    private val settings = "com.amazon.tv.settings.v2"

    private inner class FireTv(val ignoreSetHome: Boolean = false, val refuse: Set<String> = emptySet()) : TvShell {
        val installed = mutableSetOf(vitre, starter, launcher, settings)
        val disabled = mutableSetOf<String>()
        val log = mutableListOf<String>()
        // The HOME activities: the starter's (priority 0), VITRE's (0) and the settings app's FallbackHome (-1000). The launcher has none.
        private val activity = linkedMapOf(starter to "$starter/.HomeStarter", vitre to "$vitre/.MainActivity", settings to "$settings/.system.FallbackHome")
        private val priority = mapOf(starter to 0, vitre to 0, settings to -1000)
        var preferred: String = activity.getValue(starter)

        private fun active(p: String) = p in installed && p !in disabled
        /** Who answers HOME: the preferred activity if its package is on, else the best-priority one that is. */
        fun home(): String = preferred.takeIf { active(it.substringBefore('/')) }
            ?: activity.filterKeys(::active).entries.maxByOrNull { priority.getValue(it.key) }!!.value

        override fun run(command: String): String {
            log += command
            return when {
                command.startsWith("pm grant") -> ""
                command.startsWith("pm list packages -d") -> disabled.joinToString("\n") { "package:$it" }
                command.startsWith("pm list packages") -> installed.joinToString("\n") { "package:$it" }
                command.startsWith("cmd role get-role-holders") -> "Unknown command: get-role-holders"
                command.startsWith("cmd package resolve-activity") -> {
                    val named = installed.firstOrNull { command.contains(" $it ") || command.contains("HOME $it") }
                    if (named == null) home() else activity[named]?.takeIf { active(named) } ?: "No activity found"
                }
                command.startsWith("dumpsys wifi") -> "Wi-Fi is enabled"
                command.startsWith("settings get global wifi_on") -> "1"
                command.startsWith("for p in") -> command.substringAfter("for p in ").substringBefore(';').split(' ').joinToString("\n") { "- $it" } + "\nchecked"
                command.startsWith("cmd package set-home-activity") -> {
                    val c = command.substringAfter("set-home-activity ").trim('\'')
                    when {
                        ignoreSetHome -> "Success: activity set"                       // Success that changes nothing, as reported on Fire OS 8
                        c in activity.values && active(c.substringBefore('/')) -> { preferred = c; "Success: activity set" }
                        else -> "Error: Failed to set default home."                   // what the launcher got (it has no HOME activity)
                    }
                }
                command.startsWith("pm disable-user --user 0 ") -> command.substringAfterLast(' ').let { if (it in refuse) "Exception: Shell cannot change component state" else { disabled += it; "Package $it new state: disabled-user" } }
                command.startsWith("pm enable ") -> command.substringAfterLast(' ').let { disabled -= it; "Package $it new state: enabled" }
                else -> ""
            }
        }
    }

    private fun engine(): SwitchEngine {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // The stock homes the system reports: the starter (the HOME holder) and the FallbackHome's app, which the engine must refuse to touch.
        return SwitchEngine(vitre, true, listOf(starter, settings), ctx.getSharedPreferences("homeswitch-fire-${System.nanoTime()}", 0))
    }

    @Test fun bothFireHomePackagesGoOffAndVitreWinsOverTheFallbackHome() {
        val tv = FireTv(); val e = engine()
        assertEquals("before: the starter answers HOME", "$starter/.HomeStarter", tv.home())
        val r = e.switchOn(tv)
        assertTrue(r.message, r.ok)
        assertFalse("VITRE took Home, no guard needed", r.needsGuard)
        assertEquals("the starter and the launcher are both off", setOf(starter, launcher), tv.disabled)
        assertFalse("the settings app (FallbackHome's package) is never disabled", tv.log.any { it.startsWith("pm disable") && it.contains(settings) })
        assertEquals("VITRE, not the priority -1000 FallbackHome, answers HOME", "$vitre/.MainActivity", tv.home())
    }

    @Test fun theFallbackHomeNeverBeatsVitreBecauseItIsPriorityMinus1000() {
        // Even with set-home-activity ignored (Success that changes nothing) and the starter off, VITRE's priority 0 outranks FallbackHome's -1000.
        val tv = FireTv(ignoreSetHome = true); val e = engine()
        val r = e.switchOn(tv)
        assertEquals("$vitre/.MainActivity", tv.home())
        assertFalse(r.message, r.needsGuard)
    }

    @Test fun ifFireKeepsTheStarterOnAndSetHomeDoesNothingTheGuardIsAskedFor() {
        // The starter refuses to go off and set-home-activity says Success without effect: the starter still answers HOME, and the engine says so.
        val tv = FireTv(ignoreSetHome = true, refuse = setOf(starter)); val e = engine()
        val r = e.switchOn(tv)
        assertEquals("$starter/.HomeStarter", tv.home())
        assertTrue(r.message, r.needsGuard)
        assertTrue("the message names who answers HOME", r.message.contains(starter))
    }

    @Test fun undoTurnsBothBackOnAndHandsHomeBackToTheStarter() {
        val tv = FireTv(); val e = engine()
        e.switchOn(tv)
        val back = e.restore(tv)
        assertTrue(back.message, back.ok)
        assertTrue("both are enabled again", tv.disabled.isEmpty())
        assertEquals("Home is the starter's again", "$starter/.HomeStarter", tv.home())
        assertTrue("the starter is enabled before the launcher", tv.log.indexOf("pm enable $starter") < tv.log.indexOf("pm enable $launcher"))
    }
}
