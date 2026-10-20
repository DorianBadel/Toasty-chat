package chat.simplex.common.toasty

import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatInfo

/**
 * Fuzzy match: every query character must appear in `text` in order (spaces in the query are ignored).
 * Higher is better: a prefix, characters next to each other and characters at word starts score more.
 * Null when it does not match at all.
 */
fun fuzzyScore(text: String, query: String): Int? {
  val t = text.lowercase()
  val q = query.lowercase().filterNot { it.isWhitespace() }
  if (q.isEmpty()) return 0
  var from = 0
  var previous = -2
  var score = 0
  for (c in q) {
    val i = t.indexOf(c, from)
    if (i < 0) return null
    score += 1
    if (i == previous + 1) score += 5
    if (i == 0 || !t[i - 1].isLetterOrDigit()) score += 8
    previous = i
    from = i + 1
  }
  if (t.startsWith(q)) score += 20
  return score
}

/** Chats whose name fuzzy-matches `query`, best match first (ties keep the list order). */
fun toastyFilterChats(chats: List<Chat>, query: String): List<Chat> =
  chats
    .filter { val info = it.chatInfo; !info.chatDeleted && !info.contactCard && info !is ChatInfo.InvalidJSON }
    .mapNotNull { chat ->
      val names = listOf(chat.chatInfo.displayName, chat.chatInfo.fullName, chat.chatInfo.chatViewName)
      names.mapNotNull { fuzzyScore(it, query) }.maxOrNull()?.let { chat to it }
    }
    .sortedByDescending { it.second }
    .map { it.first }
