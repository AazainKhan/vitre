package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P196 (tvOS): in Control Center's header the time, the weather and the page icons share one midline. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcHeaderLineTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun centre(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.let { (it.top + it.bottom) / 2f / compose.density.density }

    @Test fun theTimeAndThePageIconsSitOnOneMidline() {
        TvHarness.setUp()
        TvHarness.installAlexa()   // a second page, so the page icons show
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            // The header lays out while Control Center grows in: measure once the three have stopped moving, not after a fixed time.
            compose.awaitStable { Triple(centre("cc-clock"), centre("cc-page-icon:0"), centre("cc-page-icon:1")) }
            val clock = centre("cc-clock")
            val icon = centre("cc-page-icon:0")
            assertEquals("the time and the first page icon share a vertical centre (dp)", icon, clock, 1f)
            assertEquals("and every page icon shares it", icon, centre("cc-page-icon:1"), 1f)
            if (compose.onAllNodes(hasTestTag("weather"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                assertEquals("the weather joins them", icon, centre("weather"), 1f)
            assertTrue("the icons are really there", icon > 0f)
        }
    }
}
