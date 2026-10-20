package chat.simplex.common.toasty

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import chat.simplex.common.model.ChatController.appPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

// Browser-style zoom: ctrl + / ctrl - / ctrl 0 and ctrl + mouse wheel. It sets SimpleX's own desktop
// zoom (Appearance → Zoom), which scales the whole UI, text included, and is remembered. The panes
// are measured in characters of the zoomed font, so zooming in folds them into one when they no longer fit.
object ToastyZoom {
  private val levels = listOf(0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

  fun step(direction: Int) {
    val current = appPrefs.densityScale.get()
    val next = if (direction > 0) levels.firstOrNull { it > current + 0.001f } ?: levels.last()
      else levels.lastOrNull { it < current - 0.001f } ?: levels.first()
    set(next)
  }

  fun reset() = set(1f)

  private fun set(scale: Float) {
    appPrefs.densityScale.set(scale)
    ToastyVim.say("zoom ${(scale * 100).roundToInt()}%")
  }

  /** ctrl + / - / 0, in every mode. */
  fun onKeyEvent(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed || e.isAltPressed) return false
    when (e.key) {
      Key.Equals, Key.Plus, Key.NumPadAdd -> step(1)
      Key.Minus, Key.NumPadSubtract -> step(-1)
      Key.Zero, Key.NumPad0 -> reset()
      else -> return false
    }
    return true
  }
}

/** ctrl + mouse wheel zooms instead of scrolling. */
fun Modifier.toastyWheelZoom(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isCtrlPressed) continue
      val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }
      if (abs(dy) < 0.01) continue
      ToastyZoom.step(if (dy < 0) 1 else -1)
      event.changes.forEach { it.consume() }
    }
  }
}
