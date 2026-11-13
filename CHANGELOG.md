# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Automated production release workflow triggered on SemVer tag pushes and manual workflow dispatch (#49).
- Displacement-aware query execution and background location optimizations (#48).
- Screen wakeup and device unlock broadcast trigger pipeline for real-time widget updates (#47).
- Out-of-range empty state and stale cache eviction handling (#46).
- Distance formatting for stations displaying distances >= 1,000m as kilometers (#45).
- Dynamic Gradle versioning seam via `-PappVersionName` and `-PappVersionCode` (#41).
- Nightly continuous delivery workflow publishing pre-release artifacts (#43).
- Pull request and push validation continuous integration workflow (#42).
- Multi-tier location resolution pipeline with fused fallback (#35).
- 15-minute stale data cutoff and active-stop name live query fallback (#37).
- Immediate in-process refresh and expedited WorkManager background execution (#38).
- Retained cache warnings upon widget rehydration and handling of elapsed departures (#39).
- Automatic snapping to active stop when displacement exceeds 50 meters (#36).
- Background location permission declaration and settings educational prompt (#34).

### Changed
- Refactored CD workflow to skip nightly builds when no changes are detected.
- Standardized release keystore signing configuration with graceful debug fallback.

### Fixed
- Fixed distance unit formatting and stale departure board cleanup.
- Addressed code review findings across widget refresh and location pipelines.

## [0.1.0] - 2026-11-01

### Added
- Prague transit arrivals live departure board widget for Android Glance home screen (#1).
- Golemio departure boards REST API integration with real-time updates and delay calculation.
- Offline SQLite stop registry with fast geospatial nearest-stop discovery.
- Transport mode letter indicators (tram, bus, train, metro) with dedicated line badges (#22).
- Responsive scrollable stop picker and platform picker with pinned headers (#29, #30).
- Scrollable departures list widget with configurable viewport limits (#31).
- Walking distance estimation from device position to selected platform (#1).
- Automatic in-place countdown minute ticking without unnecessary network fetches.
- Friend onboarding instructions and initial beta release configuration.

### Changed
- Standardized header action button dimensions and touch targets (#23).
- Platform picker ordering prioritizing platform code and proximity (#24).
- Consolidated departure intake and countdown ticking into deep `ArrivalsEngine` domain module (#25).
- Consolidated caching and location fallback into deep `DeparturesRepository` module (#26).
- Placed `TransitDataSource` port at the network seam for modular transit backends (#27).

### Fixed
- Resolved UTF-8 mojibake in Prague stop catalog (`stops_compact.json`).
- Handled non-integer minutes (e.g. `<1`) returned by Golemio timestamp DTOs.
- Fixed reactive Glance platform switching and foreground/background GPS persistence.

[Unreleased]: https://github.com/RiegelZdenek/arrivals/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/RiegelZdenek/arrivals/releases/tag/v0.1.0
