# Karoo Critical Power

A Karoo 3 extension that displays "Critical Power" data fields for specific durations during a ride, with optional comparison to your PRs from intervals.icu.

## Features

- **11 Data Fields** - Track your best average power for: 5s, 15s, 30s, 1m, 3m, 5m, 20m, 30m, 45m, 1h, 1h30m
- **Critical Power Overview** - Visual field showing all durations as horizontal bars comparing current vs PR
- **Real-time Tracking** - See your best power for each duration update live during your ride
- **PR Comparison** - Optional intervals.icu integration shows your PRs for reference (e.g., "285W / 310W")
- **PR Alerts** - Green highlighting when you're matching or beating a PR
- **Offline Support** - PR data is cached, so it works even without connectivity

## Screenshots

| Overview Field | Single Data Field |
|:-:|:-:|
| ![Overview](screenshots/all-times-active.png) | ![Single Field](screenshots/single-times-active.png) |

## Requirements

- Karoo 3 device
- Power meter
- (Optional) [intervals.icu](https://intervals.icu) account for PR comparison

## Installation

### Easy Install (Recommended)

1. **Download the APK** from the [latest release](https://github.com/dennisroethig/karoo-critical-power/releases/latest)

2. **Install using one of these methods:**

   **Option A: Using Hammerhead's Web Installer**
   - Visit [sdk.hammerhead.io/install-app](https://sdk.hammerhead.io/install-app) on your computer
   - Connect your Karoo via USB and follow the on-screen instructions
   - Select the downloaded APK file

   **Option B: Using a File Manager on Karoo**
   - Connect your Karoo to your computer via USB
   - Copy the APK file to your Karoo's `Downloads` folder
   - On your Karoo, open a file manager app (you may need to install one first)
   - Navigate to Downloads and tap the APK to install

   **Option C: Using ADB (for technical users)**
   ```bash
   adb install app-debug.apk
   ```

3. **Add data fields to your ride profile:**
   - On your Karoo, go to **Profiles** > select your profile > **Data Pages**
   - Edit a page and tap a field to change it
   - Look for "Critical Power" fields under the extension category
   - Choose individual durations (e.g., "5s Power", "20m Power") or "Critical Power" for the overview

## Configuration (Optional)

To see your PRs from intervals.icu during rides:

1. **Get your intervals.icu API key:**
   - Log in to [intervals.icu](https://intervals.icu)
   - Go to **Settings** (gear icon) > **Developer Settings**
   - Copy your **API Key**
   - Note your **Athlete ID** (shown in the URL when viewing your calendar, e.g., `i12345`)

2. **Configure the extension:**
   - On your Karoo, find and open the **Critical Power** app
   - Enter your API Key and Athlete ID
   - Choose your PR timeframe:
     - **All-time** - Compare against your best ever
     - **90 days** - Compare against recent PRs
     - **42 days** - Compare against very recent PRs
   - Tap "Test Connection" to verify it works

## Building from Source

<details>
<summary>For developers who want to build the APK themselves</summary>

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

3. The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

</details>

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

MIT - see [LICENSE](LICENSE) for details.
