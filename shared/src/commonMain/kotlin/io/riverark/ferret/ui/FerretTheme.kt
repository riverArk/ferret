package io.riverark.ferret.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ferret.shared.generated.resources.Res
import ferret.shared.generated.resources.exo2_bold
import ferret.shared.generated.resources.exo2_semibold
import ferret.shared.generated.resources.ubuntu_mono_bold
import ferret.shared.generated.resources.ubuntu_mono_regular
import org.jetbrains.compose.resources.Font

val FerretCanvas = Color(0xFFF4E9D7)
val FerretSurface = Color(0xFFF7F4EA)
val FerretInk = Color(0xFF37353E)
val FerretMuted = Color(0xFF625F6A)
val FerretYellow = Color(0xFFF3CA40)
val FerretCoral = Color(0xFFB95836)
val FerretCoralAccent = Color(0xFFD97D55)
val FerretBlue = Color(0xFF2F6F9C)
val FerretError = Color(0xFF8E3B46)
val FerretOutline = Color(0xFF706C75)

object FerretSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

private val colors = lightColorScheme(
    primary = FerretYellow,
    onPrimary = FerretInk,
    secondary = FerretCoral,
    onSecondary = Color.White,
    tertiary = FerretBlue,
    onTertiary = Color.White,
    background = FerretCanvas,
    onBackground = FerretInk,
    surface = FerretSurface,
    onSurface = FerretInk,
    onSurfaceVariant = FerretMuted,
    error = FerretError,
    onError = Color.White,
    outline = FerretOutline,
)

@Composable
private fun ferretTypography(): Typography {
    val heading = FontFamily(
        Font(Res.font.exo2_semibold, FontWeight.SemiBold),
        Font(Res.font.exo2_bold, FontWeight.Bold),
    )
    val body = FontFamily(
        Font(Res.font.ubuntu_mono_regular, FontWeight.Normal),
        Font(Res.font.ubuntu_mono_bold, FontWeight.Bold),
    )
    fun heading(size: Int, line: Int, weight: FontWeight = FontWeight.SemiBold) =
        TextStyle(fontFamily = heading, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = body, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        displayLarge = heading(57, 64), displayMedium = heading(45, 52), displaySmall = heading(36, 44),
        headlineLarge = heading(32, 40), headlineMedium = heading(28, 36), headlineSmall = heading(24, 32),
        titleLarge = heading(22, 28), titleMedium = heading(16, 24, FontWeight.Bold), titleSmall = heading(14, 20, FontWeight.Bold),
        bodyLarge = body(16, 24), bodyMedium = body(14, 20), bodySmall = body(12, 16),
        labelLarge = body(14, 20, FontWeight.Bold), labelMedium = body(12, 16, FontWeight.Bold), labelSmall = body(11, 16, FontWeight.Bold),
    )
}

@Composable
fun FerretTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = ferretTypography(), content = content)
}
