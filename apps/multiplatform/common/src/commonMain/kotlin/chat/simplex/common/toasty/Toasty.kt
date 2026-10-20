package chat.simplex.common.toasty

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.common.platform.appPlatform
import chat.simplex.common.ui.theme.*

// Toasty: a terminal-style reskin of SimpleX, matching the bejgl finance app.
// Everything Toasty-specific lives in this package; upstream files only get small `if (Toasty.enabled)` hooks.
object Toasty {
  // Desktop first. Android keeps the SimpleX look until the skin works there too.
  val enabled: Boolean get() = appPlatform.isDesktop
}

// Warm, toasted palette, copied from bejgl (src/ui/theme.ts). One accent; green/red mean good/bad.
object ToastyPalette {
  val bg = Color(0xFF14110E)
  val panel = Color(0xFF1D1915)
  val sel = Color(0xFF2B241D)
  val line = Color(0xFF3A322A)
  val text = Color(0xFFE9DFD1)
  val dim = Color(0xFF8C8073)
  val faint = Color(0xFF5A5148)
  val accent = Color(0xFFE8A87C)
  val good = Color(0xFF8FCF9F)
  val bad = Color(0xFFE58E7F)
  val info = Color(0xFF7FB3D5)
  val warn = Color(0xFFE5C07B)
  // not in bejgl: the visual-mode badge, as in vim
  val visual = Color(0xFFC3A6E0)
}

expect val ToastyMono: FontFamily

private val P = ToastyPalette

val ToastyColors = darkColors(
  primary = P.accent,
  primaryVariant = P.accent,
  secondary = P.dim,
  secondaryVariant = P.line,
  background = P.bg,
  surface = P.panel,
  error = P.bad,
  onPrimary = P.bg,
  onSecondary = P.bg,
  onBackground = P.text,
  onSurface = P.text,
  onError = P.bg,
)

val ToastyAppColors = AppColors(
  title = P.accent,
  primaryVariant2 = P.sel,
  sentMessage = P.sel,
  sentQuote = P.line,
  receivedMessage = P.panel,
  receivedQuote = P.sel,
)

fun toastyActiveTheme(name: String) = ThemeManager.ActiveTheme(name, DefaultTheme.DARK, ToastyColors, ToastyAppColors, AppWallpaper())

// Monospace everywhere, a little smaller than the SimpleX sizes because a mono font runs wide.
val ToastyTypography: Typography by lazy {
  Typography(
    defaultFontFamily = ToastyMono,
    h1 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    h2 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 20.sp),
    h3 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    h4 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    body1 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    body2 = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    button = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
  )
}

val ToastyShapes = Shapes(
  small = RoundedCornerShape(0.dp),
  medium = RoundedCornerShape(0.dp),
  large = RoundedCornerShape(0.dp),
)
