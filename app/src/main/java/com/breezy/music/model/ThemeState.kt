package com.breezy.music.model

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

enum class ThemeMode(val label: String) {
    Monet("系统莫奈"),
    FollowSystem("跟随系统"),
    Light("浅色"),
    Dark("深色")
}

enum class CoverShape(val label: String) {
    RoundedRect("圆角矩形"),
    Circle("圆形")
}

object ThemeState {
    var themeMode by mutableStateOf(ThemeMode.FollowSystem)
    var accentColor by mutableStateOf(Color(0xFF4F46E5.toInt()))
    var coverShape by mutableStateOf(CoverShape.RoundedRect)

    fun load(context: Context) {
        themeMode = when (Preferences.getThemeMode(context)) {
            "Monet" -> ThemeMode.Monet
            "Dark" -> ThemeMode.Dark
            "Light" -> ThemeMode.Light
            else -> ThemeMode.FollowSystem
        }
        accentColor = Color(Preferences.getAccentColor(context))
        coverShape = when (Preferences.getCoverShape(context)) {
            "Circle" -> CoverShape.Circle
            else -> CoverShape.RoundedRect
        }
    }

    fun saveThemeMode(context: Context) {
        Preferences.setThemeMode(context, themeMode.name)
    }

    fun saveAccentColor(context: Context) {
        Preferences.setAccentColor(context, accentColor.toArgb())
    }

    fun saveCoverShape(context: Context) {
        Preferences.setCoverShape(context, coverShape.name)
    }
}
