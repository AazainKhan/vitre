package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P288: a focused round button says its name. The rounds are icons only, so the caption under them is the only thing
 * that tells you what one does; every Round wired its onLabel into a state nothing read, so the caption never showed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcRoundCaptionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun caption(): String =
        compose.onNodeWithTag("cc-round-label").fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.joinToString(" ")?.trim().orEmpty()

    /**
     * Opens Control Center and walks focus to the Theme round button. Wi-Fi is a 2x1 pill (no caption), so the test
     * needs a 1x1 round: "appearance" is the last tile on the default page and draws as one.
     */
    private fun openCcWithWiFiFocused() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // Down to the round row, then across to Theme: the pills above never take a caption (they show their
            // own title), so only a 1x1 round proves it.
            compose.press(Button.Down, Button.Down)
            compose.settle()
            repeat(6) {
                if (!compose.focused().orEmpty().contains("appearance")) { compose.press(Button.Right); compose.settle() }
            }
        }
    }

    @Test fun theFocusedRoundButtonShowsItsName() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // Down to the round row, then across to Theme: the pills above never take a caption (they show their
            // own title), so only a 1x1 round proves it.
            compose.stepFocus(Button.Down); compose.stepFocus(Button.Down)
            compose.settle()
            var steps = 0
            while (!compose.focused().orEmpty().contains("appearance") && steps++ < 12) compose.stepFocus(Button.Right)
            compose.awaitUntil { caption().startsWith("Theme") }
            assertTrue("the focused round button's name must be on screen, was \"${caption()}\"", caption().startsWith("Theme"))
            // Moving off it moves the caption, rather than leaving the old one behind.
            compose.stepFocus(Button.Right)
            compose.awaitUntil { !exists("cc-round-label") || !caption().startsWith("Theme") }
            val after = if (exists("cc-round-label")) caption() else ""
            assertFalse("the caption must follow focus, not stick to Theme", after.startsWith("Theme"))
        }
    }

    @Test fun theCaptionIsHiddenWhileEditing() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            dev.glasslauncher.home.CcEditEntry.startInEdit = true
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            compose.awaitUntil { exists("cc-add") }
            assertTrue("edit mode is on", exists("cc-add"))
            // The edit hints own the bottom there, so the caption stays out of the way.
            assertEquals("no caption in edit mode", false, exists("cc-round-label"))
        }
    }
}
