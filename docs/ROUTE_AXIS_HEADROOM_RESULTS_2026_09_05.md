# RouteAxis Headroom Results, 2026-09-05

Status: local verification and isolated full-feed data comparison PASS.
RouteAxis sampled heap is below 2300 MiB; the overall sampled heap is not.
These measurements do not grant activation, tag, repeat-run or release approval.

## Builds And Inputs

Run: `route-axis-headroom-20260905-01`. Baseline: completed corrected candidate
from `walk-contract09-20260905-02`, not the active production runtime.
Both builds are `0.9.9-SNAPSHOT`, FULL mode, Contract 0.9, Java 21, `-Xmx3g`.

| Artifact | SHA-256 |
| --- | --- |
| Previous Walk candidate | `2a4d730f7dd0528325ada2ed333c0d1fc2c2fed584a40c5aa1b6f174e0a75252` |
| RouteAxis headroom candidate | `464059af1e6f45f4a386270ec63b7825f62a6fd9ca97ad5f16ca930be9feffb7` |
| Shared fused DE/CH ZIP | `6d407008410d72e1f98d928bd41dfdf1e8bbc3fc18426e05843e75e60e5a3137` |
| Shared municipality dataset | `a9a9747d1b6ecdf3dbe2079363899858eb9ec14e9c84068209e95b3050e47d18` |

Raw-source provenance and original hashes are documented in the
[Walk real-feed results](WALK_REAL_FEED_RESULTS_2026_09_05.md). The same copied
inputs are reused; there is no new feed download, live-cache processing or
new fusion. Source files and previous successful outputs are not overwritten.

## Local Verification

Maven package passed: 4 RouteAxis tests, 14 Walk tests and the embedded
preprocessor suite; 19 JUnit tests, zero failures/errors/skips. Thirteen
Python audit tests pass, including retention on failed audit, all-value
comparison guards and truthful pre-GC/unknown memory reporting.

Commit verification repeated Maven `clean package` with all 19 JUnit tests
passing and reproduced the audited headroom JAR hash exactly. The 13 Python
tests also passed again. Use a clean package for artifact-hash verification:
an incremental re-shading of the existing executable JAR produced a different
hash in this check; the clean rebuild matched the retained audit artifact.

The copied consumer release rejects Contract 0.9 as expected; see the
[separate consumer gate](CONSUMER_CONTRACT_09_GATE.md). This is not an approval
to broaden its allowlist without semantic validation.

## Full-Feed Result

The full build exited 0. All 50 comparison checks passed, with no failures.
Both SQLite databases returned `ok` from `quick_check`. Every value in all
30 data tables is unchanged, including every RouteAxis ID, ordering value,
representative trip, stop membership, display name, calendar exception,
search token and Walk column. There are no missing/added keys or changed
row groups. Index definitions and row counts also match exactly.

Representative counts in both outputs:

| Table | Rows |
| --- | ---: |
| stops | 797232 |
| stop_times | 72120163 |
| route_axes | 274354 |
| route_axis_stops | 4441924 |
| stop_search_tokens | 3948154 |
| stop_footpaths | 697356 |
| transfer_edges | 1684794 |

The service-day report is identical, including 2449746 StopTimes above 24h
and a maximum of 248100 seconds (`68:55:00`). The Walk audit is unchanged:
zero invalid components or prohibited walks. Source/JAR/municipality hashes
are bound to the database metadata and copied inputs remained unchanged.
Only generation time, the new JAR hash and its derived build identity are
excluded from metadata equality.

## Memory And Time

| Measurement | Previous Walk candidate | Headroom candidate |
| --- | ---: | ---: |
| RouteAxis highest sampled used heap, MiB | 3062 | 2094 |
| Overall highest sampled used heap, MiB | 3062 | 2754 |
| RouteAxis heap-guard collections | 0 | 72 |
| Peak process RSS, KiB | 3442084 | 3449232 |
| Full build elapsed seconds | 1159.409 | 1206.231 |
| RouteAxis build plus write, ms | 236779 | 272052 |

The RouteAxis sampled peak decreased by 968 MiB (about 32 percent), including
pre-GC guard readings. The overall peak decreased by 308 MiB, but is still
2754 MiB in the unchanged search-token phase; display-quality preparation
also reached 2690 MiB. The global 2300 MiB target is **not met**. These are
sampled used-heap values, not exact live-set peaks or a hard memory bound.

The JSON reports `SAMPLED_BELOW_2300_MIB` for RouteAxis and
`SAMPLED_BELOW_90_PERCENT` overall. The latter only narrowly clears that
sample threshold and is not an operating-reserve approval. Peak process RSS
is effectively unchanged: less used Java heap is not the same as less memory
resident in the operating system.

The full build took 20m06s, about 47 seconds (4 percent) longer in this single
comparison. RouteAxis build/write took about 35 seconds longer. This is a
measured memory/time tradeoff on the same input, not a repeat-run performance
guarantee. The operative default remains `-Xmx3g`.

## Remaining Gates

- Reduce the search-token and display-quality allocation peaks before
  claiming the overall 2300 MiB goal. The contract-statistics reader's eager
  RouteAxis-stop list is another identifiable follow-up, not changed here.
- Establish repeatable headroom separately; one full rebuild does not prove
  three-run stability or soak coverage.
- Obtain independent Consumer Contract 0.9 acceptance and test its intended
  runtime package. The copied release currently rejects 0.9; the running
  container binary was not independently accessible for verification.
- Feed pathways are still absent. These data retain labelled geometric Walk
  estimates; this allocation change adds no surveyed pedestrian coverage.

Machine-readable evidence, provenance, candidate log/resources and the
consumer rejection probe are in gitignored
`build/route-axis-headroom-20260905-01/`. Large databases remain isolated on
the server. No artifact cleanup was performed.

After completion the active data pointer still identified
`20260903T121012Z-de-ch-auto-6e2826a89c`. No app, Android, core, routing,
production consumer, service, timer or activation configuration was modified.
The build/comparison process completed; no required audit process is left
running.
