package io.hammerhead.karoocriticalpower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.SettingsDataStore
import io.hammerhead.karoocriticalpower.screens.SettingsScreen
import io.hammerhead.karoocriticalpower.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val settingsDataStore by lazy { SettingsDataStore(applicationContext) }
    private val powerCurveRepository by lazy { PowerCurveRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                SettingsScreen(
                    settingsDataStore = settingsDataStore,
                    powerCurveRepository = powerCurveRepository,
                    onTestConnection = { apiKey, athleteId ->
                        powerCurveRepository.testConnection(apiKey, athleteId)
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}
