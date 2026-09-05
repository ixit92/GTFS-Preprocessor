# Walk Real-Feed Validation, 2026-09-05

Status: contract/data comparison PASS; heap headroom REVIEW_REQUIRED.
Baseline and corrected candidate builds completed successfully. No activation
or release approval; `activation_allowed=false` remains explicit in the report.

## Reproducible Inputs

Initial run: `walk-contract09-20260905-01`; corrected candidate comparison:
`walk-contract09-20260905-02`.

The inputs are unchanged copies of the DE_FULL and CH raw GTFS ZIPs identified
by the current routing-data manifest, not transformed runtime data. The
manifest's active data version is `20260903T121012Z-de-ch-auto-6e2826a89c`
(preprocessor 0.9.5, contract 0.7); it is a provenance reference only. Neither
the live runtime database nor application code is an input to the builds.

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| DE_FULL | 284526384 | `ba391f006c658120ca2693bbd572310d669e72154bc3e3749b44a08bbb9a0f70` |
| CH | 243453821 | `d325fd0954a91ac50005ad53db1976b8e528ebb1c388e4e8fd5a4415e4139a1e` |

Neither ZIP contains `pathways.txt` or `levels.txt`. This run can validate
geometry estimates and GTFS transfer constraints, but cannot establish real
station-pathway coverage. DE_FULL contributes 37621011 source StopTimes and
CH contributes 34499152 before fusion deduplication.

Fusion completed with exit 0 in 1288.903 seconds (21m29s), with a peak
process RSS of 1387656 KiB. The fused ZIP has 588822798 bytes and SHA-256
`6d407008410d72e1f98d928bd41dfdf1e8bbc3fc18426e05843e75e60e5a3137`.
It retains 338 ambiguous fusion decisions, suppresses/stitches no trips, and
matches 2513 cross-feed stop identities. These are fusion diagnostics, not
walking-route evidence.

Municipality boundaries: `bkg-vg250-gem-2025-01-01`, SHA-256
`a9a9747d1b6ecdf3dbe2079363899858eb9ec14e9c84068209e95b3050e47d18`.

## Executables

The baseline was built locally from public tag `v0.9.8`, commit
`d420d61c10d73d2c6662be59a1bca65389e6906d`, with Java 21 and Maven package
(`-DskipTests`). At audit time, the candidate was the tested, uncommitted Walk development
tree based on public main `afdf14d84f90b48412d4fee342b45006e000fa90`.

| Build | Contract | JAR SHA-256 |
| --- | --- | --- |
| 0.9.8 baseline | 0.8 | `2cd8f4d702a549c66b7a82df4708e5fe9e72c868740d9465c1f0acc276928ccc` |
| 0.9.9-SNAPSHOT initial candidate, interrupted | 0.9 | `34caccdbbfda91d0491128cd785ef6cd11fe40be30cdfb3e7f4d6403676f9a92` |
| 0.9.9-SNAPSHOT corrected audit | 0.9 | `2a4d730f7dd0528325ada2ed333c0d1fc2c2fed584a40c5aa1b6f174e0a75252` |

Both full-mode builds use the same fused ZIP, copied boundaries and `-Xmx3g`.
The baseline JAR performs fusion. Execution is sequential at reduced CPU/IO
priority inside a separate `tools/gtfs-preprocessor/` directory. No service,
timer, active data pointer, application or server consumer is changed.

## Defect Found And Corrected

The initial candidate completed its data/index writing but became
impractically slow in the generic transfer-constraint audit. A thread dump
located `TransferFootpathAuditor`'s prohibited-walk query. SQLite 3.46.1 chose
`idx_transfers_trip_scope` for the correlated subquery, repeatedly scanning
the large NULL trip-scope bucket for individual footpaths.

The corrected query explicitly uses the already contract-required
`idx_transfers_from_to` index. This changes query planning, not transfer
semantics or generated data. A regression assertion inspects the actual
query's EXPLAIN plan. On the fully written initial candidate database the
corrected predicate returned `prohibited_walks=0` in 2953 ms, using the same
SQLite JDBC version in a read-only connection.

Only the isolated initial candidate process was terminated (exit 143 after
1682.275 seconds). Its files and failed provenance are retained; it is not a
passing build. The original baseline completed successfully. Its immutable
outputs and the already hashed fusion were reused for a corrected-candidate
rebuild in run 02. This required one additional candidate attempt, but no
repeat fusion and no second baseline build. Links reference only completed
test artifacts inside the isolated tool directory.

## Verification

- Candidate Maven package and existing Java suite passed before this run,
  including 14 Walk tests and the embedded preprocessor suite.
- After the query-plan fix, Maven package passed again: 14 Walk tests plus
  the embedded suite, zero failures/errors/skips. JAR and both SBOMs built.
- Eleven audit-script tests pass, including same-count content changes,
  duplicate multiplicity, path confinement and exact-copy hash checks.
- A deliberately failed audit replaces stale PASS with FAIL and leaves both
  sentinel database artifacts byte-for-byte intact. The runner/comparator
  have no cleanup or activation operation.
- Both full-feed builds exited 0. The final comparison has no failed checks;
  both databases return `ok` from SQLite `quick_check`.
