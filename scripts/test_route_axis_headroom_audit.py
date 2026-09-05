import tempfile
import unittest
import json
from pathlib import Path

from run_route_axis_headroom_audit import memory_evidence, completed_baseline


class HeadroomEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.parent = Path(__file__).resolve().parents[1] / "target" / "audit-test-temp"
        self.parent.mkdir(parents=True, exist_ok=True)

    def test_pre_gc_peaks_and_other_phases_are_not_hidden(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            log = Path(directory) / "candidate.log"
            log.write_text("section=stop_times memory_used_mb=2510\n"
                           "section=route_axis_sql_build_write memory_used_mb=1700\n"
                           "section=heap_guard phase=route_axis_scan memory_used_mb=2080 after_gc_mb=900\n"
                           "section=heap_phase_release phase=route_axes before_mb=2200 after_mb=300\n",
                           encoding="utf-8")
            result = memory_evidence(log)
            self.assertEqual(2510, result["max_sampled_heap_mib"])
            self.assertEqual(2200, result["route_axis_max_sampled_heap_mib"])
            self.assertEqual(1, result["route_axis_gc_guards"])
            self.assertIsNone(result["phase_max_sampled_heap_mib"]["search_tokens"])

    def test_completed_headroom_baseline_and_failure_gates(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            baseline = Path(directory)
            provenance = {"candidate_jar_sha256": "a" * 64, "inputs_unchanged_after_run": True,
                          "execution": {"exit_code": 0, "command": ["java", "-Xmx3g"]}}
            report = {"pass": True, "status": "PASS"}
            def save():
                (baseline / "provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
                (baseline / "headroom-comparison.json").write_text(json.dumps(report), encoding="utf-8")
            save()
            previous, command, jar_hash = completed_baseline(baseline, "headroom")
            self.assertEqual("a" * 64, jar_hash)
            command.append("changed")
            self.assertEqual(["java", "-Xmx3g"], previous["execution"]["command"])
            for key, bad in (("pass", False), ("status", "RUNNING")):
                original = report[key]
                report[key] = bad
                save()
                with self.assertRaises(ValueError):
                    completed_baseline(baseline, "headroom")
                report[key] = original
            provenance["inputs_unchanged_after_run"] = False
            save()
            with self.assertRaises(ValueError):
                completed_baseline(baseline, "headroom")
            provenance["inputs_unchanged_after_run"] = True
            provenance["execution"]["exit_code"] = 137
            save()
            with self.assertRaises(ValueError):
                completed_baseline(baseline, "headroom")

    def test_phase_samples_include_pre_collection_heap(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            log = Path(directory) / "candidate.log"
            log.write_text("section=stop_search_token_build_write memory_used_mb=1800\n"
                           "section=heap_guard phase=stop_search_tokens memory_used_mb=2190 after_gc_mb=900\n"
                           "section=heap_phase_release phase=display_name_quality_baseline before_mb=2222 after_mb=100\n"
                           "section=contract_validation memory_used_mb=750\n", encoding="utf-8")
            result = memory_evidence(log)
            self.assertEqual(2222, result["max_sampled_heap_mib"])
            self.assertEqual({"search_tokens": 2190, "display_quality": 2222, "contract_validation": 750},
                             result["phase_max_sampled_heap_mib"])

    def test_existing_walk_baselines_remain_supported(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            baseline = Path(directory)
            provenance = {"builds_pass": True, "jar_sha256": {"candidate": "b" * 64},
                          "executions": {"candidate": {"exit_code": 0, "command": ["java", "-Xmx3g"]}}}
            (baseline / "provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
            report = baseline / "walk-comparison.json"
            report.write_text(json.dumps({"pass": True}), encoding="utf-8")
            previous, command, jar_hash = completed_baseline(baseline, "walk")
            self.assertEqual("b" * 64, jar_hash)
            command.append("changed")
            self.assertEqual(["java", "-Xmx3g"], previous["executions"]["candidate"]["command"])
            report.write_text(json.dumps({"pass": False}), encoding="utf-8")
            with self.assertRaises(ValueError):
                completed_baseline(baseline, "walk")

    def test_missing_samples_are_unknown_not_zero(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            log = Path(directory) / "candidate.log"
            log.write_text("not a memory sample\n", encoding="utf-8")
            result = memory_evidence(log)
            self.assertIsNone(result["max_sampled_heap_mib"])
            self.assertIsNone(result["route_axis_max_sampled_heap_mib"])


if __name__ == "__main__":
    unittest.main()
