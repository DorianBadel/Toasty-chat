package chat.simplex.common.toasty

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import chat.simplex.common.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.chatlist.UserPicker
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.localauth.VerticalDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

// Panes flow left to right in tab order while their minimum widths fit, like bejgl's tiling
// (bejgl/src/ui/tiling.ts). A window too narrow for two panes shows the active one full screen.
private class Pane(val name: String, val minChars: Int, val hints: String)

private val PANES = listOf(
  Pane("chats", minChars = 38, hints = "j/k move · ⏎ open · / filter · gg G ends · : cmd · ? help"),
  Pane("chat", minChars = 56, hints = "j/k move · i write · / search · r reply · e edit · p pin · P pins · y copy · dd del · v select · ? help"),
)

private fun hintsFor(mode: VimMode, pane: Int, filter: String) = when {
  mode == VimMode.INSERT -> "esc normal mode · ⏎ send · ⇧⏎ new line"
  mode == VimMode.VISUAL -> "j/k extend · y copy · d delete · esc cancel"
  mode == VimMode.COMMAND -> "⏎ run · esc cancel · :open name · :react heart · :q · :help"
  filter == PIN && pane == ToastyNav.CHAT -> "pins · j/k move · ⏎ go to pinned message · p unpin · esc close"
  filter.isNotEmpty() && pane == ToastyNav.CHAT -> "/$filter · j/k matches · ⏎ show in chat · / refine · esc clear"
  filter.isNotEmpty() -> "/$filter · j/k move · ⏎ open · / refine · esc clear"
  else -> PANES[pane].hints
}

private val HELP: List<Pair<String, String>> = listOf(
  "j k  ↑ ↓" to "move",
  "gg  G" to "top · bottom (oldest · newest)",
  "^d ^u" to "half page down · up",
  "1 2  tab" to "switch pane",
  "o  ^\\" to "one pane only · both (:only :split)",
  "^+ ^- ^0" to "zoom in · out · reset (or ctrl + wheel)",
  "h  l" to "chats · chat",
  "⏎" to "open chat · write (in chat)",
  "i  a" to "write a message",
  "/" to "filter chats (fuzzy) · search messages",
  "⏎ on a match" to "show the message in the chat",
  "r  e" to "reply · edit",
  "y  dd" to "copy · delete",
  "+" to "react 👍",
  "p  P" to "pin / unpin · list pins (🐧 replies)",
  "v" to "select messages (y copy · d delete)",
  "esc" to "back to normal · close",
  "" to "",
  ":open name" to "open a chat",
  ":react name" to "heart laugh sad rocket ok -1 …",
  ":q" to "close the chat",
  ":search text" to "filter chats (:noh clears)",
  ":info" to "chat info",
  ":pin note" to "pin with a note · :pins lists",
)

object ToastyNav {
  const val CHATS = 0
  const val CHAT = 1

  val active = mutableStateOf(CHATS)
  /** show only the active pane, even when both would fit */
  val solo = mutableStateOf(false)

  fun activate(pane: Int) {
    active.value = pane.coerceIn(0, PANES.lastIndex)
  }

  fun toggleSolo(on: Boolean = !solo.value) {
    solo.value = on
    ToastyVim.say(if (on) "one pane · ctrl+\\ or :split shows both" else "both panes when they fit")
  }

  // Window-level keys: ctrl shortcuts work in every mode, the rest goes to the vim layer.
  fun onKeyEvent(event: KeyEvent): Boolean {
    if (!Toasty.enabled) return false
    if (ToastyZoom.onKeyEvent(event)) return true
    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
      val digit = event.key.digitOrNull()
      when {
        event.key == Key.Backslash -> { toggleSolo(); return true }
        digit != null && digit in 1..PANES.size -> { activate(digit - 1); return true }
        event.key == Key.Tab -> {
          activate((active.value + if (event.isShiftPressed) PANES.size - 1 else 1) % PANES.size)
          return true
        }
      }
    }
    return ToastyVim.onKeyEvent(event)
  }

  private fun Key.digitOrNull(): Int? = when (this) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    else -> null
  }
}

private val P = ToastyPalette

