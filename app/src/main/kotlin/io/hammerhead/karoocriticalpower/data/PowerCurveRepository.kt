package io.hammerhead.karoocriticalpower.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.powerCurveDataStore: DataStore<Preferences> by preferencesDataStore(name = "power_curve_cache")

/**
 * Repository for managing power curve (PR) data from intervals.icu.
 * Caches data to device storage so PRs are available even without internet.
 *
 * Process-wide singleton so the settings screen and the extension service
 * observe the same in-memory state (fetch status, errors, last-updated).
 */
class PowerCurveRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PowerCurveRepository"
        private val KEY_POWER_CURVE_JSON = stringPreferencesKey("power_curve_json")
        private val KEY_LAST_FETCH_TIME = longPreferencesKey("last_fetch_time")

        @Volatile
        private var instance: PowerCurveRepository? = null

        fun getInstance(context: Context): PowerCurveRepository =
            instance ?: synchronized(this) {
                instance ?: PowerCurveRepository(context.applicationContext).also { instance = it }
            }
    }

    private val _powerCurve = MutableStateFlow<PowerCurveData?>(null)
    val powerCurve: StateFlow<PowerCurveData?> = _powerCurve.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<ApiError?>(null)
    val lastError: StateFlow<ApiError?> = _lastError.asStateFlow()

    private val _lastFetchTime = MutableStateFlow<Long?>(null)
    val lastFetchTime: StateFlow<Long?> = _lastFetchTime.asStateFlow()

    private val _isFromCache = MutableStateFlow(false)
    val isFromCache: StateFlow<Boolean> = _isFromCache.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    // Completed once the cached curve has been loaded (or load failed);
    // fetches wait on this so a slow cache load can't overwrite fresh data
    private val cacheLoaded = CompletableDeferred<Unit>()

    init {
        // Load cached data on init
        CoroutineScope(Dispatchers.IO).launch {
            loadCachedData()
        }
    }

    /**
     * Load cached power curve data from DataStore
     */
    private suspend fun loadCachedData() {
        try {
            val prefs = context.powerCurveDataStore.data.first()
            val cachedJson = prefs[KEY_POWER_CURVE_JSON]
            val lastFetch = prefs[KEY_LAST_FETCH_TIME] ?: 0L

            if (cachedJson != null) {
                val durationToWatts = json.decodeFromString<Map<Int, Double>>(cachedJson)
                _powerCurve.value = PowerCurveData(durationToWatts)
                _lastFetchTime.value = lastFetch
                _isFromCache.value = true
                Log.d(TAG, "Loaded cached power curve (${durationToWatts.size} points, fetched ${formatAge(lastFetch)})")
            } else {
                Log.d(TAG, "No cached power curve data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached data: ${e.message}")
        } finally {
            cacheLoaded.complete(Unit)
        }
    }

    /**
     * Save power curve data to DataStore
     */
    private suspend fun saveCachedData(data: PowerCurveData) {
        try {
            context.powerCurveDataStore.edit { prefs ->
                prefs[KEY_POWER_CURVE_JSON] = json.encodeToString(data.durationToWatts)
                prefs[KEY_LAST_FETCH_TIME] = System.currentTimeMillis()
            }
            Log.d(TAG, "Saved power curve to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cached data: ${e.message}")
        }
    }

    private fun formatAge(timestamp: Long): String {
        if (timestamp == 0L) return "never"
        val ageMs = System.currentTimeMillis() - timestamp
        val hours = ageMs / (1000 * 60 * 60)
        return if (hours < 1) "just now" else "${hours}h ago"
    }

    /**
     * Fetch power curve data using the provided settings.
     * On success, caches the data for offline use.
     */
    /**
     * Fetch only if there is no usable data yet or the cache is older than [maxAgeMs].
     * Used at service start so a fresh boot with a recent cache doesn't hit the network.
     */
    suspend fun fetchIfStale(settings: CriticalPowerSettings, maxAgeMs: Long): Boolean {
        cacheLoaded.await()
        val fetchedAt = _lastFetchTime.value
        val isFresh = _powerCurve.value != null &&
            fetchedAt != null &&
            System.currentTimeMillis() - fetchedAt < maxAgeMs
        if (isFresh) {
            Log.d(TAG, "Cache is fresh, skipping fetch")
            return true
        }
        return fetchPowerCurve(settings)
    }

    suspend fun fetchPowerCurve(settings: CriticalPowerSettings): Boolean {
        cacheLoaded.await()
        if (!settings.isConfigured) {
            Log.d(TAG, "Settings not configured, skipping fetch")
            return false
        }

        if (settings.prTimeframe == PrTimeframe.PER_RIDE) {
            Log.d(TAG, "Per-ride mode, skipping API fetch")
            return false
        }

        if (_isLoading.value) {
            Log.d(TAG, "Already fetching, skipping")
            return false
        }

        _isLoading.value = true
        _lastError.value = null

        return try {
            val client = IntervalsIcuClient(
                apiKey = settings.intervalsApiKey,
                athleteId = settings.intervalsAthleteId
            )

            when (val result = client.fetchPowerCurve(settings.prTimeframe.days)) {
                is ApiResult.Success -> {
                    _powerCurve.value = result.data
                    _lastFetchTime.value = System.currentTimeMillis()
                    _isFromCache.value = false
                    saveCachedData(result.data)
                    Log.d(TAG, "Power curve fetched successfully")
                    true
                }
                is ApiResult.Failure -> {
                    _lastError.value = result.error
                    Log.e(TAG, "Failed to fetch power curve: ${result.error.message}")
                    false
                }
            }
        } catch (e: Exception) {
            _lastError.value = ApiError.UnknownError(e.message ?: "Unknown error")
            Log.e(TAG, "Error fetching power curve", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Get PR watts for a specific duration
     */
    fun getPrForDuration(seconds: Int): Double? {
        return _powerCurve.value?.getWattsForDuration(seconds)
    }

    /**
     * Test connection with provided credentials
     * @return Pair of (success, error message if failed)
     */
    suspend fun testConnection(apiKey: String, athleteId: String): Pair<Boolean, String?> {
        return try {
            val client = IntervalsIcuClient(apiKey = apiKey, athleteId = athleteId)
            when (val result = client.fetchPowerCurve(null)) {
                is ApiResult.Success ->
                    if (result.data.durationToWatts.isEmpty()) {
                        Pair(true, "no power data on intervals.icu yet")
                    } else {
                        Pair(true, null)
                    }
                is ApiResult.Failure -> Pair(false, result.error.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection failed", e)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Clear the last error (e.g., after user acknowledges it)
     */
    fun clearError() {
        _lastError.value = null
    }
}
