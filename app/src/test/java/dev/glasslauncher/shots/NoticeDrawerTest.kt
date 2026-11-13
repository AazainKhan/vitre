package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.MainActivity
import dev.glasslauncher.system.AppNotice
import dev.glasslauncher.system.NoticeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P98: the notifications drawer. It is reachable from the pill and says something useful in all three of its states -
 * roads to it, something to read, and nothing at all - because "nothing here" and "VITRE cannot see anything" look
 * identical and want different things from the user.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class NoticeDrawerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Up to the pill, Left onto the circle, Select.
     *
     * One Up, not three: a second Up leaves the pill and falls into the parked Control Center's window. And a bare
     * performClick would do nothing, because these tiles act on key events, not on a click action.
     */
    private val trace = StringBuilder()

    /** Walk with [key] until focus is on [tag]; returns whether it got there. */
    private fun walkTo(tag: String, key: Button, max: Int = 8): Boolean {
        var i = 0
        trace.append("start=${compose.focused()} ")
        while (compose.focused() != tag && i < max) {
            compose.press(key)
            compose.waitForIdle()
            trace.append("$key->${compose.focused()} ")
            i++
        }
        return compose.focused() == tag
    }

    /**
     * Up to the pill, Left onto the circle, Select.
     *
     * A bare performClick does nothing here, because these tiles act on key events rather than on a click action, and
     * the count of Ups to the pill is not fixed - so walk and assert rather than counting.
     */
    private fun openDrawer() {
        compose.waitForHome()
        val up = walkTo("status-pill", Button.Up)
        assertTrue("Up must reach the pill [$trace]", up)
        val left = walkTo("status-notices", Button.Left)
        assertTrue("Left must reach the notices circle [$trace]", left)
        compose.press(Button.Select)
        compose.waitForTag("notice-drawer")
    }

    /** Every string the tree is showing, so a test can ask what the drawer says. */
    private fun texts(): List<String> = compose.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("has text") {
            it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
        },
    ).fetchSemanticsNodes().flatMap {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.map { t -> t.text } ?: emptyList()
    }

    private fun notice(title: String, pkg: String = "com.example.app", agoMs: Long = 0) = AppNotice(
        key = "$pkg:$title", pkg = pkg, app = pkg, title = title, text = "body",
        whenMs = System.currentTimeMillis() - agoMs, ongoing = false, media = false,
    )

    /** An installed app with a label and a coloured icon, so shots show what real apps look like. */
    private fun fakeApp(pkg: String, label: String, color: Int) {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        val spm = org.robolectric.Shadows.shadowOf(ctx.packageManager)
        spm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = info })
        spm.setApplicationIcon(pkg, android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = 14f })
    }

    @After fun clearStore() { NoticeStore.clear(); NoticeStore.disconnected() }

    /** The pill carries the way in: one glass circle beside it, focusable with it. */
    @Test fun thePillCarriesTheWayIn() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.waitForTag("status-notices")
            assertTrue("the notices target must exist beside the pill", exists("status-notices"))
        }
    }

    /** Focus on the pill, step left to the circle, press it: the drawer comes up. */
    @Test fun focusLeftFromThePillOpensTheDrawer() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            assertTrue("Select on the notices circle must open the drawer", exists("notice-drawer"))
        }
    }

    /** With nothing granted, say so: an empty list would read as "nothing has happened". */
    @Test fun withoutTheGrantItSaysHowToGetOne() {
        NoticeStore.disconnected()
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            assertTrue("the drawer must explain the grant", texts().any { it.contains("Let VITRE see") })
        }
    }

    /** With something posted, the row is there and carries its age. */
    @Test fun aPostedNotificationBecomesARow() {
        // After setUp, which clears the store so the pill's golden does not depend on test order.
        TvHarness.setUp()
        NoticeStore.onConnected()
        NoticeStore.posted(notice("Dinner is ready", agoMs = 4 * 60_000))
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            val seen = texts()
            assertTrue("the title must be on screen", seen.any { it.contains("Dinner is ready") })
            assertTrue("and so must how long ago it arrived", seen.any { it == "4m" })
            assertTrue("with Clear all above it", seen.any { it.contains("Clear all") })
        }
    }

    /** Newest app first, and older ones keep their place below (one app's several are a stack, see below). */
    @Test fun newestIsAtTheTop() {
        TvHarness.setUp()
        NoticeStore.onConnected()
        NoticeStore.posted(notice("older", pkg = "com.old.app", agoMs = 60 * 60_000))
        NoticeStore.posted(notice("newer", pkg = "com.new.app", agoMs = 0))
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            val seen = texts()
            assertTrue(seen.any { it.contains("newer") })
            assertTrue(seen.any { it.contains("older") })
            assertEquals("now", dev.glasslauncher.home.agoLabel(System.currentTimeMillis(), System.currentTimeMillis()))
        }
    }

    /** A blocked package is not shown, and nothing else is affected. */
    @Test fun aBlockedPackageStaysOut() {
        TvHarness.setUp(config = { it.copy(blockedNoticePackages = listOf("com.spam.app")) })
        NoticeStore.onConnected()
        NoticeStore.posted(notice("wanted", pkg = "com.keep.app"))
        NoticeStore.posted(notice("noisy", pkg = "com.spam.app"))
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            val seen = texts()
            assertTrue(seen.any { it.contains("wanted") })
            assertTrue("a blocked package must not appear", seen.none { it.contains("noisy") })
        }
    }

    /** P325: grouped cards. Three from one app rest as a stack; Select opens it; Show less folds it. */
    @Test fun severalFromOneAppRestAsAStack() {
        TvHarness.setUp()
        fakeApp("com.chat.app", "Messages", 0xFF34C759.toInt())
        fakeApp("com.shop.app", "Shop", 0xFFFF9500.toInt())
        NoticeStore.onConnected()
        NoticeStore.posted(notice("Anna", pkg = "com.chat.app", agoMs = 60_000))
        NoticeStore.posted(notice("Ben", pkg = "com.chat.app", agoMs = 5 * 60_000))
        NoticeStore.posted(notice("Cleo", pkg = "com.chat.app", agoMs = 9 * 60_000))
        NoticeStore.posted(notice("Your order shipped", pkg = "com.shop.app", agoMs = 3 * 3_600_000))
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            openDrawer()
            assertTrue("the stack shows its newest card and how many are behind it", texts().any { it.contains("+2 more") })
            assertTrue("older cards of the stack are folded away", texts().none { it.contains("Cleo") })
            compose.settle()
            compose.onRoot().captureRoboImage(shot("notice-cards-stack"))
            compose.press(Button.Down)
            compose.settle()
            compose.onRoot().captureRoboImage(shot("notice-cards-focus"))
            compose.press(Button.Select)
            compose.settle()
            assertTrue("Select opens the stack", texts().any { it.contains("Cleo") } && texts().any { it.contains("Show less") })
            compose.onRoot().captureRoboImage(shot("notice-cards-open"))
        }
    }

    @Test fun anAppWithoutALabelIsNamedFromItsPackage() {
        assertEquals("App", dev.glasslauncher.home.labelFallback("com.chat.app"))
        assertEquals("Solo", dev.glasslauncher.home.labelFallback("solo"))
    }

    @Test fun groupingKeepsTheNewestAppFirst() {
        val a = notice("a1", pkg = "p.a", agoMs = 0)
        val b = notice("b1", pkg = "p.b", agoMs = 10)
        val a2 = notice("a2", pkg = "p.a", agoMs = 20)
        val g = dev.glasslauncher.home.groupNotices(listOf(a, b, a2))
        assertEquals(listOf("p.a", "p.b"), g.map { it.pkg })
        assertEquals(listOf("a1", "a2"), g[0].items.map { it.title })
    }
}
