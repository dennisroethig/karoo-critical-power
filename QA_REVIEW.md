# Karoo Critical Power - QA Review

Comprehensive quality assurance and product review conducted on 2026-01-11.

---

## Critical Bug

Issues that could cause crashes, data corruption, or major user-facing problems.

### 1. Double-Sampling Bug: Values Appear at Half the Expected Time

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/datatypes/CriticalPowerDataType.kt`
**Lines:** 70-80 (stream) and 162-170 (view)

**Also in:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/datatypes/PowerCurveOverviewDataType.kt`
**Lines:** 94-98 (stream) and 219-223 (view)

Both `startStream()` and `startView()` subscribe to the Karoo power data stream and **both** call `powerBuffer.addSample(watts)` on the **same** buffer instance. This means every power sample is counted twice.

**Impact:** A 30-minute buffer that should need 1800 samples (30 min × 60 sec at 1Hz) fills after only 900 seconds (~15 minutes). Users see power values for durations they haven't actually reached yet.

**Observed behavior:** 30-minute power values appearing after ~16 minutes of riding.

**Recommendation:** Only add samples in ONE place - either the stream or the view, not both. The stream should be the source of truth, and the view should only read from the buffer.

---

### 2. Values Disappear Mid-Ride (Memory/Lifecycle Issue)

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/extension/KarooCriticalPowerExtension.kt`

**Observed behavior:** After 60-70 minutes of riding, values that were showing (30m, 60m) suddenly reset to "--".

**Possible causes:**

1. **Android killing the extension service under memory pressure**
   - The extension maintains 22 PowerBuffers (11 individual + 11 in overview)
   - Large buffers for 60m (3600 samples) and 90m (5400 samples) consume significant memory
   - When Android kills and recreates the service, all `lazy` data types are reinitialized with fresh buffers
   - `bestAverage` resets to 0.0, causing values to show "--"

2. **Spurious RideState.Recording events** (Lines 76-89)
   - When `RideState.Recording` is detected, ALL buffers are reset
   - If Karoo sends this event mid-ride (after pause/resume, GPS glitch, etc.), data is wiped
   - No guard against resetting buffers that already have data

3. **Duplicate buffer memory waste**
   - Individual data types each have their own PowerBuffer
   - PowerCurveOverviewDataType has a SEPARATE set of 11 buffers
   - This doubles memory usage unnecessarily

**Recommendations:**
- Add logging when buffers are reset to diagnose the trigger
- Consider persisting `bestAverage` values to survive service restarts
- Share buffers between individual data types and overview (single source of truth)
- Add a guard: only reset buffers if ride time is < 30 seconds (new ride detection)

---

### 3. Race Condition in PowerBuffer (Thread Safety)

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/PowerBuffer.kt`
**Lines:** 24-42

The `PowerBuffer` class has no synchronization for multi-threaded access. Power data is added on the IO thread via `streamDataFlow`, while views read from the Main thread. This can cause:
- Stale or inconsistent power values displayed
- Incorrect PR comparison results
- Potential crashes from corrupted state

```kotlin
fun addSample(watts: Double) {
    // No synchronization - multiple threads access this
    buffer[index] = watts
    currentSum += watts
    // ...
}
```

