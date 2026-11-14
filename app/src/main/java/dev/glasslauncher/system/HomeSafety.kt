package dev.glasslauncher.system

// The PROTECTED and DEBLOAT lists and the CORE/FACTORY guards are ported from Free My TV's HomeMode.java
// (github.com/simbasjungles/free-my-tv, commit 0e33f16, MIT, Copyright (c) 2026 Free My TV contributors).
// The MIT text is in licenses/free-my-tv-MIT.txt and NOTICE.

/**
 * What the no-root Home switch may turn off ([HomeSwitch]). Checked before every disable: a TV that loses its picture, inputs, remote
 * or Settings app is worse than a TV with a stock home screen, so this errs toward refusing.
 */
object HomeSafety {
    /** Never turned off, not even by mistake (picture, sound, inputs, remote, store). */
    private val PROTECTED: Set<String> = setOf(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
        "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
        "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config",
        "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service", "com.tcl.initsetup",
        "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        // TCL on Google TV (C825, Android 11): system server, Bluetooth remote and mic, keys, settings and quick menu,
        // keyboard, webview, Wi-Fi overlay, partner setup
        "com.tcl.system.server", "com.tcl.tcl_bt_rcu_service", "com.tcl.micmanager", "com.tcl.keyhelp",
        "com.tcl.settings", "com.tcl.common.shortcutmenu", "com.tcl.inputmethod.international", "com.tcl.android.webview",
        "com.tcl.wifi.resources.overlay", "com.tcl.partnercustomizer", "com.tcl.tv.tclhome_passive",
        "com.tcl.keycustomfunctionservice",                                           // remote keys; a fallback home too
        // Hotack X1BR projector (LuminaOS, Android 14): its home, settings, Wi-Fi, Bluetooth and keystone in one (#342)
        "com.htc.luminaos",
        "com.tcl.channelplus",                                                        // the channel source TCL's Live TV tunes (off: black screen)
        "com.tcl.waterfall.overseas",                                                 // TCL Channel: partnercustomizer's entry and key open it
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.inputmethod.latin", "com.google.android.webview",
        "com.android.tv.settings", "com.android.systemui", "android",
        // Philips (TP Vision) and MediaTek: system, Ambilight, live TV and inputs, setup, factory menu (#11)
        "org.droidtv", "org.droidtv.ambisvc", "org.droidtv.ui.strings", "org.droidtv.tv.packageinfo", "org.droidtv.icp",
        "org.droidtv.gel", "org.droidtv.eum", "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.network",
        "com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.setupwizard", "com.mediatek.tv.factory",
        "mediatek.factorymenu.ui", "com.tpv.xmic.ots.cms.client",
        // Multilaser (Expressluck / CVTE): remote pairing and keys, live TV, media player, factory menus
        "com.express.bt.autopair", "com.mediatek.autopair", "com.mediatek.hotkey.dispatcher",
        "com.mediatek.android.tv.globalkeyoverlay", "com.cltv.fast", "com.mediatek.wwtv.mediaplayer",
        "com.mediatek.androidbox", "com.expressluck.facmenu", "com.express.at.factory.factorymanager",
        "com.at.factory.androidtvaidlservice", "com.cvte.tv.at", "com.cvte.tv.fac.aging", "com.express.fac.test",
        // Philco (Homwee/Changhong, MediaTek): picture tests, overlays, updates, factory menu (pairing is above)
        "com.mediatek.tv.factorykey", "com.mediatek.testpq", "com.mediatek.overlay.tvcenter",
        "com.mediatek.overlay.tvsettings", "com.homwee.overlay.tvservice", "com.homwee.synckey", "com.homwee.upgrade",
        "com.homwee.dmt.upgrade", "com.homwee.factory", "com.homwee.chinstallapk", "com.homwee.chtvmanager",
        "com.mstar.android.tv.disclaimercustomization",
        // Xiaomi (MediaTek): system service, remote pairing, live TV, quick settings, child lock, updates, casting, HDMI player (MIUI TV)
        "mitv.service", "com.mi.bluetooth", "com.mitv.livetv.ci_mediatek", "com.xiaomi.tvqs.overseas",
        "com.mitv.tvlock", "com.xiaomi.mitv.updateservice", "com.xiaomi.android.tvsetup.partnercustomizer",
        "com.mitv.overseaservice", "com.xiaomi.mitv.tvmanager", "com.xiaomi.wfdsinkhelperservice",
        "com.mitv.milinkservice", "com.xiaomi.mitv.smartshare", "android.autoinstalls.config.xiaomi.amelie",
        "com.xiaomi.mitv.tvplayer",
        // Skyworth (MediaTek): live TV, remote pairing and keys, setup, service menu, casting, OLED care, boot
        "com.smartdevice.livetv", "com.smartdevice.livetv.overlay_SANLUX", "com.smartdevice.bleconnectservice",
        "com.smartdevice.duplebluetooth", "com.smartdevice.myapp", "com.smartdevice.setupwraith",
        "com.smartdevice.aftersale", "com.smartdevice.factory", "com.smartdevice.dailymanage",
        "com.smartdevice.global.service", "com.smartdevice.dailyshortcut", "com.smartdevice.service", // remote, quick settings, system
        "com.smartdevice.dtv.icast", "com.smartdevice.leanbacklauncher.customizer", "com.mediatek.OledFuctionService",
        "com.mediatek.android.boot.appsplashscreen", "com.mediatek.android.leanbacklauncher.partnercustomizer",
        // KTC (NextStar; MediaTek): live TV and tuners, setup, settings, updates, casting, remote pairing, factory menus
        "com.mediatek.tv.oneworld.tvcenter", "com.mediatek.tis", "com.mediatek.tv.agent",
        "com.mediatek.dtv.tvinput.dvbtuner", "com.mediatek.tv.oneworld.wizardkt", "com.mediatek.tv.settings",
        "com.mediatek.tv.systemupdater", "com.mediatek.extservice", "com.mediatek.dialservice", "com.mediatek.miracast",
        "com.mediatek.tv.launcher.partner", "com.google.android.tv.googletvremotepairer", "com.ktc.serialport",
        "com.ktc.tv.factory", "ktc.factorymenu.ui",
        // Xiaomi projectors (Fengmi/Formovie, MediaTek): keystone, focus, picture and sound, system service, remote
        // pairing, settings provider, factory tests, launcher config, Freeview live TV
        "com.fengmi.projector.imaging", "com.fengmi.platform", "com.fengmi.Autopair", "com.fengmi.fmsettings",
        "com.fengmi.testsuite", "android.autoinstalls.config.fomovie",
        "uk.co.freeview.tifbridge",
        "uk.co.freeview.mdsclient", "uk.co.freeview.fvpconfigauth",
        // onn (Walmart; Amlogic S905Y4, 4K Streaming Box, Google TV 14): remote pairing, update notices, the remote's
        // app buttons (GLOBAL_BUTTON), hardware info, Amlogic system, settings, mic switch and launcher provider
        "com.onn.bluetoothconnect", "com.onn.btpairoverlay", "com.onn.updatenotification", "com.gretzky.GlobalKey",
        "com.gretzky.hwinfo", "com.droidlogic", "com.droidlogic.tv.settings", "com.droidlogic.mictoggle",
        "com.droidlogic.launcher.provider", "com.droidlogic.android.tv.connecteddevices",
        // Sony BRAVIA (MediaTek, Android TV 10): inputs and tuner, TV API and live TV, system and vendor services
        // (the remote's keys), settings, Bravia Sync (HDMI-CEC), picture and sound, updates, IP control, AirPlay and
        // HomeKit with the Vewd core (persistent: AirPlay crash-loops without it, CleanYourTV's UR3 list)
        "com.sony.dtv.tvinput.external", "com.sony.dtv.tvinput.hardwaremanager", "com.sony.dtv.tvinput.provider",
        "com.sony.dtv.tvinput.tuner", "com.sony.dtv.tvapi", "com.sony.dtv.tvx", "com.sony.dtv.sonysystemservice",
        "com.sony.dtv.sonyvendorservice", "com.sony.dtv.settingsprovider", "com.sony.dtv.braviasyncmenu",
        "com.sony.dtv.braviasyncservice", "com.sony.dtv.braviasyncsetting", "com.sony.dtv.panelsettings",
        "com.sony.dtv.audiooutput", "com.sony.dtv.acousticautocalibration", "com.sony.dtv.imeproxy",
        "com.sony.dtv.update", "com.sony.dtv.systemupdate", "com.sony.dtv.oadservice", "com.sony.dtv.otaurlprovisioning",
        "com.sony.dtv.upgradesetup", "com.sony.dtv.webapi.core", "com.sony.dtv.airplayapp", "com.sony.dtv.homekit",
        "com.vewd.core.service",
        // Wanbo projectors (T2 Ultra: a Skyworth board, Amlogic, Android TV 11): remote pairing and reconnect, Amlogic
        // overlay, settings and customization, projector service, Skyworth and OEM services, factory and QA tests
        "com.droidlogic.ble", "com.droidlogic.overlay", "com.mft.rcupair", "com.mtf.blereconnect", "com.mtf.customization",
        "com.mtf.tvsettingadapt", "com.sdt.projector.observer", "com.skyworth.superservice", "com.skyworth.smallclient",
        "com.oemservice", "com.oem.qa", "com.mft.producttest", "android.autoinstalls.config.AMLSTBAndroid.device",
        // HY300 projectors (Hotack, Allwinner H713; Amlogic txhd2): keystone and projection settings, the HDMI player,
        // Miracast receiver
        "com.htc.htcsettings", "com.softwinner.awlivetv", "com.softwinner.miracastReceiver", "com.softwinner.tcorrection",
        // Amazon Fire TV: launcher, settings, setup, remote pairing, inputs, live TV, channel scan, OTA updates, Appstore
        // (every com.amazon.* is refused by mayTurnOff too)
        "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.tv.settings.v2", "com.amazon.tv.settings.core",
        "com.amazon.tv.settings.mediatek", "com.amazon.tv.settingsprovider.configuration", "com.amazon.android.settings",
        "com.amazon.tv.quicksettings", "com.amazon.tv.quicksettings.ui", "com.amazon.tv.oobe", "com.amazon.device.blepa",
        "com.amazon.device.bluetoothpa", "com.amazon.device.bluetoothdfu", "com.amazon.autopairservice",
        "com.amazon.fireinputdevices", "com.amazon.tv.inputpreference.service", "com.amazon.franktvinput",
        "com.amazon.tv.livetv", "com.amazon.mediatek.livetv", "com.amazon.tv.channelscan",
        "com.amazon.tv.conditionalaccess", "com.amazon.tv.devicecontrol", "com.amazon.tv.devicecontrolsettings",
        "com.amazon.tv.routing", "com.amazon.tv.ime", "com.amazon.tv.keypolicymanager", "com.amazon.ftv.screensaver",
        "com.amazon.device.software.ota", "com.amazon.webview.chromium", "com.amazon.venezia", "amazon.fireos"
    )

