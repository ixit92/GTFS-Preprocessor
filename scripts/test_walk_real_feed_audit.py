import json
import sqlite3
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import compare_walk_real_feed
from compare_walk_real_feed import compare_table
from run_walk_real_feed_audit import copy_verified, inside, sha256, zip_inventory


class WalkAuditTest(unittest.TestCase):
    def setUp(self):
        parent = Path(__file__).resolve().parents[1] / "target" / "audit-test-temp"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def database(self, definition):
        connection = sqlite3.connect(":memory:")
        self.addCleanup(connection.close)
        connection.execute("ATTACH DATABASE ':memory:' AS baseline")
        for schema in ("main", "baseline"):
            connection.execute(f"CREATE TABLE {schema}.stops ({definition})")
        return connection

    def test_primary_key_values_not_only_counts(self):
        connection = self.database("stop_id TEXT PRIMARY KEY, name TEXT")
        connection.execute("INSERT INTO main.stops VALUES ('A','Changed')")
        connection.execute("INSERT INTO baseline.stops VALUES ('A','Original')")
        connection.execute("PRAGMA query_only=ON")
        result = compare_table(connection, "stops")
        self.assertFalse(result["pass"])
        self.assertEqual(1, result["changed_rows_or_groups"])

    def test_missing_and_added_ids(self):
        connection = self.database("stop_id TEXT PRIMARY KEY, name TEXT")
        connection.execute("INSERT INTO main.stops VALUES ('B',NULL)")
        connection.execute("INSERT INTO baseline.stops VALUES ('A',NULL)")
        result = compare_table(connection, "stops")
        self.assertFalse(result["pass"])
        self.assertEqual(1, result["missing_keys"])
        self.assertEqual(1, result["added_keys"])

    def test_null_and_reordered_rows_equal(self):
        connection = self.database("stop_id TEXT PRIMARY KEY, name TEXT")
        connection.executemany("INSERT INTO main.stops VALUES (?,?)", [("A", None), ("B", "Str.")])
        connection.executemany("INSERT INTO baseline.stops VALUES (?,?)", [("B", "Str."), ("A", None)])
        self.assertTrue(compare_table(connection, "stops")["pass"])

    def test_duplicate_multiplicity_is_not_ignored(self):
        connection = self.database("token TEXT")
        connection.executemany("INSERT INTO main.stops VALUES (?)", [("a",), ("a",), ("b",)])
        connection.executemany("INSERT INTO baseline.stops VALUES (?)", [("a",), ("b",), ("b",)])
        self.assertFalse(compare_table(connection, "stops")["pass"])

    def test_empty_missing_table_cannot_pass(self):
        connection = self.database("stop_id TEXT")
        self.assertFalse(compare_table(connection, "missing")["pass"])

    def test_live_walk_time_tampering_is_rejected(self):
        connection = sqlite3.connect(":memory:")
        self.addCleanup(connection.close)
        connection.execute("""CREATE TABLE stop_footpaths (
            is_traversable INTEGER, walk_seconds INTEGER, transfer_buffer_seconds INTEGER,
            min_transfer_seconds INTEGER, gtfs_min_transfer_seconds INTEGER, source TEXT, distance_meters INTEGER)
        """)
        connection.execute("INSERT INTO stop_footpaths VALUES (1,180,60,300,300,'SAME_STOP_AREA_GEOMETRY',160)")
        self.assertEqual(0, compare_walk_real_feed.invalid_walk_components(connection))
        connection.execute("UPDATE stop_footpaths SET transfer_buffer_seconds=0")
        self.assertEqual(1, compare_walk_real_feed.invalid_walk_components(connection))
        connection.execute("UPDATE stop_footpaths SET transfer_buffer_seconds=60,min_transfer_seconds=240")
        self.assertEqual(1, compare_walk_real_feed.invalid_walk_components(connection))

    def test_copy_is_exact_and_never_overwrites(self):
        source = self.root / "source.zip"
        target = self.root / "copied.zip"
        source.write_bytes(b"immutable input")
        self.assertEqual(sha256(source), copy_verified(source, target, sha256(source)))
        self.assertEqual(source.read_bytes(), target.read_bytes())
        with self.assertRaises(FileExistsError):
            copy_verified(source, target)

    def test_wrong_source_hash_is_rejected_before_copy(self):
        source = self.root / "source.zip"
        target = self.root / "copied.zip"
        source.write_bytes(b"input")
        with self.assertRaises(ValueError):
            copy_verified(source, target, "0" * 64)
        self.assertFalse(target.exists())

    def test_raw_gtfs_zip_required(self):
        archive = self.root / "runtime.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("runtime.sqlite", b"transformed")
        with self.assertRaises(ValueError):
            zip_inventory(archive)

    def test_path_escape_rejected(self):
        with self.assertRaises(ValueError):
            inside(self.root, self.root / ".." / "outside")
        with self.assertRaises(ValueError):
            inside(self.root, self.root)
        self.assertEqual(self.root / "build", inside(self.root, self.root / "build"))

    def test_failed_audit_replaces_stale_pass_and_deletes_nothing(self):
        run = self.root / "build" / "failure-test"
        run.mkdir(parents=True)
        (run / "tmp").mkdir()
        for name in ("baseline.sqlite", "candidate.sqlite"):
            (run / name).write_bytes(b"artifact must remain")
        report = run / "walk-comparison.json"
        report.write_text('{"pass":true}', encoding="utf-8")
        arguments = ["compare", "--tool-root", str(self.root), "--run-directory", str(run)]
        with patch("sys.argv", arguments), patch.object(compare_walk_real_feed, "compare", side_effect=ValueError("deliberate audit failure")):
            self.assertEqual(1, compare_walk_real_feed.main())
        self.assertFalse(json.loads(report.read_text(encoding="utf-8"))["pass"])
        for name in ("baseline.sqlite", "candidate.sqlite"):
            self.assertEqual(b"artifact must remain", (run / name).read_bytes())


if __name__ == "__main__":
    unittest.main()
