# Prague Transit Arrivals

A lightweight Android widget displaying real-time public transit departure boards for nearby stops in Prague.

## Language

**Stop**:
A named public transport hub or station comprising one or more physical Platforms (e.g., *I. P. Pavlova*, *Anděl*).
_Avoid_: Stop Group, Station, Stop Area

**Platform**:
A specific physical boarding pole, track, or quay within a Stop, designated by a code or letter (e.g., Platform A, Platform B).
_Avoid_: Pole, Označník, Post, Bay

**Departure**:
A scheduled or real-time transit vehicle leaving a Platform toward a given Headsign.
_Avoid_: Arrival, Trip

**Delay**:
The difference in minutes between the scheduled departure time and the predicted real-time departure time.
_Avoid_: Tardiness, Latency

**Line**:
The public transit route identifier (e.g., Tram 22, Metro A, Bus 119).
_Avoid_: Route, Service

**Headsign**:
The final destination text displayed on the vehicle indicating its travel direction.
_Avoid_: Destination, Direction, Terminus

**Transport Mode**:
The category of transit vehicle serving a platform, primarily Metro, Tram, Bus, and Trolleybus.
_Avoid_: Vehicle Type, Category
