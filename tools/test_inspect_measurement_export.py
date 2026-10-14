import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from inspect_measurement_export import inspect_archive


class ExportInspectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = Path(__file__).resolve().parents[1] / "work" / "selected-capture-evidence-fixture.zip"
        if not cls.original.is_file():
            raise RuntimeError("Generate the Kotlin CalibrationEvidenceExportTest integration fixture first")

    def archive(self, transform):
        with zipfile.ZipFile(self.original) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        transform(entries)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "capture.zip"
        with zipfile.ZipFile(path, "w") as destination:
            for name, data in entries.items():
                destination.writestr(name, data)
        return path

    def reindex(self, entries):
        index = json.loads(entries["evidence-manifest.json"])
        index["entries"] = [{"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                            for name, data in entries.items() if name != "evidence-manifest.json"]
        entries["evidence-manifest.json"] = json.dumps(index).encode()

    def test_actual_kotlin_export_round_trip_keeps_zero_and_never_approves_hardware(self):
        report = inspect_archive(self.original)
        self.assertTrue(report["integrityVerified"])
        self.assertEqual(0, report["reviewedReps"])
        self.assertEqual(6, report["sensor"]["sampleCount"])
        self.assertEqual(2, report["sensor"]["formatVersion"])
        self.assertFalse(report["hardwareAcceptanceVerified"])
        self.assertFalse(report["accuracyImprovementVerified"])
        self.assertIn("clock_alignment_windows_missing", report["notVerified"])

    def test_changed_bytes_or_capture_identity_refuse_integrity(self):
        path = self.archive(lambda entries: entries.__setitem__("watch.kcs", entries["watch.kcs"] + b"x"))
        with self.assertRaisesRegex(ValueError, "changed"):
            inspect_archive(path)

        def change_identity(entries):
            review = json.loads(entries["review.json"])
            review["captureId"] = "other-capture"
            entries["review.json"] = json.dumps(review).encode()
            self.reindex(entries)
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            inspect_archive(self.archive(change_identity))

    def test_missing_count_is_not_zero_and_camera_written_field_uses_app_codec_name(self):
        def change_metadata(entries):
            review = json.loads(entries["review.json"])
            review["count"] = None
            entries["review.json"] = json.dumps(review).encode()
            entries["camera-frames.jsonl"] = b'{"cameraNs":1000,"videoFrameWritten":true}\n'
            self.reindex(entries)
        report = inspect_archive(self.archive(change_metadata))
        self.assertIsNone(report["reviewedReps"])
        self.assertEqual(1, report["framesDeclaredWritten"])
        self.assertIn("count_not_reviewed", report["notVerified"])

    def test_unknown_files_and_forged_hardware_boolean_do_not_pass_as_acceptance(self):
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            inspect_archive(self.archive(lambda entries: entries.__setitem__("account.json", b"{}")))

        def forged_flag(entries):
            index = json.loads(entries["evidence-manifest.json"])
            index["deviceAcceptanceVerified"] = True
            entries["evidence-manifest.json"] = json.dumps(index).encode()
        self.assertFalse(inspect_archive(self.archive(forged_flag))["hardwareAcceptanceVerified"])


if __name__ == "__main__":
    unittest.main()
