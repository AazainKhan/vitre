package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P280: Glass can open Fire OS's own quick menu. The panel activity is `exported=false`, but it starts anyway, so the
 * reliable way into it is a row here rather than the quick-menu DB row (which an OTA wipes).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class QuickMenuEntryTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun texts(): List<String> = compose.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("has text") {
            it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
        },
    ).fetchSemanticsNodes().flatMap {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.map { t -> t.text } ?: emptyList()
    }

    /**
     * The Fire TV settings page (Control Center's tile) offers the quick menu, first.
     *
     * It is the page's first row, so it takes the initial focus - which is also the assertion: if the row were missing
     * the page would open on a section instead.
     */
    @Test fun theFireTvSettingsPageOffersTheQuickMenu() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            // Walk to the Fire TV settings tile; RequestFocus is not on the parked tiles.
            var steps = 0
            while (compose.focused()?.contains("fire_settings") != true && steps < 30) {
                compose.stepFocus(Button.Right)
                steps++
            }
            assertTrue("the Fire TV settings tile must be reachable (focus=${compose.focused()})", compose.focused()?.contains("fire_settings") == true)
            compose.press(Button.Select)
            // The page opens on its own time (a real-time wait: a fixed one loses when the machine is busy).
            compose.awaitUntil { texts().any { it.contains("Quick Menu") } && compose.focused()?.contains("Quick Menu") == true }
            assertTrue("the page must offer the Quick Menu", texts().any { it.contains("Quick Menu") })
            assertTrue("and it is the first row (focus=${compose.focused()})", compose.focused()?.contains("Quick Menu") == true)
        }
    }
}