**Note:** If the double-sampling bug (#1) is fixed by only adding samples in the stream, this becomes less critical since only one thread would be writing. Still worth fixing for correctness.

**Recommendation:** Use `@Synchronized` annotation, `AtomicReference`, or a mutex to protect shared state.

---

### 4. Precision Loss in Power Value Conversion

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/datatypes/CriticalPowerDataType.kt`
**Lines:** 171, 187

Power values are converted from `Double` to `Int`, silently truncating fractional watts:

```kotlin
val bestPower = powerBuffer.getBestAverage()?.toInt()
val currentRolling = powerBuffer.getCurrentAverage()?.toInt()
```

**Impact:** A power reading of 309.7W becomes 309W. If your PR is 310W, you'll miss the "beating PR" highlight by 0.3W when you might actually be at 309.9W.

**Recommendation:** Keep as `Double` for calculations, round properly for display using `roundToInt()`.

---

### 5. Unmanaged CoroutineScope Lifecycle

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/extension/KarooCriticalPowerExtension.kt`
**Lines:** 70-109, 85-87

Multiple places create `CoroutineScope(Dispatchers.IO)` that are not properly tied to the service lifecycle:

```kotlin
serviceJob = CoroutineScope(Dispatchers.IO).launch {
    // This scope survives even if serviceJob is cancelled
}

// And later:
CoroutineScope(Dispatchers.IO).launch {
    fetchPowerCurve()  // Unbounded scope, never cancelled
}
```

**Impact:** Memory leaks, potential crashes when callbacks fire after extension destruction.

**Recommendation:** Use a single managed scope, cancel it in `onDestroy()`.

---

### 6. PER_RIDE Mode Passes Invalid Days to API

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/data/Settings.kt` (Line 12)
**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/data/IntervalsIcuClient.kt` (Lines 139-141)

`PER_RIDE` is defined with `-1` days:

```kotlin
PER_RIDE(-1, "Per Ride")
```

When this is passed to the API query logic:

```kotlin
if (timeframeDays != null) {
    val oldest = LocalDate.now().minusDays(timeframeDays.toLong())
    // -1 days = +1 day in the future!
}
```

**Impact:** API query uses a future date, potentially returning unexpected or no results.

**Recommendation:** Add explicit guard for `PER_RIDE` mode before making API calls.

---

## Not So Important Bug/Inconsistency

Minor bugs or inconsistencies that don't break functionality but should be addressed.

### 1. Hardcoded 1Hz Sample Rate Assumption

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/PowerBuffer.kt`
**Lines:** 10-14

```kotlin
class PowerBuffer(
    private val durationSeconds: Int,
    private val sampleRateHz: Int = 1  // Hardcoded assumption
)
```

If Karoo's power stream runs at 2Hz or 0.5Hz, buffer size calculations will be wrong. A "5 second" buffer at 2Hz would only hold 2.5 seconds of data.

**Recommendation:** Document the expected sample rate, or dynamically detect it.

---

### 2. Inconsistent Green Colors for "Beating PR"

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerWithPrView.kt` (Line 35)
**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerBarsView.kt` (Line 47)

Two different shades of green are used:
- PowerWithPrView: `Color(0xFF2E7D32)` (darker)
- PowerBarsView: `Color(0xFF4CAF50)` (lighter)

**Impact:** Visual inconsistency; users see different colors for the same concept.

**Recommendation:** Define colors in `Theme.kt` and reference consistently.

---

### 3. Hardcoded Colors Bypass Theme System

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerBarsView.kt` (Lines 44-52)
**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerWithPrView.kt` (Lines 34-38)
**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/theme/Theme.kt` (Lines 10-13)

`Theme.kt` provides no custom colors - just wraps `MaterialTheme` with defaults. Meanwhile, views use hardcoded colors:

```kotlin
// PowerBarsView
val notRecording = Color(0xFFE0E0E0)
val belowPr = Color(0xFFFFC107)
val atOrAbovePr = Color(0xFF4CAF50)
```

**Impact:** If theming is added later, these colors won't update.

**Recommendation:** Define a proper color palette in `Theme.kt`.

---

### 4. Duration Label Inconsistency: "90m" vs "1h30m"

**File:** `app/src/main/res/values/strings.xml` (Line 46)
**File:** `app/src/main/res/xml/extension_info.xml` (Line 87)

The 90-minute duration uses different labels:
- `strings.xml`: "90m Power"
- Code/extension: "1h30m"

**Impact:** User confusion about which duration they're looking at.

**Recommendation:** Standardize on one format (prefer "1h30m" as it's more intuitive).

---

### 5. Empty MainScreen Serves No Purpose

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/MainScreen.kt`
**Line:** 24

`MainScreen()` just displays "Karoo Critical Power" centered with no useful content.

**Impact:** Wasted screen real estate; users might be confused about its purpose.

**Recommendation:** Either remove and launch directly to Settings, or add onboarding/help content.

---

### 6. Best Average Check Uses Wrong Comparison

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/PowerBuffer.kt`
**Line:** 48

```kotlin
fun getBestAverage(): Double? = if (bestAverage > 0) bestAverage else null
```

Uses `> 0` but should check if `bestAverage` has been set. If a very low power reading sets it to exactly 0.0, this returns null incorrectly.

**Recommendation:** Use a nullable `Double?` with explicit null initialization.

---

### 7. JSON Parsing Can Throw Uncaught Exceptions

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/data/IntervalsIcuClient.kt`
**Lines:** 208-209

```kotlin
val duration = secs[i].jsonPrimitive.int
val power = watts[i].jsonPrimitive.double
```

If API returns malformed JSON (null, string instead of number), these throw exceptions that aren't caught.

**Impact:** One bad value breaks entire power curve parsing.

**Recommendation:** Wrap in try-catch, skip invalid entries gracefully.

---

### 8. viewJob CoroutineScope Memory Leak

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/datatypes/CriticalPowerDataType.kt`
**Lines:** 125-194

```kotlin
val scope = CoroutineScope(Dispatchers.Main)
viewJob = scope.launch { ... }

emitter.setCancellable {
    viewJob?.cancel()  // Job cancelled, but scope isn't
}
```

**Impact:** Minor memory leak; scope resources not released.

**Recommendation:** Call `scope.cancel()` in the cancellable block.

---

## Nice-to-Have

UX improvements and polish items that would enhance the user experience.

### 1. Color-Only PR Indicators (Accessibility)

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerBarsView.kt` (Lines 117-142)

Bar colors (green/yellow/gray) are the only indication of PR status. Users with color blindness cannot distinguish them.

**Recommendation:** Add pattern fills (stripes, checkmarks) or text labels ("PR!") in addition to color.

---

### 2. Per Ride Mode Not Explained in UI

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt` (Lines 163-186)

The timeframe dropdown includes "Per Ride" but doesn't explain what it means. README explains it, but users shouldn't need to read documentation.

**Recommendation:** Add help text below dropdown explaining each option.

---

### 3. Error Messages Lack Actionable Guidance

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/data/IntervalsIcuClient.kt` (Lines 126-132)

Current messages:
- "Invalid API key or unauthorized"
- "Athlete not found - check your athlete ID"

**Recommendation:** Add specific guidance:
- "Invalid API key - find it at intervals.icu > Settings > Developer"
- "Athlete ID not found - check your profile URL format (e.g., i12345)"

---

### 4. No Retry Button for Failed Operations

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt`

When test connection fails or data fetch fails, users must manually re-enter settings or restart the app.

**Recommendation:** Add "Retry" button that appears on errors.

---

### 5. Test Button Disabled Too Aggressively

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt`
**Line:** 245

Button disabled if ANY validation error exists. Users might want to test partial credentials to understand what's wrong.

**Recommendation:** Allow testing with at least one field filled to show specific error.

---

### 6. Missing Mode Indicator in Power Display

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerWithPrView.kt`
**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerBarsView.kt`

Display shows "285 / 310" but users don't know if 310 is today's best or an all-time PR.

**Recommendation:** Add small label like "vs PR" or "vs Today" to clarify comparison mode.

---

### 7. Cache Status Not User-Friendly

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt` (Lines 374-380)

Shows "(cached)" but doesn't explain:
- When was it cached?
- Is it stale?
- Will it auto-refresh?

**Recommendation:** Show "Last updated: 2 hours ago" instead of just "(cached)".

---

### 8. Non-Standard Navigation Element

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt`
**Lines:** 85-89

Uses custom text back button (`<`) instead of standard Material icon.

**Recommendation:** Use `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack`.

---

### 9. Error Messages Stay Visible Indefinitely

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/screens/SettingsScreen.kt`
**Line:** 325

Errors display with no dismiss mechanism.

**Recommendation:** Add close button or auto-hide after timeout.

---

### 10. Missing Content Descriptions for Accessibility

**File:** `app/src/main/kotlin/io/hammerhead/karoocriticalpower/views/PowerBarsView.kt`

Glance components lack `contentDescription` parameters. Screen readers won't understand bar meanings.

**Recommendation:** Add semantic labels to all interactive/informational elements.

---

## Future Improvement or Idea

Feature suggestions and architectural enhancements for future development.

### 1. Validate Sample Rate at Runtime

Currently assumes 1Hz. Could detect actual sample rate from stream and adjust buffer size dynamically. This would make the extension more robust across different power meter configurations.

---

### 2. Add Offline-First Architecture

Currently fetches PRs at ride start if configured. Could implement:
- Background sync when on WiFi
- Last sync timestamp visible to user
- Manual "Refresh PRs" button in settings

---

### 3. Add Export/Share Functionality

After a ride with new PRs, allow users to:
- Share their achievements to social media
- Export power curve data
- Send results to intervals.icu automatically

---

### 4. Support Multiple Athletes/Profiles

Some users might share a Karoo or want to compare against training partners. Could add:
- Profile switching in settings
- "Compare to..." feature with friend's PRs

---

### 5. Add Sound/Vibration Alerts for New PRs

When a new PR is achieved mid-ride:
- Play celebration sound
- Vibrate the device
- Flash the screen briefly

Would require careful implementation to avoid distraction during riding.

---

### 6. Historical Power Curve Visualization

Add a screen showing:
- Power curve graph over time
- PR progression charts
- Trends (improving/declining)

---

### 7. Add Widget for Karoo Home Screen

Quick-glance widget showing:
- Today's best efforts
- Distance from PRs
- Training suggestions

---

### 8. Implement Proper Typography/Design System

Create consistent:
- Text size scale (16sp, 18sp, 20sp, etc.)
- Spacing scale (4dp, 8dp, 12dp, 16dp, etc.)
- Color palette with semantic names

Would improve maintainability and visual consistency.

---

### 9. Add Unit Tests for PowerBuffer

Critical calculation logic has no tests. Should add:
- Edge case tests (empty buffer, single sample)
- Thread safety tests
- Precision tests for averages

---

### 10. Consider StateFlow for Thread-Safe Settings

Replace direct `currentSettings` variable access with `StateFlow<CriticalPowerSettings>` to ensure thread-safe reads across all consumers.

---

### 11. Add Integration with More Platforms

Beyond intervals.icu, could support:
- TrainingPeaks
- Strava
- Golden Cheetah
- Today's Plan

---

### 12. Localization/Internationalization

Currently English-only. Could add:
- German, French, Spanish, Italian translations
- Locale-aware number formatting
- RTL support for applicable languages

---

*End of QA Review*
