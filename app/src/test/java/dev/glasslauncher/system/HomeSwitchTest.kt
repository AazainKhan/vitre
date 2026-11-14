package dev.glasslauncher.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The no-root Home switch (P341): key format, safety lists, the switch and its undo against a fake TV shell, and the crash window. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class HomeSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    // ── SelfAdb: what adbd checks ─────────────────────────────────────────────────────────────

    @Test fun signatureVerifiesWithThePublicKeyAndCarriesTheToken() {
        val keys = SelfAdb.keys(tmp.newFolder())
        val token = ByteArray(20) { it.toByte() }
        val sig = SelfAdb.sign(keys.private, token)
        assertEquals(256, sig.size)
        val block = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, keys.public) }.doFinal(sig)
        assertEquals(1, block[1].toInt())
        assertTrue("the 20-byte token ends the PKCS#1 block", block.takeLast(20) == token.toList())
    }

    @Test fun publicKeyBlobIsAdbdsFormat() {
        val keys = SelfAdb.keys(tmp.newFolder())
        val blob = String(SelfAdb.publicKeyBlob(keys.public) { java.util.Base64.getEncoder().encodeToString(it) }, Charsets.US_ASCII)
        assertTrue(blob.endsWith(" vitre@tv\u0000"))
        val raw = java.util.Base64.getDecoder().decode(blob.substringBefore(' '))
        assertEquals("adbd's RSAPublicKey struct", 524, raw.size)
        val b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val n = (keys.public as RSAPublicKey).modulus
        // n0inv is -1/n mod 2^32: n * n0inv + 1 is a multiple of 2^32.
        assertEquals(BigInteger.ZERO, n.multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)))
    }

    @Test fun theSameKeyIsKeptAcrossRuns() {
        val dir = tmp.newFolder()
        assertEquals(SelfAdb.keys(dir).public, SelfAdb.keys(dir).public)
    }

    // ── HomeSafety ────────────────────────────────────────────────────────────────────────────

    @Test fun safetyListsRefuseWhatABrokenTvNeeds() {
        assertFalse(HomeSafety.mayTurnOff("com.android.systemui"))
        assertFalse(HomeSafety.mayTurnOff("com.android.providers.tv"))
        assertFalse(HomeSafety.mayTurnOff("com.google.android.gms"))
        assertFalse("a factory menu on any brand", HomeSafety.mayTurnOff("com.cvte.tv.fac.aging"))
        assertFalse("a settings package is never off", HomeSafety.mayTurnOff("com.amazon.tv.settings.v2", fire = true))
        assertFalse("Amazon's launcher stays on off a Fire", HomeSafety.mayTurnOff("com.amazon.tv.launcher", fire = false))
        assertTrue("only Fire's home may go, and only on a Fire", HomeSafety.mayTurnOff("com.amazon.tv.launcher", fire = true))
        assertFalse("every other com.amazon package is refused even on a Fire", HomeSafety.mayTurnOff("com.amazon.device.software.ota", fire = true))
        assertFalse("a name that isn't a package never reaches a shell", HomeSafety.mayTurnOff("x; reboot"))
        assertEquals("", HomeSafety.safeComponent("a/b; reboot"))
        assertEquals("com.x/.Main\$Inner", HomeSafety.safeComponent("com.x/.Main\$Inner"))
    }

    // ── The switch and Original, against a fake TV ────────────────────────────────────────────

    private class FakeTv(val installed: Set<String>, val disabled: MutableSet<String> = mutableSetOf(), var home: String = "com.google.android.tvlauncher/.MainActivity",
                         val wifiOwners: String = "Wi-Fi is enabled", val refuse: Set<String> = emptySet(), val needsInput: Set<String> = emptySet(), val ignoreSetHome: Boolean = false) : TvShell {
        val log = mutableListOf<String>()
        override fun run(command: String): String {
            log += command
            return when {
                command.startsWith("pm grant") -> ""
                command.startsWith("pm list packages -d") -> disabled.joinToString("\n") { "package:$it" }
                command.startsWith("pm list packages") -> installed.joinToString("\n") { "package:$it" }
                command.startsWith("cmd role get-role-holders") -> "Unknown command: get-role-holders" // the AVD's answer: the fallback must cope
                command.startsWith("cmd package resolve-activity") -> {
                    val named = installed.firstOrNull { command.contains(" $it ") || command.contains("HOME $it") }
                    // the real command ends in tail -n 1: one component, or the current home when no package is named
                    if (named == null) home else if (named == home.substringBefore('/')) home else "$named/.MainActivity"
                }
                command.startsWith("dumpsys wifi") -> wifiOwners
                command.startsWith("settings get global wifi_on") -> "1"
                command.startsWith("for p in") -> command.substringAfter("for p in ").substringBefore(';').split(' ')
                    .joinToString("\n") { if (it in needsInput) "keep $it" else "- $it" } + "\nchecked"
                command.startsWith("cmd package set-home-activity") -> { if (!ignoreSetHome) home = command.substringAfter("set-home-activity ").trim('\''); "Success: activity set" }
                command.startsWith("pm disable-user --user 0 ") -> command.substringAfterLast(' ').let {
                    if (it in refuse) "Exception: Shell cannot change component state" else { disabled += it; "Package $it new state: disabled-user" }
                }
                command.startsWith("pm enable ") -> command.substringAfterLast(' ').let { disabled -= it; "Package $it new state: enabled" }
                command.startsWith("cmd role remove-role-holder") -> ""
                else -> ""
            }
        }
    }

    private fun engine(fire: Boolean, tv: FakeTv, stock: List<String>): Pair<SwitchEngine, FakeTv> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("homeswitch-test-${System.nanoTime()}", 0)
        return SwitchEngine("dev.glasslauncher", fire, stock, prefs) to tv
    }

    @Test fun switchSetsHomeThenTurnsOffTheStockHomeAndOriginalPutsItAllBack() {
        val tv = FakeTv(setOf("dev.glasslauncher", "com.google.android.tvlauncher", "com.google.android.play.games", "com.android.systemui"))
        val (e, _) = engine(false, tv, listOf("com.google.android.tvlauncher"))
        val on = e.switchOn(tv)
        assertTrue(on.message, on.ok)
        assertEquals("dev.glasslauncher/.MainActivity", tv.home)
        assertEquals(setOf("com.google.android.tvlauncher", "com.google.android.play.games"), tv.disabled)
        assertTrue("home is set before anything is turned off", tv.log.indexOfFirst { it.startsWith("cmd package set-home") } < tv.log.indexOfFirst { it.startsWith("pm disable-user") })
        assertFalse("android's own UI is never touched", tv.log.any { it.contains("systemui") && it.startsWith("pm disable") })
        assertTrue(e.isOn)

        val off = e.restore(tv)
        assertTrue(off.message, off.ok)
        assertTrue(tv.disabled.isEmpty())
        assertEquals("com.google.android.tvlauncher/.MainActivity", tv.home)
        assertFalse(e.isOn)
        assertTrue("the TV's home is enabled before the rest", tv.log.indexOf("pm enable com.google.android.tvlauncher") < tv.log.indexOf("pm enable com.google.android.play.games"))
    }

    @Test fun anAppThatSavedTheWifiIsNeverTurnedOff() {
        val tv = FakeTv(setOf("com.google.android.tvlauncher"), wifiOwners = "cname=com.google.android.tvlauncher")
        val (e, _) = engine(false, tv, listOf("com.google.android.tvlauncher"))
        val r = e.switchOn(tv)
        assertFalse(r.ok)
        assertTrue(tv.log.none { it.startsWith("pm disable") || it.startsWith("cmd package set-home") })
    }

    @Test fun anInputAppStaysOn() {
        val tv = FakeTv(setOf("com.google.android.play.games", "com.google.android.youtube.tvmusic"), needsInput = setOf("com.google.android.play.games"))
        val (e, _) = engine(false, tv, emptyList())
        e.switchOn(tv)
        assertEquals(setOf("com.google.android.youtube.tvmusic"), tv.disabled)
    }

    @Test fun fireRefusingTheDisableFallsBackToTheGuardAndStaysUndoable() {
        val tv = FakeTv(setOf("com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.device.software.ota"),
            home = "com.amazon.tv.launcher/.Home", refuse = setOf("com.amazon.tv.launcher", "com.amazon.firehomestarter"))
        val (e, _) = engine(true, tv, listOf("com.amazon.tv.launcher"))
        val r = e.switchOn(tv)
        assertTrue(r.needsGuard)
        assertTrue("only Fire's two home packages were even tried", tv.log.filter { it.startsWith("pm disable") }.all { it.endsWith("launcher") || it.endsWith("firehomestarter") })
        assertTrue(e.restore(tv).ok)
        assertEquals("com.amazon.tv.launcher/.Home", tv.home)
    }

    @Test fun aHomeThatDidNotChangeFallsBackToTheGuardEvenIfAndroidSaidSuccess() {
        val tv = FakeTv(setOf("com.amazon.tv.launcher"), home = "com.amazon.tv.launcher/.Home", refuse = setOf("com.amazon.tv.launcher"), ignoreSetHome = true)
        val (e, _) = engine(true, tv, listOf("com.amazon.tv.launcher"))
        val r = e.switchOn(tv)
        assertTrue(r.message, r.needsGuard)
        assertTrue(r.message.contains("com.amazon.tv.launcher"))
    }

    @Test fun anUnreadableWifiServiceStopsEverythingOnWifi() {
        val tv = FakeTv(setOf("com.google.android.play.games"), wifiOwners = "")
        val (e, _) = engine(false, tv, emptyList())
        assertFalse(e.switchOn(tv).ok)
        assertTrue(tv.disabled.isEmpty())
    }

    // ── The crash window ──────────────────────────────────────────────────────────────────────

    @Test fun threeCrashesInFiveMinutesTripTheNet() {
        val now = 1_000_000_000L
        assertEquals(3, HomeWatchdog.recent(listOf(now - 10, now - 120_000, now - 299_000), now))
        assertEquals("older than five minutes doesn't count", 2, HomeWatchdog.recent(listOf(now - 10, now - 120_000, now - 301_000), now))
        assertEquals("a time in the future doesn't count", 0, HomeWatchdog.recent(listOf(now + 5), now))
    }
}
