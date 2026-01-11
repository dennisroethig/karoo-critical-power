# Changelog

All notable changes to this project will be documented in this file.

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
