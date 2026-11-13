package dev.glasslauncher.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.glasslauncher.R
import dev.glasslauncher.home.CcApps
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Type
import dev.glasslauncher.ui.isSelectKey
import android.view.KeyEvent as AndroidKeyEvent

/** The state wording, exactly as tvOS writes it (rom/research/tvos-search.md). */
object SearchText {
    const val RECENT = "Recently Searched"
    const val CLEAR_TITLE = "Clear Searches?"
    const val CLEAR_MESSAGE = "Clearing your searches will remove your search history from this device."
    const val CLEAR_BUTTON = "Clear Searches"
    const val NO_RESULTS = "No Results"
    fun noResults(query: String) = "There were no results for \"$query\" in Apple TV app. Try a new search."
    const val UNAVAILABLE = "Search Not Available"
    const val OFFLINE = "Connect to the internet or try again later."
}

/**
 * System search (P161): a text field that opens the device's own keyboard (P194), completion pills with the typed part bold, a Top Results / TV Shows /
 * Movies filter, and a three-column grid of poster cards. Results are live: they follow every key. With no text it shows "Recently
 * Searched"; with no results, a centered title and one line. Back closes the keyboard first (the system's), then search (the overlay's own handling).
 */
@Composable
fun SearchScreen(
    model: SearchModel,
    active: Boolean,
    onOpen: (SearchHit) -> Unit,
    askClear: (onConfirm: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val query by model.query.collectAsState()
    val results by model.results.collectAsState()
    val busy by model.busy.collectAsState()
    val offline by model.offline.collectAsState()
    var filter by remember { mutableStateOf(SearchIndex.Filter.Top) }
    var editing by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(RecentSearches.read(context)) }
    val field = remember { FocusRequester() }
    val row = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Opens on the search row; Select on it opens the device's own keyboard (on Fire TV it is modal, so the field exists only while
    // typing, and a normal row holds the query otherwise). Nothing here draws a keyboard of Glass's own (P230).
    LaunchedEffect(active) { if (active) { androidx.compose.runtime.withFrameNanos { }; runCatching { row.requestFocus() } } }
    // The keyboard's own Search key puts focus back on the row; focus that left the field some other way (the user moved on to the
    // results) stays where it went.
    var returnToRow by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { androidx.compose.runtime.withFrameNanos { }; runCatching { field.requestFocus() }; keyboard?.show() }
        else if (active) {
            keyboard?.hide()
            if (returnToRow) { returnToRow = false; androidx.compose.runtime.withFrameNanos { }; runCatching { row.requestFocus() } }
        }
    }
    // Tabs only once there are results to sort (user, 2026-10-21), and only the types the results have; a tab whose results went away falls back to Top.
    val tabs = if (query.isBlank()) emptyList() else SearchIndex.available(results)
    val active = if (filter in tabs) filter else SearchIndex.Filter.Top
    val shown = SearchIndex.filter(results, active)
    val ink = if (palette.light) Color(0xFF14161C) else Color.White
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp).padding(top = 26.dp).testTag("search")) {
        // The query, in the same field as every text input in Glass: a magnifying glass, then the field while typing or a row to open it.
        // One fixed size whether typing or not (P323: it was nearly full width and grew when the keyboard opened). The gap is the
        // look's 24 dp plus the focused row's 1.06 lift (3% of its width each side), so the lifted row never reaches the glass.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SEARCH_GAP), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(R.drawable.ic_search, ink.copy(alpha = 0.7f), 32)
            if (editing) dev.glasslauncher.home.SystemTextField(
                value = query, onValueChange = { model.setQuery(it) }, focusRequester = field,
                onFocusLost = { editing = false }, onBack = { returnToRow = true; editing = false }, imeAction = androidx.compose.ui.text.input.ImeAction.Search, onAction = { returnToRow = true; editing = false }, label = "Search",
                verticalPadding = 10.dp, modifier = Modifier.size(SEARCH_W, SEARCH_H).testTag("search-field"),
            ) else Pill(ink, Modifier.size(SEARCH_W, SEARCH_H).focusRequester(row).testTag("search-row"), onClick = { editing = true }) { focused ->
                Text(
                    query.ifEmpty { "Search" }, style = Type.heading, color = if (query.isEmpty()) pillInk(focused, ink).copy(alpha = 0.45f) else pillInk(focused, ink),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("search-query"),
                )
            }
        }
        Spacer(10)
        // Completions: the typed part bold, the rest lighter; Select runs that search.
        val suggestions = SearchIndex.suggestions(query, results)
        if (suggestions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("search-suggestions")) {
            items(suggestions) { s -> Pill(ink, onClick = { model.setQuery(s) }, modifier = Modifier.testTag("search-suggestion")) { focused ->
                Icon(R.drawable.ic_search, pillInk(focused, ink), 14)
                Text(completion(s, query, if (focused) Color.Black else ink), style = Type.caption, maxLines = 1)
            } }
        } else Spacer(30)
        Spacer(8)
        // The type filter, centered; it grows in with the first results instead of popping in (continuity rule).
        androidx.compose.animation.AnimatedVisibility(tabs.isNotEmpty(), Modifier.fillMaxWidth(),
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            // The last non-empty set, so the row keeps its pills while it shrinks away.
            var lastTabs by remember { mutableStateOf(tabs) }
            if (tabs.isNotEmpty()) lastTabs = tabs
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("search-filter")) {
                        lastTabs.forEach { f ->
                            Pill(ink, selected = active == f, label = f.label, onClick = { filter = f }, modifier = Modifier.testTag("search-filter:${f.name}")) { focused ->
                                Text(f.label, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = pillInk(focused || active == f, ink), maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(12)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.isBlank() -> RecentShelf(recent, ink, onPick = { model.setQuery(it) }, onRemove = { q -> recent = RecentSearches.remove(recent, q).also { RecentSearches.write(context, it) } },
                    onClear = { askClear { recent = emptyList(); RecentSearches.write(context, emptyList()) } })
                shown.isEmpty() && busy -> Unit
                shown.isEmpty() && offline && results.isEmpty() -> Notice(SearchText.UNAVAILABLE, SearchText.OFFLINE, ink)
                shown.isEmpty() -> Notice(SearchText.NO_RESULTS, SearchText.noResults(query), ink)
                else -> {
                  // The first row stays in view (P241): the grid keeps the scroll it had, and results arriving or the keyboard resizing the
                  // window left it scrolled past its first row, cut off under the tabs. While focus is not in it (typing, or just back from
                  // the keyboard), it goes back to the top when the results or its height change.
                  val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                  var gridFocused by remember { mutableStateOf(false) }
                  var gridHeight by remember { mutableStateOf(0) }
                  val keys = shown.map { it.key }
                  LaunchedEffect(keys, gridHeight) { if (!gridFocused) gridState.scrollToItem(0) }
                  LazyVerticalGrid(
                    GridCells.Fixed(3), state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().onSizeChanged { gridHeight = it.height }.onFocusChanged { gridFocused = it.hasFocus }.testTag("search-results"),
                  ) {
                    items(shown, key = { it.key }) { hit ->
                        ResultCard(hit, ink) {
                            recent = RecentSearches.add(recent, query).also { RecentSearches.write(context, it) }
                            onOpen(hit)
                        }
                    }
                  }
                }
            }
        }
    }
}

