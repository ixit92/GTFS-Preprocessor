# Isolated Walk Real-Feed Audit

This audit compares public tag `v0.9.8` (Contract 0.8) with the local
`0.9.9-SNAPSHOT` candidate (Contract 0.9). It does not deploy a consumer,
activate a database, restart a service, or perform artifact cleanup.

## Inputs And Isolation

The Linux runner needs Python 3.11+, Java 21, `/usr/bin/time` and at least
90 GiB of free disk space. Keep its checkout and every generated file under
`tools/gtfs-preprocessor/`. Both JARs must be in its `target/` directory.

An explicitly supplied active-data manifest identifies exact DE_FULL and CH
raw ZIP hashes. The runner copies those files from the cache into
`local-data/from-routing-cache/<run-id>/`, verifies source/copy SHA-256 before
and after copying, and confirms the required raw GTFS entries. Transformed
runtime files are never used as preprocessor inputs. Municipality boundaries
are copied and hashed too. `local-data/`, `build/` and `target/` are gitignored.

The baseline fusion CLI combines these copies once. Both full rebuilds use
that same hashed fused ZIP, identical municipality data and `-Xmx3g`,
sequentially. Java and SQLite temporary files stay under the isolated run.
The active data version is a provenance reference, not the comparison
baseline: no live runtime database is opened by these scripts.

## Run

Use absolute paths for the placeholders below. Supply the existing manifest
and municipality dataset explicitly; the runner does not discover secrets,
download feeds or change operational configuration.

```bash
nice -n 10 ionice -c 2 -n 7 python3 scripts/run_walk_real_feed_audit.py \
  --tool-root "$TOOL_ROOT" --run-id "$RUN_ID" --java "$JAVA" \
  --baseline-jar "$TOOL_ROOT/target/gtfs-preprocessor-0.9.8.jar" \
  --candidate-jar "$TOOL_ROOT/target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar" \
  --manifest "$ACTIVE_MANIFEST" --cache-root "$RAW_FEED_CACHE" \
  --municipalities "$MUNICIPALITIES" --municipalities-version "$BOUNDARY_VERSION"

nice -n 10 ionice -c 2 -n 7 python3 -B scripts/compare_walk_real_feed.py \
  --tool-root "$TOOL_ROOT" --run-directory "$TOOL_ROOT/build/$RUN_ID"
```

Existing run/input directories are rejected. There is intentionally no
cleanup command. A failed or interrupted comparison cannot leave an earlier
PASS verdict as the current verdict: it starts with `pass=false` and records
errors without deleting databases or changing activation state.

## Evidence

`provenance.json` records source and fused hashes, ZIP inventory, active data
version, municipality hash, JAR hashes, exact commands and process exit codes.
Each process has its own log and GNU time resource report. The JSON comparison
contains checks, complete stable-table comparisons, walk deltas, samples,
warnings and performance measurements.
Live SQLite metadata must match report and provenance for source ZIP,
municipality data, JAR hash and the expected versions.

Stable tables are compared by primary key and every column, with NULL-aware
equality. Unkeyed search tokens use bidirectional multiset differences,
including duplicate multiplicities. All `stop_times` columns are compared in
their shared GTFS input order using SQLite rowids; reordering would fail this
audit even if the timetable were semantically equivalent. No large row sets
are accumulated in Python memory. SQLite connections use
`mode=ro&immutable=1` and `query_only=ON` after both builds have closed.

Only metadata, `transfer_edges` and `stop_footpaths` are excluded from exact
stable-table equality; `pathways` is the expected added table. Reports must
have the explicitly expected versions and passing service-day, display and
footpath audits. Walk identities must remain stable. On feeds without
pathways, shorter or newly enabled walks fail the comparison; increases and
disabled candidates are reported for review, not silently treated as an
activation approval.

A direct SQL guard additionally checks the written walking time, one-time
buffer, GTFS lower bound and geometry range. A stale report cannot hide a
subsequently altered buffer or an insufficient stored minimum time.

Elapsed time, process peak RSS and sampled Java heap are distinct metrics.
Sampled heap is not an exact allocation peak. One run per version does not
establish a repeat-run headroom or soak guarantee.
At 90 percent or more of the 3 GiB heap limit, `headroom_status` is
`REVIEW_REQUIRED`. The `pass` field describes the contract/data comparison,
not heap-reserve approval. Activation remains blocked independently.

An empty pathways inventory limits real-feed evidence to geometry estimates
and GTFS transfer minima. Synthetic directed-path, stair, lift and invalid
provenance tests remain necessary; they are not replaced by this audit.

Even a passing comparison retains `activation_allowed=false`. Consumer
Contract 0.9 acceptance and a separately authorized activation are still
required.

```bash
python3 -B -m unittest discover -s scripts -p 'test_walk_real_feed_audit.py' -v
```
