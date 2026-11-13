package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.glasslauncher.app
import dev.glasslauncher.featured.open
import dev.glasslauncher.apps.AppEntry
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.appKey
import dev.glasslauncher.data.folderKey
import dev.glasslauncher.glass.GlassStyle
import dev.glasslauncher.glass.LocalBackdrop
import dev.glasslauncher.glass.glass
import dev.glasslauncher.settings.ImagePicker
import dev.glasslauncher.settings.SettingsPanel
import dev.glasslauncher.ui.FocusTile
import dev.glasslauncher.ui.Hint
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.MenuRow
import dev.glasslauncher.ui.MenuRowPair
import dev.glasslauncher.ui.SectionLabel
import dev.glasslauncher.ui.Shapes
import dev.glasslauncher.ui.Type
import dev.glasslauncher.system.PhoneField
import dev.glasslauncher.system.PhoneSetupServer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch

@Composable
fun OverlayContent(
    overlay: Overlay,
    model: HomeModel,
    layout: HomeLayout,
    cfg: LauncherConfig,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
    closeAll: () -> Unit,
    startMove: (String) -> Unit,
    /** The app being rearranged inside the open folder (null when none), and how to start and finish that. */
    folderMoving: String? = null,
    startFolderMove: (String) -> Unit = {},
    stopFolderMove: () -> Unit = {},
) {
    when (overlay) {
        is Overlay.AppMenu -> AnchoredMenu(active, overlay.anchor) { first -> AppMenuBody(overlay, model, first, open, closeAll, startMove, startFolderMove) }
        is Overlay.MoveTo -> AnchoredMenu(active, overlay.anchor) { first -> MoveToBody(overlay, model, layout, cfg, first, open, closeAll) }
        is Overlay.FolderMenu -> AnchoredMenu(active, overlay.anchor) { first ->
            // A context menu beside the folder's tile, like an app's (P105 #17), named for the folder it acts on.
            val palette = LocalPalette.current
            Text(
                overlay.folder.name, style = Type.secondary, color = palette.secondary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 2.dp).testTag("menu-header"),
            )
            MenuRow("Open", { closeAll(); open(Overlay.FolderOpen(overlay.folder.id)) }, Modifier.focusRequester(first))
            MenuRow("Rename", {
                open(Overlay.TextInput("Rename Folder", overlay.folder.name) { model.renameFolder(overlay.folder.id, it); closeAll() })
            })
            MenuRow("Move", { startMove(folderKey(overlay.folder.id)) })
            MenuRow("Remove Folder", {
                open(Overlay.Confirm("Remove “${overlay.folder.name}”?", "Its apps go back to Home; nothing is uninstalled.", "Remove Folder", destructive = true) {
                    model.deleteFolder(overlay.folder.id); closeAll()
                })
            })
        }
        is Overlay.FolderPicker -> SidePanel(active) {
            MenuList(active) { first ->
                PanelTitle("Move to Folder")
                MenuRow("New Folder", {
                    val id = model.newFolder(overlay.app.packageName, "New Folder")
                    closeAll()
                    open(Overlay.TextInput("Name Folder", "New Folder") { model.renameFolder(id, it); closeAll() })
                }, Modifier.focusRequester(first))
                cfg.folders.filter { f -> layout.grid.any { it.key == folderKey(f.id) } }.forEach { f ->
                    MenuRow(f.name, { model.addToFolder(overlay.app.packageName, f.id); closeAll() }, value = "${f.apps.size}")
                }
            }
        }
        is Overlay.IconPicker -> SidePanel(active, width = 520.dp) { IconPickerBody(overlay.app, model, active, open, closeAll) }
        is Overlay.TextInput -> SidePanel(active, width = 340.dp, compact = true, paneTitle = overlay.title) { TextInputBody(overlay, active, close, open) }
        is Overlay.PhoneSetup -> SidePanel(active, width = 460.dp) { PhoneSetupBody(overlay, active, close) }
        is Overlay.Confirm -> ConfirmCard(overlay, active, close)
        is Overlay.InstallConfirm -> InstallConfirmHost(overlay, active, close)
        is Overlay.AirPlayCode -> AirPlayCodeCard(overlay, active, close)
        Overlay.Tips -> FullOverlay(active, paneTitle = "Welcome to VITRE") { TipsCard(active) { model.edit { it.copy(tipsSeen = true) }; close() } }
        is Overlay.FolderOpen -> FolderView(overlay.folderId, overlay.anchor, model, layout, active, open, close, folderMoving, stopFolderMove)
        Overlay.Settings -> SettingsPage(active) { SettingsPanel(model, cfg, layout, active, open, close) }
        Overlay.ControlCenter -> ControlCenter({ model.edit(it) }, cfg, active, open, closeAll, close)
        Overlay.Notices -> NoticeDrawer(cfg, active, open, close)
        Overlay.AppSwitcher -> AppSwitcher(model, layout, cfg, active, closeAll)
        Overlay.Search -> SearchHost(model, layout, active, closeAll)
        Overlay.Remotes -> SettingsPage(active) { RemotesPanel(active) }
        Overlay.Downloads -> SettingsPage(active) { DownloadsPage(model, cfg, active, open, close) }
        is Overlay.DownloadMenu -> SidePanel(active) { DownloadMenuBody(overlay.id, active, open, close) }
        Overlay.Files -> SettingsPage(active) { FilesPage(cfg, active, open) }
        is Overlay.FileMenu -> SidePanel(active) { FileMenuBody(overlay.path, active, open, close) }
        Overlay.TvSettings -> SettingsPage(active) {
            val context = androidx.compose.ui.platform.LocalContext.current
            MenuList(active) { first ->
                PanelTitle("Settings")
                // Fire OS's own quick menu first: it is the panel the Settings key opens, and the one place the TV's
                // picture/audio/sleep controls live together (P280).
                MenuRow("Quick Menu", { dev.glasslauncher.system.SystemControls.openQuickMenu(context) }, Modifier.focusRequester(first), chevron = true)
                dev.glasslauncher.system.SystemControls.tvSettingsSections.forEachIndexed { i, section ->
                    MenuRow(section.title, { dev.glasslauncher.system.SystemControls.openTvSettings(context, section) }, Modifier, chevron = true)
                }
            }
        }
    }
}

