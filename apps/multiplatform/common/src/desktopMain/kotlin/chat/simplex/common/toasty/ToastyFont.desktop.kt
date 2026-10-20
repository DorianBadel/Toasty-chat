package chat.simplex.common.toasty

import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.skia.FontMgr

// First installed font from the list; a bundled font can replace this later.
private val preferredMonoFonts = listOf(
  "JetBrains Mono", "Iosevka", "Cascadia Mono", "Cascadia Code", "Consolas",
  "SF Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono",
)

actual val ToastyMono: FontFamily = run {
  val fontMgr = FontMgr.default
  val name = preferredMonoFonts.firstOrNull { name ->
    fontMgr.matchFamilyStyle(name, org.jetbrains.skia.FontStyle.NORMAL)?.familyName.equals(name, ignoreCase = true)
  }
  if (name == null) {
    FontFamily.Monospace
  } else {
    FontFamily(
      SystemFont(name, FontWeight.Normal),
      SystemFont(name, FontWeight.Normal, FontStyle.Italic),
      SystemFont(name, FontWeight.Light),
      SystemFont(name, FontWeight.Medium),
      SystemFont(name, FontWeight.SemiBold),
      SystemFont(name, FontWeight.Bold),
    )
  }
}
