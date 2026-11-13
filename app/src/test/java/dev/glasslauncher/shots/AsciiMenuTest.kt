package dev.glasslauncher.shots

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.dream.AsciiTestPacks
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** P327: Settings › Screen Saver › ASCII Art lists the categories of the cached catalogue, then a category's pieces. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class AsciiMenuTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun texts(): List<String> = compose.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("has text") { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) },
    ).fetchSemanticsNodes().flatMap {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.map { t -> t.text } ?: emptyList()
    }

    private fun walkTo(text: String, max: Int = 40): Boolean {
        var steps = 0
        while (compose.focused()?.contains(text) != true && steps++ < max) compose.stepFocus(Button.Down)
        return compose.focused()?.contains(text) == true
    }

    /** A catalogue already on disk (what the first successful fetch leaves), with a still for each piece, so the menu needs no network. */
    private fun cacheIndex() {
        val dir = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "ascii").also { it.mkdirs() }
        val colours = listOf("#ef4444", "#22c55e", "#3b82f6", "#eab308", "#a855f7", "#14b8a6")
        fun still(slug: String, n: Int) {
            val text = (0 until 24).joinToString("") { i -> "\u2588\u2593\u2592\u2591  ".let { r -> r[(i + n) % r.length].toString() } }
            val head = """{"v":2,"slug":"$slug","cols":8,"rows":3,"fps":10,"n":1,"loop":"still","palette":["${colours[n % colours.size]}","${colours[(n + 2) % colours.size]}"]}"""
            File(File(dir, "preview").also { it.mkdirs() }, "$slug.asc").writeBytes(AsciiTestPacks.pack(listOf(AsciiTestPacks.frame(text, 1 + n % 2)), head))
        }
        val slugs = listOf("rain-city" to "Rain City", "moai-coast" to "Moai Coast", "night-pagoda" to "Night Pagoda", "orbit-window" to "Orbit Window", "comet-trail" to "Comet Trail", "bee-flight" to "Busy Bees")
        fun piece(i: Int, cat: String) =
            """{"slug":"${slugs[i].first}","name":"${slugs[i].second}","category":"$cat","cols":96,"rows":32,"fps":24,"frames":288,"bytes":1000,"sha256":"x","zip":"$cat.zip","note":"A rainy neon street at night.","previewSha256":"x"}"""
        slugs.forEachIndexed { i, (slug, _) -> still(slug, i) }
        File(dir, "index.json").writeText(
            """{"v":1,"categories":[
              {"id":"landscapes","name":"Landscapes","blurb":"Hand-built scenery with moving skies.","pieces":3,"previewZip":"p1.zip"},
              {"id":"cosmos","name":"Cosmos","blurb":"Stars, planets and orbits.","pieces":2,"previewZip":"p2.zip"},
              {"id":"animals","name":"Animals","blurb":"Creatures in motion.","pieces":1,"previewZip":"p3.zip"}],
              "pieces":[${piece(0, "landscapes")},${piece(1, "landscapes")},${piece(2, "landscapes")},${piece(3, "cosmos")},${piece(4, "cosmos")},${piece(5, "animals")}]}""",
        )
    }

    /** Yin's stick check: a catalogue cached before the stills existed has no preview zip anywhere, and was never refreshed. */
    @Test fun aCatalogueCachedBeforeStillsIsFlaggedForARefresh() {
        val dir = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "ascii").also { it.mkdirs() }
        File(dir, "index.json").writeText("""{"v":1,"categories":[{"id":"cosmos","name":"Cosmos","pieces":1}],"pieces":[{"slug":"orbit-window","name":"Orbit Window","category":"cosmos","cols":8,"rows":3,"fps":24,"frames":9,"bytes":1,"sha256":"x","zip":"c.zip"}]}""")
        val cached = dev.glasslauncher.dream.AsciiPacks.cachedIndex(ApplicationProvider.getApplicationContext())!!
        assertTrue("no still anywhere: ensurePreviews must fetch the catalogue again", cached.categories.none { it.previewZip != null })
    }

    @Test fun theMenuIsCategoriesOnTheLeftAndStillsOnTheRight() {
        TvHarness.setUp()
        cacheIndex()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.focusTag("settings-tile")
            compose.press(Button.Select)
            assertTrue("Settings must offer Screen Saver", walkTo("Screen Saver"))
            compose.press(Button.Select)
            assertTrue("the Screen Saver page must offer ASCII Art", walkTo("ASCII Art"))
            compose.press(Button.Select)
            assertTrue("the categories must be listed", compose.awaitUntil { texts().any { it == "Landscapes" } && texts().any { it == "Cosmos" } })
            assertTrue("the first category's pieces are shown as tiles", compose.awaitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ascii:rain-city")).fetchSemanticsNodes().isNotEmpty() })
            compose.settle()
            compose.onRoot().captureRoboImage(shot("ascii-menu"))
            // Focusing the next category swaps the grid, as in Choose Aerials.
            assertTrue(walkTo("Cosmos"))
            assertTrue("Cosmos pieces replace Landscapes", compose.awaitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ascii:orbit-window")).fetchSemanticsNodes().isNotEmpty() })
            assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ascii:rain-city")).fetchSemanticsNodes().isEmpty())
        }
    }
}