/** Scrollable column of menu rows that grabs focus whenever its overlay becomes the active one. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MenuList(active: Boolean, compact: Boolean = false, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(active) {
        if (active) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll) {
        Column(
            // tvOS 27: slim rows (about 35 dp) with a clear gap between them.
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = if (compact) 16.dp else 22.dp),
        ) { content(first) }
    }
}

@Composable
fun PanelTitle(text: String) {
    val sink = LocalTitleSink.current
    if (sink != null) {
        // Full-page layouts show the title centred at the top instead, and this page's captions on the left.
        val page = LocalPageKey.current
        LaunchedEffect(text, page) { sink.title = text; sink.page = page }
        return
    }
    Text(text, style = Type.title, color = LocalPalette.current.primary, modifier = Modifier.padding(start = 18.dp, bottom = 12.dp, top = 4.dp))
}

@Composable
private fun ColumnScope.AppMenuBody(
    overlay: Overlay.AppMenu,
    model: HomeModel,
    first: FocusRequester,
    open: (Overlay) -> Unit,
    closeAll: () -> Unit,
    startMove: (String) -> Unit,
    startFolderMove: (String) -> Unit,
) {
    val app = overlay.app
    // tvOS context menu: a short list beside the tile; Move to… opens a second list in the same place.
    var firstRow = Modifier.focusRequester(first)
    fun f(): Modifier = firstRow.also { firstRow = Modifier }
    val palette = LocalPalette.current
    // The menu covers part of its tile, so it names the app it acts on.
    Text(
        app.label, style = Type.secondary, color = palette.secondary, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 2.dp).testTag("menu-header"),
    )
    if (overlay.folderId == null) {
        MenuRow("Edit Home Screen", { startMove(appKey(app.packageName)) }, f(), leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_tv) })
    } else {
        // Inside an open folder: move this app among the folder's apps (the same move mode, scoped to the folder).
        MenuRow("Rearrange Folder", { startFolderMove(appKey(app.packageName)) }, f(), leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_apps) })
    }
    MenuRow("Move to…", { open(Overlay.MoveTo(app, overlay.inDock, overlay.folderId, overlay.anchor)) }, f(), chevron = true,
        leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_drive_file_move) })
    MenuRow("Change Icon", { open(Overlay.IconPicker(app)) }, leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_image) })
    MenuRow("Hide", { model.hide(app.packageName); closeAll() }, leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_visibility_off) })
    MenuRow("App Info", { closeAll(); model.appInfo(app) }, leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_info) })
    // The destructive action sits apart from the rest, as in tvOS menus.
    // Equal room above and below (the list's 7 dp row gap plus 5 dp each side), so the line sits centred in a clear gap.
    Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(palette.secondary.copy(alpha = 0.25f)).testTag("menu-separator"))
    MenuRow("Uninstall", { closeAll(); model.uninstall(app) }, destructive = true, leading = { MenuIcon(dev.glasslauncher.R.drawable.ic_do_not_disturb_on) })
}

/** Where an app can go: the App Dock, back to the home screen, an existing folder, or a new one. */
@Composable
private fun ColumnScope.MoveToBody(
    overlay: Overlay.MoveTo,
    model: HomeModel,
    layout: HomeLayout,
    cfg: dev.glasslauncher.data.LauncherConfig,
    first: FocusRequester,
    open: (Overlay) -> Unit,
    closeAll: () -> Unit,
) {
    val app = overlay.app
    var firstRow = Modifier.focusRequester(first)
    fun f(): Modifier = firstRow.also { firstRow = Modifier }
    when {
        overlay.inDock -> MenuRow("Home Screen", { model.removeFromDock(app.packageName); closeAll() }, f())
        overlay.folderId != null -> {
            MenuRow("Home Screen", { model.removeFromFolder(app.packageName, overlay.folderId); closeAll() }, f())
            MenuRow("App Dock", { model.addToDock(app.packageName); closeAll() })
        }
        else -> MenuRow("App Dock", { model.addToDock(app.packageName); closeAll() }, f())
    }
    cfg.folders.filter { fo -> fo.id != overlay.folderId && layout.grid.any { it.key == folderKey(fo.id) } }.forEach { fo ->
        MenuRow(fo.name, { model.addToFolder(app.packageName, fo.id); closeAll() }, value = "${fo.apps.size}")
    }
    MenuRow("New Folder", {
        val id = model.newFolder(app.packageName, "New Folder")
        closeAll()
        open(Overlay.TextInput("Name Folder", "New Folder") { model.renameFolder(id, it); closeAll() })
    })
}

