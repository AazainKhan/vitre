package dev.glasslauncher

import dev.glasslauncher.widgets.ListenerGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerGrantTest {
    @Test fun grantsTheNowPlayingListener() =
        assertEquals("cmd notification allow_listener dev.glasslauncher/dev.glasslauncher.widgets.NowPlayingService",
            ListenerGrant.script("dev.glasslauncher/dev.glasslauncher.widgets.NowPlayingService"))
    @Test fun refusesAnythingThatIsNotAComponent() = assertNull(ListenerGrant.script("x/y; rm -rf /"))
}
