package chat.simplex.common.toasty

import androidx.compose.ui.awt.ComposeWindow
import chat.simplex.common.platform.chatModel
import com.sun.net.httpserver.HttpServer
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.InetSocketAddress
import java.net.URLDecoder
import javax.swing.SwingUtilities

// Dev-only test hook: with TOASTY_DEV_PORT set, a localhost HTTP endpoint types keys into the window
// without it having focus, so the UI can be driven by scripts while you keep using your computer.
//   GET /keys?k=jjk<cr>:open bob<cr>   vim-style key notation: <cr> <esc> <tab> <bs> <up> <down> <c-d> <s-tab>
//   GET /size?w=900&h=700              resize the window
//   GET /state                         mode, pane, cursors
object ToastyDevHook {
  private var started = false

  fun attach(window: ComposeWindow) {
    val port = System.getenv("TOASTY_DEV_PORT")?.toIntOrNull() ?: return
    if (started) return
    started = true
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/keys") { ex ->
      val keys = query(ex.requestURI.rawQuery)["k"].orEmpty()
      val sent = runCatching { type(window, keys) }
      respond(ex, sent.fold({ "ok $it" }, { "error ${it.message}" }))
    }
    server.createContext("/size") { ex ->
      val q = query(ex.requestURI.rawQuery)
      val w = q["w"]?.toIntOrNull()
      val h = q["h"]?.toIntOrNull()
      if (w != null && h != null) SwingUtilities.invokeAndWait { window.setSize(w, h) }
      respond(ex, "ok")
    }
    server.createContext("/state") { ex ->
      respond(
        ex,
        "mode=${ToastyVim.mode.value} pane=${ToastyNav.active.value + 1} chat=${chatModel.chatId.value} " +
            "chatCursor=${ToastyChatList.cursor.value} itemCursor=${ToastyChat.cursor.value} " +
            "selected=${ToastyChat.handle?.selectedChatItems?.value?.size} pending=${ToastyVim.pending.value} " +
            "command=${ToastyVim.command.value} flash=${ToastyVim.flash.value?.text}"
      )
    }
    server.start()
  }

  private fun query(raw: String?): Map<String, String> =
    raw.orEmpty().split("&").filter { "=" in it }.associate {
      val (k, v) = it.split("=", limit = 2)
      k to URLDecoder.decode(v, "UTF-8")
    }

  private fun respond(ex: com.sun.net.httpserver.HttpExchange, body: String) {
    val bytes = body.toByteArray()
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private class Stroke(val code: Int, val char: Char, val mods: Int)

  private val named = mapOf(
    "cr" to Stroke(KeyEvent.VK_ENTER, '\n', 0),
    "esc" to Stroke(KeyEvent.VK_ESCAPE, 27.toChar(), 0),
    "tab" to Stroke(KeyEvent.VK_TAB, '\t', 0),
    "s-tab" to Stroke(KeyEvent.VK_TAB, '\t', InputEvent.SHIFT_DOWN_MASK),
    "bs" to Stroke(KeyEvent.VK_BACK_SPACE, 8.toChar(), 0),
    "up" to Stroke(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0),
    "down" to Stroke(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0),
    "space" to Stroke(KeyEvent.VK_SPACE, ' ', 0),
  )

  private fun parse(keys: String): List<Stroke> {
    val out = mutableListOf<Stroke>()
    var i = 0
    while (i < keys.length) {
      val end = if (keys[i] == '<') keys.indexOf('>', i) else -1
      if (end > i) {
        val name = keys.substring(i + 1, end).lowercase()
        out += named[name] ?: if (name.startsWith("c-") && name.length == 3) {
          val c = name[2]
          Stroke(KeyEvent.getExtendedKeyCodeForChar(c.code), (c.code and 0x1f).toChar(), InputEvent.CTRL_DOWN_MASK)
        } else error("unknown key <$name>")
        i = end + 1
      } else {
        val c = keys[i]
        val shift = c.isUpperCase() || c in "~!@#$%^&*()_+{}|:\"<>?"
        out += Stroke(KeyEvent.getExtendedKeyCodeForChar(c.code), c, if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
        i++
      }
    }
    return out
  }

  private fun type(window: ComposeWindow, keys: String): Int {
    val strokes = parse(keys)
    for (s in strokes) {
      SwingUtilities.invokeAndWait {
        val target = window.mostRecentFocusOwner ?: window
        val now = System.currentTimeMillis()
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, s.mods, s.code, s.char))
        if (s.char != KeyEvent.CHAR_UNDEFINED && s.mods and InputEvent.CTRL_DOWN_MASK == 0) {
          target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_TYPED, now, s.mods, KeyEvent.VK_UNDEFINED, s.char))
        }
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now, s.mods, s.code, s.char))
      }
      Thread.sleep(60)
    }
    return strokes.size
  }
}
