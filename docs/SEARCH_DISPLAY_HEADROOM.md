# Search, Display And Statistics Headroom

Development version: `0.9.9-SNAPSHOT`, Contract `0.9`, schema `0.1`.
This work only changes preprocessing allocation and diagnostics. No display
normalization rule, timetable value, Walk policy or consumer is changed.

## Implementation

- The CLI reads committed `stops` and `stop_areas` in insertion order. Their
  primary keys give each source name a unique token identity, so duplicate
  detection only retains the current name's tokens. Area tokens still have
  a null `stop_id`; stop tokens retain their original ID and parent-derived
  `area_id`.
- Tokens go directly into the writer's bounded SQLite batch and one
  transaction. SQL and progress-callback failures roll back flushed batches.
- Empty-source counts are exact, with the first five samples retained for
  the existing warning text. Duplicate counts and token order are unchanged.
  The materializing `build(List, List)` API remains for small diagnostics and
  preserves its existing global deduplication behavior even for repeated IDs.
- Normalization and display-prefix inspection reuse compiled regex patterns.
  Regex expressions, replacement order, Unicode handling and output are
  unchanged. There is no global name cache or more aggressive name stripping.
- The RouteAxis report uses SQL `COUNT(*)` for `route_axis_stops`, retaining
  only the smaller axis list. Counts outside the report's integer range fail
  explicitly. Structural and semantic contract validation remain in place.
- Search and display progress use the existing derived-phase heap guard,
  capped at 2050 MiB, 70 percent of heap, or a lower configured threshold.
  The `stop_times` and `calendar_dates` defaults are unchanged. Guards are
  periodic explicit collections, not hard memory limits.

The token stage's performance key is now `build_write_stop_search_tokens_ms`;
compare it with the previous build plus write durations. The other stage
timing keys and all SQLite data/metadata policies are unchanged.

## Verification

`mvn clean package` covers ordered token equality, duplicate words, source
identities, blank names, empty feeds, flushed-batch rollback for SQL/runtime
errors, retry after rollback and RouteAxis statistics equality. A seeded
4000-input Unicode/punctuation corpus compares normalization with the exact
previous algorithm. Display classification/progress and the existing city,
Walk, calendar and overflow-time regressions remain covered.

The isolated real-feed runner can chain a completed passing headroom run:

```bash
python3 -B scripts/run_route_axis_headroom_audit.py \
  --tool-root "$TOOL_ROOT" \
  --baseline-run "$TOOL_ROOT/build/$COMPLETED_HEADROOM_RUN" \
  --baseline-kind headroom \
  --candidate-jar "$TOOL_ROOT/target/$CANDIDATE_JAR" \
  --run-id "$FRESH_RUN_ID"
```

The runner requires a fresh directory and 40 GiB free space. It reuses only
the previous isolated, unchanged feed copies, performs one FULL `-Xmx3g`
rebuild, and compares all values in all data tables, schema indexes, report
diagnostics and provenance. No runtime cache is used as a working database.
Reports are fail-closed while running or after failure. There is no cleanup,
timer/service operation, production activation or app integration.

`pass` describes data equivalence, not heap-target approval. The report keeps
the global 2300-MiB target separate from per-phase peaks and the 90-percent
indicator. Sampled Java heap, process RSS and elapsed time are distinct.
One run does not establish repeatability or replace the independent
[consumer compatibility gate](CONSUMER_CONTRACT_09_GATE.md).

Run evidence: [2026-09-05 results](SEARCH_DISPLAY_HEADROOM_RESULTS_2026_09_05.md).
