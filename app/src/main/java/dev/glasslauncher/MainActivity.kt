package dev.glasslauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.glasslauncher.home.HomeModel
import dev.glasslauncher.home.HomeRequest
import dev.glasslauncher.home.HomeScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val model: HomeModel by viewModels()
    // Conflated channel: keeps a request that arrives before Home starts collecting (cold start).
    private val requests = Channel<HomeRequest>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(model, requests.receiveAsFlow()) }
        // The wallpaper covers the whole window; skipping the window background saves a full-screen fill per frame.
        window.setBackgroundDrawable(null)
        request(intent)
        current = java.lang.ref.WeakReference(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private var stoppedSinceResume = false

    override fun onStop() {
        super.onStop()
        stoppedSinceResume = true
        (application as GlassApp).homeHidden()
    }

    /** Any key or touch Home receives cancels a Sleep Timer that is showing its one-minute banner. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        dev.glasslauncher.system.SleepTimer.cancelOnInteraction(this)
    }

    override fun onStart() {
        super.onStart()
        (application as GlassApp).homeShown()
    }

    override fun onPause() {
        super.onPause()
        dev.glasslauncher.system.RemoteWatch.homeInFront = false
        // P247: Home loses the foreground. On this Fire OS build it never gets onStop (the Home task stays
        // "visible" under the app), so onPause is the only reliable "an app is in front now" signal.
        (application as GlassApp).homeHidden()
    }

    override fun onResume() {
        super.onResume()
        (application as GlassApp).homeShown()
        dev.glasslauncher.system.HomeBounce.resumed()
        dev.glasslauncher.system.HomeWatchdog.rescueIfTripped(this) // a flag read: the safety net's second chance to be on top (P341)
        stoppedSinceResume = false
        dev.glasslauncher.system.RemoteWatch.homeInFront = true
        dev.glasslauncher.system.RemoteWatch.evaluate(this)
        dev.glasslauncher.downloads.Downloads.onForeground(this)
        // Coming back to Home while the banner is up (the screensaver was dismissed) is the user returning.
        dev.glasslauncher.system.SleepTimer.cancelOnInteraction(this)
        // However Glass came back (Home, Back out of the store), Amazon's launcher goes off again; and if it
        // came back on some other way, Glass takes Home back (keepHome).
        val app = application as GlassApp
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { dev.glasslauncher.widgets.ListenerGrant.ensure(app) } }
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            dev.glasslauncher.system.AmazonStore.close(app, bringHome = false)
            dev.glasslauncher.system.RootFeatures.keepHome(app)
            // The remote's buttons, Recent Apps and instant back run on this service: never left off after an update.
            dev.glasslauncher.system.KeysService.check(app)
            // Storage nearly full is said once a day, when Home is in front (P214).
            dev.glasslauncher.system.StorageWatch.check(app)
            // Glass crashed and was started again: say so now that Home is in front (P214).
            dev.glasslauncher.system.CrashRecovery.showPending(app)
        }
    }

    /** The Appstore's launcher entry asks Home for Amazon's apps page; show the store instead of ignoring it. */
    private fun storeRequest(intent: Intent?): Boolean {
        if (!dev.glasslauncher.system.AmazonStore.isStoreRequest(intent)) return false
        // Opening the store turns Amazon's launcher on (root), so only the Appstore itself may ask. The referrer is the
        // system's record of the caller unless the intent supplies its own, which any app could forge.
        val forged = intent?.hasExtra(Intent.EXTRA_REFERRER) == true || intent?.hasExtra(Intent.EXTRA_REFERRER_NAME) == true
        if (!dev.glasslauncher.system.AmazonStore.trustedCaller(referrer?.host, forged)) {
            intent?.removeExtra("navigate_node")
            return false
        }
        val node = intent?.getStringExtra("navigate_node") ?: return false
        val source = intent.getStringExtra("source") ?: "appstore"
        intent.removeExtra("navigate_node")
        val app = application as GlassApp
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { dev.glasslauncher.system.AmazonStore.open(app, node, source) }
        return true
    }

    override fun onRestart() {
        super.onRestart()
        // No system animation on the way back: Glass draws the app closing into its tile itself.
        // Fire OS ignores the theme's window animations for the home task (it fades Home in from black
        // ~300 ms after the Home press), but honours a pending override.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed on Home goes back to the top; Home pressed in an app just returns, leaving focus on
        // the app's tile (that's where the close animation lands, as on tvOS).
        // (Android pauses Home before delivering the intent, so "stopped since it was last resumed" is
        // what tells the two apart, not the lifecycle state.)
        if (storeRequest(intent)) return
        if (intent.action == Intent.ACTION_MAIN) { if (!stoppedSinceResume) requests.trySend(HomeRequest.Home) } else request(intent)
    }

    /** Remote buttons (RemoteKeysService) ask for Control Center or the app switcher this way. */
    private fun request(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONTROL_CENTER -> requests.trySend(HomeRequest.ControlCenter)
            ACTION_SETTINGS -> requests.trySend(HomeRequest.Settings)
            ACTION_APP_SWITCHER -> requests.trySend(HomeRequest.AppSwitcher)
            ACTION_TV_SETTINGS -> requests.trySend(HomeRequest.TvSettings)
            ACTION_REMOTES -> requests.trySend(HomeRequest.Remotes)
            ACTION_DOWNLOADS -> requests.trySend(HomeRequest.Downloads)
        }
    }

    companion object {
        const val ACTION_CONTROL_CENTER = "dev.glasslauncher.action.CONTROL_CENTER"
        const val ACTION_APP_SWITCHER = "dev.glasslauncher.action.APP_SWITCHER"
        const val ACTION_TV_SETTINGS = "dev.glasslauncher.action.TV_SETTINGS"
        const val ACTION_SETTINGS = "dev.glasslauncher.action.SETTINGS"
        const val ACTION_REMOTES = "dev.glasslauncher.action.REMOTES"
        const val ACTION_DOWNLOADS = "dev.glasslauncher.action.DOWNLOADS"

        @Volatile private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /** Home's activity while it is in front (resumed), else null: where an in-app notice card is drawn (P234). */
        fun resumed(): MainActivity? = current?.get()?.takeIf { it.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }

        /**
         * Home's semantics tree for the device tests (DebugDumpReceiver), only while Glass's window has
         * focus: with an app or a system dialog in front, uiautomator is the right source.
         */
        fun dumpXml(): String? {
            val activity = current?.get() ?: return null
            if (!activity.hasWindowFocus()) return null
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return null
            val compose = content.getChildAt(0) as? android.view.ViewGroup ?: return null
            val root = compose.getChildAt(0) as? androidx.compose.ui.node.RootForTest ?: return null
            return dev.glasslauncher.system.SemanticsDump.xml(root)
        }
    }
}
