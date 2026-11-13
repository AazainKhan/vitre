package dev.glasslauncher.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Text
import dev.glasslauncher.system.AppNotice
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.LocalUiPrefs
import dev.glasslauncher.ui.Motion
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.isSelectKey
import dev.glasslauncher.ui.navigationSound

private val CardShape = RoundedCornerShape(20.dp)

/**
 * One app's notifications (P325). A single one is a card; several rest as a stack (the newest card with the edges of two
 * more behind it) that Select opens into the full list, and "Show less" folds again. Select on a card opens its app.
 */
@Composable
internal fun NoticeGroupView(group: NoticeGroup, now: Long, open: (AppNotice) -> Unit) {
    val many = group.items.size > 1
    var expanded by rememberSaveable(group.pkg) { mutableStateOf(false) }
    val head = remember { FocusRequester() }
    var wasExpanded by remember { mutableStateOf(false) }
    // Folding removes the row that had focus; put it back on the stack's card.
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) { androidx.compose.runtime.withFrameNanos { }; runCatching { head.requestFocus() } }
        wasExpanded = expanded
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // An opened group names its app once, above the cards, instead of on every card.
        if (many && expanded) GroupHeader(group.pkg, group.items.size)
        group.items.forEachIndexed { i, n ->
            if (i > 0 && !expanded) return@forEachIndexed
            key(n.key) {
                val stacked = many && !expanded
                NoticeCard(
                    n = n, now = now, more = if (stacked) group.items.size - 1 else 0, showApp = !(many && expanded),
                    onClick = { if (stacked) expanded = true else open(n) },
                    modifier = if (i == 0) Modifier.focusRequester(head) else Modifier,
                )
            }
        }
        if (many && expanded) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PillButton("Show less") { expanded = false } }
    }
}

/** An iOS-style notification card: the app's icon, its name and age, a bold title and the body. Only the focused card lifts. */
@Composable
private fun NoticeCard(n: AppNotice, now: Long, more: Int, showApp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val prefs = LocalUiPrefs.current
    val view = androidx.compose.ui.platform.LocalView.current
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, if (focused) Motion.focusIn() else Motion.focusOut(), label = "cardScale")
    val bg by animateColorAsState(
        if (focused) palette.focusFill else palette.rowFill,
        if (focused) Motion.selectIn() else Motion.selectOut(),
        label = "cardBg",
    )
    val fg = if (focused) palette.onFocusFill else palette.primary
    val sub = if (focused) palette.onFocusFill.copy(alpha = 0.7f) else palette.secondary
    val app = appLabel(n.pkg)
    // The edges of the cards behind a stack: each one narrower and fainter, peeking out under the top card.
    val behind = more.coerceAtMost(2)
    Box(modifier.fillMaxWidth().padding(bottom = if (behind > 0) (7 * behind + 7).dp else 0.dp)) {
        // Only a lip of each shows, below the card (the card is translucent: nothing may sit under it).
        for (i in behind downTo 1) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp).offset(y = (7 * i + 7).dp).padding(horizontal = (14 * i).dp)
                    .background(palette.rowFill.copy(alpha = palette.rowFill.alpha * (1f - 0.25f * i)), CardShape),
            )
        }
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (focused) Modifier.shadow(14.dp, CardShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f)) else Modifier)
                .background(bg, CardShape)
                .onFocusChanged {
                    if (it.isFocused && !focused && prefs.sounds) view.playSoundEffect(navigationSound())
                    focused = it.isFocused
                    if (!it.isFocused) pressed[0] = false
                }
                .onKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val select = isSelectKey(k.keyCode)
                    if (select) {
                        if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                        if (k.action == AndroidKeyEvent.ACTION_UP) { if (pressed[0]) onClick(); pressed[0] = false }
                    }
                    select
                }
                .semantics {
                    contentDescription = listOfNotNull(app, n.title.ifBlank { null }, n.text).joinToString(", ")
                    role = Role.Button
                    onClick { onClick(); true }
                }
                .focusable()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            AppIcon(n.pkg, app, fg, focused)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (showApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (more > 0) "$app  ·  +$more more" else app,
                        style = Type.caption, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(agoLabel(now, n.whenMs), style = Type.caption, color = sub, maxLines = 1)
                }
                // Under an app header the time moves onto the title's line (the card names no app).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (n.title.isNotBlank()) Text(n.title, style = Type.body.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    else Box(Modifier.weight(1f))
                    if (!showApp) Text(agoLabel(now, n.whenMs), style = Type.caption, color = sub, maxLines = 1)
                }
                n.text?.let { Text(it, style = Type.secondary, color = fg.copy(alpha = if (focused) 0.85f else 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** The app's launcher icon in a rounded square; a letter if it is gone. */
@Composable
internal fun AppIcon(pkg: String, label: String, ink: Color, focused: Boolean = false, size: Int = 36) {
    val context = LocalContext.current
    val bitmap = remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(9.dp)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp).clip(shape))
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(if (focused) Color.Black.copy(alpha = 0.12f) else LocalPalette.current.rowFill), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), style = Type.body, color = ink)
        }
    }
}

/** The name above an opened group: the app's icon and label, and how many it has said. */
@Composable
private fun GroupHeader(pkg: String, count: Int) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 6.dp, top = 6.dp)) {
        AppIcon(pkg, appLabel(pkg), palette.primary, size = 22)
        Text(appLabel(pkg), style = Type.caption, color = palette.secondary)
        Text("$count", style = Type.caption, color = palette.faint)
    }
}

/** A small action button (Clear all, Show less): a pill, not a card, so the cards are the only things that read as notifications. */
@Composable
internal fun PillButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    val prefs = LocalUiPrefs.current
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    val bg by animateColorAsState(if (focused) palette.focusFill else palette.rowFill, if (focused) Motion.selectIn() else Motion.selectOut(), label = "pillBg")
    Box(
        modifier
            .heightIn(min = 30.dp)
            .background(bg, RoundedCornerShape(50))
            .onFocusChanged {
                if (it.isFocused && !focused && prefs.sounds) view.playSoundEffect(navigationSound())
                focused = it.isFocused
                if (!it.isFocused) pressed[0] = false
            }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                val select = isSelectKey(k.keyCode)
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP) { if (pressed[0]) onClick(); pressed[0] = false }
                }
                select
            }
            .semantics { contentDescription = text; role = Role.Button; onClick { onClick(); true } }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.caption, color = if (focused) palette.onFocusFill else palette.primary, maxLines = 1)
    }
}
