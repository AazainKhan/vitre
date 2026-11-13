package dev.glasslauncher.system

import dev.glasslauncher.system.KeysServicePolicy.Action
import dev.glasslauncher.system.KeysServicePolicy.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** P93: the remote-keys service per install way: APK asks, root / secure-settings keep it on, a manual off is followed. */
class KeysServicePolicyTest {
    private fun state(
        setting: Boolean = true, service: Boolean = false, mode: WriteMode = WriteMode.Root, version: Long = 10,
        last: Long? = 10, boot: Boolean = false, card: Boolean = false, off: Boolean = false,
    ) = State(setting, service, mode, version, last, boot, card, off)

    @Test fun modeDecision() {
        assertEquals(WriteMode.SecureSettings, KeysServicePolicy.mode(hasSecureWrite = true, hasRoot = true))
        assertEquals(WriteMode.SecureSettings, KeysServicePolicy.mode(hasSecureWrite = true, hasRoot = false))
        assertEquals(WriteMode.Root, KeysServicePolicy.mode(hasSecureWrite = false, hasRoot = true))
        assertEquals(WriteMode.None, KeysServicePolicy.mode(hasSecureWrite = false, hasRoot = false))
    }

    @Test fun aBootIsAChangedBootCounterNotANewProcess() {
        assertEquals(true, KeysServicePolicy.isBoot(bootCount = 8, lastBootCount = 7))
        assertEquals("same boot, process restarted", false, KeysServicePolicy.isBoot(bootCount = 7, lastBootCount = 7))
        assertEquals("first run, nothing to compare", false, KeysServicePolicy.isBoot(bootCount = 7, lastBootCount = null))
        assertEquals("no counter", false, KeysServicePolicy.isBoot(bootCount = -1, lastBootCount = 7))
    }

    @Test fun settingOffMeansNothingIsDone() {
        for (mode in WriteMode.entries) {
            assertEquals(Action.Nothing, KeysServicePolicy.decide(state(setting = false, service = false, mode = mode)))
        }
    }

    @Test fun serviceOnIsRecorded() {
        assertEquals(Action.RecordOn, KeysServicePolicy.decide(state(service = true)))
        assertEquals(Action.RecordOn, KeysServicePolicy.decide(state(service = true, mode = WriteMode.None)))
    }

    @Test fun rootOrSecureSettingsReEnableAfterAnUpdate() {
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings)) {
            assertEquals("new version", Action.Enable, KeysServicePolicy.decide(state(mode = mode, version = 11, last = 10)))
            assertEquals("never seen on", Action.Enable, KeysServicePolicy.decide(state(mode = mode, last = null)))
        }
    }

    @Test fun aReinstallOfTheSameVersionCodeIsStillAnUpdate() {
        // versionCode never changes between builds; the install time does.
        assertEquals(Action.Enable, KeysServicePolicy.decide(state(version = 2_000, last = 1_000)))
    }

    @Test fun aManualOffRightAfterTheHealIsTheUsersBecauseTheHealRecordedTheInstall() {
        // The update heal ran (Enable) and recorded this install time; the user then turns the service off.
        val installed = 2_000L
        assertEquals(Action.Enable, KeysServicePolicy.decide(state(version = installed, last = 1_000)))
        assertEquals(Action.UserTurnedOff, KeysServicePolicy.decide(state(version = installed, last = installed, off = true)))
    }

    @Test fun atBootItIsReEnabledEvenOnTheSameVersion() {
        assertEquals(Action.Enable, KeysServicePolicy.decide(state(boot = true)))
    }

    @Test fun offOnTheSameVersionAfterBeingSeenOnIsTheUserAndGlassFollows() {
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings)) {
            assertEquals(Action.UserTurnedOff, KeysServicePolicy.decide(state(mode = mode, version = 10, last = 10, off = true)))
        }
    }

    @Test fun anApkInstallAsksOnceAndNeverEnables() {
        assertEquals(Action.ShowCard, KeysServicePolicy.decide(state(mode = WriteMode.None, last = null)))
        assertEquals("after the card", Action.Nothing, KeysServicePolicy.decide(state(mode = WriteMode.None, last = null, card = true)))
        // Never an Enable or UserTurnedOff for the no-write mode, whatever the version history.
        for (last in listOf(null, 9L, 10L)) for (boot in listOf(false, true)) for (off in listOf(false, true)) {
            val a = KeysServicePolicy.decide(state(mode = WriteMode.None, last = last, boot = boot, card = true, off = off))
            assert(a != Action.Enable && a != Action.UserTurnedOff) { "$a for last=$last boot=$boot off=$off" }
        }
    }

    // P332: a force-stop also unbinds the service, but it kills VITRE, so the settings change is never observed. That is a
    // kill, not the user: re-enable where VITRE can write the setting, otherwise say so and keep the setting on.
    @Test fun aKillWithNoObservedSwitchOffIsReEnabled() {
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings))
            assertEquals(Action.Enable, KeysServicePolicy.decide(state(mode = mode, version = 10, last = 10, off = false)))
    }

    @Test fun aKillOnAPlainApkGetsANoticeAndTheSettingStaysOn() {
        assertEquals(Action.NoticeKilled, KeysServicePolicy.decide(state(mode = WriteMode.None, version = 10, last = 10, card = true, off = false)))
        assertEquals("the user's own off is left alone", Action.Nothing, KeysServicePolicy.decide(state(mode = WriteMode.None, version = 10, last = 10, card = true, off = true)))
    }
}

