# Search/Display Headroom Results: 2026-09-05

Status: **PASS**, all 50 comparison checks passed; no failed checks.
Both baseline and candidate SQLite `quick_check` results are `ok`.
No release or activation approval is implied by these measurements.

## Identity And Scope

- Run: `search-display-headroom-20260905-01`.
- Baseline: completed `route-axis-headroom-20260905-01`, commit `546699c`.
- Both runs: Java 21, FULL mode, `-Xmx3g`, preprocessor `0.9.9-SNAPSHOT`,
  SQLite schema `0.1`, Contract `0.9`.
- Candidate is the clean-build artifact of the search/display follow-up.
  The commit containing this report records the corresponding source changes.
- Input is the same isolated fusion of unchanged raw DE/CH GTFS cache copies
  used by the preceding audit, not a live runtime database or a new feed revision.
- No production service, timer, active pointer, app, core or routing code was
  changed. No artifact was deleted. Review remains a draft, not a release.

| Artifact | SHA-256 |
| --- | --- |
| Baseline JAR | `464059af1e6f45f4a386270ec63b7825f62a6fd9ca97ad5f16ca930be9feffb7` |
| Candidate JAR | `62e8ebd366cd2a601014995547dd1e664c70d5cf6820ce9136fa75524f5f8337` |
| Fused GTFS input | `6d407008410d72e1f98d928bd41dfdf1e8bbc3fc18426e05843e75e60e5a3137` |
| Municipality data | `a9a9747d1b6ecdf3dbe2079363899858eb9ec14e9c84068209e95b3050e47d18` |

Local `mvn clean package` passed 24 JUnit tests, including the existing
self-test suite. All 16 Python audit tests passed. The local and uploaded JAR
hashes match. No dependencies, contract version or output policy changed.

## Data Equivalence

All values in all 30 data tables are unchanged. Comparison includes token
multisets with duplicate multiplicity, every public display name, every
RouteAxis sequence and every transfer/footpath field. The only permitted
metadata differences are generation time, artifact hash and derived build
identity. Schema indexes, row counts and report diagnostics are unchanged.

Selected identical counts: 797,232 stops; 3,988,671 trips; 72,120,163 stop
times; 3,948,154 search tokens; 284,345 public area names; 4,441,924 RouteAxis
stop rows; 697,356 footpaths; 291 display-quality findings.

Service-day data is identical: 87,560 services, zero unresolved trip services,
2,449,746 overflow stop times and maximum 248,100 seconds (`68:55:00`).
The fixture still contains no `pathways.txt`; unchanged geometry-estimated
walks do not establish real station-path or accessibility coverage.

## Memory And Timing

These are logged Java-heap samples, including pre-GC values, not exact peak
live sets. The performance-report snapshots do not exceed the logged maximum.

| Measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Search-token phase, MiB | 2754 | 2093 |
| Display-quality phase, MiB | 2690 | 2376 |
| Contract-validation phase, MiB | 2701 | 2163 |
| RouteAxis phase, MiB | 2094 | 2088 |
| Overall sampled heap, MiB | 2754 | 2745 |
| Peak process RSS, KiB | 3449232 | 3435748 |
| Full build elapsed, seconds | 1206.231 | 1192.503 |
| Search build + write, milliseconds | 7745 | 10815 |
| Display-quality build, milliseconds | 3037 | 2809 |
| Contract validation, milliseconds | 16598 | 14204 |

Search streaming trades some runtime for lower retained memory; it used ten
heap-guard collections. Total runtime and RSS are essentially comparable in
this one shared-server run. No repeatability or performance guarantee follows.

The global **below-2300-MiB target is not met**. Remaining measured peaks:
2745 MiB in `stop_area_display_names`, 2672 MiB after app-ready validation,
2632 MiB in `stop_times`. The display-quality guard also overshoots its
2050-MiB threshold between 100,000-row checks. The 90-percent-of-3g indicator
must not be mistaken for the stricter target or a 2560-MiB operational approval.

## Next Gates

1. Stream public display-name construction instead of retaining the full
   `displayNamesFrom` list before batching; inspect later readiness allocations.
2. Review guard cadence and the unchanged 2500-MiB streaming defaults, with
   allocation evidence and unchanged-data regression checks.
3. Plan retention explicitly before another full run: approximately 30 GiB
   remain free, while the runner requires 40 GiB. Do not silently delete audits.
4. Establish repeatability only after the remaining global peaks are reduced.
   Keep `3g` as the operational default and consumer acceptance separate.

Compact evidence is retained under `build/search-display-headroom-20260905-01/`;
large databases remain in the isolated server tool directory. No cleanup,
soak schedule, production activation or consumer deployment was performed.
See [implementation](SEARCH_DISPLAY_HEADROOM.md) and the independent
[Contract 0.9 consumer gate](CONSUMER_CONTRACT_09_GATE.md).
