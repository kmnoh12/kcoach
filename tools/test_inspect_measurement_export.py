import hashlib
import base64
import json
from pathlib import Path
import tempfile
import struct
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

    def sensor_archive(self, records, version=2):
        """Short synthetic KCS in the app's archive envelope; never hardware acceptance evidence."""
        def replace(entries):
            raw = struct.pack(">4siid", b"KCS1", version, len(records), 50.0)
            for i, values in enumerate(records):
                timestamp = values.get("timestamp", 1_000_000_000 + i * 20_000_000)
                raw += struct.pack(">q6fii", timestamp, 0, 0, 9.8, 0, 0, 0, values.get("gyro_accuracy", 3), -1)
                if version == 2:
                    raw += struct.pack(">qqqiq", values.get("accel", timestamp), values.get("gyro", timestamp),
                                       values.get("received", timestamp + 1_000_000), values.get("accel_accuracy", 3), -1)
            entries["watch.kcs"] = raw
            fields = entries["watch.manifest"].decode().split(".")
            payload = dict(item.split("=", 1) for item in fields[11].split(","))
            def encoded(value):
                return base64.urlsafe_b64encode(str(value).encode()).decode().rstrip("=")
            payload.update(sha256=encoded(hashlib.sha256(raw).hexdigest()), sampleCount=encoded(len(records)),
                           formatVersion=encoded(version))
            fields[11] = ",".join(key + "=" + value for key, value in payload.items())
            entries["watch.manifest"] = ".".join(fields).encode()
            self.reindex(entries)
        return self.archive(replace)

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

    def test_join_age_boundary_future_and_missing_acceleration_are_distinct(self):
        report = inspect_archive(self.sensor_archive([
            {"accel": 960_000_000},  # exactly 40 ms, still fresh
            {"accel": 979_999_999},  # 40 ms + 1 ns, stale
            {"accel": 1_040_000_001},  # future by 1 ns
            {"accel": -1},
        ]))
        sensor = report["sensor"]
        joined = sensor["accelerationJoin"]
        self.assertEqual(3, joined["ageObservedSamples"])
        self.assertEqual(1, joined["staleSamples"])
        self.assertEqual(1, joined["futureAccelerationSamples"])
        self.assertEqual(1, joined["missingAccelerationClockSamples"])
        self.assertAlmostEqual(-0.000001, joined["minimumAgeMs"])
        self.assertAlmostEqual(40.000001, joined["maximumAgeMs"])
        self.assertFalse(sensor["eligibleForSensorMetadataReview"])
        self.assertTrue(report["integrityVerified"])

    def test_sensor_accuracy_unknown_unreliable_low_and_good_are_reported_separately(self):
        report = inspect_archive(self.sensor_archive([
            {"gyro_accuracy": -1, "accel_accuracy": 0},
            {"gyro_accuracy": 0, "accel_accuracy": -1},
            {"gyro_accuracy": 1, "accel_accuracy": 1},
            {"gyro_accuracy": 2, "accel_accuracy": 2},
            {"gyro_accuracy": 3, "accel_accuracy": 3},
            {"gyro_accuracy": 9, "accel_accuracy": -2},
        ]))
        sensor = report["sensor"]
        for field in ["gyroscopeAccuracy", "accelerationAccuracy"]:
            self.assertEqual({"unknownSamples": 1, "unreliableSamples": 1, "lowAccuracySamples": 1,
                              "mediumAccuracySamples": 1, "highAccuracySamples": 1,
                              "unrecognizedAccuracySamples": 1}, sensor[field])
        self.assertIn("acceleration_accuracy_unrecognized", sensor["sensorMetadataIssues"])
        self.assertFalse(sensor["eligibleForSensorMetadataReview"])
        self.assertFalse(report["hardwareAcceptanceVerified"])

    def test_legacy_metadata_stays_unknown_and_join_age_is_not_filled_with_zero(self):
        report = inspect_archive(self.sensor_archive([{}, {}], version=1))
        sensor = report["sensor"]
        self.assertIsNone(sensor["accelerationJoin"]["minimumAgeMs"])
        self.assertIsNone(sensor["accelerationJoin"]["maximumAgeMs"])
        self.assertEqual(0, sensor["accelerationJoin"]["ageObservedSamples"])
        self.assertEqual(2, sensor["accelerationAccuracy"]["unknownSamples"])
        self.assertIn("legacy_original_metadata_unavailable", sensor["sensorMetadataIssues"])
        self.assertEqual(0, report["reviewedReps"])  # explicit human zero remains explicit
        self.assertFalse(sensor["eligibleForSensorMetadataReview"])

    def test_known_good_metadata_is_reviewable_but_does_not_approve_an_engine(self):
        report = inspect_archive(self.sensor_archive([{}, {}]))
        self.assertTrue(report["sensor"]["eligibleForSensorMetadataReview"])
        self.assertEqual([], report["sensor"]["sensorMetadataIssues"])
        self.assertFalse(report["hardwareAcceptanceVerified"])
        self.assertFalse(report["accuracyImprovementVerified"])

    def test_missing_gyro_and_early_callback_are_not_normal_join_metadata(self):
        sensor = inspect_archive(self.sensor_archive([{"gyro": -1, "received": 999_999_999}]))["sensor"]
        self.assertIsNone(sensor["accelerationJoin"]["maximumAgeMs"])
        self.assertEqual(1, sensor["accelerationJoin"]["missingGyroscopeClockSamples"])
        self.assertIn("callback_before_sample", sensor["sensorMetadataIssues"])

    def test_structurally_corrupt_clock_or_sensor_samples_still_refuse_integrity(self):
        for record in [{"gyro": 1_000_000_001}, {"accel": -2}, {"timestamp": -1}]:
            with self.subTest(record=record), self.assertRaises(ValueError):
                inspect_archive(self.sensor_archive([record]))
        with self.assertRaisesRegex(ValueError, "non-increasing"):
            inspect_archive(self.sensor_archive([{}, {"timestamp": 1_000_000_000}]))


if __name__ == "__main__":
    unittest.main()