@Composable
fun ToastyDesktopScreen(userPickerState: MutableStateFlow<AnimatedViewState>) {
  val active by ToastyNav.active
  // Follow what the app opens: a chat or a center modal brings pane 2 forward,
  // the user picker or a settings sheet in the start column brings pane 1 back.
  LaunchedEffect(Unit) {
    snapshotFlow {
      when {
        ModalManager.start.hasModalsOpen() -> ToastyNav.CHATS
        chatModel.chatId.value != null || ModalManager.center.hasModalsOpen() -> ToastyNav.CHAT
        else -> ToastyNav.CHATS
      }
    }
      .distinctUntilChanged()
      .collect { ToastyNav.activate(it) }
  }
  LaunchedEffect(Unit) {
    userPickerState.collect { if (it.isVisible()) ToastyNav.activate(ToastyNav.CHATS) }
  }
  // a new chat starts without a message cursor or selection
  LaunchedEffect(Unit) {
    snapshotFlow { chatModel.chatId.value }
      .distinctUntilChanged()
      .collect {
        ToastyVim.endVisual()
        ToastyChat.cursor.value = null
        if (it != null) ToastyChatList.cursor.value = it
      }
  }
  ToastyVim.focusManager = LocalFocusManager.current
  ToastyVim.clipboard = LocalClipboardManager.current
  ToastyVim.scope = rememberCoroutineScope()
  DisposableEffect(Unit) {
    ToastyVim.shellActive = true
    onDispose { ToastyVim.shellActive = false }
  }

  BoxWithConstraints(Modifier.fillMaxSize().background(P.bg).toastyWheelZoom()) {
    val cell = charWidthPx()
    val framePx = with(LocalDensity.current) { (FRAME_INSET * 2).roundToPx() }
    val minWidths = PANES.map { it.minChars * cell + framePx }
    val width = constraints.maxWidth
    var first by remember { mutableStateOf(0) }
    first = firstVisible(minWidths, first, active, width)
    val visible = if (ToastyNav.solo.value) listOf(active) else flowRow(minWidths, first, width)
    val framed = visible.size > 1

    Column(Modifier.fillMaxSize()) {
      TopBar(active, visible, compact = width < 66 * cell)
      TiledPanes(minWidths, visible, Modifier.weight(1f).fillMaxWidth()) { index ->
        PaneFrame(index, framed, active == index) {
          when (index) {
            ToastyNav.CHATS -> ChatsPane(userPickerState)
            ToastyNav.CHAT -> ChatPane(userPickerState, framed)
          }
        }
      }
      Footer(active)
    }
    if (ToastyVim.help.value) Help()
  }
  ModalManager.fullscreen.showInView()
}

@Composable
private fun ChatsPane(userPickerState: MutableStateFlow<AnimatedViewState>) {
  Box(Modifier.fillMaxSize()) {
    StartPartOfScreen(userPickerState)
    tryOrShowError("UserPicker", error = {}) {
      UserPicker(chatModel, userPickerState, setPerformLA = AppLock::setPerformLA)
    }
    ModalManager.start.showInView()
    SwitchingUsersView()
  }
}

@Composable
private fun ChatPane(userPickerState: MutableStateFlow<AnimatedViewState>, framed: Boolean) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val endOpen = ModalManager.end.hasModalsOpen()
    val endWidth = DEFAULT_END_MODAL_WIDTH * fontSizeSqrtMultiplier
    // chat info sits beside the chat when there is room for both, otherwise on top of it
    if (maxWidth >= endWidth * 2) {
      Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { CenterPartOfScreen() }
        if (endOpen) VerticalDivider()
        Box(Modifier.widthIn(max = endWidth).clipToBounds()) { EndPartOfScreen() }
      }
    } else {
      Box(Modifier.fillMaxSize()) {
        CenterPartOfScreen()
        if (endOpen) Box(Modifier.fillMaxSize().background(P.bg)) { EndPartOfScreen() }
      }
    }
    // clicking the chat closes the user picker or a start sheet, as on the SimpleX desktop layout
    if (framed && (userPickerState.collectAsState().value.isVisible() || (ModalManager.start.hasModalsOpen && !ModalManager.center.hasModalsOpen))) {
      Box(
        Modifier
          .fillMaxSize()
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
            if (chatModel.centerPanelBackgroundClickHandler == null || chatModel.centerPanelBackgroundClickHandler?.invoke() == false) {
              ModalManager.start.closeModals()
              userPickerState.value = AnimatedViewState.HIDING
            }
          })
      )
    }
  }
}