@Composable private fun Spacer(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))

@Composable private fun Icon(res: Int, tint: Color, size: Int) =
    androidx.compose.foundation.Image(painterResource(res), null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size.dp))

private fun pillInk(focusedOrSelected: Boolean, ink: Color) = if (focusedOrSelected) Color.Black else ink

/** The completion with what was typed in bold and the rest lighter (tvOS's suggestion pills). */
private fun completion(title: String, typed: String, color: Color): AnnotatedString = buildAnnotatedString {
    val n = typed.length
    if (title.length >= n && title.take(n).equals(typed, ignoreCase = true)) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(title.take(n)) }
        withStyle(SpanStyle(color = color.copy(alpha = 0.7f))) { append(title.drop(n)) }
    } else withStyle(SpanStyle(color = color.copy(alpha = 0.85f))) { append(title) }
}

/** A focusable capsule: translucent at rest, white (dark text) when focused or selected. */
private val SEARCH_W = 480.dp
private val SEARCH_H = 44.dp
private val SEARCH_GAP = 40.dp

@Composable
private fun Pill(ink: Color, modifier: Modifier = Modifier, selected: Boolean = false, label: String? = null, contentPadding: androidx.compose.ui.unit.Dp = 14.dp, onClick: () -> Unit, onPlayPause: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    Row(
        modifier
            .graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }
            .height(34.dp)
            .background(if (focused || selected) Color.White else ink.copy(alpha = 0.14f), RoundedCornerShape(17.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (onPlayPause != null && k.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if (k.action == AndroidKeyEvent.ACTION_UP) onPlayPause(); return@onKeyEvent true }
                val select = isSelectKey(k.keyCode)
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP && pressed[0]) { pressed[0] = false; onClick() }
                }
                select
            }
            .semantics { role = Role.Button; label?.let { contentDescription = it }; if (selected) this.selected = true }
            .focusable()
            .padding(horizontal = contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content(focused || selected) }
}

