package com.unfiltered.studio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unfiltered.studio.domain.models.AppSettings
import com.unfiltered.studio.domain.models.ThemeMode
import com.unfiltered.studio.domain.repositories.SettingsRepository
import com.unfiltered.studio.presentation.navigation.AppRoot
import com.unfiltered.studio.presentation.navigation.GenerateActions
import com.unfiltered.studio.presentation.theme.UnfilteredTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var generateActions: GenerateActions

    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val s = settings ?: return@setContent
            LaunchedEffect(Unit) { ready = true }
            val dark = when (s.themeMode) {
                ThemeMode.DARK, ThemeMode.AMOLED -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            UnfilteredTheme(mode = s.themeMode, dynamicColor = s.dynamicColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(generateActions)
                }
            }
        }
    }
}