/**
 * Menu glyphs drawn as platform VectorDrawables (P204). A Compose vector painter builds a small composition of its own the first
 * time each icon is drawn (seven of them were most of the app menu's first-frame applyChanges), and a tinted bitmap needs a
 * renderer program the vectors never did (a 300 ms first frame on the stick). A VectorDrawable records straight into the display
 * list. [warm] inflates each one before the first menu opens, off the main thread; every draw gets its own copy, since the tint is
 * state on the drawable.
 */
internal object MenuIcons {
    private val states = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.drawable.Drawable.ConstantState>()
    val ALL = intArrayOf(
        dev.glasslauncher.R.drawable.ic_tv, dev.glasslauncher.R.drawable.ic_apps, dev.glasslauncher.R.drawable.ic_drive_file_move,
        dev.glasslauncher.R.drawable.ic_image, dev.glasslauncher.R.drawable.ic_visibility_off, dev.glasslauncher.R.drawable.ic_info,
        dev.glasslauncher.R.drawable.ic_do_not_disturb_on,
    )

    fun drawable(context: android.content.Context, @androidx.annotation.DrawableRes icon: Int): android.graphics.drawable.Drawable? {
        val state = states[icon] ?: androidx.core.content.ContextCompat.getDrawable(context, icon)?.constantState?.also { states[icon] = it } ?: return null
        return state.newDrawable(context.resources).mutate()
    }

    /** The app menu's glyphs, inflated ahead of time. */
    fun warm(context: android.content.Context) = ALL.forEach { runCatching { drawable(context, it) } }
}

@Composable
internal fun MenuIcon(@androidx.annotation.DrawableRes icon: Int) {
    val context = LocalContext.current
    val drawable = remember(icon) { MenuIcons.drawable(context, icon) }
    val tint = androidx.tv.material3.LocalContentColor.current
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        drawable ?: return@Canvas
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawable.setTint(tint.toArgb())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

@Composable
private fun IconPickerBody(app: AppEntry, model: HomeModel, active: Boolean, open: (Overlay) -> Unit, closeAll: () -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(start = 14.dp, top = 22.dp, end = 14.dp)) {
        PanelTitle("Icon for ${app.label}")
        val first = remember { FocusRequester() }
        LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
        MenuRow("Use Default", { model.setCustomIcon(app.packageName, null); closeAll() }, Modifier.focusRequester(first))
        MenuRow("Image from URL…", {
            open(Overlay.TextInput("Icon Image URL", "https://", "Square images get a generated tile; wide images fill the tile.") { url ->
                scope.launch {
                    status = "Downloading…"
                    val path = graph.wallpapers.downloadImage(url, "icon-${app.packageName}-${System.currentTimeMillis()}")
                    if (path != null) { model.setCustomIcon(app.packageName, path); closeAll() } else status = "Couldn't load that image."
                }
            })
        })
        status?.let { Hint(it) }
        SectionLabel("On this device")
        ImagePicker(
            modifier = Modifier.weight(1f),
            // The panel's 22 dp bottom inset, inside the grid so a focused tile's shadow isn't cut short.
            bottomInset = 22.dp,
            onPicked = { uri ->
                scope.launch {
                    val path = graph.wallpapers.importImage(uri, "icon-${app.packageName}-${System.currentTimeMillis()}")
                    if (path != null) { model.setCustomIcon(app.packageName, path); closeAll() } else status = "Couldn't read that image."
                }
            },
        )
    }
}

/**
 * The one text field Glass uses (rename, names, URLs, and Search): a plain field that opens the device's own keyboard, in a soft rounded
 * row. On Fire TV that keyboard is full-screen and modal and opens whenever a field takes focus, so callers show this only while editing
 * and keep a normal row otherwise. [onFocusLost] fires when focus leaves the field after it had it (the keyboard was closed).
 */
