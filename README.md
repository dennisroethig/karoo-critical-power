# Karoo Critical Power

A Karoo 3 extension that displays "Critical Power" data fields for specific durations during a ride, with optional comparison to all-time or recent PRs fetched from intervals.icu.

## Features

- **11 Data Fields** - One for each duration: 5s, 15s, 30s, 1m, 3m, 5m, 20m, 30m, 45m, 1h, 1h30m
- **Current Ride Best** - Rolling calculation of best average power for each duration
- **intervals.icu PRs** - Fetch power curve bests at ride start for comparison
- **Configurable** - API key, athlete ID, PR timeframe (all-time vs 90 days)
- **Display Format** - Shows "285W (PR: 310W)" or just "285W" if no PR data

## Requirements

- Karoo 3 device
- Power meter
- (Optional) intervals.icu account for PR comparison

## Building

### Prerequisites

- JDK 17+
- Android SDK
- GitHub token with `read:packages` scope for karoo-ext dependency

### Setup

1. Create `~/.gradle/gradle.properties` with your GitHub credentials:
   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=YOUR_GITHUB_TOKEN
   ```

2. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install on Karoo:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Configuration

1. Open the Critical Power app on your Karoo
2. Enter your intervals.icu API key and athlete ID
3. Select PR timeframe preference (all-time, 90 days, or 42 days)
4. Add Critical Power data fields to your ride screens

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

MIT - see [LICENSE](LICENSE) for details.
