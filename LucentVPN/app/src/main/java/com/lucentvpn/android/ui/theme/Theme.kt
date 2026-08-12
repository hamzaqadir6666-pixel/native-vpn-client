package com.lucentvpn.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.lucentvpn.android.data.SettingsStore

private val DarkScheme = darkColorScheme(
    primary = LucentColors.Aqua,
    onPrimary = LucentColors.DeepInk,
    secondary = LucentColors.Mist,
    onSecondary = LucentColors.DeepInk,
    background = LucentColors.DeepInk,
    onBackground = LucentColors.Bone,
    surface = LucentColors.Slate,
    onSurface = LucentColors.Bone,
    surfaceVariant = LucentColors.SlateEdge,
    onSurfaceVariant = LucentColors.Mist,
    outline = LucentColors.SlateEdge,
    error = LucentColors.Amber,
    onError = LucentColors.DeepInk,
)

private val LightScheme = lightColorScheme(
    primary = LucentColors.AquaDeep,
    onPrimary = Color.White,
    secondary = LucentColors.CharcoalMist,
    onSecondary = Color.White,
    background = LucentColors.Paper,
    onBackground = LucentColors.Charcoal,
    surface = LucentColors.PaperRaised,
    onSurface = LucentColors.Charcoal,
    surfaceVariant = LucentColors.PaperEdge,
    onSurfaceVariant = LucentColors.CharcoalMist,
    outline = LucentColors.PaperEdge,
    error = LucentColors.AmberDeep,
    onError = Color.White,
)

@Composable
fun LucentTheme(
    themeMode: SettingsStore.ThemeMode = SettingsStore.ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        SettingsStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        SettingsStore.ThemeMode.DARK -> true
        SettingsStore.ThemeMode.LIGHT -> false
    }

    // Dynamic colour is deliberately not used. The aqua-means-protected signal
    // is load-bearing here, and letting the wallpaper repaint it would break
    // the one piece of colour semantics this app depends on.
    val scheme = if (dark) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = LucentTypography,
        content = content,
    )
}
