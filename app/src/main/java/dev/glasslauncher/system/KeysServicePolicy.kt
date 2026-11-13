package dev.glasslauncher.system

/**
 * How Glass keeps its remote-keys accessibility service (remote app buttons, Recent Apps, instant Back) on, per install
 * way (P93). Pure, so the decision is unit-tested; [KeysService] gathers the inputs and carries the action out.
 *
 *  - APK, no root: Glass can't enable a service itself. It asks once, with a Home card that opens Fire's Accessibility
 *    settings, and never calls su.
 *  - Glass Pro (rooted) or Glass OS: it keeps the service enabled while the setting is on.
 *  - Any install with WRITE_SECURE_SETTINGS granted (adb): the same, through the Settings provider (no su).
 */
enum class WriteMode { SecureSettings, Root, None }

object KeysServicePolicy {
    /** Prefers the Settings provider (no su) when WRITE_SECURE_SETTINGS is granted; root otherwise; else none. */
    fun mode(hasSecureWrite: Boolean, hasRoot: Boolean): WriteMode = when {
        hasSecureWrite -> WriteMode.SecureSettings
        hasRoot -> WriteMode.Root
        else -> WriteMode.None
    }

    /**
     * A real boot, not a process restart: the system's boot counter changed since the last check. A missing counter
     * (-1) is never a boot, so a killed Glass process can't undo a manual off.
     */
    fun isBoot(bootCount: Int, lastBootCount: Int?): Boolean = bootCount >= 0 && lastBootCount != null && bootCount != lastBootCount

    sealed interface Action {
        data object Nothing : Action
        /** The setting is on and the service is: remember this version as the last one it was seen on. */
        data object RecordOn : Action
        data object Enable : Action
        /** Found off without an update since it was last seen on: the user turned it off; follow them. */
        data object UserTurnedOff : Action
        data object ShowCard : Action
        /** P332: found off with no switch-off observed (a force-stop or a kill dropped it) and VITRE can't write it back: say so, keep the setting on. */
        data object NoticeKilled : Action
    }

    class State(
        val settingOn: Boolean,
        val serviceOn: Boolean,
        val mode: WriteMode,
        val updateTime: Long,
        /** The install time (PackageInfo.lastUpdateTime) the service was last seen on under, or null (never seen, or the setting was re-enabled). */
        val lastOnUpdate: Long?,
        /** The first check of this process: Glass has just started (boot, or a restart after an update). */
        val atBoot: Boolean,
        /** The Home card has already been shown since the setting was last turned on. */
        val cardShown: Boolean,
        /**
         * P332: VITRE's process saw enabled_accessibility_services lose the service (a ContentObserver) after this install: a
         * switch-off in Accessibility settings with VITRE alive. A force-stop also unbinds the service (onUnbind fired for it on
         * Fire, which read as the user and turned the setting off), but it kills the process, so the change is never observed.
         */
        val userSwitchedOff: Boolean = false,
    )

    fun decide(s: State): Action {
        if (!s.settingOn) return Action.Nothing                          // the user said off: don't fight it
        if (s.serviceOn) return Action.RecordOn
        // After an update or at boot (or never seen on) an absence is the system dropping it.
        val fresh = s.atBoot || s.lastOnUpdate == null || s.lastOnUpdate != s.updateTime
        if (s.mode == WriteMode.None) return when {
            fresh -> if (s.cardShown) Action.Nothing else Action.ShowCard
            s.userSwitchedOff -> Action.Nothing                          // the user's off; nothing to write anyway
            else -> Action.NoticeKilled                                   // seen on, now gone, nobody switched it off
        }
        return when {
            fresh -> Action.Enable
            s.userSwitchedOff -> Action.UserTurnedOff                    // observed while VITRE was alive: the user's choice
            else -> Action.Enable                                        // gone with nothing observed: killed, not a choice
        }
    }
}

/** P95 gap 1: a plain APK isn't Home until the user does one of two things, so Glass says so once. */
object HomeCardPolicy {
    /** Shown once, only where Glass isn't Home, the Home guard isn't on and root isn't there to make it Home. */
    fun show(isDefaultHome: Boolean, guardOn: Boolean, hasRoot: Boolean, shown: Boolean): Boolean =
        !isDefaultHome && !guardOn && !hasRoot && !shown
}

/** P95 review: the Home guard follows the same rule as the keys service: healed at boot or an update, a manual off is respected. */
object HomeGuardPolicy {
    enum class Action { Nothing, RecordOn, Enable, UserTurnedOff }

    fun decide(settingOn: Boolean, guardOn: Boolean, hasSecure: Boolean, atBoot: Boolean, updateTime: Long, lastOnUpdate: Long?): Action = when {
        !settingOn -> Action.Nothing
        guardOn -> Action.RecordOn
        !hasSecure -> Action.Nothing
        atBoot || lastOnUpdate == null || lastOnUpdate != updateTime -> Action.Enable
        else -> Action.UserTurnedOff
    }
}