/** P95: the Home card and the Sleep Timer's capability. */
class HomeCardPolicyTest {
    @Test fun cardOnlyWhereGlassIsNotHomeAndNothingElseWillMakeItHome() {
        assertEquals(true, HomeCardPolicy.show(isDefaultHome = false, guardOn = false, hasRoot = false, shown = false))
        assertEquals("already home", false, HomeCardPolicy.show(true, false, false, false))
        assertEquals("guard on", false, HomeCardPolicy.show(false, true, false, false))
        assertEquals("root keeps Home", false, HomeCardPolicy.show(false, false, true, false))
        assertEquals("only once", false, HomeCardPolicy.show(false, false, false, true))
    }

    @Test fun sleepNeedsRootOrTheKeysService() {
        assertEquals(true, SleepTimer.canSleep(root = true, keysServiceOn = false))
        assertEquals(true, SleepTimer.canSleep(root = false, keysServiceOn = true))
        assertEquals(false, SleepTimer.canSleep(root = false, keysServiceOn = false))
    }

    @Test fun aServiceDroppedByForceStopOrAPackageChangeIsHealedNotFollowed() {
        // Same version, seen on before, not a boot, but the service never saw an unbind: force-stop or uninstall-system-updates.
        for (mode in listOf(WriteMode.Root, WriteMode.SecureSettings)) {
            assertEquals(Action.Enable, KeysServicePolicy.decide(State(true, false, mode, 10, 10, false, false, false)))
        }
    }
}

class HomeGuardPolicyTest {
    private fun d(setting: Boolean = true, guard: Boolean = false, secure: Boolean = true, boot: Boolean = false, update: Long = 10, last: Long? = 10) =
        HomeGuardPolicy.decide(setting, guard, secure, boot, update, last)

    @Test fun healedAtBootAndAfterAnUpdate() {
        assertEquals(HomeGuardPolicy.Action.Enable, d(boot = true))
        assertEquals(HomeGuardPolicy.Action.Enable, d(update = 11, last = 10))
        assertEquals(HomeGuardPolicy.Action.Enable, d(last = null))
    }

    @Test fun aManualOffOnTheSameInstallIsRespected() {
        assertEquals(HomeGuardPolicy.Action.UserTurnedOff, d())
    }

    @Test fun aManualOffRightAfterTheGuardHealIsRespected() {
        assertEquals(HomeGuardPolicy.Action.Enable, d(update = 2_000, last = 1_000))
        assertEquals(HomeGuardPolicy.Action.UserTurnedOff, d(update = 2_000, last = 2_000))
    }

    @Test fun nothingWithoutTheSettingOrTheGrant() {
        assertEquals(HomeGuardPolicy.Action.Nothing, d(setting = false))
        assertEquals(HomeGuardPolicy.Action.Nothing, d(secure = false))
        assertEquals(HomeGuardPolicy.Action.RecordOn, d(guard = true))
    }
}
