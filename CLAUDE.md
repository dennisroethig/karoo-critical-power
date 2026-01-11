# Claude Instructions for Karoo Critical Power

## Build & Deploy

### Environment Setup (required for Claude terminal)
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
export ADB=~/Library/Android/sdk/platform-tools/adb
```

### Build the APK
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17" && ./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Deploy to Karoo (via ADB)
```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build + Deploy in one command
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17" && ./gradlew assembleDebug && ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

- `app/src/main/kotlin/io/hammerhead/karoocriticalpower/`
  - `extension/` - Main extension service
  - `datatypes/` - Data type implementations (11 individual + 1 overview)
  - `data/` - Settings, repository, API client
  - `views/` - Glance composable views
  - `screens/` - Jetpack Compose settings screens
  - `PowerBuffer.kt` - Rolling average calculation
  - `PowerBufferManager.kt` - Centralized buffer management with persistence

## Key Architecture Points

1. **Single source of truth for power data**: `PowerBufferManager` holds all 11 buffers
2. **Extension adds samples**: Only `KarooCriticalPowerExtension.startPowerStream()` calls `bufferManager.addSample()`
3. **Data types read-only**: Data types poll the buffer manager, never add samples
4. **Persistence**: Best averages are saved to DataStore and restored on service restart
5. **Diagnostic logging**: Events logged to `power_buffer_diagnostics.log` in app files directory

## Testing on Device

After deploying:
1. Open Critical Power app to configure (optional - works without intervals.icu)
2. Start a ride
3. Add Critical Power data fields to your ride screen
4. Check logcat: `adb logcat -s KarooCriticalPower PowerBufferManager`

## Reading Diagnostic Logs

```bash
adb shell cat /data/data/io.hammerhead.karoocriticalpower/files/power_buffer_diagnostics.log
```

## Common Issues

- **Java not found**: Ensure JDK 17+ is installed and JAVA_HOME is set
- **GitHub packages auth**: Need `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties`
- **ADB not found**: Ensure Android SDK platform-tools is in PATH
