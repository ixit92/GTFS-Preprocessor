"""Read-only Contract 0.8 -> 0.9 comparison for identical copied DE/CH feeds."""

import argparse
import json
import os
import re
import sqlite3
import sys
from pathlib import Path

from run_walk_real_feed_audit import inside, sha256, write_json


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def scalar(connection, sql):
    return connection.execute(sql).fetchone()[0]


def invalid_walk_components(connection):
    return scalar(connection, """
        SELECT COUNT(*) FROM stop_footpaths WHERE is_traversable=1 AND (
            walk_seconds IS NULL OR walk_seconds<0
            OR transfer_buffer_seconds IS NULL OR transfer_buffer_seconds<>60
            OR min_transfer_seconds IS NULL
            OR min_transfer_seconds<MAX(120,walk_seconds+transfer_buffer_seconds,COALESCE(gtfs_min_transfer_seconds,0))
            OR gtfs_min_transfer_seconds<0
            OR source NOT IN ('SAME_STOP_AREA_GEOMETRY','GTFS_PATHWAYS')
            OR source='SAME_STOP_AREA_GEOMETRY' AND (distance_meters IS NULL OR distance_meters<0 OR distance_meters>400)
        )
    """)


def compare_table(connection, table):
    """Compare all values, including duplicate multiplicities and NULLs."""
    name = quote(table)
    baseline_info = connection.execute(f"PRAGMA baseline.table_info({name})").fetchall()
    candidate_info = connection.execute(f"PRAGMA main.table_info({name})").fetchall()
    if baseline_info != candidate_info:
        return {"pass": False, "reason": "column_contract_changed"}
    if not baseline_info:
        return {"pass": False, "reason": "table_missing"}
    columns = [row[1] for row in baseline_info]
    keys = [row[1] for row in sorted(baseline_info, key=lambda row: row[5]) if row[5]]
    before = scalar(connection, f"SELECT COUNT(*) FROM baseline.{name}")
    after = scalar(connection, f"SELECT COUNT(*) FROM main.{name}")
    if keys or table == "stop_times":
        # stop_times is streamed from the identical ZIP in input order in both builds.
        join = " AND ".join(f"b.{quote(key)} IS c.{quote(key)}" for key in keys) if keys else "b.rowid=c.rowid"
        differs = " OR ".join(f"b.{quote(col)} IS NOT c.{quote(col)}" for col in columns)
        matched = scalar(connection, f"SELECT COUNT(*) FROM baseline.{name} b JOIN main.{name} c ON {join}")
        changed = scalar(connection, f"SELECT COUNT(*) FROM baseline.{name} b JOIN main.{name} c ON {join} WHERE {differs}")
        method = "primary_key_all_columns" if keys else "identical_input_order_all_columns"
    else:
        cols = ",".join(quote(col) for col in columns)
        left = f"SELECT {cols},COUNT(*) FROM baseline.{name} GROUP BY {cols}"
        right = f"SELECT {cols},COUNT(*) FROM main.{name} GROUP BY {cols}"
        changed = scalar(connection, f"SELECT COUNT(*) FROM ({left} EXCEPT {right})")
        changed += scalar(connection, f"SELECT COUNT(*) FROM ({right} EXCEPT {left})")
        matched = min(before, after)
        method = "bidirectional_multiset_difference"
    return {"pass": before == after == matched and changed == 0, "baseline_rows": before,
            "candidate_rows": after, "changed_rows_or_groups": changed,
            "missing_keys": before - matched, "added_keys": after - matched, "method": method}


def walk_deltas(connection, table, key):
    name = quote(table)
    pair = f"baseline.{name} b JOIN main.{name} c ON b.{quote(key)}=c.{quote(key)}"
    conditions = {
        "time_changed": "b.min_transfer_seconds IS NOT c.min_transfer_seconds",
        "time_increased": "c.min_transfer_seconds>b.min_transfer_seconds",
        "time_decreased": "c.min_transfer_seconds<b.min_transfer_seconds",
        "disabled": "b.is_traversable=1 AND c.is_traversable=0",
        "enabled": "b.is_traversable=0 AND c.is_traversable=1",
        "quality_changed": "b.quality IS NOT c.quality",
    }
    result = {key: scalar(connection, f"SELECT COUNT(*) FROM {pair} WHERE {condition}")
              for key, condition in conditions.items()}
    result["baseline_rows"] = scalar(connection, f"SELECT COUNT(*) FROM baseline.{name}")
    result["candidate_rows"] = scalar(connection, f"SELECT COUNT(*) FROM main.{name}")
    result["matched_keys"] = scalar(connection, f"SELECT COUNT(*) FROM {pair}")
    result["samples"] = [dict(zip(("id", "from_stop_id", "to_stop_id", "before_seconds", "after_seconds",
                                    "before_traversable", "after_traversable"), row))
                         for row in connection.execute(f"""
            SELECT b.{quote(key)}, c.from_stop_id, c.to_stop_id,
                   b.min_transfer_seconds, c.min_transfer_seconds, b.is_traversable, c.is_traversable
            FROM {pair} WHERE b.min_transfer_seconds IS NOT c.min_transfer_seconds
                OR b.is_traversable IS NOT c.is_traversable ORDER BY b.{quote(key)} LIMIT 20
        """)]
    return result


