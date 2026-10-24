package io.github.gl1ch5.linuxarm.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val Fallback = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00285E),
    primaryContainer = Color(0xFF1E3A6B),
    secondary = Color(0xFFB9C6EA),
    tertiary = Color(0xFF7FD8BE),
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF22262E),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(LocalContext.current) else Fallback
    val base = Typography()
    // Slightly larger text: the app is meant to be used one-handed on a phone.
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
