"""Isolated, sequential real-feed rebuilds. Never activates or deletes artifacts."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inside(root, path):
    resolved = path.resolve()
    if resolved == root or not resolved.is_relative_to(root):
        raise ValueError(f"Path must stay below tool root: {path}")
    return resolved


def copy_verified(source, destination, expected=None):
    before = sha256(source)
    if expected is not None and before != expected:
        raise ValueError(f"Source hash mismatch: {source.name}")
    with source.open("rb") as src, destination.open("xb") as dst:
        shutil.copyfileobj(src, dst)
    copied = sha256(destination)
    if copied != before or sha256(source) != before:
        raise ValueError(f"Source changed while copying: {source.name}")
    return copied


def zip_inventory(path):
    with zipfile.ZipFile(path) as archive:
        names = [item.filename for item in archive.infolist() if not item.is_dir()]
        basenames = [Path(name).name.lower() for name in names]
        required = {"stops.txt", "routes.txt", "trips.txt", "stop_times.txt"}
        if not required.issubset(basenames):
            raise ValueError(f"Not a raw GTFS feed: {path.name}")
        return {"files": names, "has_pathways": "pathways.txt" in basenames,
                "has_levels": "levels.txt" in basenames,
                "uncompressed_bytes": sum(item.file_size for item in archive.infolist())}


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def execute(name, command, root, run):
    print(f"Starting {name}", flush=True)
    started = time.monotonic()
    timed = ["/usr/bin/time", "-v", "-o", str(run / f"{name}-resources.txt"), *command]
    temporary_environment = {"TMPDIR": str(run / "tmp"), "SQLITE_TMPDIR": str(run / "tmp")}
    with (run / f"{name}.log").open("x", encoding="utf-8") as log:
        result = subprocess.run(timed, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                env={**os.environ, **temporary_environment}, check=False)
    record = {"command": command, "exit_code": result.returncode,
              "temporary_environment": temporary_environment,
              "elapsed_seconds": round(time.monotonic() - started, 3)}
    write_json(run / f"{name}-execution.json", record)
    print(f"Finished {name}: exit={result.returncode}, seconds={record['elapsed_seconds']}", flush=True)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-root", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--baseline-jar", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cache-root", type=Path, required=True)
    parser.add_argument("--municipalities", type=Path, required=True)
    parser.add_argument("--municipalities-version", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.run_id):
        parser.error("Invalid run ID")
    root = args.tool_root.resolve(strict=True)
    run = inside(root, root / "build" / args.run_id)
    inputs = inside(root, root / "local-data" / "from-routing-cache" / args.run_id)
    if run.exists() or inputs.exists():
        parser.error("Run/input directory already exists; choose a new run ID")
    for jar in (args.baseline_jar, args.candidate_jar):
        if inside(root, jar).parent != root / "target" or not jar.is_file():
            parser.error("JARs must be separate files in tool-root/target")
    if args.baseline_jar.resolve() == args.candidate_jar.resolve():
        parser.error("Baseline and candidate must differ")
    if shutil.disk_usage(root).free < 90 * 1024**3:
        parser.error("At least 90 GiB free space required for two full rebuilds")
    run.mkdir(parents=True)
    inputs.mkdir(parents=True)
    (run / "tmp").mkdir()
    provenance = {"audit_version": "walk-real-feed-1", "run_id": args.run_id,
                  "generated_at": datetime.now(timezone.utc).isoformat(),
                  "input_provenance": "unchanged_copies_of_active_routing_raw_gtfs_cache",
                  "production_database_used_as_input": False, "activation_allowed": False,
                  "cleanup_performed": False, "heap": "3g", "sources": {}, "executions": {}}
    try:
        copy_verified(args.manifest, inputs / "active-manifest.json")
        manifest = json.loads((inputs / "active-manifest.json").read_text(encoding="utf-8"))
        provenance["active_data_version"] = manifest["dataVersion"]
        provenance["active_contract_version"] = manifest["contractVersion"]
        provenance["active_preprocessor_version"] = manifest["preprocessorVersion"]
        for source_id in ("DE_FULL", "CH"):
            source = manifest["sourceFeeds"][source_id]
            digest = source["sha256"]
            if not re.fullmatch(r"[a-f0-9]{64}", digest):
                raise ValueError(f"Invalid source digest: {source_id}")
            original = args.cache_root / source_id.lower() / f"{digest}.zip"
            copied = inputs / f"{source_id.lower()}.zip"
            copy_verified(original, copied, digest)
            provenance["sources"][source_id] = {"sha256": digest, "bytes": copied.stat().st_size,
                                                   **zip_inventory(copied)}
        geo = inputs / "municipalities.geojson"
        provenance["municipalities_sha256"] = copy_verified(args.municipalities, geo)
        provenance["municipalities_version"] = args.municipalities_version
        jars = {"baseline": args.baseline_jar.resolve(), "candidate": args.candidate_jar.resolve()}
        provenance["jar_sha256"] = {key: sha256(path) for key, path in jars.items()}
        write_json(run / "provenance.json", provenance)
        java = [str(args.java), "-Xmx3g", f"-Djava.io.tmpdir={run / 'tmp'}", "-jar"]
        fused = run / "de-ch-fused.zip"
        fusion = execute("fuse", [*java, str(jars["baseline"]), "fuse",
                         "--source", f"DE_FULL={inputs / 'de_full.zip'}",
                         "--source", f"CH={inputs / 'ch.zip'}", "--output", str(fused),
                         "--report-output", str(run / "fusion-report.json")], root, run)
        provenance["executions"]["fuse"] = fusion
        if fusion["exit_code"]:
            raise RuntimeError("Fusion failed; see fuse.log")
        provenance["fused_sha256"] = sha256(fused)
        provenance["fused_inventory"] = zip_inventory(fused)
        write_json(run / "provenance.json", provenance)
        for name in ("candidate", "baseline"):
            if sha256(jars[name]) != provenance["jar_sha256"][name]:
                raise RuntimeError("JAR changed after preparation")
            record = execute(name, [*java, str(jars[name]), "--input", str(fused),
                             "--output", str(run / f"{name}.sqlite"),
                             "--report-output", str(run / f"{name}-contract.json"),
                             "--municipalities-geojson", str(geo),
                             "--municipalities-version", args.municipalities_version,
                             "--run-mode", "full"], root, run)
            provenance["executions"][name] = record
            write_json(run / "provenance.json", provenance)
        if sha256(fused) != provenance["fused_sha256"]:
            raise RuntimeError("Fused input changed during rebuilds")
        for source_id, source in provenance["sources"].items():
            if sha256(inputs / f"{source_id.lower()}.zip") != source["sha256"]:
                raise RuntimeError("Copied input changed during rebuilds")
        provenance["inputs_unchanged_after_runs"] = True
        provenance["builds_pass"] = all(item["exit_code"] == 0 for item in provenance["executions"].values())
        write_json(run / "provenance.json", provenance)
        return 0 if provenance["builds_pass"] else 1
    except Exception as exc:
        provenance["builds_pass"] = False
        provenance["error"] = str(exc)
        write_json(run / "provenance.json", provenance)
        print(f"Audit failed without activation or cleanup: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
