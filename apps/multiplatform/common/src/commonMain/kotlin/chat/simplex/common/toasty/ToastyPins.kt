package chat.simplex.common.toasty

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.simplex.common.model.*
import chat.simplex.common.platform.chatModel
import chat.simplex.common.views.helpers.withBGApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Pins, without changing SimpleX's protocol: a pin is a reply whose text starts with 🐧, quoting the
// pinned message. Everyone in the chat sees it (on any SimpleX app, as a normal reply), anyone's
// 🐧 reply counts, and unpinning deletes your 🐧 reply.

const val PIN = "🐧"

val ChatItem.isPinMarker: Boolean
  get() = quotedItem?.itemId != null && content.text.trimStart().startsWith(PIN) && meta.itemDeleted == null

val ChatItem.pinnedItemId: Long? get() = if (isPinMarker) quotedItem?.itemId else null

/** Ids of the loaded messages that a loaded 🐧 reply points at. */
fun pinnedIds(items: List<ChatItem>): Set<Long> = items.mapNotNullTo(mutableSetOf()) { it.pinnedItemId }

object ToastyPins {
  /** `p`: pins the message, or unpins it if you pinned it (also works on your 🐧 reply itself). */
  fun toggle(h: ToastyChatHandle, item: ChatItem, note: String = "") {
    val info = h.chatInfo
    if (info is ChatInfo.Local) return ToastyVim.say("notes can't quote messages, so no pins there", warn = true)
    if (!info.sendMsgEnabled) return ToastyVim.say("can't send to this chat", warn = true)
    val items = h.items.value
    val myMarker = if (item.isPinMarker && item.chatDir.sent) item
      else items.firstOrNull { it.chatDir.sent && it.pinnedItemId == item.id }
    when {
      myMarker != null -> {
        h.deleteMessage(myMarker.id, if (myMarker.meta.deletable) CIDeleteMode.cidmBroadcast else CIDeleteMode.cidmInternal)
        ToastyVim.say("unpingvinned")
      }
      item.isPinMarker -> ToastyVim.say("that pin is someone else's", warn = true)
      item.content !is CIContent.SndMsgContent && item.content !is CIContent.RcvMsgContent ->
        ToastyVim.say("only messages can be pinned", warn = true)
      // not a lone 🐧: SimpleX draws emoji-only messages huge, and "pinned" reads well in other apps
      else -> send(info, item, "$PIN ${note.ifBlank { "pinned" }}")
    }
  }

  private fun send(info: ChatInfo, item: ChatItem, text: String) {
    val chat = chatModel.getChat(info.id) ?: return
    withBGApi {
      val sent = chatModel.controller.apiSendMessages(
        rh = chat.remoteHostId,
        type = info.chatType,
        id = info.apiId,
        scope = info.groupChatScope(),
        sendAsGroup = info.sendAsGroup,
        composedMessages = listOf(ComposedMessage(null, item.id, MsgContent.MCText(text), emptyMap()))
      )
      withContext(Dispatchers.Main) {
        sent?.forEach { chatModel.chatsContext.addChatItem(chat.remoteHostId, it.chatInfo, it.chatItem) }
        ToastyVim.say(if (sent.isNullOrEmpty()) "pin failed" else "pingvinned 🐧", warn = sent.isNullOrEmpty())
      }
    }
  }
}

/** A 🐧 in the left "sign column" of a pinned message, like a vim sign. */
@Composable
fun BoxScope.ToastyPinBadge(item: ChatItem) {
  if (!Toasty.enabled) return
  val pinned = ToastyChat.current?.pinned?.value ?: return
  if (item.id !in pinned) return
  Text(PIN, Modifier.align(Alignment.TopStart).padding(start = 4.dp), style = MaterialTheme.typography.caption)
}
