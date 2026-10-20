package chat.simplex.common.toasty

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import chat.simplex.common.model.*
import chat.simplex.common.platform.chatModel
import chat.simplex.common.views.chat.*
import chat.simplex.common.views.chat.item.*
import chat.simplex.common.views.chatlist.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Vim-style modal controls. Normal mode owns the keyboard; insert mode is whenever a text field
// has input (clicking one switches automatically); visual mode is SimpleX's multi-select; command
// mode is a `:` line in the footer.

enum class VimMode(val label: String) { NORMAL("N"), INSERT("I"), VISUAL("V"), COMMAND("C"), SEARCH("/") }

class ToastyFlash(val text: String, val warn: Boolean, val id: Long = System.nanoTime())

// What the chat list shares with the vim layer; set from ChatListView on every composition.
object ToastyChatList {
  var chats: List<Chat> = emptyList()
  var listState: LazyListState? = null
  var headerItems = 0
  var reversed = false
  var searchText: MutableState<TextFieldValue>? = null
  var searchFocus: FocusRequester? = null
  val cursor = mutableStateOf<String?>(null)
  val shownCount = mutableIntStateOf(0)

  fun update(chats: List<Chat>, listState: LazyListState, headerItems: Int, reversed: Boolean, searchText: MutableState<TextFieldValue>) {
    this.chats = chats
    if (shownCount.intValue != chats.size) shownCount.intValue = chats.size
    this.listState = listState
    this.headerItems = headerItems
    this.reversed = reversed
    this.searchText = searchText
  }
}

// What the open chat shares with the vim layer; set from ChatItemsList on every composition.
class ToastyChatHandle(
  val chatInfo: ChatInfo,
  val composeState: MutableState<ComposeState>,
  val selectedChatItems: MutableState<Set<Long>?>,
  /** newest first, as displayed from the bottom up */
  val items: State<List<ChatItem>>,
  val listState: State<LazyListState>,
  val listIndexOf: (Long) -> Int?,
  val topPaddingPx: Int,
  val bottomPaddingPx: Int,
  val useLinkPreviews: Boolean,
  val deleteMessage: (Long, CIDeleteMode) -> Unit,
  val deleteMessages: (List<Long>) -> Unit,
  val setReaction: (ChatInfo, ChatItem, Boolean, MsgReaction) -> Unit,
  val showChatInfo: () -> Unit,
  /** messages a loaded 🐧 reply points at */
  val pinned: State<Set<Long>>,
)

object ToastyChat {
  var handle: ToastyChatHandle? = null
  var composeFocus: FocusRequester? = null
  val cursor = mutableStateOf<Long?>(null)
  /** the open chat's message search: the shown messages are only the matches while it is not empty */
  var searchValue: State<String>? = null
  var search: ((String) -> Unit)? = null

  /** The handle, if it belongs to the chat that is open now. */
  val current: ToastyChatHandle? get() = handle?.takeIf { it.chatInfo.id == chatModel.chatId.value }
}

private val P = ToastyPalette

/** Cursor highlight for a chat list row. */
@Composable
fun Modifier.toastyChatCursor(chatId: String): Modifier {
  if (!Toasty.enabled || ToastyChatList.cursor.value != chatId) return this
  return cursorHighlight(ToastyNav.active.value == ToastyNav.CHATS)
}

/** Cursor highlight for a message. */
@Composable
fun Modifier.toastyItemCursor(itemId: Long): Modifier {
  if (!Toasty.enabled || ToastyChat.cursor.value != itemId) return this
  return cursorHighlight(ToastyNav.active.value == ToastyNav.CHAT)
}

private fun Modifier.cursorHighlight(paneActive: Boolean) = drawWithContent {
  drawContent()
  drawRect(P.accent.copy(alpha = if (paneActive) 0.10f else 0.04f))
  if (paneActive) drawRect(P.accent, size = Size(3.dp.toPx(), size.height))
}