- All 27 stable tables match in every compared value, including duplicate
  multiplicities for search tokens. This is not just a row-count comparison.
- Source, municipality and JAR hashes in both live SQLite metadata tables
  match their reports and provenance. Copied input hashes remain unchanged.

### Unchanged Data

Service-day, footpath, display-name and display-quality audits pass for both
builds; `app_ready_sqlite.app_ready` is true as a data-quality check, not an
activation decision. Original stops, trips, routes, StopArea memberships,
display names, search tokens, calendars, RouteAxes and transfer rules are
unchanged. The new `pathways` table is empty, matching the source inventory.

| Data in both builds | Rows |
| --- | ---: |
| Stops | 797232 |
| StopAreas | 284345 |
| Routes | 29990 |
| Trips | 3988671 |
| StopTimes | 72120163 |
| Calendar exceptions | 10775906 |
| Search tokens | 3948154 |
| Concrete stop footpaths | 697356 |

There are 2449746 overflow StopTimes. The maximum service-day time is
248100 seconds (`68:55:00`), not reduced modulo 24. The feed-derived service
timezone is `Europe/Berlin`; unresolved trip services and invalid timezone
services are both zero. These service-day values and their underlying data
are identical in the candidate.

### Walk Changes

| Comparison | stop_footpaths | transfer_edges |
| --- | ---: | ---: |
| Rows in each build / matched IDs | 697356 | 1684794 |
| Increased minimum transfer time | 22623 | 53106 |
| Decreased minimum transfer time | 0 | 0 |
| Newly traversable | 0 | 0 |
| No longer traversable | 0 | 0 |
| Changed quality label | 0 | 13899 |

The time increases follow the new lower-bound model: walking estimate plus
one buffer, with applicable unscoped GTFS minimum times also respected.
For example, the copied feed's `CH::8102336` to
`CH::8102336_gen:missingSLOID_pf:1A-B` footpath changes from 120 to 300 seconds.
The GTFS transfer from `CH::ch:1:sloid:3184::52` to itself changes from 60 to
120 seconds; identical coordinates do not imply a zero-time transfer.
Transfer quality labels are derived from duration thresholds, not independent
evidence of surveyed walking conditions. `transfer_edges` also contains
non-traversable structural candidates; its total is not a usable-path count.

There are 695548 traversable stop footpaths, all explicitly labelled geometry
estimates, and 1808 unknown/non-traversable footpaths. There are 109002
traversable transfer edges. The audit reports zero invalid time components,
prohibited walks, traversable over-distance footpaths, zero-time footpaths,
or generic traversable edges derived from scoped/in-seat rules or mere area
membership. A direct query of the completed database also finds zero invalid
walking/buffer/GTFS-minimum components.

The longest diagnostic footpath is 6564 meters and remains UNKNOWN, not
traversable. The audit flags 388 oversized and 62 extreme StopAreas; these are
retained data-quality findings, not automatically corrected station clusters.

### Performance And Headroom

| Measurement | Baseline 0.9.8 | Corrected 0.9.9-SNAPSHOT |
| --- | ---: | ---: |
| Full build elapsed seconds | 1246.719 (20m47s) | 1159.409 (19m19s) |
| Highest sampled used heap, MiB | 2871 | 3062 |
| Peak process RSS, KiB | 3465468 | 3442084 |
| SQLite bytes | 21142220800 | 21150990336 |
| Transfer/footpath audit, ms | 4314 | 7143 |
| Contract validation, ms | 13364 | 16041 |

The corrected candidate completed without OOM, but its sampled heap reached
3062 of 3072 MiB during `route_axis_sql_build`, before the Walk builders.
This is insufficient demonstrated operating reserve: `headroom_status` is
`REVIEW_REQUIRED`. Samples include uncollected objects and are not exact live
set peaks; the observed timing advantage is also not a repeat-run performance
guarantee. No heap-stability, soak or production approval is claimed.

Before activation, investigate RouteAxis memory pressure, establish repeatable
headroom, and obtain explicit consumer acceptance of Contract 0.9. Actual
station-path coverage needs a separate feed with pathways or independently
verified pedestrian data; geometry estimates alone do not establish it.

## Evidence And Boundaries

Final machine-readable evidence is in the gitignored
`build/walk-contract09-20260905-02/walk-comparison.json`, alongside provenance,
contract reports and process resource logs. The initial failed attempt is
retained in run 01, not relabelled as PASS. The local checkout holds compact
reports; the large ZIP/SQLite artifacts remain in the isolated server tool
directory. No cleanup was performed.

After completion, the active data pointer still identifies
`20260903T121012Z-de-ch-auto-6e2826a89c`. No audit/build process remains running.
All edits and retained artifacts are under `tools/gtfs-preprocessor/`; no
app, core, routing, Android, service, timer or production data was changed.

Method and reproduction: [WALK_REAL_FEED_AUDIT.md](WALK_REAL_FEED_AUDIT.md).

Raw ZIPs, SQLite outputs and detailed operational JSON/log files remain in
gitignored local-data/build directories. Only this concise result summary
belongs in source control. At audit completion, no commit, tag, release or
production activation had been performed for this candidate. Committing the
tested source later does not grant production or consumer compatibility approval.
