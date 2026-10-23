# Prague Transit Arrivals: Live Departure Board Widget

## Problem Statement

When using public transport in Prague, commuters walking toward a tram junction, bus stop, or metro entrance need to know immediately when their next vehicle is departing, which specific platform/pole (*označník*) it leaves from, and whether it is delayed. Having to unlock the phone, launch a full transit application, search for the stop, and find the right direction adds unnecessary friction when walking or rushing to catch a connection.

## Solution

A home screen Android widget built with Jetpack Glance that acts as an automatic, live departure board. The widget dynamically detects the user's nearest stop, displays the specific platform poles as selectable tabs (pre-selecting the closest pole), and lists upcoming departures with relative countdown minutes and real-time delay badges. Users can refresh on demand, cycle between nearby stops, and switch platforms with a single tap directly on their home screen.

## User Stories

1. As a commuter walking toward a tram stop, I want the widget to automatically detect my closest stop, so that I don't have to search for where I am.
2. As a commuter standing at a specific tram pole, I want the widget to display departures for the pole closest to me by default, so that I immediately see vehicles in my direction.
3. As a commuter at a transit hub with multiple platforms (e.g. A, B, C), I want to see platform letter tabs along the top, so that I can quickly switch to opposing directions or alternative platforms.
4. As a passenger at a suburban stop without platform letters, I want platform tabs to display the primary direction/headsign (e.g. "→ Centrum"), so that I know which pole goes where.
5. As a rider, I want to see the transport mode icon (tram, metro, bus) and line number for each departure, so that I can identify my vehicle at a glance.
6. As a rider, I want to see the destination headsign for each departure, so that I confirm the vehicle is traveling the correct route.
7. As a rushing commuter, I want departure times displayed as relative countdown minutes (e.g. `<1 min`, `3 min`), so that I know whether I need to run without doing mental math.
8. As a commuter, I want to see real-time delay indicators (e.g. `+2 min` in amber) when vehicles are delayed, so that I know if a connection is running late.
9. As a commuter viewing trips departing more than 30 minutes away, I want the time displayed as clock time (e.g. `16:45`), so that it reads naturally like a timetable.
10. As a pedestrian walking toward a stop, I want to see my walking distance in meters (e.g. `15m`), so that I know how close I am.
11. As a user standing in an area with multiple transit stops within 500m, I want previous/next arrows (`◀` / `▶`) on the widget header, so that I can cycle through other nearby stops.
12. As a commuter wanting the absolute latest transit updates, I want a dedicated refresh button (`↻`) on the widget, so that I can trigger an instant location and departure refresh.
13. As a traveler heading down into deep underground metro vestibules where GPS is lost, I want the widget to use my last known GPS fix from the past 30 minutes, so that I can still refresh metro departures underground.
14. As a user with spotty mobile data or rate limits, I want the widget to keep showing the last retrieved departures with a timestamped warning banner (*"Cached 15:42 • Tap to retry"*), so that my screen doesn't turn blank during temporary network outages.
15. As a user who places different widget sizes on my home screen, I want a responsive layout that displays 3 departures on a compact 2x2 widget and 5–6 departures on larger widgets, so that the layout fits my screen cleanly.
16. As a user needing navigation directions, I want tapping the stop name to open the stop coordinates in my default maps app, so that I can walk directly to it.
17. As a new user placing the widget for the first time, I want to be prompted to enter my Golemio API key via a clean settings screen, so that I can configure data access without confusion.
18. As a user whose closest stop is 150m away, I want the widget to automatically expand its search radius up to 500m, so that I don't see a false "No stops found" message.
19. As a bilingual user, I want all widget interface labels in English or Czech depending on my system language, while keeping Prague stop names in original Czech.

## Implementation Decisions

- **Presentation Layer**: Built using Jetpack Glance (`androidx.glance:glance-appwidget`) targeting Min SDK 26 (Android 8.0) and Target SDK 35. The layout matches `design_idea.png` with a dark card background, a unified header row containing stop title, platform selector pills, walking distance, refresh icon, and stop cycling controls (`◀` / `▶`), followed by vertically stacked departure rows.
- **Client-Side REST Architecture**: The application connects directly to Prague's Golemio Open Data API (`https://api.golemio.cz/v2/departureboards`) using an API token stored securely on-device. No intermediary backend proxy is used.
- **Stop and Platform Normalization**: The data layer queries Golemio departure boards using GPS coordinates and an adaptive search radius (100m, expanding up to 500m). Results are grouped by transit Stop, partitioned into individual Platforms (poles), and ordered by proximity to the user.
- **Location Strategy & Stale Fix Decay**: Integrates Google Play Services `FusedLocationProviderClient`. High-accuracy fixes are fetched on manual tap refresh and periodic background WorkManager runs. If a fresh GPS fix fails (such as underground in metro stations), the engine accepts cached coordinates up to 30 minutes old before reporting a location error.
- **Cache and Offline Resilience**: In the event of network timeouts, HTTP 429 rate limits, or connectivity loss, previously fetched departures are retained in state and displayed with an amber cache warning banner and retry button.
- **Companion Settings Activity**: A lightweight Android Activity serves as the configuration portal for entering and validating the Golemio API key, selecting preference defaults (max search distance, refresh intervals), and launching from unconfigured widget instances.

## Testing Decisions

- **Good Test Definition**: Tests verify strictly observable external behavior (given inputs -> expected outputs) without inspecting private state or verifying internal helper methods.
- **Modules Tested**:
  - `ArrivalsEngine`: Tests the primary state reducer using mocked coordinates and Golemio API JSON responses. Verifies nearest-stop resolution, platform tab generation and default selection, relative countdown formatting, delay chip display, stop pagination, underground stale location acceptance, and network failure caching.
  - `GolemioApiClient`: Contract tests verifying serialization and deserialization against Prague Golemio departure board JSON fixtures.
- **Prior Art**: Modern Android Kotlin architecture with JUnit 5 / Kotest, MockWebServer for HTTP contracts, and FakeLocationManager for coordinate feeds.

## Out of Scope

- In-widget map rendering or turn-by-turn pedestrian navigation (delegated to external map applications).
- Multi-leg journey planning or route finding.
- Ticket purchasing and PID Lítačka account synchronization.
- Push notifications for transit disruptions or approaching vehicles.
- Custom proxy server or backend deployment.

## Further Notes

- Canonical domain terminology is maintained in `GLOSSARY.md`.
- Architectural trade-offs are documented in `docs/adr/0001-direct-golemio-client.md`, `docs/adr/0002-jetpack-glance-ui.md`, and `docs/adr/0003-last-known-location-fallback.md`.
- Reference stop database structure is available in `data/stops.json`.