    /** Turned off with the stock home, when the TV has them (Google and TV-maker extras that only add ads or telemetry). */
    val DEBLOAT: List<String> = listOf(
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
        // TCL
        "com.tcl.magiconnectfree", "com.tcl.miracast",                                // screen mirroring apps
        "com.tcl.messagebox", "com.tcl.usercenter",                                   // TCL messages and account
        "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",                   // store demo, hotel menu
        "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",        // repair guide, manual, logs
        // Multilaser (Expressluck / CVTE board)
        "tv.anoki.acr.anokiacroptin",                                                 // Anoki ACR: watches what's on screen
        "fusion.android.tv.demo", "com.expressluck.tvhelpmanual",                     // store demo (also Xiaomi projectors), manual
        // Philco (Homwee/Changhong)
        "com.homwee.aipont",                                                          // AI PONT: promo channels, app downloads
        "com.homwee.help",                                                            // user manual
        // Xiaomi
        "com.miui.tv.analytics", "com.xiaomi.statistic",                              // telemetry
        "com.mitv.tvhome.mitvplus", "com.xm.webcontent",                              // Mi TV+ promo channels, promo web content
        // Skyworth (VEON and other badges; MediaTek)
        "com.smartdevice.epopplayer", "com.coocaa.demonow.lightining",               // store demos (start on screen on)
        "com.smartdevice.recommendation",                                             // Coolita promo channels
        // KTC (NextStar; MediaTek)
        "tv.anoki.acr.controller",                                                    // Anoki ACR: watches what's on screen
        "com.mediatek.tv.retaildemo", "com.electronic.manual",                        // store demo, manual
        // Sony BRAVIA (BRAVIA_UR3, MediaTek, Android TV 10 tested; CleanYourTV's examples/sony-bravia-4k-ur3 list,
        // reboot-checked there on four sets)
        "tv.samba.ssm",                                                               // Samba TV ACR: watches what's on screen
        "com.qterics.da.product", "com.sony.dtv.da.service",                          // device agents (remote support)
        "com.sonyericsson.idd.agent", "com.sony.dtv.sonybugreportsys",                // usage data, bug-report uploader
        "com.sony.dtv.usercontext",                                                   // viewing context for recommendations
        "com.sony.dtv.promos", "com.sony.dtv.sonyselect",                             // promo apps
        "com.sony.dtv.demosupport", "com.sony.dtv.demosystemsupport", "com.sony.dtv.multiscreendemo",   // store demo
    )