@Composable
private fun TopBar(active: Int, visible: List<Int>, compact: Boolean) {
  val style = MaterialTheme.typography.body2
  Row(
    Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(" ◯ toasty ", style = style, color = P.accent, fontWeight = FontWeight.Bold)
    PANES.forEachIndexed { i, pane ->
      val label = if (compact && i != active) " ${i + 1} " else " ${i + 1} ${pane.name} "
      Text(
        label,
        Modifier
          .background(if (i == active) P.sel else Color.Transparent)
          .clickable { ToastyNav.activate(i) },
        style = style,
        color = when {
          i == active -> P.text
          i in visible -> P.dim
          else -> P.faint
        },
        maxLines = 1
      )
    }
    Spacer(Modifier.weight(1f))
    val user = chatModel.currentUser.value
    if (user != null && !compact) {
      Text(user.displayName, style = style, color = P.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    // one pane / both panes, same as ctrl+\
    val solo = ToastyNav.solo.value
    Text(
      if (solo) " [1] " else " [1|2] ",
      Modifier.padding(start = 8.dp).background(if (solo) P.sel else Color.Transparent).clickable { ToastyNav.toggleSolo() },
      style = style,
      color = if (solo) P.accent else P.dim,
      maxLines = 1
    )
  }
}

@Composable
private fun Footer(active: Int) {
  val mode by ToastyVim.mode
  val flash by ToastyVim.flash
  // a message lingers, then gives the line back to the hints
  LaunchedEffect(flash?.id) {
    if (flash != null) {
      delay(2600)
      ToastyVim.flash.value = null
    }
  }
  val style = MaterialTheme.typography.body2
  Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    val f = flash
    when {
      mode == VimMode.COMMAND -> Text(":${ToastyVim.command.value}█", Modifier.weight(1f), style = style, color = P.text, maxLines = 1)
      mode == VimMode.SEARCH -> {
        Text("/${ToastyVim.query.value}█", Modifier.weight(1f), style = style, color = P.text, maxLines = 1)
        Text(searchCount(active), style = style, color = P.dim, maxLines = 1)
      }
      f != null -> Text(
        (if (f.warn) " ! " else " ◯ ") + f.text,
        Modifier.weight(1f), style = style, color = if (f.warn) P.warn else P.accent, maxLines = 1, overflow = TextOverflow.Ellipsis
      )
      else -> {
        val filter = ToastyVim.activeFilter(active == ToastyNav.CHATS)
        Text(" " + hintsFor(mode, active, filter), Modifier.weight(1f), style = style, color = if (filter.isEmpty()) P.faint else P.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Text(ToastyVim.pending.value, Modifier.padding(horizontal = 8.dp), style = style, color = P.dim)
    ModeBadge(mode)
  }
}

@Composable
private fun searchCount(active: Int): String {
  val query = ToastyVim.query.value
  if (query.isEmpty()) return ""
  if (active == ToastyNav.CHATS) {
    val n = ToastyChatList.shownCount.intValue
    return if (n == 1) "1 chat " else "$n chats "
  }
  val h = ToastyChat.current ?: return ""
  if (ToastyChat.searchValue?.value != query) return "searching… "
  val n = h.items.value.size
  return if (n == 1) "1 message " else "$n messages "
}

@Composable
private fun ModeBadge(mode: VimMode) {
  val color = when (mode) {
    VimMode.NORMAL -> P.info
    VimMode.INSERT -> P.good
    VimMode.VISUAL -> P.visual
    VimMode.COMMAND -> P.warn
    VimMode.SEARCH -> P.accent
  }
  Text(
    " ${mode.label} ",
    Modifier.fillMaxHeight().background(color).wrapContentHeight(Alignment.CenterVertically),
    style = MaterialTheme.typography.body2,
    color = P.bg,
    fontWeight = FontWeight.Bold
  )
}

@Composable
private fun BoxScope.Help() {
  val style = MaterialTheme.typography.body2
  Column(
    Modifier
      .align(Alignment.Center)
      .widthIn(max = 520.dp)
      .padding(16.dp)
      .background(P.panel)
      .border(1.dp, P.accent, RoundedCornerShape(4.dp))
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    Text("keys · any key closes", style = style, color = P.accent, modifier = Modifier.padding(bottom = 6.dp))
    HELP.forEach { (keys, what) ->
      Row {
        Text(keys, Modifier.width(140.dp), style = style, color = P.accent, maxLines = 1)
        Text(what, style = style, color = P.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

private val FRAME_INSET = 4.dp

@Composable
private fun PaneFrame(index: Int, framed: Boolean, active: Boolean, content: @Composable () -> Unit) {
  val activate = Modifier.pointerInput(index) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      ToastyNav.activate(index)
    }
  }
  if (!framed) {
    // a lone pane needs no frame; that is also the narrow-window layout
    Box(Modifier.fillMaxSize().then(activate)) { content() }
    return
  }
  val color = if (active) P.accent else P.line
  Box(Modifier.fillMaxSize().padding(FRAME_INSET).then(activate)) {
    Box(
      Modifier
        .fillMaxSize()
        .padding(top = 8.dp)
        .border(1.dp, color, RoundedCornerShape(4.dp))
        .padding(start = 1.dp, end = 1.dp, bottom = 1.dp, top = 9.dp)
        .clipToBounds()
    ) {
      content()
    }
    Text(
      " ${index + 1} ${PANES[index].name} ",
      Modifier.padding(start = 12.dp).background(P.bg),
      style = MaterialTheme.typography.body2,
      color = if (active) P.accent else P.dim,
      maxLines = 1
    )
  }
}

// Panes are always composed and measured, but only visible ones are placed, so a hidden pane
// keeps its scroll position and state while the layout reflows around it.
@Composable
private fun TiledPanes(minWidths: List<Int>, visible: List<Int>, modifier: Modifier, pane: @Composable (Int) -> Unit) {
  Layout(
    content = { PANES.indices.forEach { i -> key(i) { Box { pane(i) } } } },
    modifier = modifier
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val total = visible.sumOf { minWidths[it] }
    val spare = (width - total).coerceAtLeast(0)
    val lefts = IntArray(PANES.size)
    val widths = IntArray(PANES.size) { width }
    var left = 0
    visible.forEachIndexed { c, i ->
      val w = if (c == visible.lastIndex) width - left else minWidths[i] + spare * minWidths[i] / total
      lefts[i] = left
      widths[i] = w
      left += w
    }
    val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), height)) }
    layout(width, height) {
      visible.forEach { i -> placeables[i].place(lefts[i], 0) }
    }
  }
}

@Composable
private fun charWidthPx(): Int {
  val measurer = rememberTextMeasurer()
  val style = MaterialTheme.typography.body2
  return remember(style) { measurer.measure("0000000000", style).size.width / 10 }.coerceAtLeast(1)
}

/** Panes shown starting from `first`: as many as fit side by side, always at least one. */
private fun flowRow(minWidths: List<Int>, first: Int, width: Int): List<Int> {
  val row = mutableListOf(first)
  var used = minWidths[first]
  var i = first + 1
  while (i < minWidths.size && used + minWidths[i] <= width) {
    used += minWidths[i]
    row.add(i++)
  }
  return row
}

/** First pane to show so `active` is on screen, moving as little as possible from `previous`. */
private fun firstVisible(minWidths: List<Int>, previous: Int, active: Int, width: Int): Int {
  fun shows(first: Int, i: Int) = i in flowRow(minWidths, first, width)
  var first = minOf(previous, active).coerceIn(0, minWidths.lastIndex)
  while (!shows(first, active)) first++
  while (first > 0 && shows(first - 1, active) && shows(first - 1, minWidths.lastIndex)) first--
  return first
}
