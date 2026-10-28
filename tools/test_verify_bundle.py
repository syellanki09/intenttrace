import hashlib
import json
import unittest
import warnings
import zipfile

import test_bundle_results as fixtures
from bundle_results import bundle
from verify_bundle import verify_bundle


class VerifyBundleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.BundleResultsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.write()
        self.manifest = bundle(self.fixture.source, self.fixture.output)

    def rewrite(self, change):
        with zipfile.ZipFile(self.fixture.output) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        change(files)
        with zipfile.ZipFile(self.fixture.output, "w") as archive:
            for name, content in files.items():
                archive.writestr(name, content)

    def test_valid_bundle(self):
        self.assertEqual(self.manifest, verify_bundle(self.fixture.output))

    def test_tampered_raw_evidence_is_rejected(self):
        self.rewrite(lambda files: files.update({"results.jsonl": files["results.jsonl"] + b" "}))
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)

    def test_extra_and_duplicate_entries_are_rejected(self):
        self.rewrite(lambda files: files.update({"../unexpected": b"data"}))
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)
        bundle(self.fixture.source, self.fixture.output)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.fixture.output, "a") as archive:
                archive.writestr("results.jsonl", b"duplicate")
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)

    def test_changed_metadata_is_rejected(self):
        def change(files):
            manifest = json.loads(files["manifest.json"])
            manifest["sourceCommitSha"] = "b" * 40
            files["manifest.json"] = json.dumps(manifest).encode()
        self.rewrite(change)
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)

    def test_invalid_results_fail_even_if_checksum_is_recomputed(self):
        def change(files):
            records = [json.loads(line) for line in files["results.jsonl"].splitlines()]
            records[1]["evaluation"]["detected"] = True
            raw = ("\n".join(json.dumps(record) for record in records) + "\n").encode()
            manifest = json.loads(files["manifest.json"])
            manifest["files"]["results.jsonl"] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
            files.update({"results.jsonl": raw, "manifest.json": json.dumps(manifest).encode()})
        self.rewrite(change)
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)

    def test_versioned_replay_must_match_recorded_configuration(self):
        self.fixture.configure_versioned()
        self.fixture.write()
        bundle(self.fixture.source, self.fixture.output)
        verify_bundle(self.fixture.output)
        def change(files):
            replay = json.loads(files["scenario-config.json"])
            replay["olderCategory"] = "changed"
            raw = json.dumps(replay).encode()
            manifest = json.loads(files["manifest.json"])
            manifest["files"]["scenario-config.json"] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
            files.update({"scenario-config.json": raw, "manifest.json": json.dumps(manifest).encode()})
        self.rewrite(change)
        with self.assertRaises(ValueError):
            verify_bundle(self.fixture.output)


if __name__ == "__main__":
    unittest.main()
