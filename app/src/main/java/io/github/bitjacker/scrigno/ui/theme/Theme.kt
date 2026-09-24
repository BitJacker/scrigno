package io.github.bitjacker.scrigno.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF0F766E)
private val TealLight = Color(0xFF5EEAD4)
private val Gold = Color(0xFFB7791F)
private val GoldLight = Color(0xFFF6C35B)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDEBC8),
    onSecondaryContainer = Color(0xFF3D2800),
    background = Color(0xFFFBFDFB),
    surface = Color(0xFFFBFDFB),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = GoldLight,
    onSecondary = Color(0xFF3D2800),
    secondaryContainer = Color(0xFF5C4100),
    onSecondaryContainer = Color(0xFFFDEBC8),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
)

/** Material 3, with the wallpaper colors on Android 12+ and teal & gold elsewhere. */
@Composable
fun ScrignoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
