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
export JAVA_HOME="/opt/homebrew/opt/openjdk@17" && ./gradlew clean assembleDebug && ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**IMPORTANT: Always use `clean` before building to ensure a fresh APK with the current version.**

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

## Release Process (MANDATORY)

**IMPORTANT: When making changes that warrant a version bump, ALL of these steps MUST be completed:**

### 1. Update Version
Edit `app/build.gradle.kts`:
- Increment `versionCode` (integer, always +1)
- Update `versionName` (semantic versioning: major.minor.patch)

### 2. Update Changelog
Edit `CHANGELOG.md`:
- Add new version section at the top with date: `## [X.Y.Z] - YYYY-MM-DD`
- Document all changes under appropriate headers (Added, Changed, Fixed, Removed)

### 3. Commit Changes
```bash
git add -A && git commit -m "Release vX.Y.Z - Brief description"
git push
```

### 4. Build Release APK
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17" && ./gradlew clean assembleRelease assembleDebug
```

**Note: Builds both release (for GitHub) and debug (for ADB deploy) from clean state.**

### 5. Create GitHub Release
```bash
gh release create vX.Y.Z \
  --title "vX.Y.Z - Brief Title" \
  --notes "$(cat <<'EOF'
## Changes

- List key changes here (copy from CHANGELOG.md)

See [CHANGELOG.md](CHANGELOG.md) for full details.
EOF
)" \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

### 6. Deploy to Karoo (if connected)
```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**NEVER skip creating the GitHub release after bumping the version!**
