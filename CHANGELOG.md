# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-11-13

### Added
- **release**: companion create-release skill and baseline changelog (#50)
- **release**: automated release pipeline workflow and ADR (#49)
- **worker**: displacement-aware query execution (#48)
- **widget**: screen wakeup and device unlock broadcast trigger pipeline (#47)
- **domain**: out-of-range empty state and stale cache eviction (#46)
- **widget**: format distances >= 1,000m as kilometers (#45)
- **build**: dynamic Gradle versioning seam and ADR-0004 (#41)
- **resilience**: 15-minute stale cutoff and active-stop name live query fallback (#37)
- **widget**: immediate in-process refresh and expedited WorkManager execution (#38)
- **location**: multi-tier location resolution pipeline (#35)
- **widget**: retain cache warning on rehydration and handle elapsed departures (#39)
- **domain**: auto-snap active stop on >50m displacement (#36)
- declare background location permissions and add settings educational prompt (#34)
- **widget**: scrollable departures list with retained viewport limits (#31)
- **widget**: scrollable platform picker with pinned header and stable item IDs (#30)
- **widget**: scrollable stop picker with pinned header and stable item IDs (#29)

### Changed
- **cd**: extract env var and eliminate duplicated output branches
- **cd**: skip nightly build when no changes are detected
- ignore .worktrees directory
- add ADR-0005 and update GLOSSARY for issue #44
- remove machine-specific org.gradle.java.home from gradle.properties
- ensure executable permissions on gradlew
- add Nightly Continuous Delivery workflow with GitHub Releases (#43)
- **build**: verify version name and code are populated in package info
- add Continuous Integration workflow for PRs and pushes (#42)
- update GLOSSARY and ADR 0003 for issue #33
- add GNU General Public License v3.0 (GPL-3.0)
- **readme**: remove stale design preview image
- add project README
- **release**: prepare 0.1.0-beta release configuration and friend onboarding
- **widget**: address code review findings on stop naming, dimension reuse, and item ID tests

### Fixed
- **review**: address standards review findings for release automation (#49, #50)
- **review**: address standards and spec review findings for issue #44
- **review**: address standards and spec review findings for issue 40
- **review**: address standards and spec review findings

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

[Unreleased]: https://github.com/RiegelZdenek/arrivals/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/RiegelZdenek/arrivals/compare/beta...v0.2.0
[0.1.0]: https://github.com/RiegelZdenek/arrivals/releases/tag/v0.1.0
