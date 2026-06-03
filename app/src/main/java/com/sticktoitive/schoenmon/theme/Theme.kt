package com.sticktoitive.schoenmon.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = CyberCyan,
  secondary = NeoGreen,
  tertiary = ElectricPink,
  background = ObsidianBg,
  surface = ObsidianCard,
  onPrimary = androidx.compose.ui.graphics.Color.Black,
  onSecondary = androidx.compose.ui.graphics.Color.Black,
  onTertiary = androidx.compose.ui.graphics.Color.Black,
  onBackground = TextWhite,
  onSurface = TextWhite,
  onSurfaceVariant = TextGray,
  outline = CharcoalBorder
)

private val LightColorScheme = DarkColorScheme // Keep premium dark aesthetic consistent across light/dark system settings

@Composable
fun SchoenMonTheme(
  darkTheme: Boolean = true, // Default to true for premium dark cyberpunk experience
  // Dynamic color is disabled by default to preserve the cyberpunk obsidian theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