@Composable
internal fun SystemTextField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    /** Back with the keyboard up: end the editing and put focus back on the caller's row. Defaults to [onFocusLost]. */
    onBack: () -> Unit = onFocusLost,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
    /** What a screen reader says for the field (its text is read as the value). */
    label: String? = null,
    /** Search keeps the field in a fixed 44 dp box (P323), so it needs less than the default. */
    verticalPadding: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val palette = LocalPalette.current
    var hadFocus by remember { mutableStateOf(false) }
    // Back is taken before the keyboard sees it only while this field has focus, i.e. while its keyboard is up (P230).
    var focused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Type.heading.copy(color = palette.primary),
        cursorBrush = SolidColor(palette.primary),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onAction() }, onNext = { onAction() }, onGo = { onAction() }, onSearch = { onAction() }, onSend = { onAction() }),
        modifier = modifier
            .then(if (label != null) Modifier.semantics { this.contentDescription = label } else Modifier)
            // Back before the keyboard sees it (Android's pre-IME stage, no accessibility service needed): while the keyboard is up, Back
            // closes it and ends the editing through [onBack], which puts focus on the caller's own row, so the next Back is the
            // screen's. Not taken when the field has no focus. (A clearFocus here left focus on the field and swallowed every later Back.)
            .onInterceptKeyBeforeSoftKeyboard { e ->
                if (!focused || e.key != androidx.compose.ui.input.key.Key.Back) false
                else {
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) { keyboardController?.hide(); onBack() }
                    true
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                // onFocusChanged also fires on attach with the initial unfocused state; only a real loss of focus ends it.
                focused = it.isFocused
                if (it.isFocused) hadFocus = true else if (hadFocus) onFocusLost()
            }
            .background(palette.primary.copy(alpha = 0.16f), Shapes.row)
            .padding(horizontal = 18.dp, vertical = verticalPadding),
    )
}

@Composable
private fun TextInputBody(overlay: Overlay.TextInput, active: Boolean, close: () -> Unit, open: (Overlay) -> Unit) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(overlay.initial) }
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val editRow = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Fire TV's keyboard is full-screen and modal and opens whenever a text field takes focus, so the
    // real field is only focusable while editing; otherwise the value sits in a normal row.
    LaunchedEffect(active) { if (active && !editing) { withFrameNanos { }; runCatching { editRow.requestFocus() } } }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { field.requestFocus() }
            keyboard?.show()
        } else if (active) {
            // Editing ended (keyboard closed or focus moved): give focus back to the value row.
            withFrameNanos { }
            runCatching { editRow.requestFocus() }
        }
    }
    DisposableEffect(overlay) { onDispose { overlay.onClosed?.invoke() } }
    var submitted by remember { mutableStateOf(false) }
    fun submit() {
        if (submitted) return
        submitted = true
        keyboard?.hide(); close(); overlay.onDone(if (overlay.isPassword) text else text.trim())
    }
    MenuList(active && !editing, compact = true) { _ ->
        PanelTitle(overlay.title)
        if (editing) {
            SystemTextField(
                value = text, onValueChange = { text = it }, focusRequester = field,
                onFocusLost = { if (editing) editing = false },
                isPassword = overlay.isPassword, keyboardType = if (overlay.isPassword) KeyboardType.Password else KeyboardType.Uri,
                imeAction = ImeAction.Done, onAction = { submit() }, label = overlay.title,
                modifier = Modifier.fillMaxWidth().testTag("text-input"),
            )
        } else {
            MenuRow(
                title = if (text.isEmpty()) "Empty" else if (overlay.isPassword) "\u2022".repeat(text.length) else text,
                onClick = { editing = true },
                modifier = Modifier.focusRequester(editRow).testTag("text-value"),
                value = "Edit",
            )
        }
        if (overlay.hint.isNotEmpty()) Hint(overlay.hint)
        val pasteContext = androidx.compose.ui.platform.LocalContext.current
        overlay.paste?.let { read ->
            MenuRow("Paste", {
                val clip = read()
                if (clip != null) text = clip else dev.glasslauncher.system.RemoteNotice.show(pasteContext, "The clipboard has no link")
            }, modifier = Modifier.testTag("text-paste"))
        }
        Spacer(Modifier.height(8.dp))
        if (!overlay.isPassword && overlay.offerPhone) MenuRow("Type on Your Phone", {
            open(Overlay.PhoneSetup(overlay.title, listOf(PhoneField("value", overlay.title, text, overlay.hint))) { values ->
                values["value"]?.takeIf { it.isNotBlank() }?.let { text = it; submit() }
            })
        }, value = "QR code")
        MenuRowPair("Done", { submit() }, "Cancel", { keyboard?.hide(); close() })
        if (overlay.suggestions.isNotEmpty()) {
            SectionLabel("Recent")
            overlay.suggestions.forEach { s -> MenuRow(s, { text = s; submit() }) }
        }
    }
}

