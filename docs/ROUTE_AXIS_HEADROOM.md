# RouteAxis Headroom Hardening

Development version: `0.9.9-SNAPSHOT`, Contract `0.9`. This is an allocation
and output-pipeline change, not a timetable, Walk-policy or schema change.

## Implementation

- A build-local dictionary shares equal area/route identifier strings across
  retained sequence groups. No global `String.intern()` pool is used.
- `streamFromDatabase` retains exact sequence groups and sorted axis order,
  but sends each axis and its immutable area sequence directly to the writer.
  The CLI's RouteAxis build stage no longer creates a list containing every
  `RouteAxisStop` object.
- SQLite writes axes and their stop rows in bounded batches within one
  transaction. A SQL or runtime failure rolls back the RouteAxis transaction.
- The existing `buildFromDatabase` materializing API remains available for
  small diagnostics/tests. Full builds use the streaming writer instead.
- Scan/write progress uses the existing periodic heap guard. Its RouteAxis
  threshold is capped at 2050 MiB and 70 percent of maximum heap, or a lower
  configured streaming threshold. Other phase defaults remain unchanged.

Exact sequence grouping, adjacent-area deduplication, route/direction/first
trip ordering, representative trip selection, ID hash and ordinal generation,
warnings and output columns remain unchanged. The aggregation still grows
with distinct sequences; this is not a constant-memory algorithm. Very long
individual trips or feeds with unusually many unique axes remain a risk.
The later read-only contract-statistics collector still materializes its
RouteAxis stop rows; this change does not claim to eliminate every allocation
in the complete preprocessing/audit pipeline.

Progress sections are `route_axis_scan`, `route_axis_write` and
`route_axis_sql_build_write`. The performance report uses
`build_write_route_axes_ms` in place of the former separate build/write times;
compare that duration to their sum. Source-index timing remains separate.
Heap-guard values before collection are retained in evidence, not hidden by
reporting only the post-GC live set. The guard is not a hard heap limit and
`-XX:+DisableExplicitGC` prevents its explicit collections.

## Isolated Verification

`scripts/run_route_axis_headroom_audit.py` takes a completed, passing isolated
Walk comparison. It reuses that run's unchanged copied/fused GTFS inputs and
municipality data, and its successful Contract 0.9 candidate as the baseline.
Only the newly hashed JAR, output paths and temporary directory change.

```bash
nice -n 10 ionice -c 2 -n 7 python3 -B scripts/run_route_axis_headroom_audit.py \
  --tool-root "$TOOL_ROOT" \
  --baseline-run "$TOOL_ROOT/build/$COMPLETED_WALK_RUN" \
  --candidate-jar "$TOOL_ROOT/target/gtfs-preprocessor-0.9.9-SNAPSHOT-headroom.jar" \
  --run-id "$FRESH_RUN_ID"
```

The script requires 40 GiB free space and a fresh run directory. It starts one
FULL rebuild with the unchanged `-Xmx3g` default, then compares every data
table by all columns, including RouteAxis IDs and every Walk column. Only
generation time, artifact hash and derived build identity may differ in
metadata. Source/JAR/municipality metadata are bound to provenance. Schema
indexes, row counts, report diagnostics and SQLite `quick_check` are checked.

The JSON verdict is fail-closed during execution or on exceptions. It reports
RouteAxis sampled peak against 2300 MiB, overall sampled peak against 90
percent of 3 GiB, elapsed time and process RSS separately. Neither a data PASS
nor a single low sample peak establishes three-run headroom or soak approval.
No activation, service operation, feed download or artifact cleanup exists.

Unit coverage includes ID/order stability, null versus empty directions,
loop sequences, adjacent platform collapse, absent stops/trips, overflow
times, shared string identity, batch boundaries, rollback and empty feeds.

Run evidence: [2026-09-05 results](ROUTE_AXIS_HEADROOM_RESULTS_2026_09_05.md).
Consumer gate: [Contract 0.9 acceptance](CONSUMER_CONTRACT_09_GATE.md).
