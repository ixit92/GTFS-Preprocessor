"""Rebuild a completed isolated Walk input and compare every data table read-only."""

import argparse
import json
import os
import re
import shutil
import sqlite3
import sys
from pathlib import Path

from compare_walk_real_feed import compare_table
from run_walk_real_feed_audit import execute, inside, sha256, write_json


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def memory_evidence(log):
    overall, route_axis, guards = [], [], []
    with log.open(encoding="utf-8") as stream:
        for line in stream:
            values = [int(value) for value in re.findall(r"(?:memory_used_mb|before_mb)=(\d+)", line)]
            overall.extend(values)
            if "route_axis" in line or "phase=route_axes " in line:
                route_axis.extend(values)
                if "section=heap_guard " in line:
                    guards.append(line.strip())
    return {"max_sampled_heap_mib": max(overall) if overall else None,
            "route_axis_max_sampled_heap_mib": max(route_axis) if route_axis else None,
            "route_axis_gc_guards": len(guards), "guard_samples": guards[:3]}


def compare_databases(baseline, run, provenance):
    result = {"pass": False, "status": "RUNNING", "activation_allowed": False,
              "stable_tables": {}, "checks": {}, "performance": {}}
    checks = result["checks"]
    before = read_json(baseline / "candidate-contract.json")
    after = read_json(run / "candidate-contract.json")
    checks["same_contract_09"] = before["contract_version"] == after["contract_version"] == "0.9"
    checks["row_counts"] = before["row_counts"] == after["row_counts"]
    for key in ("service_day_model", "route_axes", "transfer_footpath_audit", "warnings"):
        checks[f"report_{key}"] = before[key] == after[key]
    checks["candidate_audits_pass"] = (after["service_day_model"]["pass"]
            and after["transfer_footpath_audit"]["pass"] and after["app_ready_sqlite"]["app_ready"])
    with sqlite3.connect((run / "candidate.sqlite").as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        db.execute("ATTACH DATABASE ? AS baseline", ((baseline / "candidate.sqlite").as_uri() + "?mode=ro&immutable=1",))
        db.execute("PRAGMA query_only=ON")
        db.execute("PRAGMA cache_size=-32768")
        db.execute("PRAGMA baseline.cache_size=-32768")
        metadata = {}
        for label, schema, report in (("baseline", "baseline", before), ("candidate", "main", after)):
            metadata[label] = dict(db.execute(f"SELECT key,value FROM {schema}.ixit_metadata"))
            checks[f"{label}_metadata_report_binding"] = metadata[label] == report["metadata"]
            checks[f"{label}_jar_binding"] = metadata[label]["preprocessor_artifact_sha256"] == provenance[f"{label}_jar_sha256"]
            checks[f"{label}_source_binding"] = metadata[label]["source_gtfs_sha256"] == provenance["fused_sha256"]
            checks[f"{label}_municipality_binding"] = metadata[label]["municipality_data_sha256"] == provenance["municipalities_sha256"]
        excluded = {"generated_at", "preprocessor_artifact_sha256", "build_identity_sha256"}
        checks["stable_metadata"] = ({k: v for k, v in metadata["baseline"].items() if k not in excluded}
                                     == {k: v for k, v in metadata["candidate"].items() if k not in excluded})
        result["expected_metadata_changes"] = sorted(excluded)
        tables = {schema: {row[0] for row in db.execute(f"SELECT name FROM {schema}.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
                  for schema in ("main", "baseline")}
        checks["same_tables"] = tables["main"] == tables["baseline"]
        for table in sorted((tables["main"] | tables["baseline"]) - {"ixit_metadata"}):
            print(f"Comparing all values in {table}", flush=True)
            comparison = compare_table(db, table)
            result["stable_tables"][table] = comparison
            checks[f"stable_{table}"] = comparison["pass"]
            write_json(run / "headroom-comparison.json", result)
        indexes = {schema: db.execute(f"SELECT name,tbl_name,sql FROM {schema}.sqlite_master WHERE type='index' ORDER BY name").fetchall()
                   for schema in ("main", "baseline")}
        checks["same_indexes"] = indexes["main"] == indexes["baseline"]
        for label, schema in (("baseline", "baseline"), ("candidate", "main")):
            print(f"SQLite quick_check: {label}", flush=True)
            checks[f"{label}_quick_check"] = db.execute(f"PRAGMA {schema}.quick_check").fetchall() == [("ok",)]
    for label, path in (("baseline", baseline), ("candidate", run)):
        memory = memory_evidence(path / "candidate.log")
        rss = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", (path / "candidate-resources.txt").read_text())
        result["performance"][label] = {**memory,
                "elapsed_seconds": read_json(path / "candidate-execution.json")["elapsed_seconds"],
                "max_rss_kib": int(rss.group(1)) if rss else None}
    peak = result["performance"]["candidate"]["route_axis_max_sampled_heap_mib"]
    overall = result["performance"]["candidate"]["max_sampled_heap_mib"]
    result["route_axis_headroom_status"] = "SAMPLED_BELOW_2300_MIB" if peak is not None and peak < 2300 else "REVIEW_REQUIRED"
    result["overall_headroom_status"] = "SAMPLED_BELOW_90_PERCENT" if overall is not None and overall < 3072 * 0.9 else "REVIEW_REQUIRED"
    result["note"] = "One full run; sampled heap is not an exact live-set peak or a repeat-run approval."
    result["pass"] = all(checks.values())
    result["failed_checks"] = [key for key, value in checks.items() if not value]
    result["status"] = "PASS" if result["pass"] else "FAIL"
    write_json(run / "headroom-comparison.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-root", type=Path, required=True)
    parser.add_argument("--baseline-run", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    root = args.tool_root.resolve(strict=True)
    baseline = inside(root, args.baseline_run)
    jar = inside(root, args.candidate_jar)
    if not baseline.is_relative_to(root / "build") or jar.parent != root / "target":
        parser.error("Baseline must be inside build and JAR inside target")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.run_id):
        parser.error("Invalid run ID")
    run = inside(root, root / "build" / args.run_id)
    if run.exists() or shutil.disk_usage(root).free < 40 * 1024**3:
        parser.error("Fresh run directory and 40 GiB free space required")
    previous = read_json(baseline / "provenance.json")
    if previous.get("builds_pass") is not True or read_json(baseline / "walk-comparison.json").get("pass") is not True:
        parser.error("Baseline must be a completed passing isolated Walk comparison")
    command = previous["executions"]["candidate"]["command"].copy()
    fused = inside(root, Path(command[command.index("--input") + 1]))
    geo = inside(root, Path(command[command.index("--municipalities-geojson") + 1]))
    if sha256(fused) != previous["fused_sha256"] or sha256(geo) != previous["municipalities_sha256"]:
        parser.error("Completed copied inputs have changed")
    for name in ("candidate.sqlite", "candidate-contract.json", "candidate.log", "candidate-execution.json", "candidate-resources.txt"):
        inside(root, baseline / name)
    run.mkdir()
    (run / "tmp").mkdir()
    os.environ["SQLITE_TMPDIR"] = str(run / "tmp")
    command[command.index("-jar") + 1] = str(jar)
    for i, item in enumerate(command):
        if item.startswith("-Djava.io.tmpdir="):
            command[i] = f"-Djava.io.tmpdir={run / 'tmp'}"
    command[command.index("--output") + 1] = str(run / "candidate.sqlite")
    command[command.index("--report-output") + 1] = str(run / "candidate-contract.json")
    provenance = {"baseline_run": str(baseline), "candidate_jar_sha256": sha256(jar),
                  "baseline_jar_sha256": previous["jar_sha256"]["candidate"],
                  "fused_sha256": previous["fused_sha256"], "municipalities_sha256": previous["municipalities_sha256"],
                  "input_provenance": previous["input_provenance"], "sources": previous["sources"],
                  "heap": "3g", "activation_allowed": False, "cleanup_performed": False}
    write_json(run / "provenance.json", provenance)
    write_json(run / "headroom-comparison.json", {"pass": False, "status": "RUNNING", "activation_allowed": False})
    try:
        if "-Xmx3g" not in command or command[command.index("--run-mode") + 1] != "full":
            raise ValueError("Expected unchanged FULL / -Xmx3g configuration")
        provenance["execution"] = execute("candidate", command, root, run)
        write_json(run / "provenance.json", provenance)
        if provenance["execution"]["exit_code"] != 0:
            raise RuntimeError("Full build failed; artifacts retained")
        if (sha256(fused) != provenance["fused_sha256"] or sha256(geo) != provenance["municipalities_sha256"]
                or sha256(jar) != provenance["candidate_jar_sha256"]):
            raise RuntimeError("Input/JAR changed during rebuild")
        provenance["inputs_unchanged_after_run"] = True
        write_json(run / "provenance.json", provenance)
        result = compare_databases(baseline, run, provenance)
        print(json.dumps({key: result[key] for key in ("pass", "failed_checks", "route_axis_headroom_status", "overall_headroom_status")}))
        return 0 if result["pass"] else 1
    except Exception as exc:
        write_json(run / "headroom-comparison.json", {"pass": False, "status": "ERROR", "error": str(exc), "activation_allowed": False})
        print(f"Headroom audit failed without activation or cleanup: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