@Composable
private fun FolderView(
    folderId: String,
    anchor: androidx.compose.ui.geometry.Rect?,
    model: HomeModel,
    layout: HomeLayout,
    active: Boolean,
    open: (Overlay) -> Unit,
    close: () -> Unit,
    /** The app being rearranged (an app key), if any: D-pad moves it among the folder's apps, Select or Back ends it. */
    rearranging: String? = null,
    stopRearranging: () -> Unit = {},
) {
    val folder = layout.grid.filterIsInstance<GridItem.FolderItem>().firstOrNull { it.folder.id == folderId }
    LaunchedEffect(folder == null) { if (folder == null) close() }
    folder ?: return
    val palette = LocalPalette.current
    val lastFocused = remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    LaunchedEffect(active, folder.apps.size) {
        if (active) {
            withFrameNanos { }
            val target = lastFocused.value?.let { requesters[it] } ?: folder.apps.firstOrNull()?.let { requester(appKey(it.packageName)) }
            runCatching { target?.requestFocus() }
        }
    }
    // Rearranging: focus follows the moved app to its new place, and its tile and the others glide there.
    LaunchedEffect(folder.apps, rearranging) {
        if (rearranging != null && active) { withFrameNanos { }; runCatching { requester(rearranging).requestFocus() } }
    }
    // The app being moved went away (hidden or uninstalled): the move is over.
    LaunchedEffect(folder.apps, rearranging) {
        if (rearranging != null && folder.apps.none { appKey(it.packageName) == rearranging }) stopRearranging()
    }
    val swallowSelect = remember { booleanArrayOf(false) }
    val rtl = dev.glasslauncher.ui.isRtl()
    val glide = remember { GlideTracker() }
    remember(folder.apps) { if (rearranging != null) glide.generation++; glide.generation }
    // Provided only while rearranging (and for the tail of the last glide), as on Home.
    var gliding by remember { mutableStateOf(false) }
    LaunchedEffect(rearranging != null) {
        if (rearranging != null) gliding = true else { kotlinx.coroutines.delay(GLIDE_TAIL_MS); gliding = false; glide.reset() }
    }
    // The home screen behind is shown blurred (a snapshot, Home not drawn), with a frosted panel and a
    // capsule name above it. The blur is not a one-frame cut (P192: that was the flash): the backdrop starts as Home's own picture and is
    // blurred along with the panel's growth out of the folder tile, and goes back the same way on close.
    // tvOS: the panel grows out of the folder tile (about 170 ms of travel, settled by ~500 ms) and shrinks back into it on close.
    val morph = remember { Animatable(0f) }
    val folderLeaving = LocalOverlayExiting.current
    LaunchedEffect(folderLeaving) {
        if (folderLeaving) morph.animateTo(0f, tween(170, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        // Settles in ~400 ms (was ~1 s at stiffness 260: a long tail of full-panel redraws).
        else morph.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f))
    }
    SnapshotBackdrop({ morph.value.coerceIn(0f, 1f) }, LocalBackdrop.current.overlay, fallbackGlass = true)
    FullOverlay(active, paneTitle = "Folder") {
        val leaving = folderLeaving
        var panelBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    val e = ev.nativeKeyEvent
                    val select = e.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                    // The Select that ended the move: its repeats and its key-up must not reach the tile under it.
                    if (select && swallowSelect[0]) { if (e.action == AndroidKeyEvent.ACTION_UP) swallowSelect[0] = false; return@onPreviewKeyEvent true }
                    val key = rearranging ?: return@onPreviewKeyEvent false
                    // Back ends it through the home screen's BackHandler.
                    if (e.keyCode == AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (e.action == AndroidKeyEvent.ACTION_DOWN) {
                        val pkg = key.removePrefix("app:")
                        val columns = FolderGeometry.COLUMNS
                        when (e.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> model.moveInFolder(folderId, pkg, dev.glasslauncher.ui.horizontalStep(e.keyCode, rtl), 0, columns)
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> model.moveInFolder(folderId, pkg, 0, -1, columns)
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> model.moveInFolder(folderId, pkg, 0, 1, columns)
                            else -> if (select) { swallowSelect[0] = true; stopRearranging() }
                        }
                    }
                    true
                }
                .onGloballyPositioned { panelBounds = it.boundsInWindow() }
                .graphicsLayer {
                    val from = anchor; val to = panelBounds
                    if (from != null && to != null && to.width > 0f) {
                        val m = morph.value
                        val s0 = (from.width / FolderGeometry.panelWidth.toPx()).coerceIn(0.15f, 1f)
                        val sc = s0 + (1f - s0) * m
                        scaleX = sc; scaleY = sc
                        translationX = (from.center.x - to.center.x) * (1f - m)
                        translationY = (from.center.y - to.center.y) * (1f - m)
                    }
                },
        ) {
            Spacer(Modifier.height(FolderGeometry.capsuleTop))
            FocusTile(
                label = "Folder name, ${folder.folder.name}",
                shape = Shapes.pill,
                focusedScale = 1.06f,
                shadow = false,
                onClick = { open(Overlay.TextInput("Rename Folder", folder.folder.name) { model.renameFolder(folderId, it) }) },
                modifier = Modifier.testTag("folder-title"),
            ) { focused ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = FolderGeometry.capsuleWidth, minHeight = FolderGeometry.capsuleHeight)
                        // Glass stays put under the focus fill (a swapped-in glass node can draw before it's positioned).
                        .glass(LocalBackdrop.current, Shapes.pill, GlassStyle.panel(palette.light))
                        .then(
                            if (focused) Modifier.background(palette.focusFill, Shapes.pill) else Modifier,
                        )
                        .padding(horizontal = 24.dp),
                ) {
                    Text(
                        folder.folder.name,
                        style = Type.heading,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (focused) palette.onFocusFill else palette.primary,
                    )
                }
            }
            Spacer(Modifier.height(FolderGeometry.panelTop - FolderGeometry.capsuleTop - FolderGeometry.capsuleHeight))
            // A fixed 3 x 3 panel (tvOS keeps its size however few apps are in it); more than nine scroll.
            val panelShape = RoundedCornerShape(FolderGeometry.panelRadius)
            Box(
                Modifier
                    .size(FolderGeometry.panelWidth, FolderGeometry.panelHeight)
                    .glass(LocalBackdrop.current, panelShape, GlassStyle.panel(palette.light))
                    .clip(panelShape)
                    .testTag("folder-panel"),
            ) {
                val folderAnchor = LocalMenuAnchor.current
                androidx.compose.runtime.CompositionLocalProvider(LocalGlide provides glide.takeIf { gliding || rearranging != null }) {
                // The scroller fills the panel (its clip must not cut a focused tile's shadow or name short).
                Column(
                    verticalArrangement = Arrangement.spacedBy(FolderGeometry.rowGap),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = FolderGeometry.padX, end = FolderGeometry.padX, top = FolderGeometry.padY, bottom = FolderGeometry.padBottom),
                ) {
                    folder.apps.chunked(FolderGeometry.COLUMNS).forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FolderGeometry.colGap)) {
                            row.forEachIndexed { i, app ->
                                val key = appKey(app.packageName)
                                Box(Modifier.width(FolderGeometry.tileWidth).glide(key, lifted = rearranging == key)) {
                                    AppCell(
                                        app = app,
                                        model = model,
                                        moving = rearranging == key,
                                        focusRequester = requester(key),
                                        onFocused = { lastFocused.value = key },
                                        // The name shows under the focused tile; no row space is reserved for it (the pitch is tvOS's).
                                        showLabel = false,
                                        floatingLabel = true,
                                        onMenu = { open(Overlay.AppMenu(app, inDock = false, folderId = folderId, anchor = folderAnchor.bounds)) },
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        if (rearranging != null) MoveBanner(rearranging, layout, Modifier.align(Alignment.BottomCenter), inFolder = true)
    }
}

/**
 * tvOS 27's open folder, measured from the 1080p reference and halved to dp: a 604 x 385 dp panel (fixed:
 * three columns by three rows) with Home-sized tiles on a 200 x 121 dp pitch, and the name in a capsule above.
 */
internal object FolderGeometry {
    const val COLUMNS = 3
    const val ROWS = 3
    private val home = dev.glasslauncher.ui.Metrics()
    /** Home's tile on the 960 dp screen at normal text size (6 columns): 125 dp. The folder does not follow Text Size. */
    val tileWidth: Dp = (960.dp - home.inset * 2 - home.gutter * 5) / 6
    val tileHeight: Dp = tileWidth / home.tileAspect
    /** Left edge to left edge: 400 px. */
    val colPitch: Dp = tileWidth * 1.6f
    val colGap: Dp = colPitch - tileWidth
    /** Top to top: 242 px. */
    val rowPitch: Dp = 121.dp
    val rowGap: Dp = rowPitch - tileHeight
    val padX: Dp = 39.5.dp
    val padY: Dp = 26.dp
    /** Under the last row: room for the focused tile's name. */
    val padBottom: Dp = 42.dp
    val panelWidth: Dp = padX * 2 + tileWidth * COLUMNS + colGap * (COLUMNS - 1)
    val panelHeight: Dp = 385.dp
    val panelRadius: Dp = 35.dp
    /** Panel and capsule tops, from the screen top (150 and 28 px). */
    val panelTop: Dp = 75.dp
    val capsuleTop: Dp = 14.dp
    val capsuleWidth: Dp = 115.dp
    val capsuleHeight: Dp = 50.dp

    /** Rows [apps] apps take; more than [ROWS] scroll. */
    fun rows(apps: Int): Int = (apps + COLUMNS - 1) / COLUMNS
    fun scrolls(apps: Int): Boolean = rows(apps) > ROWS

    /** Where tile [index] sits inside the panel, before scrolling. */
    fun tileOffset(index: Int): Pair<Dp, Dp> = Pair(padX + colPitch * (index % COLUMNS), padY + rowPitch * (index / COLUMNS))
}

/** How long the phone form stays open before the panel closes itself. */
internal const val PHONE_SETUP_MAX_MS = 120_000L

@Composable
private fun PhoneSetupBody(overlay: Overlay.PhoneSetup, active: Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var status by remember { mutableStateOf("Starting…") }
    var closed by remember { mutableStateOf(false) }
    fun closeOnce() { if (!closed) { closed = true; close() } }
    val server = remember {
        PhoneSetupServer(overlay.title, overlay.fields) { values ->
            status = "Received ✓"
            overlay.onSubmit(values)
            kotlinx.coroutines.delay(900)
            closeOnce()
        }
    }
    // The form is open on the LAN only briefly.
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(server) { kotlinx.coroutines.delay(PHONE_SETUP_MAX_MS); expired = true }
    // Closing pops the top panel: only when this one is on top, so a card opened over it is never the one that goes.
    LaunchedEffect(expired, active) { if (expired && active) closeOnce() }
    DisposableEffect(server) {
        scope.launch {
            val u = server.start(scope)
            url = u
            qr = u?.let { PhoneSetupServer.qr(it) }
            status = if (u == null) "Connect the TV to Wi-Fi or Ethernet to use your phone." else "Waiting for your phone…"
        }
        onDispose { server.stop() }
    }
    MenuList(active) { first ->
        PanelTitle(overlay.title)
        Hint("Scan with your phone's camera, fill in the form, and tap Send. Your phone must be on the same network.")
        qr?.let {
            Box(
                Modifier
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .size(210.dp)
                    .background(Color.White, Shapes.row)
                    .padding(12.dp),
            ) {
                Image(it, "QR code for $url", filterQuality = androidx.compose.ui.graphics.FilterQuality.None, modifier = Modifier.fillMaxSize())
            }
        }
        url?.let { Text(it, style = Type.caption, color = palette.secondary, modifier = Modifier.padding(horizontal = 18.dp)) }
        Text(status, style = Type.body, color = palette.primary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp).testTag("phone-status"))
        MenuRow("Done", { closeOnce() }, Modifier.focusRequester(first))
    }
}

/** One-time orientation for the remote: where settings live and how to edit apps. */
@Composable
private fun TipsCard(active: Boolean, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        GlassBoxCard(Modifier.width(620.dp)) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Welcome to VITRE", style = Type.title, color = palette.primary)
                Tip(dev.glasslauncher.R.drawable.ic_edit_square, "Press Menu, or hold Select, on any app to move it, hide it, change its icon or put it in a folder.")
                Tip(dev.glasslauncher.R.drawable.ic_settings, "Settings are at the top right. Press Up from the top row.")
                Tip(dev.glasslauncher.R.drawable.ic_play_arrow, "Press Play/Pause on an app or a featured title to open it straight away.")
                Tip(dev.glasslauncher.R.drawable.ic_smartphone, "Long text like API keys can be typed on your phone. Look for \"Type on Your Phone\".")
                Spacer(Modifier.height(6.dp))
                MenuRow("Get Started", dismiss, Modifier.focusRequester(first))
            }
        }
    }
}