@Composable
private fun RecentShelf(recent: List<String>, ink: Color, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("search-recent")) {
        Text(SearchText.RECENT, style = Type.secondary.copy(fontWeight = FontWeight.SemiBold), color = ink.copy(alpha = 0.7f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent) { q ->
                Pill(ink, onClick = { onPick(q) }, onPlayPause = { onRemove(q) }, modifier = Modifier.testTag("search-recent:$q")) { f ->
                    Icon(R.drawable.ic_search, pillInk(f, ink), 14); Text(q, style = Type.caption, color = pillInk(f, ink), maxLines = 1)
                }
            }
            item { Pill(ink, onClick = onClear, modifier = Modifier.testTag("search-clear")) { f -> Text(SearchText.CLEAR_BUTTON, style = Type.caption, color = pillInk(f, ink), maxLines = 1) } }
        }
    }
}

/** A centered title and one line (tvOS's empty and error states). */
@Composable
private fun Notice(title: String, line: String, ink: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("search-notice")) {
            Text(title, style = Type.heading, color = ink, textAlign = TextAlign.Center)
            Text(line, style = Type.secondary, color = ink.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.width(520.dp))
        }
    }
}

/** A poster, the name and a "Type · Year" line; Select opens it. */
@Composable
private fun ResultCard(hit: SearchHit, ink: Color, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val pressed = remember { booleanArrayOf(false) }
    val appIcon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, hit.key) {
        value = hit.app?.let { CcApps.icon(context.packageManager, it.packageName) }
    }
    Row(
        Modifier.fillMaxWidth().height(120.dp)
            .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
            .background(if (focused) Color.White else ink.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                val select = isSelectKey(k.keyCode)
                if (select) {
                    if (k.action == AndroidKeyEvent.ACTION_DOWN && k.repeatCount == 0) pressed[0] = true
                    if (k.action == AndroidKeyEvent.ACTION_UP && pressed[0]) { pressed[0] = false; onClick() }
                }
                select
            }
            .semantics { role = Role.Button; contentDescription = listOfNotNull(hit.title, hit.subtitle ?: if (hit.kind == SearchKind.App) "App" else null, hit.source).joinToString(", ") }
            .focusable()
            .padding(8.dp)
            .testTag("search-result:${hit.key}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(72.dp).fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            when {
                hit.image != null -> AsyncImage(
                    model = ImageRequest.Builder(context).data(hit.image).size(144, 216).crossfade(true).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                appIcon != null -> androidx.compose.foundation.Image(appIcon!!, null, modifier = Modifier.size(52.dp))
                else -> Text(hit.title.take(1).uppercase(), style = Type.title, color = ink.copy(alpha = 0.6f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val fg = if (focused) Color.Black else ink
            Text(hit.title, style = Type.secondary.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hit.subtitle ?: if (hit.kind == SearchKind.App) "App" else "", style = Type.caption, color = fg.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Where it comes from: the app that will open (P171).
            hit.source?.let { Text(it, style = Type.caption, color = fg.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("search-source")) }
        }
    }
}
