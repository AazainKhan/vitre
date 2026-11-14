package dev.glasslauncher.system

import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import dev.glasslauncher.MainActivity
import dev.glasslauncher.app

/**
 * Optional fallback for devices that never let a third-party launcher become Home (Fire TV):
 * when the stock launcher's home screen comes to the front, bring Glass TV Launcher back.
 * It only watches window changes of known stock launchers and never filters keys.
 */
class HomeGuardService : AccessibilityService() {
    private var lastJump = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!app.config.config.value.homeGuard) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        val isStockHome = when (pkg) {
            "com.amazon.tv.launcher" -> cls.contains("Home", ignoreCase = true) || cls.contains("Launcher", ignoreCase = true)
            in HomeSetup.STOCK_LAUNCHERS -> true
            else -> false
        }
        if (!isStockHome) return
        val now = SystemClock.uptimeMillis()
        if (now - lastJump < 800) return
        lastJump = now
        HomeBounce.mark(now)
        startActivity(HomeSetup.homeIntent(this))
    }

    override fun onInterrupt() = Unit
}

/**
 * Measures the fallback (P341): from the moment the guard sees the stock home window to VITRE's first frame after it, logged as
 * "bounce N ms" under VitreBounce. It costs a timestamp per bounce and one frame callback after it; nothing runs otherwise.
 * `adb logcat -s VitreBounce`. (The press itself is a little earlier than the window, so this is a floor.)
 */
object HomeBounce {
    @Volatile private var at = 0L
    fun mark(now: Long) { at = now }

    /** MainActivity.onResume: if a bounce is open, log the time to the next frame. */
    fun resumed() {
        val t0 = at.takeIf { it != 0L } ?: return
        at = 0L
        android.view.Choreographer.getInstance().postFrameCallback {
            android.util.Log.i("VitreBounce", "bounce ${SystemClock.uptimeMillis() - t0} ms from the stock home window to VITRE's first frame")
        }
    }
}

object HomeSetup {
    val STOCK_LAUNCHERS = setOf(
        "com.amazon.tv.launcher",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )

    val isFireTv: Boolean get() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) || Build.MODEL.startsWith("AFT")

    fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun isDefaultHome(context: Context): Boolean {
        val info = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return info?.activityInfo?.packageName == context.packageName
    }

    /** Standard Android path: the Home role dialog, or the system home picker. Null if neither exists. */
    fun requestDefaultHomeIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        val settings = Intent(Settings.ACTION_HOME_SETTINGS)
        return settings.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * Starts what [requestDefaultHomeIntent] returned. The Home role request must be started for a result from an Activity: started
     * with plain startActivity the system's role dialog sees no caller and quietly shows nothing ("Package name cannot be null",
     * found on a plain Android TV AVD), so the Make Glass the Default row did nothing.
     */
    fun startRequest(context: Context, intent: Intent) {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        if (needsResult(intent) && activity != null) activity.startActivityForResult(intent, REQUEST_HOME_ROLE)
        else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** True for an intent that must be started for a result (the role request). */
    fun needsResult(intent: Intent): Boolean = intent.action == ACTION_REQUEST_ROLE

    private const val REQUEST_HOME_ROLE = 0x4801
    /** RoleManager.createRequestRoleIntent's action (the constant is hidden API). */
    const val ACTION_REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE"

    private fun guardComponent(context: Context) = ComponentName(context, HomeGuardService::class.java)

    fun isGuardEnabled(context: Context): Boolean = isServiceEnabled(context, guardComponent(context))
    fun isRemoteKeysEnabled(context: Context): Boolean = isServiceEnabled(context, ComponentName(context, RemoteKeysService::class.java))

    private fun isServiceEnabled(context: Context, component: ComponentName): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    /** Turns the accessibility service on or off directly; needs WRITE_SECURE_SETTINGS. */
    fun setGuardEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val cr = context.contentResolver
        val own = guardComponent(context).flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != guardComponent(context) }.orEmpty()
        val next = if (enabled) current + own else current
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
        if (next.isNotEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)

    /** The first-run "Make VITRE your Home" card's text: the adb command names Fire's launcher, so only a Fire TV is shown it. */
    fun homeCardText(fire: Boolean): String =
        if (fire) "Turn on VITRE in Accessibility so the Home button comes back here. Or from a computer, once: $DISABLE_STOCK_COMMAND"
        else "Turn on VITRE in Accessibility so the Home button comes back here, or choose VITRE as the default in Settings, Home Button."

    const val DISABLE_STOCK_COMMAND = "adb shell pm disable-user --user 0 com.amazon.tv.launcher"
    const val RESTORE_STOCK_COMMAND = "adb shell pm enable com.amazon.tv.launcher"
}