/**
 * tvOS's confirmation card (the logic, not tvOS 26's look): a compact glass card over a dim, the title and
 * what will happen, then the action first and focused (red when destructive) and Cancel beside it (half-width pair, P154).
 */
@Composable
private fun ConfirmCard(c: Overlay.Confirm, active: Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    AlertCard(active) {
        Text(c.title, style = Type.heading, color = palette.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Text(c.message, style = Type.secondary, color = palette.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Spacer(Modifier.height(6.dp))
        // A destructive card opens on Cancel: a stray Select must not replace or remove anything.
        MenuRowPair(
            c.confirm, { close(); c.onConfirm() }, "Cancel", close,
            leftModifier = if (c.destructive) Modifier else Modifier.focusRequester(first),
            rightModifier = if (c.destructive) Modifier.focusRequester(first) else Modifier,
            leftDestructive = c.destructive,
        )
    }
}

/** The alert chrome shared by the confirm cards: a dim, then a glass card (about 252 dp) holding [content]. */
@Composable
internal fun AlertCard(active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    // tvOS's alert (P105 #6): the screen dims first (about 200 ms), then the card fades in at full size 80 ms later, with
    // no scale; the scrim is its own layer, not part of anything scaled. Leaving runs it back, quicker.
    val dim = remember { androidx.compose.animation.core.Animatable(0f) }
    val card = remember { androidx.compose.animation.core.Animatable(0f) }
    val leaving = dev.glasslauncher.home.LocalOverlayExiting.current
    val reduceMotion = dev.glasslauncher.ui.LocalUiPrefs.current.reduceMotion
    LaunchedEffect(leaving) {
        if (reduceMotion) { dim.snapTo(if (leaving) 0f else 1f); card.snapTo(if (leaving) 0f else 1f); return@LaunchedEffect }
        if (leaving) {
            launch { card.animateTo(0f, tween(70)) }
            dim.animateTo(0f, tween(140))
        } else {
            launch { dim.animateTo(1f, tween(200)) }
            kotlinx.coroutines.delay(80)
            card.animateTo(1f, tween(100))
        }
    }
    Box(Modifier.fillMaxSize().trapFocus(active), contentAlignment = Alignment.Center) {
        // Drawn, not a graphicsLayer alpha: no layer for a full-screen fade (the draw reads the animation directly).
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val a = dim.value
            if (a > 0.001f) drawRect(Scrim, alpha = a)
        }
        Box(Modifier.graphicsLayer { alpha = card.value }) {
            // About 252 dp (tvOS's 504 px alert), the title and message leading, the buttons centred.
            GlassBoxCard(Modifier.width(252.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.Start, content = content)
            }
        }
    }
}

/** The install card over a dim, like [ConfirmCard]; leaving without Install discards the staged copy. */
@Composable
internal fun InstallConfirmHost(o: Overlay.InstallConfirm, active: Boolean, close: () -> Unit) {
    var confirmed by remember { androidx.compose.runtime.mutableStateOf(false) }
    val discard by androidx.compose.runtime.rememberUpdatedState(o.onDiscard)
    DisposableEffect(Unit) { onDispose { if (!confirmed) discard() } }
    Box(Modifier.fillMaxSize().background(Scrim).trapFocus(active), contentAlignment = Alignment.Center) {
        dev.glasslauncher.system.InstallConfirmCard(
            o.staged.info, 300.dp, onCancel = close, unchecked = o.unchecked,
            onConfirm = { confirmed = true; close(); o.onConfirm() },
        )
    }
}

@Composable
private fun Tip(@androidx.annotation.DrawableRes icon: Int, text: String) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(
            androidx.compose.ui.res.painterResource(icon), null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(palette.primary),
            modifier = Modifier.size(26.dp),
        )
        Text(text, style = Type.secondary, color = palette.secondary)
    }
}

