import unittest
import zipfile

import test_bundle_results as fixtures
from bundle_results import bundle
from verify_bundle import verify_bundle
from verify_results import verify


class StrictEvidenceJsonTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.BundleResultsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.write()

    def test_boolean_and_float_versions_are_not_integers(self):
        for value in (True, 1.0):
            self.fixture.records[0]["schemaVersion"] = value
            self.fixture.write()
            with self.assertRaises(ValueError):
                verify(self.fixture.source)
        self.fixture.records[0]["schemaVersion"] = 1
        self.fixture.records[1]["usedContext"]["version"] = True
        self.fixture.write()
        with self.assertRaises(ValueError):
            verify(self.fixture.source)

    def test_duplicate_nested_keys_are_rejected_even_if_last_value_is_correct(self):
        raw = self.fixture.source.read_text()
        raw = raw.replace('"detected": null', '"detected": true, "detected": null')
        self.fixture.source.write_text(raw)
        with self.assertRaises(ValueError):
            verify(self.fixture.source)

    def test_nonstandard_numeric_constants_are_rejected(self):
        for constant in ("NaN", "Infinity", "-Infinity"):
            self.fixture.write()
            raw = self.fixture.source.read_text().replace('"scenarioVersion": "1"',
                f'"scenarioVersion": "1", "extra": {constant}')
            self.fixture.source.write_text(raw)
            with self.assertRaises(ValueError):
                verify(self.fixture.source)

    def test_bundle_manifest_is_parsed_with_the_same_strict_rules(self):
        for change in (lambda raw: raw.replace('"bundleVersion": 1', '"bundleVersion": true'),
                       lambda raw: raw.replace('"bundleVersion": 1', '"bundleVersion": 2, "bundleVersion": 1')):
            bundle(self.fixture.source, self.fixture.output)
            with zipfile.ZipFile(self.fixture.output) as archive:
                files = {name: archive.read(name) for name in archive.namelist()}
            files["manifest.json"] = change(files["manifest.json"].decode()).encode()
            with zipfile.ZipFile(self.fixture.output, "w") as archive:
                for name, content in files.items():
                    archive.writestr(name, content)
            with self.assertRaises(ValueError):
                verify_bundle(self.fixture.output)


if __name__ == "__main__":
    unittest.main()
