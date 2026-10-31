import unittest

import test_bundle_results as fixtures
from verify_results import verify


class VerifyTimestampsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.BundleResultsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.configure_versioned()

    def times(self, older, latest, boundary):
        for record in self.fixture.records:
            config = record["reproduction"]["configuration"]
            config.update({"olderCommittedAt": older, "latestCommittedAt": latest, "decidedAt": boundary})
            record["decidedAt"] = boundary
            record["usedContext"]["committedAt"] = latest if record["execution"] == "CONTROL" else older
            record["latestEligibleContext"]["committedAt"] = latest
        self.fixture.write()

    def test_matching_metadata_cannot_make_future_or_reversed_history_valid(self):
        for older, latest, boundary in [
            ("2026-01-01T00:00:01Z", "2026-01-01T00:00:04Z", "2026-01-01T00:00:03Z"),
            ("2026-01-01T00:00:03Z", "2026-01-01T00:00:02Z", "2026-01-01T00:00:04Z"),
        ]:
            self.times(older, latest, boundary)
            with self.assertRaises(ValueError):
                verify(self.fixture.source)

    def test_nanosecond_boundary_is_not_rounded_to_microseconds(self):
        self.times("2026-01-01T00:00:01Z", "2026-01-01T00:00:03.000000002Z", "2026-01-01T00:00:03.000000001Z")
        with self.assertRaises(ValueError):
            verify(self.fixture.source)
        self.times("2026-01-01T00:00:01Z", "2026-01-01T00:00:03.000000001Z", "2026-01-01T00:00:03.000000002Z")
        verify(self.fixture.source)

    def test_inclusive_boundaries_and_extended_years_are_supported(self):
        for timestamp in ["2024-02-29T00:00:00Z", "+10000-01-01T00:00:00Z", "0000-01-01T00:00:00Z"]:
            self.times(timestamp, timestamp, timestamp)
            verify(self.fixture.source)

    def test_invalid_calendar_dates_times_and_formats_are_rejected(self):
        for timestamp in ["2026-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-01-01T24:00:00Z",
                          "2026-01-01T00:00:00.1234567890Z", "not-a-time", None]:
            self.times(timestamp, timestamp, timestamp)
            with self.assertRaises(ValueError):
                verify(self.fixture.source)


if __name__ == "__main__":
    unittest.main()
