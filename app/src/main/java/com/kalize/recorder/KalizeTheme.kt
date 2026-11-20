package com.kalize.recorder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Shared persisted theme for every Kalize Activity window. */
@Composable
fun KalizeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = remember {
        context.getSharedPreferences("kalize_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("dark_theme", true)
    }
    val accent = if (dark) Color(0xFF9B86FF) else Color(0xFF6952D8)
    val background = if (dark) Color(0xFF0B1020) else Color(0xFFF4F5FA)
    val surface = if (dark) Color(0xFF171F32) else Color.White
    val scheme = if (dark) {
        darkColorScheme(primary = accent, background = background, surface = surface)
    } else {
        lightColorScheme(primary = accent, background = background, surface = surface)
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = background, content = content)
    }
}
