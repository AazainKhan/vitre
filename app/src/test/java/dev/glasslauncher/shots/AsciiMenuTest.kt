package dev.glasslauncher.shots

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
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

    /** A catalogue already on disk (what the first successful fetch leaves), so the menu needs no network. */
    private fun cacheIndex() {
        val dir = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "ascii").also { it.mkdirs() }
        fun piece(slug: String, name: String, cat: String) =
            """{"slug":"$slug","name":"$name","category":"$cat","cols":96,"rows":32,"fps":24,"frames":288,"bytes":1000,"sha256":"x","zip":"$cat.zip","note":"A rainy neon street at night, with passing umbrellas."}"""
        File(dir, "index.json").writeText(
            """{"v":1,"categories":[
              {"id":"landscapes","name":"Landscapes","blurb":"Hand-built scenery with moving skies.","pieces":3},
              {"id":"cosmos","name":"Cosmos","blurb":"Stars, planets and orbits.","pieces":2},
              {"id":"animals","name":"Animals","blurb":"Creatures in motion.","pieces":1}],
              "pieces":[${piece("rain-city", "Rain City", "landscapes")},${piece("moai-coast", "Moai Coast", "landscapes")},${piece("night-pagoda", "Night Pagoda", "landscapes")},
                ${piece("orbit-window", "Orbit Window", "cosmos")},${piece("comet-trail", "Comet Trail", "cosmos")},${piece("bee-flight", "Busy Bees", "animals")}]}""",
        )
    }

    @Test fun theMenuListsCategoriesThenPieces() {
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
            assertTrue("the categories must be listed", compose.awaitUntil { texts().any { it == "Landscapes" } })
            compose.settle()
            compose.onRoot().captureRoboImage(shot("ascii-categories"))
            // Focus starts on the always-present first row (Play Selected), then Down to the first category.
            assertTrue(walkTo("Landscapes"))
            compose.press(Button.Select)
            assertTrue("the category's pieces must be listed", compose.awaitUntil { texts().any { it == "Rain City" } })
            assertTrue(compose.awaitUntil { compose.focused()?.contains("Rain City") == true })
            compose.settle()
            compose.onRoot().captureRoboImage(shot("ascii-pieces"))
            assertTrue(texts().any { it == "Night Pagoda" } && texts().none { it == "Comet Trail" })
        }
    }
}