def compare(run):
    provenance = json.loads((run / "provenance.json").read_text(encoding="utf-8"))
    reports = {name: json.loads((run / f"{name}-contract.json").read_text(encoding="utf-8"))
               for name in ("baseline", "candidate")}
    checks = {}

    def check(name, passed):
        checks[name] = bool(passed)

    check("builds_pass", provenance.get("builds_pass") is True)
    check("heap_budget_3g", provenance.get("heap") == "3g"
          and all("-Xmx3g" in provenance["executions"][name]["command"] for name in ("baseline", "candidate")))
    check("inputs_unchanged", provenance.get("inputs_unchanged_after_runs") is True
          and sha256(run / "de-ch-fused.zip") == provenance["fused_sha256"])
    for name, version, contract in (("baseline", "0.9.8", "0.8"), ("candidate", "0.9.9-SNAPSHOT", "0.9")):
        report = reports[name]
        check(f"{name}_version", report.get("preprocessor_version") == version
              and report.get("contract_version") == contract)
        check(f"{name}_footpath_audit", report["transfer_footpath_audit"].get("pass") is True)
        check(f"{name}_service_day", report["service_day_model"].get("pass") is True)
        check(f"{name}_display_audit", report["app_ready_sqlite"]["display_name_audit"].get("pass") is True)
        check(f"{name}_display_quality", report["app_ready_sqlite"]["display_name_quality_baseline"].get("pass") is True)
        check(f"{name}_app_ready", report["app_ready_sqlite"].get("app_ready") is True)
    check("time_model_unchanged", reports["baseline"]["time_model"] == reports["candidate"]["time_model"])
    check("id_policies_unchanged", reports["baseline"]["id_policies"] == reports["candidate"]["id_policies"])
    check("service_day_report_unchanged", reports["baseline"]["service_day_model"] == reports["candidate"]["service_day_model"])
    no_paths = not provenance["fused_inventory"]["has_pathways"]
    result = {"audit_version": "walk-real-feed-1", "activation_allowed": False,
              "coverage": "geometry_and_gtfs_minima_only" if no_paths else "includes_feed_pathways",
              "checks": checks, "stable_tables": {}, "walk_deltas": {},
              "service_day": reports["candidate"]["service_day_model"],
              "transfer_footpath_audit": reports["candidate"]["transfer_footpath_audit"],
              "performance": {}, "warnings": reports["candidate"]["warnings"]}
    os.environ["SQLITE_TMPDIR"] = str(run / "tmp")
    with sqlite3.connect((run / "candidate.sqlite").as_uri() + "?mode=ro&immutable=1", uri=True) as connection:
        connection.execute("ATTACH DATABASE ? AS baseline", ((run / "baseline.sqlite").as_uri() + "?mode=ro&immutable=1",))
        connection.execute("PRAGMA query_only=ON")
        connection.execute("PRAGMA cache_size=-32768")
        connection.execute("PRAGMA baseline.cache_size=-32768")
        for name, schema in (("baseline", "baseline"), ("candidate", "main")):
            metadata = dict(connection.execute(f"SELECT key,value FROM {schema}.ixit_metadata"))
            expected = {"source_gtfs_sha256": provenance["fused_sha256"],
                        "preprocessor_artifact_sha256": provenance["jar_sha256"][name],
                        "municipality_data_sha256": provenance["municipalities_sha256"],
                        "preprocessor_version": reports[name]["preprocessor_version"],
                        "contract_version": reports[name]["contract_version"]}
            for key, value in expected.items():
                check(f"{name}_binding_{key}", metadata.get(key) == value and reports[name]["metadata"].get(key) == value)
        base_tables = {row[0] for row in connection.execute("SELECT name FROM baseline.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        cand_tables = {row[0] for row in connection.execute("SELECT name FROM main.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        check("table_delta_only_pathways", cand_tables == base_tables | {"pathways"})
        for table in sorted(base_tables - {"ixit_metadata", "stop_footpaths", "transfer_edges"}):
            print(f"Comparing all values in {table}", flush=True)
            result["stable_tables"][table] = compare_table(connection, table)
            check(f"stable_{table}", result["stable_tables"][table]["pass"])
            write_json(run / "comparison-progress.json", result)
        for table, key in (("stop_footpaths", "footpath_id"), ("transfer_edges", "transfer_edge_id")):
            delta = walk_deltas(connection, table, key)
            result["walk_deltas"][table] = delta
            check(f"{table}_identities_unchanged", delta["baseline_rows"] == delta["candidate_rows"] == delta["matched_keys"])
            if no_paths:
                check(f"{table}_no_shorter_or_new_walk", delta["time_decreased"] == 0 and delta["enabled"] == 0)
        for name in ("baseline", "candidate"):
            check(f"{name}_sqlite_quick_check", connection.execute(f"PRAGMA {'main' if name == 'candidate' else 'baseline'}.quick_check").fetchall() == [("ok",)])
        check("no_invalid_components", reports["candidate"]["transfer_footpath_audit"].get("invalid_walk_components") == 0)
        check("no_prohibited_walks", reports["candidate"]["transfer_footpath_audit"].get("prohibited_walks") == 0)
        result["live_invalid_walk_components"] = invalid_walk_components(connection)
        check("live_walk_components", result["live_invalid_walk_components"] == 0)
        if no_paths:
            check("no_invented_pathways", scalar(connection, "SELECT COUNT(*) FROM pathways") == 0
                  and scalar(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE source='GTFS_PATHWAYS'") == 0)
    for name in ("baseline", "candidate"):
        snapshots = reports[name]["real_feed_validation"].get("memory_snapshots_mb", {})
        values = list(snapshots.values())
        with (run / f"{name}.log").open(encoding="utf-8") as log:
            for line in log:
                values.extend(int(value) for value in re.findall(r"(?:memory_used_mb|before_mb)=(\d+)", line))
        resource_text = (run / f"{name}-resources.txt").read_text(encoding="utf-8")
        rss = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", resource_text)
        result["performance"][name] = {"elapsed_seconds": provenance["executions"][name]["elapsed_seconds"],
                                      "sections_ms": reports[name]["real_feed_validation"].get("performance_sections", {}),
                                      "max_sampled_heap_mb": max(values) if values else None,
                                      "max_rss_kb": int(rss.group(1)) if rss else None,
                                      "sqlite_bytes": (run / f"{name}.sqlite").stat().st_size}
    peak = result["performance"]["candidate"]["max_sampled_heap_mb"]
    result["headroom_status"] = "NOT_MEASURED" if peak is None else "REVIEW_REQUIRED" if peak >= 3072 * 0.9 else "SAMPLED_BELOW_90_PERCENT"
    result["headroom_note"] = "Data comparison PASS is not a heap-reserve or activation approval; heap values are sampled, not exact live-set peaks."
    result["pass"] = all(checks.values())
    result["failed_checks"] = [name for name, passed in checks.items() if not passed]
    write_json(run / "walk-comparison.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-root", type=Path, required=True)
    parser.add_argument("--run-directory", type=Path, required=True)
    args = parser.parse_args()
    root = args.tool_root.resolve(strict=True)
    run = inside(root, args.run_directory)
    if not run.is_relative_to(root / "build"):
        parser.error("Run directory must be inside tool-root/build")
    for filename in ("baseline.sqlite", "candidate.sqlite", "tmp", "walk-comparison.json", "comparison-progress.json"):
        inside(root, run / filename)
    write_json(run / "walk-comparison.json", {"pass": False, "status": "RUNNING", "activation_allowed": False})
    try:
        result = compare(run)
    except Exception as exc:
        write_json(run / "walk-comparison.json", {"pass": False, "status": "ERROR",
                                                  "error": str(exc), "activation_allowed": False})
        print(f"Comparison failed without activation or cleanup: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"pass": result["pass"], "failed_checks": result["failed_checks"],
                      "coverage": result["coverage"], "headroom_status": result["headroom_status"], "activation_allowed": False}, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