    /** Fire's own launcher and its home starter: the only `com.amazon.*` packages [mayTurnOff] allows, and only on a Fire TV (P341). */
    val FIRE_HOME = listOf("com.amazon.tv.launcher", "com.amazon.firehomestarter")

    /** Android's own pieces: with one off a TV may not start again (CleanYourTV #884: the permission controller and package installer). */
    private val CORE = Regex("android|com\\.android\\.(systemui|shell|phone|settings|tv\\.settings)" +
        "|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)" +
        "|com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*")

    /** Factory and service menus, on any brand ("com.tcl.factory.view", "com.cvte.tv.fac.aging", "mediatek.factorymenu.ui"). */
    private val FACTORY = Regex("(.*\\.)?(factory|factorymenu|facmenu|fac|hkcfac|testpq)(\\..*)?")

    /** Checked before every disable: never the protected list, Amazon's packages (except Fire's home on a Fire), Android's own pieces or a factory menu. */
    fun mayTurnOff(pkg: String, fire: Boolean = false): Boolean {
        if (!SAFE_NAME.matches(pkg) || DeviceProfile.neverDisable(pkg)) return false
        if (fire && pkg in FIRE_HOME) return true
        return pkg !in PROTECTED && !pkg.startsWith("com.amazon.") && !CORE.matches(pkg) && !FACTORY.matches(pkg)
    }

    private val SAFE_NAME = Regex("[A-Za-z][\\w.]*")
    private val COMPONENT = Regex("[A-Za-z][\\w.]*/[\\w.$]+")

    /** [component] if it is a plain "package/class" (nested "Outer\$Inner" allowed), else "": the only shape that goes into a shell command. */
    fun safeComponent(component: String): String = if (COMPONENT.matches(component)) component else ""

    fun packageOf(component: String): String = component.substringBefore('/')
}
