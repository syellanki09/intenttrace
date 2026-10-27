import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import test_verify_results as fixtures
from bundle_results import bundle


class BundleResultsTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.VerifyResultsTest()
        fixture.setUp()
        self.records = fixture.records
        for record in self.records:
            record["reproduction"].update({
                "sourceCommitSha": "a" * 40, "configuration": {"sourceDirty": "false"},
                "runtime": "test-runtime", "jdk": "17", "operatingSystem": "test-os",
            })
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.source = Path(self.directory.name) / "results.jsonl"
        self.output = Path(self.directory.name) / "bundle.zip"

    def write(self):
        self.source.write_text("\n".join(json.dumps(record) for record in self.records) + "\n", encoding="utf-8")

    def test_bundle_preserves_raw_bytes_manifest_and_reproduction_instructions(self):
        self.write()
        manifest = bundle(self.source, self.output)
        first = self.output.read_bytes()
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual({"results.jsonl", "manifest.json", "README.txt"}, set(archive.namelist()))
            self.assertEqual(self.source.read_bytes(), archive.read("results.jsonl"))
            self.assertEqual(manifest, json.loads(archive.read("manifest.json")))
            self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(),
                             manifest["files"]["results.jsonl"]["sha256"])
            self.assertIn("a" * 40, archive.read("README.txt").decode())
        bundle(self.source, self.output)
        self.assertEqual(first, self.output.read_bytes())

    def test_dirty_and_caller_supplied_sources_are_rejected(self):
        for configuration in [{"sourceDirty": "true"}, {"sourceCommitOrigin": "caller-supplied"}]:
            for record in self.records:
                record["reproduction"]["configuration"] = configuration
            self.write()
            with self.assertRaises(ValueError):
                bundle(self.source, self.output)
            self.assertFalse(self.output.exists())

    def test_invalid_commit_and_corrupt_results_are_rejected(self):
        for record in self.records:
            record["reproduction"]["sourceCommitSha"] = "invalid"
        self.write()
        with self.assertRaises(ValueError):
            bundle(self.source, self.output)
        self.records[1]["evaluation"]["detected"] = True
        self.write()
        with self.assertRaises(ValueError):
            bundle(self.source, self.output)

    def test_output_cannot_overwrite_source(self):
        self.write()
        original = self.source.read_bytes()
        with self.assertRaises(ValueError):
            bundle(self.source, self.source)
        self.assertEqual(original, self.source.read_bytes())


if __name__ == "__main__":
    unittest.main()
