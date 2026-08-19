# Changelog

All notable changes to this project will be documented in this file.

## [1.4.0] - 2026-08-19

### Fixed

- **Warmup/previous-ride power leaking into a new ride** - Samples were fed to the buffers whenever the power meter was connected, even before pressing Start, and the time-based reset guard blocked the reset at ride start whenever the sensor had been streaming recently. Sampling is now gated on the ride actually recording, and buffers reset exactly on the Idle → Recording transition.
- **Long stops wiping ride bests** - Resuming after a pause longer than 5 minutes (café stop, mechanical) re-triggered the "new ride" reset and destroyed all best averages from earlier in the ride. Resuming from pause no longer resets anything.
- **Sensor dropouts inflating best averages** - Rolling windows were sample-count based, so a gap in the power stream stitched two separate efforts together (e.g. a "20-minute best" spanning a dropout). Gaps are now zero-filled so windows always cover contiguous time; sub-second duplicate samples are dropped.
- **One stream error permanently freezing all fields** - An error in the power stream silently ended sample collection for the rest of the ride. The stream now restarts automatically after 5 seconds.
- **Wrong stream/view cancelled when a field is on multiple pages** - Each data field start now cancels exactly its own polling loop instead of whichever started last, fixing frozen fields and leaked render loops.
- **Restored bests could be clobbered on service restart** - Persisted-state loading now runs under the buffer lock and resets wait for it, closing two races from the v1.2.1 era.
- **Final state persisting was fire-and-forget** - Buffer state is now flushed synchronously (with a timeout) on service shutdown, and also persisted on every pause.

### Changed

- **Battery: views only re-render on change** - All 12 data fields previously re-composed their Glance views every second regardless. They now skip rendering when the displayed values haven't changed.
- **Fewer network calls** - The intervals.icu power curve is only refetched when credentials or the PR timeframe change (or at ride start), not on every settings write.
- **Consolidated the 11 duration data types into one class** - A single duration registry now drives the data types, buffers, and API client (the duration list previously existed in four places). Data type IDs are unchanged, so existing ride profiles are unaffected.

### Added

- **Unit tests for the core rolling-average math** (`PowerBuffer`), including window wraparound, gap zero-fill, and restore semantics.

### Removed

- Dead code: unused status/aggregate APIs, the empty main screen, stale `QA_REVIEW.md`, unused parameters and imports.

## [1.3.1] - 2026-05-18

### Fixed

- **Unreadable text in dark mode** - Duration labels on the left of the overview bars (5s, 15s, 1m, …) and the main power value on individual critical-power data fields were hardcoded to black, rendering invisibly on the Karoo's dark background in dark mode. Text colors now use day/night-aware color resources so they switch to white automatically. Bar fill colors and the values inside the colored bars are unchanged.

## [1.3.0] - 2026-03-28

### Fixed

- **Installation failing on Karoo** - Release APK was unsigned, causing install to fail when sideloading via the Companion App. Release builds are now properly signed.

### Changed

- **Updated Karoo SDK** - Bumped karoo-ext from 1.1.3 to 1.1.9, bringing compatibility with the latest Karoo firmware

## [1.2.2] - 2025-01-24

### Added

- **Version display in settings** - App version now shown at the bottom of the settings screen

## [1.2.1] - 2025-01-24

### Fixed

- **Power values disappearing on long rides** - Two race conditions on service restart:
  1. `sampleCount` was not persisted, causing reset guard to fail
  2. Reset could trigger before persisted state finished loading

  Now `sampleCount` is persisted, and resets are blocked until state loading completes.

## [1.2.0] - 2025-01-11

### Fixed

- **Double-sampling bug** - Values were appearing at half the expected time (e.g., 30m power showing after 15 minutes). Root cause: both stream and view were adding samples to the buffer, counting each sample twice.
- **Values disappearing mid-ride** - On long rides (60-70+ min), power values would reset to "--". Fixed with persistence and smart reset guards.

### Changed

- **Centralized buffer management** - New `PowerBufferManager` provides single source of truth for all power data
- **Reduced memory usage** - Shared buffers between individual data types and overview (from 22 buffers down to 11)
- **Thread-safe access** - Mutex protection for buffer operations
- **State persistence** - Best average values are now saved to DataStore and restored on service restart
- **Smart reset guards** - Prevents accidental mid-ride buffer resets from spurious ride state events

### Added

- **Diagnostic logging** - Events logged to file for post-ride analysis (`power_buffer_diagnostics.log`)
- **CLAUDE.md** - Build and deploy instructions for development

## [1.1.0] - 2025-01-10

### Added

- **Per Ride Mode** - New comparison mode that works without intervals.icu configuration
  - Compare your current effort against your best this ride
  - Bars update dynamically as you set new bests
  - Automatically used as fallback when intervals.icu is not configured

### Changed

- Extension now works out of the box without any configuration required

## [1.0.0] - 2025-01-10

### Features

- **11 Critical Power Data Fields** - Track best average power for 5s, 15s, 30s, 1m, 3m, 5m, 20m, 30m, 45m, 1h, and 1h30m durations
- **Critical Power Overview** - Visual data field showing all durations as stacked horizontal bars comparing current ride to PRs
- **intervals.icu Integration** - Fetch your power curve PRs for comparison during rides
- **PR Comparison Display** - Shows current power with PR reference (e.g., "285W / 310W")
- **Visual PR Indicators** - Green highlighting when matching or exceeding a PR
- **Configurable PR Timeframe** - Compare against all-time, 90-day, or 42-day PRs
- **Offline Support** - Cached PR data available when riding without connectivity
- **Settings UI** - Easy configuration of intervals.icu API credentials