@Composable
private fun GlassBoxCard(modifier: Modifier, content: @Composable () -> Unit) {
    dev.glasslauncher.ui.GlassBox(modifier = modifier, shape = Shapes.panel) { content() }
}

/** Control Center's Remotes tile (P100): each paired remote with its state, then how to pair another. */
@Composable
private fun RemotesPanel(active: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) { while (active) { kotlinx.coroutines.delay(5_000); tick++ } }
    val rows = remember(tick) { dev.glasslauncher.system.RemoteList.read(context) }
    MenuList(active) { first ->
        PanelTitle("Remotes")
        if (rows.isEmpty()) {
            MenuRow("No remote paired", {}, Modifier.focusRequester(first))
        } else rows.forEachIndexed { i, r ->
            MenuRow(r.name, {}, if (i == 0) Modifier.focusRequester(first) else Modifier, value = r.line)
        }
        SectionLabel("Pair a remote")
        // Plain text, not a row: nothing here can be selected, so focus never lands on it.
        Hint("To pair a remote: hold Home on it for 10 seconds.")
        MenuRow("Fire TV remote settings", { runCatching { dev.glasslauncher.system.SystemControls.openGameControllers(context) } }, chevron = true)
        Hint("VITRE pairs a remote on its own while none is connected. If the remote still doesn't pair, hold Left + Menu + Back for 12 seconds, then hold Home for 10 seconds.")
    }
}

