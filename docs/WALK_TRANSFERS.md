# Walk Transfer Preparation

Development version: `0.9.9-SNAPSHOT`; SQLite contract: `0.9`; walk model: `1`.

## Time Model

For a prepared transfer between concrete boarding stops:

```text
required_seconds = max(120, walk_seconds + 60, applicable_gtfs_minimum)
```

Walking and transfer reserve are separate values. A chain with 100 seconds
of stairs and 80 seconds through a hall has 180 walking seconds and a minimum
of 240 seconds. If GTFS specifies 300 seconds, the result is 300, not 360 or
480. The floor, buffer and estimated speeds are explicit IXIT defaults, not
measured properties or universal guarantees.

Identical coordinates of different platforms still produce a 120 second
minimum. There is no synthetic zero-time change based solely on station
membership. `stop_footpaths` contains distinct stop pairs; an explicit
same-stop transfer in `transfer_edges` retains a nonzero change allowance.
Staying aboard a vehicle must be resolved using the original in-seat rule.

## Station Pathways

When `pathways.txt` exists, the preprocessor builds a directed graph for
each represented station and computes a fastest timed chain for each
boarding-stop pair. Bidirectional edges may be used both ways. Intermediate
halls, entrances and boarding areas are retained as graph nodes. Platforms
with child boarding areas use those areas as start/end alternatives, without
adding zero-time edges between them.

Each used pathway prefers its supplied positive `traversal_time`. Otherwise:

| Pathway | Estimate when timing is missing |
| --- | --- |
| Walkway | supplied length / 1.2 m/s, rounded up |
| Stairs | absolute stair count / 0.75 steps/s, plus supplied horizontal length / 1.2 m/s |
| Fare/exit gate | supplied length / 1.2 m/s plus 10 seconds |
| Moving walkway, escalator, elevator | unknown; supplied traversal time required |

Each edge has a positive duration. Only one transfer buffer is applied to
the complete walk. Mechanical waiting times are not guessed from distance.
Exit gates cannot be bidirectional. Unknown endpoints, cross-station edges,
invalid modes, directions, lengths or durations are unusable and reported.

Any supplied pathway touching a station suppresses geometry shortcuts there,
including incomplete or unusable data. A missing reverse connection stays
unavailable. Repeated source searches retain only one shortest-path tree;
the generated stop pairs continue to stream into SQLite.

The ordered `pathway_ids` JSON array permits replay. `pathway_modes` records
all used modes as bits 0 through 6. The distance is the sum of supplied
lengths; if any segment lacks a length, distance is NULL. Supplied traversal
times are `FEED_PROVIDED`; chains containing estimated times are `ESTIMATED`.
Neither label asserts a surveyed or currently unobstructed path.

## Geometry And Transfer Rules

Stations without pathways retain the existing estimate:
`ceil(straight_line_meters * 1.35 / 1.2)` walking seconds, followed by the
time model above. The stored distance remains a straight-line lower bound.
Only pairs up to 400 m with valid coordinates are traversable candidates.
Proximity between different StopAreas remains a non-traversable candidate;
this change does not invent street-network walking routes.

Unscoped stop/station GTFS minima are lower bounds. Parent-station rules
apply to child stops; stop IDs remain unchanged. If overlapping generic
rules conflict, preparation conservatively keeps the largest minimum and
any prohibition. Missing mandatory or negative transfer minima block the
generic candidate. This conservative conflict handling may reject a
connection and is not a substitute for trip-specific specificity resolution.

Scoped rules and in-seat transfers are preserved separately. They never
become generic pedestrian permission. A router must evaluate the original
rules for the actual arriving and departing trips, including route, service,
date and transfer specificity, before using a walk candidate. A timed-transfer
rule does not turn a scheduled departure into a guaranteed real-time hold.

## Validation And Boundaries

`pathways` and the additional `stop_footpaths` columns are contract-required
in every mode. CORE_ONLY imports pathway rows but skips derived walks.
Text/JSON reports separate pathway walks from geometry estimates and count
invalid walk components and violations of generic transfer rules.

The audit checks time components, minimum times, prohibited pairs and the
geometry threshold. It replays mapped pathway chains against their source
rows, checking direction, continuity, final stop, duration, length and modes.
Invalid output fails contract validation.

The generic transfer-constraint audit pins the contract-required stop-pair
index. A full DE/CH feed otherwise caused SQLite to scan its large NULL
trip-scope bucket once per footpath; the exact query plan is regression-tested.

Original stop, area, trip, service and service-day time identities remain
unchanged. Times such as `24:30:00` and `25:10:00` remain 88200 and 90600
seconds respectively. A future consumer compares arrival plus required
transfer seconds with departure on the resolved service-day timeline.

Accessibility, stairs suitability, ticket entitlement at gates, closures,
crowding and live elevator availability are not evaluated. Optional
`max_slope`, `min_width`, signage and level annotations are not yet imported;
`pathway_modes` provides mode evidence, not an accessibility guarantee.
The current estimates describe a typical pedestrian. An accessible-walk
profile needs its own validated graph and policy.

Contract 0.9 requires explicit downstream acceptance and an isolated real-feed
audit before activation. Existing application or server consumers are not
activated by building this development version.

The reproducible comparison procedure is in
[WALK_REAL_FEED_AUDIT.md](WALK_REAL_FEED_AUDIT.md).

Reference: [GTFS Schedule, transfers and pathways](https://gtfs.org/documentation/schedule/reference/).

## Tests

`WalkTransferTest` covers directed chains, alternate paths, one-time buffers,
GTFS minima, parent prohibitions, scoped/in-seat separation, missing
coordinates, coincident platforms, boarding-area endpoints, unknown lift
times, CSV quoting, and ZIP-to-SQLite output including times beyond midnight.
Tampered durations and reversed pathway provenance must fail the audit.

```bash
./mvnw -Dtest=WalkTransferTest test
./mvnw package
```
