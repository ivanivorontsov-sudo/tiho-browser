package org.ivanivorontsov.tiho.ui

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

private val Light = lightColorScheme(
    primary = Color(0xFF00696B), onPrimary = Color.White, primaryContainer = Color(0xFF9CF1F2), onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363), secondaryContainer = Color(0xFFCCE8E7), surface = Color(0xFFF7FAF9), surfaceVariant = Color(0xFFDAE5E4),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80D5D6), onPrimary = Color(0xFF003738), primaryContainer = Color(0xFF004F50), onPrimaryContainer = Color(0xFF9CF1F2),
    secondary = Color(0xFFB0CCCB), secondaryContainer = Color(0xFF324B4B), surface = Color(0xFF0F1414), surfaceVariant = Color(0xFF3F4948),
)

@Composable
fun TihoTheme(theme: Int, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
