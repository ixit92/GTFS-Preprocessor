# Consumer Contract 0.9 Gate

Inspected: 2026-09-05. Status: **NOT APPROVED**. No server consumer, Android,
app, core, routing, service, timer or active data pointer was modified.

## Observed Evidence

The configured API build context contains an artifact matching the retained
`transit-api-contract-0.7-b7d4e61-3a4a74ee` release. Both JARs have SHA-256
`3a4a74ee7cfac1afebbc17c9547e30313aec6f70bc5759a7a4151752e0bc7432`.
The configured build label also identifies `transit-api-contract-07-b7d4e61`.

An unchanged copy of that release JAR was placed in the isolated tool's
gitignored `local-data/consumer-contract-09/`. `ConsumerContractProbe.java`
invoked only its `VersionedDataStore.loadDirectDatabase` and
`validateDatabase` against the completed isolated Contract 0.9 Walk database.
No HTTP server was started and no production database was opened.
The probed database is a diagnostic FULL build, not an activated runtime
package. Rejection occurs at the version gate; this does not test all later
schema/run-mode checks. Future acceptance must also validate the intended
APP_RUNTIME package separately, without relabelling this FULL artifact.

Result:

```text
supported_contract_versions: [0.6, 0.7]
consumer_accepts_database: false
reason: Transit API supports contract_version 0.6, 0.7; found 0.9
activation_allowed: false
```

This proves rejection by the copied release artifact. Docker inspection and
access to the running container's JAR were denied to the existing SSH user;
therefore the in-memory/running container artifact identity was **not**
independently verified. No privileges were changed to obtain it. The older
build-source checkout is also not accepted as evidence of the active binary.
Private probe output remains in the isolated build directory.

The preprocessor's own Consumer PoC accepts its compiled
`SqliteContract.CONTRACT_VERSION`; that is a tool-local validation path, not
independent downstream acceptance. A passing preprocessor audit or
`app_ready=true` does not override this release gate.

## Required Before Acceptance

1. Identify and hash the exact proposed consumer artifact independently of
   the preprocessor, and explicitly pin the supported contract versions.
2. Validate Contract 0.9 metadata, `pathways`, the extended `stop_footpaths`
   schema and provenance. Reject unknown versions and malformed components.
3. Resolve selected areas to concrete stop members. Validate trips, stop
   order, `service_id`, calendar exceptions and date, retaining times over 24h.
4. Respect directed pathways and blocked/unknown walks. Geometry fallback is
   an estimate, not proof of a street crossing, entrance or accessible route.
5. Interpret `min_transfer_seconds` as the already combined lower bound;
   do not add its buffer again. Scoped GTFS rules still need trip/route-aware
   validation and must not become generic pedestrian edges.
6. Test same-platform, platform-change, parent minimum/prohibition, one-way
   pathway, incomplete graph, long/unknown walk and midnight/service-exception
   cases in an isolated consumer process before any activation decision.

No version allowlist has been relaxed and no production compatibility is
claimed. A separately authorized consumer change and its evidence are needed.