/** Tracks every text input session in the window: any focused text field means insert mode. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ToastyTextInputTracker(content: @Composable () -> Unit) {
  if (!Toasty.enabled) return content()
  val interceptor = remember {
    PlatformTextInputInterceptor { request, nextHandler ->
      ToastyVim.textInputStarted()
      try {
        nextHandler.startInputMethod(request)
      } finally {
        ToastyVim.textInputEnded()
      }
    }
  }
  InterceptPlatformTextInput(interceptor, content)
}

object ToastyVim {
  val mode = mutableStateOf(VimMode.NORMAL)
  /** keys typed so far of a multi-key command, like the first `g` of `gg` */
  val pending = mutableStateOf("")
  val command = mutableStateOf("")
  /** the `/` line: filters chats in pane 1, searches messages in pane 2, as you type */
  val query = mutableStateOf("")
  val help = mutableStateOf(false)
  val flash = mutableStateOf<ToastyFlash?>(null)

  var shellActive = false
  var focusManager: FocusManager? = null
  var clipboard: ClipboardManager? = null
  var scope: CoroutineScope? = null

  private var textInputs = 0
  private var visualAnchor: Long? = null
  private var searchInChats = true
  private var searchJob: Job? = null
  // Esc we handled on key down must not reach the window's Esc-on-key-up "back" handler
  private var swallowEscapeUp = false

  fun say(text: String, warn: Boolean = false) {
    flash.value = ToastyFlash(text, warn)
  }

  fun textInputStarted() {
    textInputs++
    if (mode.value == VimMode.NORMAL || mode.value == VimMode.VISUAL) {
      endVisual()
      mode.value = VimMode.INSERT
    }
  }

  fun textInputEnded() {
    textInputs = (textInputs - 1).coerceAtLeast(0)
    if (textInputs == 0 && mode.value == VimMode.INSERT) mode.value = VimMode.NORMAL
  }

  fun onKeyEvent(e: KeyEvent): Boolean {
    if (!Toasty.enabled || !shellActive) return false
    if (e.key == Key.Escape && e.type == KeyEventType.KeyUp && swallowEscapeUp) {
      swallowEscapeUp = false
      return true
    }
    // an open alert owns the keyboard (its Enter, Esc and buttons)
    if (AlertManager.shared.hasAlertsShown()) return false
    if (help.value) {
      if (e.type == KeyEventType.KeyDown) {
        help.value = false
        if (e.key == Key.Escape) swallowEscapeUp = true
      }
      return true
    }
    return when (mode.value) {
      VimMode.INSERT -> insertKey(e)
      VimMode.COMMAND -> commandKey(e)
      VimMode.SEARCH -> searchKey(e)
      VimMode.NORMAL, VimMode.VISUAL -> normalKey(e)
    }
  }

  // ---- search ----

  /** The filter showing in the active pane, if any. */
  fun activeFilter(chats: Boolean = inChats): String =
    if (chats) ToastyChatList.searchText?.value?.text.orEmpty() else ToastyChat.current?.let { ToastyChat.searchValue?.value }.orEmpty()

  private fun startSearch() {
    if (!inChats && ToastyChat.current == null) return say("open a chat to search it", warn = true)
    endVisual()
    searchInChats = inChats
    query.value = activeFilter(searchInChats)
    mode.value = VimMode.SEARCH
  }

  private fun searchKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return true
    when (e.key) {
      Key.Escape -> {
        query.value = ""
        applySearch(immediately = true)
        mode.value = VimMode.NORMAL
        swallowEscapeUp = true
      }
      Key.Enter, Key.NumPadEnter -> {
        applySearch(immediately = true)
        mode.value = VimMode.NORMAL
        if (searchInChats) {
          ToastyChatList.cursor.value = null
          if (query.value.isNotEmpty()) scope?.launch { setChatsCursorWhenFiltered() }
        } else {
          ToastyChat.cursor.value = null // j/k start from the newest match
        }
      }
      Key.Backspace -> {
        if (query.value.isEmpty()) {
          mode.value = VimMode.NORMAL
        } else {
          query.value = query.value.dropLast(1)
          applySearch()
        }
      }
      else -> charOf(e)?.let {
        query.value += it
        applySearch()
      }
    }
    return true
  }

  private fun applySearch(immediately: Boolean = false) {
    val q = query.value
    if (searchInChats) {
      ToastyChatList.searchText?.value = TextFieldValue(q)
      return
    }
    // every search is a database query, so wait for a pause in typing
    searchJob?.cancel()
    searchJob = scope?.launch {
      if (!immediately) delay(250)
      ToastyChat.search?.invoke(q)
    }
  }

  // the filtered list, best match first, arrives with the next composition
  private suspend fun setChatsCursorWhenFiltered() {
    delay(50)
    if (ToastyChatList.chats.isNotEmpty()) setChatsCursor(0)
  }

  /** `P`: the chat's pins, found with the message search; ⏎ goes to the pinned message. */
  private fun showPins() {
    if (ToastyChat.current == null) return say("open a chat to see its pins", warn = true)
    endVisual()
    ToastyNav.activate(ToastyNav.CHAT)
    searchInChats = false
    query.value = PIN
    applySearch(immediately = true)
    ToastyChat.cursor.value = null
  }

  private fun clearFilter(): Boolean {
    if (activeFilter().isEmpty()) return false
    query.value = ""
    searchInChats = inChats
    applySearch(immediately = true)
    return true
  }

  private fun insertKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || e.key != Key.Escape) return false
    focusManager?.clearFocus(force = true)
    mode.value = VimMode.NORMAL
    swallowEscapeUp = true
    return true
  }

  private fun commandKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return true
    when (e.key) {
      Key.Escape -> {
        leaveCommand()
        swallowEscapeUp = true
      }
      Key.Enter, Key.NumPadEnter -> {
        val line = command.value
        leaveCommand()
        runCommand(line)
      }
      Key.Backspace -> if (command.value.isEmpty()) leaveCommand() else command.value = command.value.dropLast(1)
      else -> charOf(e)?.let { command.value += it }
    }
    return true
  }

  private fun leaveCommand() {
    mode.value = VimMode.NORMAL
    command.value = ""
  }

  private fun normalKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.isCtrlPressed && !e.isAltPressed) {
      return when (e.key) {
        Key.D -> { halfPage(1); true }
        Key.U -> { halfPage(-1); true }
        else -> false
      }
    }
    if (e.isAltPressed || e.isMetaPressed) return false
    when (e.key) {
      Key.Escape -> {
        when {
          pending.value.isNotEmpty() -> pending.value = ""
          mode.value == VimMode.VISUAL -> endVisual()
          clearFilter() -> {}
          else -> return false // nothing of ours to cancel: let Esc close modals and chats as before
        }
        swallowEscapeUp = true
        return true
      }
      Key.Enter, Key.NumPadEnter -> { pending.value = ""; enter(); return true }
      Key.DirectionDown -> { move(1); return true }
      Key.DirectionUp -> { move(-1); return true }
      Key.Tab -> {
        ToastyNav.activate((ToastyNav.active.value + if (e.isShiftPressed) PANE_COUNT - 1 else 1) % PANE_COUNT)
        return true
      }
    }
    val ch = charOf(e) ?: return false
    val keys = pending.value + ch
    pending.value = ""
    when (keys) {
      "j" -> move(1)
      "k" -> move(-1)
      "g" -> pending.value = keys
      // `d` deletes the selection straight away in visual mode, like vim; `dd` in normal mode
      "d" -> if (mode.value == VimMode.VISUAL) deleteSelection() else pending.value = keys
      "gg" -> jump(toNewest = false)
      "G" -> jump(toNewest = true)
      "h" -> ToastyNav.activate(ToastyNav.CHATS)
      "l" -> if (ToastyNav.active.value == ToastyNav.CHATS) openCursorChat() else ToastyNav.activate(ToastyNav.CHAT)
      "1", "2" -> ToastyNav.activate(keys.toInt() - 1)
      "i", "a" -> insert()
      "/" -> startSearch()
      ":" -> { mode.value = VimMode.COMMAND; command.value = "" }
      "?" -> help.value = true
      "v" -> toggleVisual()
      "y" -> if (mode.value == VimMode.VISUAL) yankSelection() else yankCursor()
      "dd" -> deleteCursor()
      "r" -> reply()
      "e" -> edit()
      "+" -> react(MREmojiChar.ThumbsUp)
      "p" -> cursorItem()?.let { (h, item) -> ToastyPins.toggle(h, item) }
      "P" -> showPins()
      "o" -> ToastyNav.toggleSolo()
    }
    return true // normal mode owns printable keys, even unbound ones
  }

  private fun charOf(e: KeyEvent): Char? {
    val cp = e.utf16CodePoint
    return if (cp in 32..0xFFFF && cp != 127) cp.toChar() else null
  }

  // ---- movement ----

  private val inChats get() = ToastyNav.active.value == ToastyNav.CHATS

  private fun move(delta: Int) {
    if (inChats) moveInChats(delta) else moveInChat(delta)
  }

  private fun moveInChats(delta: Int) {
    if (ModalManager.start.hasModalsOpen()) return
    val chats = ToastyChatList.chats
    if (chats.isEmpty()) return
    val d = if (ToastyChatList.reversed) -delta else delta
    val current = chats.indexOfFirst { it.id == ToastyChatList.cursor.value }
      .takeIf { it >= 0 } ?: chats.indexOfFirst { it.id == chatModel.chatId.value }
    val next = if (current < 0) (if (d > 0) 0 else chats.lastIndex) else (current + d).coerceIn(0, chats.lastIndex)
    setChatsCursor(next)
  }

  private fun setChatsCursor(index: Int) {
    val chats = ToastyChatList.chats
    ToastyChatList.cursor.value = chats.getOrNull(index)?.id ?: return
    val state = ToastyChatList.listState ?: return
    val listIndex = ToastyChatList.headerItems + index
    scope?.launch {
      // the sticky search bar covers the start of the list
      val header = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 }?.size ?: 0
      ensureVisible(state, listIndex, startMargin = header, endMargin = 0)
    }
  }

  private fun moveInChat(delta: Int) {
    val h = ToastyChat.current ?: return
    val items = h.items.value
    if (items.isEmpty()) return
    // items are newest first and drawn bottom up, so down (j) means newer
    val current = items.indexOfFirst { it.id == ToastyChat.cursor.value }
    val next = if (current < 0) 0 else (current - delta).coerceIn(0, items.lastIndex)
    setChatCursor(h, items[next].id)
  }

  private fun setChatCursor(h: ToastyChatHandle, itemId: Long) {
    ToastyChat.cursor.value = itemId
    if (mode.value == VimMode.VISUAL) extendVisual(h)
    val listIndex = h.listIndexOf(itemId) ?: return
    scope?.launch { ensureVisible(h.listState.value, listIndex, startMargin = h.bottomPaddingPx, endMargin = h.topPaddingPx) }
  }

  private fun jump(toNewest: Boolean) {
    if (inChats) {
      val chats = ToastyChatList.chats
      if (chats.isEmpty() || ModalManager.start.hasModalsOpen()) return
      val top = if (ToastyChatList.reversed) toNewest else !toNewest
      setChatsCursor(if (top) 0 else chats.lastIndex)
    } else {
      val h = ToastyChat.current ?: return
      val items = h.items.value
      if (items.isEmpty()) return
      setChatCursor(h, if (toNewest) items.first().id else items.last().id)
    }
  }

  private fun halfPage(direction: Int) {
    val state = if (inChats) ToastyChatList.listState else ToastyChat.current?.listState?.value
    state ?: return
    // the chat is drawn bottom up, so scrolling forward goes up the screen
    val forward = if (inChats && !ToastyChatList.reversed) direction > 0 else direction < 0
    val half = (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset) / 2f
    scope?.launch { state.animateScrollBy(if (forward) half else -half) }
  }

  // ---- actions ----

  private fun enter() {
    when {
      inChats -> openCursorChat()
      activeFilter().isNotEmpty() -> jumpToMatch()
      else -> insert()
    }
  }

  /** From a search match to the same message in the full chat, as SimpleX's "go to message" button does. */
  private fun jumpToMatch() {
    val h = ToastyChat.current ?: return
    val match = h.items.value.firstOrNull { it.id == ToastyChat.cursor.value } ?: h.items.value.firstOrNull() ?: return
    // from a 🐧 reply, go to the message it pins
    val id = match.pinnedItemId ?: match.id
    val info = h.chatInfo
    val rhId = chatModel.getChat(info.id)?.remoteHostId
    scope?.launch {
      openChat(secondaryChatsCtx = null, rhId, info.chatType, info.apiId, openAroundItemId = id)
      ToastyChat.cursor.value = id
    }
  }

  private fun openCursorChat() {
    if (ModalManager.start.hasModalsOpen()) return
    val chat = ToastyChatList.chats.firstOrNull { it.id == ToastyChatList.cursor.value } ?: return
    val rhId = chat.remoteHostId
    if (chat.id == chatModel.chatId.value) {
      ToastyNav.activate(ToastyNav.CHAT)
      return
    }
    scope?.launch {
      when (val info = chat.chatInfo) {
        is ChatInfo.Direct -> directChatAction(rhId, info.contact, chatModel)
        is ChatInfo.Group -> groupChatAction(rhId, info.groupInfo, chatModel)
        is ChatInfo.Local -> noteFolderChatAction(rhId, info.noteFolder)
        else -> say("open this one with a click for now", warn = true)
      }
    }
  }

  private fun insert() {
    if (chatModel.chatId.value != null && ToastyChat.current?.chatInfo?.sendMsgEnabled != false) {
      ToastyNav.activate(ToastyNav.CHAT)
      focusComposer()
    } else {
      ToastyNav.activate(ToastyNav.CHATS)
      startSearch()
    }
  }

  private fun focusComposer() = focusLater(ToastyChat.composeFocus)

  // The key that asked for focus still has its typed character on the way; focusing right away
  // would let that character land in the field (`r` replying with "r…"), so focus after it.
  private fun focusLater(focus: FocusRequester?) {
    focus ?: return
    scope?.launch { runCatching { focus.requestFocus() } }
  }

  private fun cursorItem(): Pair<ToastyChatHandle, ChatItem>? {
    if (inChats) return null
    val h = ToastyChat.current ?: return null
    val item = h.items.value.firstOrNull { it.id == ToastyChat.cursor.value }
    if (item == null) say("pick a message with j/k first", warn = true)
    return item?.let { h to it }
  }

  private fun yankCursor() {
    val (_, item) = cursorItem() ?: return
    val clipboard = clipboard ?: return
    copyItemToClipboard(item, clipboard)
    say("copied")
  }

  private fun reply() {
    val (h, item) = cursorItem() ?: return
    val repliable = (item.content is CIContent.SndMsgContent || item.content is CIContent.RcvMsgContent) &&
        h.chatInfo !is ChatInfo.Local && !item.isReport && item.id != ChatItem.TEMP_LIVE_CHAT_ITEM_ID && h.chatInfo.sendMsgEnabled
    if (!repliable) return say("can't reply to this one", warn = true)
    val cs = h.composeState
    cs.value = if (cs.value.editing) {
      ComposeState(contextItem = ComposeContextItem.QuotedItem(item), useLinkPreviews = h.useLinkPreviews)
    } else {
      cs.value.copy(contextItem = ComposeContextItem.QuotedItem(item))
    }
    focusComposer()
  }

  private fun edit() {
    val (h, item) = cursorItem() ?: return
    if (!item.meta.editable || item.content.msgContent is MsgContent.MCVoice) return say("can't edit this one", warn = true)
    h.composeState.value = ComposeState(editingItem = item, useLinkPreviews = h.useLinkPreviews)
    focusComposer()
  }

  private fun deleteCursor() {
    val (h, item) = cursorItem() ?: return
    val info = h.chatInfo
    val question = if (!item.chatDir.sent || info.featureEnabled(ChatFeature.FullDelete) || info is ChatInfo.Local) {
      generalGetString(MR.strings.delete_message_cannot_be_undone_warning)
    } else {
      generalGetString(MR.strings.delete_message_mark_deleted_warning)
    }
    deleteMessageAlertDialog(item, question, info, h.deleteMessage)
  }

  fun react(emoji: MREmojiChar) {
    val (h, item) = cursorItem() ?: return
    if (!h.chatInfo.featureEnabled(ChatFeature.Reactions) || !item.allowAddReaction) return say("no reactions here", warn = true)
    val reaction = MsgReaction.Emoji(emoji)
    val reacted = item.reactions.any { it.userReacted && it.reaction.text == reaction.text }
    h.setReaction(h.chatInfo, item, !reacted, reaction)
  }

  // ---- visual mode ----

  private fun toggleVisual() {
    if (mode.value == VimMode.VISUAL) return endVisual()
    if (inChats) return say("visual mode works on messages", warn = true)
    val h = ToastyChat.current ?: return
    val items = h.items.value
    if (items.isEmpty()) return
    val start = ToastyChat.cursor.value?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
    ToastyChat.cursor.value = start
    visualAnchor = start
    mode.value = VimMode.VISUAL
    extendVisual(h)
  }

  private fun extendVisual(h: ToastyChatHandle) {
    val items = h.items.value
    val a = items.indexOfFirst { it.id == visualAnchor }
    val c = items.indexOfFirst { it.id == ToastyChat.cursor.value }
    if (a < 0 || c < 0) return
    h.selectedChatItems.value = items.subList(minOf(a, c), maxOf(a, c) + 1).map { it.id }.toSet()
  }

  fun endVisual() {
    if (mode.value == VimMode.VISUAL) mode.value = VimMode.NORMAL
    if (visualAnchor != null) ToastyChat.handle?.selectedChatItems?.value = null
    visualAnchor = null
  }

  private fun selectedItems(h: ToastyChatHandle): List<ChatItem> {
    val ids = h.selectedChatItems.value ?: return emptyList()
    return h.items.value.filter { it.id in ids }.asReversed() // oldest first
  }

  private fun yankSelection() {
    val h = ToastyChat.current ?: return
    val items = selectedItems(h)
    clipboard?.setText(AnnotatedString(items.joinToString("\n") { it.content.text }))
    endVisual()
    say("copied ${items.size} messages")
  }

  private fun deleteSelection() {
    val h = ToastyChat.current ?: return
    val ids = selectedItems(h).map { it.id }
    if (ids.isEmpty()) return endVisual()
    deleteMessagesAlertDialog(ids, generalGetString(MR.strings.delete_message_cannot_be_undone_warning), forAll = false) { toDelete, _ ->
      h.deleteMessages(toDelete)
      endVisual()
    }
  }

  // ---- commands ----

  private val reactionNames = mapOf(
    "+1" to MREmojiChar.ThumbsUp, "like" to MREmojiChar.ThumbsUp, "up" to MREmojiChar.ThumbsUp,
    "-1" to MREmojiChar.ThumbsDown, "down" to MREmojiChar.ThumbsDown,
    "smile" to MREmojiChar.Smile, "laugh" to MREmojiChar.Laugh, "lol" to MREmojiChar.Laugh,
    "sad" to MREmojiChar.Sad, "heart" to MREmojiChar.Heart, "love" to MREmojiChar.Heart,
    "rocket" to MREmojiChar.Launch, "check" to MREmojiChar.Check, "ok" to MREmojiChar.Check,
  )

  fun runCommand(line: String) {
    val parts = line.trim().split(Regex("\\s+"), limit = 2)
    val name = parts[0]
    val arg = parts.getOrNull(1)?.trim().orEmpty()
    when (name) {
      "" -> {}
      "q", "close" -> {
        if (chatModel.chatId.value == null) return say("no chat open")
        chatModel.chatId.value = null
      }
      "1", "2" -> ToastyNav.activate(name.toInt() - 1)
      "o", "open" -> openByName(arg)
      "react" -> {
        val emoji = if (arg.isEmpty()) MREmojiChar.ThumbsUp else reactionNames[arg.lowercase()] ?: MREmojiChar.entries.firstOrNull { it.value == arg }
        if (emoji == null) say("reactions: ${reactionNames.keys.joinToString(" ")}", warn = true) else react(emoji)
      }
      "info" -> ToastyChat.current?.showChatInfo?.invoke() ?: say("no chat open")
      "pin", "unpin" -> cursorItem()?.let { (h, item) ->
        val pinnedByMe = item.isPinMarker || h.items.value.any { it.chatDir.sent && it.pinnedItemId == item.id }
        if ((name == "pin") == pinnedByMe) say(if (pinnedByMe) "already pinned" else "not pinned by you")
        else ToastyPins.toggle(h, item, note = arg)
      }
      "pins" -> showPins()
      "only", "on" -> ToastyNav.toggleSolo(true)
      "split", "sp", "all" -> ToastyNav.toggleSolo(false)
      "s", "search" -> {
        ToastyChatList.searchText?.value = TextFieldValue(arg)
        ToastyNav.activate(ToastyNav.CHATS)
      }
      "noh", "nohl" -> ToastyChatList.searchText?.value = TextFieldValue()
      "h", "help" -> help.value = true
      else -> say("not a command: $name", warn = true)
    }
  }

  private fun openByName(query: String) {
    if (query.isEmpty()) return say("usage: :open <name>", warn = true)
    val chats = ToastyChatList.chats.ifEmpty { chatModel.chats.value }
    val q = query.lowercase()
    val match = chats.firstOrNull { it.chatInfo.displayName.lowercase().startsWith(q) }
      ?: chats.firstOrNull { it.chatInfo.displayName.lowercase().contains(q) }
      ?: return say("no chat matches \"$query\"", warn = true)
    val index = ToastyChatList.chats.indexOf(match)
    if (index >= 0) setChatsCursor(index) else ToastyChatList.cursor.value = match.id
    openCursorChat()
  }
}

private const val PANE_COUNT = 2

/** Scrolls the least amount that shows the whole item (or its start, if it is taller than the view). */
private suspend fun ensureVisible(state: LazyListState, index: Int, startMargin: Int, endMargin: Int) {
  var item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
  if (item == null) {
    state.scrollToItem(index)
    item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
  }
  val info = state.layoutInfo
  val start = info.viewportStartOffset + startMargin
  val end = info.viewportEndOffset - endMargin
  val delta = when {
    item.offset < start -> item.offset - start
    item.offset + item.size > end -> minOf(item.offset + item.size - end, item.offset - start)
    else -> 0
  }
  if (delta != 0) state.animateScrollBy(delta.toFloat())
}
