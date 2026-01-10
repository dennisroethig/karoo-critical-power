package io.hammerhead.karoocriticalpower.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Categorized error types for API operations
 */
sealed class ApiError(val message: String) {
    class NetworkError(message: String) : ApiError(message)
    class AuthenticationError(message: String) : ApiError(message)
    class NotFoundError(message: String) : ApiError(message)
    class ServerError(message: String) : ApiError(message)
    class ParseError(message: String) : ApiError(message)
    class UnknownError(message: String) : ApiError(message)
}

/**
 * Result wrapper for API calls
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

/**
 * Power curve data containing best watts for specific durations
 */
data class PowerCurveData(
    val durationToWatts: Map<Int, Double>
) {
    fun getWattsForDuration(seconds: Int): Double? = durationToWatts[seconds]
}

/**
 * Client for fetching power curve data from intervals.icu API
 */
class IntervalsIcuClient(
    private val apiKey: String,
    private val athleteId: String
) {
    companion object {
        private const val TAG = "IntervalsIcuClient"
        private const val BASE_URL = "https://intervals.icu/api/v1"

        // Durations we care about (in seconds)
        val TARGET_DURATIONS = listOf(5, 15, 30, 60, 180, 300, 1200, 1800, 2700, 3600, 5400)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch power curve data from intervals.icu
     * @param timeframeDays Optional number of days to look back (null = all time)
     * @return ApiResult with PowerCurveData on success, or categorized error on failure
     */
    suspend fun fetchPowerCurve(timeframeDays: Int? = null): ApiResult<PowerCurveData> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(timeframeDays)
            Log.d(TAG, "Fetching power curve from: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic("API_KEY", apiKey))
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "API request failed: ${response.code} ${response.message}")
                return@withContext ApiResult.Failure(categorizeHttpError(response.code, response.message))
            }

            val body = response.body?.string()
            if (body == null) {
                Log.e(TAG, "Empty response body")
                return@withContext ApiResult.Failure(ApiError.ParseError("Empty response from server"))
            }

            Log.d(TAG, "Response body (first 500 chars): ${body.take(500)}")
            val data = parsePowerCurveResponse(body)
            if (data != null) {
                ApiResult.Success(data)
            } else {
                ApiResult.Failure(ApiError.ParseError("Could not parse power curve data"))
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Network error: ${e.message}")
            ApiResult.Failure(ApiError.NetworkError("No internet connection"))
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Timeout: ${e.message}")
            ApiResult.Failure(ApiError.NetworkError("Connection timed out"))
        } catch (e: IOException) {
            Log.e(TAG, "IO error: ${e.message}")
            ApiResult.Failure(ApiError.NetworkError("Network error: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching power curve: ${e.javaClass.simpleName}: ${e.message}")
            ApiResult.Failure(ApiError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    private fun categorizeHttpError(code: Int, message: String?): ApiError {
        return when (code) {
            401, 403 -> ApiError.AuthenticationError("Invalid API key or unauthorized")
            404 -> ApiError.NotFoundError("Athlete not found - check your athlete ID")
            in 500..599 -> ApiError.ServerError("intervals.icu server error ($code)")
            else -> ApiError.UnknownError("HTTP error $code: ${message ?: "Unknown"}")
        }
    }

    private fun buildUrl(timeframeDays: Int?): String {
        val baseUrl = "$BASE_URL/athlete/$athleteId/power-curves"
        val params = mutableListOf("type=Ride")

        if (timeframeDays != null) {
            val oldest = LocalDate.now().minusDays(timeframeDays.toLong())
            params.add("oldest=${oldest.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
        }

        return "$baseUrl?${params.joinToString("&")}"
    }

    /**
     * Parse the power curve API response.
     * The response format contains curve data with secs (durations) and watts arrays.
     */
    private fun parsePowerCurveResponse(responseBody: String): PowerCurveData? {
        return try {
            val jsonElement = json.parseToJsonElement(responseBody)

            // The API returns an object with curves data
            // Try to find the power curve data in the response
            val durationToWatts = mutableMapOf<Int, Double>()

            when (jsonElement) {
                is JsonObject -> {
                    // API returns {"list": [...]} with power curve data
                    val list = jsonElement["list"]?.jsonArray
                    if (list != null && list.isNotEmpty()) {
                        // Use the first item (typically "1y" - 1 year of data)
                        val curve = list[0].jsonObject
                        extractCurveData(curve, durationToWatts)
                    } else {
                        // Try direct extraction from root object
                        extractCurveData(jsonElement, durationToWatts)
                    }
                }
                is JsonArray -> {
                    // If response is an array, use first element
                    if (jsonElement.isNotEmpty()) {
                        extractCurveData(jsonElement[0].jsonObject, durationToWatts)
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected response format")
                    return null
                }
            }

            if (durationToWatts.isEmpty()) {
                Log.w(TAG, "No power curve data found in response")
                return null
            }

            Log.d(TAG, "Parsed ${durationToWatts.size} power curve points")
            PowerCurveData(durationToWatts)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing power curve response", e)
            null
        }
    }

    /**
     * Extract secs and watts arrays from a curve object
     */
    private fun extractCurveData(curveObject: JsonObject, result: MutableMap<Int, Double>) {
        val secs = curveObject["secs"]?.jsonArray
        val watts = curveObject["watts"]?.jsonArray

        Log.d(TAG, "Extracting curve data: secs=${secs?.size}, watts=${watts?.size}")

        if (secs != null && watts != null && secs.size == watts.size) {
            for (i in secs.indices) {
                val duration = secs[i].jsonPrimitive.int
                val power = watts[i].jsonPrimitive.double

                // Only store if it's one of our target durations
                if (duration in TARGET_DURATIONS && power > 0) {
                    result[duration] = power
                    Log.d(TAG, "Found power for ${duration}s: ${power}W")
                }
            }
        } else {
            Log.w(TAG, "Missing secs or watts array, or size mismatch")
            // Log available keys in the object for debugging
            Log.d(TAG, "Available keys: ${curveObject.keys}")
        }
    }
}
