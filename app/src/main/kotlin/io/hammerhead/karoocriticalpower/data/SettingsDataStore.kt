package io.hammerhead.karoocriticalpower.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "power_max_settings")

/**
 * DataStore helper for persisting settings
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        private val KEY_API_KEY = stringPreferencesKey("intervals_api_key")
        private val KEY_ATHLETE_ID = stringPreferencesKey("intervals_athlete_id")
        private val KEY_PR_TIMEFRAME = stringPreferencesKey("pr_timeframe")
        private val KEY_SHOW_PR_COMPARISON = booleanPreferencesKey("show_pr_comparison")
    }

    val settings: Flow<CriticalPowerSettings> = context.dataStore.data.map { prefs ->
        CriticalPowerSettings(
            intervalsApiKey = prefs[KEY_API_KEY] ?: "",
            intervalsAthleteId = prefs[KEY_ATHLETE_ID] ?: "",
            prTimeframe = prefs[KEY_PR_TIMEFRAME]?.let { name ->
                PrTimeframe.entries.find { it.name == name }
            } ?: PrTimeframe.ALL_TIME,
            showPrComparison = prefs[KEY_SHOW_PR_COMPARISON] ?: true
        )
    }

    suspend fun updateSettings(settings: CriticalPowerSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = settings.intervalsApiKey
            prefs[KEY_ATHLETE_ID] = settings.intervalsAthleteId
            prefs[KEY_PR_TIMEFRAME] = settings.prTimeframe.name
            prefs[KEY_SHOW_PR_COMPARISON] = settings.showPrComparison
        }
    }

    suspend fun updateApiKey(apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = apiKey
        }
    }

    suspend fun updateAthleteId(athleteId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ATHLETE_ID] = athleteId
        }
    }

    suspend fun updatePrTimeframe(timeframe: PrTimeframe) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PR_TIMEFRAME] = timeframe.name
        }
    }

    suspend fun updateShowPrComparison(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SHOW_PR_COMPARISON] = show
        }
    }
}
