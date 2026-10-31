package com.blaisegg.idfwidget.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val IdfmNavy = Color(0xFF0B1F3A)
val IdfmYellow = Color(0xFFFFCD00)

@Composable
fun IdfTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = IdfmYellow, onPrimary = IdfmNavy, secondary = Color(0xFF8FB3E8))
    } else {
        lightColorScheme(primary = IdfmNavy, onPrimary = Color.White, secondary = Color(0xFF2E5C9A))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