/** The search page (P161): a calm full page; Select on a result opens it and leaves search, a failure says so in a capsule. */
@Composable
private fun SearchHost(model: HomeModel, layout: HomeLayout, active: Boolean, closeAll: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = context.app
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val palette = LocalPalette.current
    val ask = LocalConfirm.current
    val installed by androidx.compose.runtime.rememberUpdatedState(layout.installed)
    val searchHttp = remember { dev.glasslauncher.net.PublicHttp.client() }
    val search = remember {
        dev.glasslauncher.search.SearchModel(
            scope, { installed },
            {
                // What Glass already knows: Continue Watching and the other apps' rows (with the system app permission), and the Top Shelf's titles.
                val rows = if (dev.glasslauncher.featured.TvRows.available(context)) runCatching { dev.glasslauncher.featured.TvRows.continueWatching(context).items }.getOrDefault(emptyList()) else emptyList()
                rows + graph.featured.state.value.feed?.items.orEmpty()
            },
            { q -> dev.glasslauncher.search.CinemetaSearch.search(searchHttp, q) },
        )
    }
    FullOverlay(active, scale = false, paneTitle = "Search") {
        Box(Modifier.fillMaxSize().background(if (palette.light) Color(0xFFEFEFF4) else Color(0xFF14161C))) {
            dev.glasslauncher.search.SearchScreen(
                search, active,
                onOpen = { hit ->
                    val opened = when {
                        hit.app != null -> { model.launch(hit.app); true }
                        hit.music != null -> dev.glasslauncher.search.MusicSources.open(context, hit.music)
                        hit.item != null -> hit.item.open(context)
                        else -> false
                    }
                    if (opened) closeAll() else dev.glasslauncher.system.RemoteNotice.show(context, "Couldn't open ${hit.title}.")
                },
                askClear = { onConfirm ->
                    ask(Overlay.Confirm(
                        dev.glasslauncher.search.SearchText.CLEAR_TITLE, dev.glasslauncher.search.SearchText.CLEAR_MESSAGE,
                        dev.glasslauncher.search.SearchText.CLEAR_BUTTON, destructive = true, onConfirm = onConfirm,
                    ))
                },
            )
        }
    }
}
