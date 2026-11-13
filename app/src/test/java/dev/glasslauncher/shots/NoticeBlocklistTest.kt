package dev.glasslauncher.shots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P98: the notifications blocklist page. Every app that can show on Home is listed and switched on until silenced,
 * because the useful question is "what is noisy" - and an app that has never posted looks exactly like one that has.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class NoticeBlocklistTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * The switch state of the row named [label], or null when it is not an on/off row.
     *
     * Matched on the content description, not the text: an on/off row puts its label and its state in one
     * `semantics` block on the row itself, and the Text inside is a separate, stateless node.
     */
    private fun stateOf(label: String): ToggleableState? {
        val nodes = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes()
        // Two nodes can carry the label: the dock tile on the Home behind Settings, and the row itself. The row is
        // the one that is a switch.
        return nodes.firstOrNull { it.config.contains(SemanticsProperties.ToggleableState) }
            ?.config?.getOrElseNullable(SemanticsProperties.ToggleableState) { null }
    }

    /** Walk Down until the focused row's text contains [text]; returns whether it got there. */
    /**
     * Wait for a row's state, in REAL time.
     *
     * `mainClock.advanceTimeBy` moves virtual time instantly, but the config write goes through DataStore on a real
     * dispatcher - so a poll on the virtual clock finishes before the write lands and reads the old value. It passed
     * alone and failed under the gates run for exactly that reason.
     */
    private fun awaitState(label: String, want: ToggleableState, timeoutMs: Long = 10_000): ToggleableState? {
        runCatching { compose.waitUntil(timeoutMs) { stateOf(label) == want } }
        return stateOf(label)
    }

    private fun walkTo(text: String, max: Int = 40): Boolean {
        var steps = 0
        while (compose.focused()?.contains(text) != true && steps < max) {
            compose.stepFocus(Button.Down)
            steps++
        }
        return compose.focused()?.contains(text) == true
    }

    /** Home -> Settings -> down to the Notifications row -> in. */
    private fun openNotificationsPage() {
        compose.waitForHome()
        compose.focusTag("settings-tile")
        compose.press(Button.Select)
        compose.waitForIdle()
        assertTrue("Settings must offer a Notifications row", walkTo("Notifications"))
        compose.press(Button.Select)
        compose.waitForIdle()
    }

    @Test fun everyAppIsListedAndOnUntilSilenced() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openNotificationsPage()
            assertTrue("the app list must be on screen", walkTo("Netflix"))
            assertEquals("apps are on until silenced", ToggleableState.On, stateOf("Netflix"))
        }
    }

    @Test fun switchingAnAppOffIsRememberedOnTheRow() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openNotificationsPage()
            assertTrue("Netflix must be listed", walkTo("Netflix"))
            assertEquals("the row must start on", ToggleableState.On, stateOf("Netflix"))
            compose.press(Button.Select)
            assertEquals("Select must silence that app", ToggleableState.Off, awaitState("Netflix", ToggleableState.Off))
            // The row is rebuilt when the write lands; focus is back on it once the page has settled.
            compose.awaitUntil { compose.focused()?.contains("Netflix") == true }
            assertTrue("focus stays on the row after a toggle", compose.focused()?.contains("Netflix") == true)
            // And another app is untouched, so it is a per-app switch and not a whole-page one.
            assertEquals(ToggleableState.On, stateOf("YouTube"))
        }
    }
}
