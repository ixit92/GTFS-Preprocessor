# IXIT GTFS Preprocessor v0.7 Service Day & Calendar Preparation

Status: implemented, isolated data preparation

Date: 2026-08-04

## Purpose

v0.7 makes GTFS service availability testable by original `service_id` and a
local GTFS service date. It does not choose a connection, route between stops,
integrate with Android/Core/Runtime, calculate wake times or process Realtime.

## Resolution order

For a `(service_id, date)` pair:

1. `calendar_dates.exception_type = 2` removes the service.
2. `calendar_dates.exception_type = 1` adds the service.
3. Otherwise `calendar.start_date`, `calendar.end_date` and the weekday flag decide.
4. A service represented only by exceptions is inactive on dates without an addition.
5. A trip service with neither base calendar nor exceptions is `UNRESOLVED`.

This order is stored as metadata policy
`calendar_dates_override_calendar`. Additions and removals are also exposed as
`calendar_dates.exception_action` while the original numeric GTFS field is
preserved.

Holiday effects are represented by their concrete GTFS exception date and
action. GTFS does not require a holiday name, so the preprocessor does not
guess one.

## Timezone and day rollover

`agency_timezone` is preserved in `feed_agencies`. A service gets one timezone
from the agencies of its trips, or the single feed timezone as fallback.
Ambiguous and unavailable cases are explicitly `MULTIPLE` or `UNKNOWN`.

The checked date is the local GTFS service date in that timezone. Stop times
remain seconds since service-day start. `24:30:00` remains 88,200 seconds and
`25:10:00` remains 90,600 seconds; neither is rewritten to a new calendar date.
Conversion to instants, DST handling for a concrete journey and routing are
deliberately outside this sprint.

## SQLite and reports

`service_calendar_summary` combines base weekdays/date range, exception
counts, trip count, timezone, status and explanation. Its indexes support
status, timezone and trip-reference checks.

The text and JSON reports contain `service_day_model` with:

- total, trip-bound, base-calendar and exception service counts
- exception-only and unresolved trip-service counts
- invalid weekday, range, exception-date and exception-type counts
- timezone distribution
- count and maximum of stop times at or beyond 24:00:00

The contract validator requires the tables, columns, indexes and metadata. The
App-Ready gate rejects unresolved trip services or invalid calendar data.
Unknown timezones remain visible diagnostics because older feeds may omit
agency metadata.

## Diagnostic command

```bash
java -jar target/gtfs-preprocessor-0.9.7.jar service-day \
  --database build/ixit_gtfs.sqlite \
  --service-id SERVICE_ID \
  --date 2026-01-02
```

The command opens SQLite with `mode=ro&immutable=1` and reports the resolution
reason plus raw/active trip counts and bounded trip samples.

## Test baseline

Self-tests cover base weekday activation, `calendar_dates` addition and
removal precedence, exception-only services, inactive exception-only dates,
timezone propagation, trip counts, metadata/schema/index requirements and
the existing 24:30/25:10 overflow behavior.
