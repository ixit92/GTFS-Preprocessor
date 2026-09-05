import tempfile
import unittest
from pathlib import Path

from run_route_axis_headroom_audit import memory_evidence


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

    def test_missing_samples_are_unknown_not_zero(self):
        with tempfile.TemporaryDirectory(dir=self.parent) as directory:
            log = Path(directory) / "candidate.log"
            log.write_text("not a memory sample\n", encoding="utf-8")
            result = memory_evidence(log)
            self.assertIsNone(result["max_sampled_heap_mib"])
            self.assertIsNone(result["route_axis_max_sampled_heap_mib"])


if __name__ == "__main__":
    unittest.main()
