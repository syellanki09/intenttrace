import io
import subprocess
import shutil
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

import test_suite as fixtures
from run_suite import main, run_logged, run_suite
from verify_suite import verify_suite


class SuiteExecutionTest(unittest.TestCase):
    def test_package_only_verifies_existing_outputs_without_running_commands(self):
        fixture = fixtures.SuiteTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.collect()
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            results = Path(directory) / "benchmarks/build/results"
            for source, name in [(fixture.preview.stale.source, "stale-context"),
                                 (fixture.preview.contract_path, "contract-unit-drift"),
                                 (fixture.replay.path, "duplicate-replay"), (fixture.events.path, "event-time-ordering"),
                                 (fixture.missing.path, "missing-context"),
                                 (fixture.conflicting.path, "conflicting-values")]:
                target = results / name / "results.jsonl"
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, "", "a" * 40, ""]), \
                 patch("run_suite.run_logged") as execute:
                destination = run_suite(directory, package_only=True, release_assets=Path("build/release-assets"))
                execute.assert_not_called()
            self.assertEqual(3, verify_suite(destination / "suite.zip")["suiteReportVersion"])
            assets = Path(directory) / "build/release-assets"
            self.assertEqual({"suite.zip", "suite-report.json", "manifest.json", "SHA256SUMS"},
                             {path.name for path in assets.iterdir()})
            self.assertEqual((destination / "suite.zip").read_bytes(), (assets / "suite.zip").read_bytes())
            self.assertEqual("a" * 40, verify_suite(assets / "suite.zip")["sourceCommitSha"])

    def test_existing_release_assets_are_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory) / "build/release-assets"
            assets.mkdir(parents=True)
            marker = assets / "keep.txt"
            marker.write_text("previous evidence", encoding="utf-8")
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, ""]), \
                 patch("run_suite.run_logged") as execute:
                with self.assertRaises(FileExistsError):
                    run_suite(directory, release_assets=Path("build/release-assets"))
                execute.assert_not_called()
            self.assertEqual("previous evidence", marker.read_text())

    def test_identical_evidence_produces_identical_manifest_and_archive(self):
        fixture = fixtures.SuiteTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.collect()
        manifest = (fixture.destination / "manifest.json").read_bytes()
        archive = fixture.archive.read_bytes()
        fixture.collect()
        self.assertEqual(manifest, (fixture.destination / "manifest.json").read_bytes())
        self.assertEqual(archive, fixture.archive.read_bytes())
        with zipfile.ZipFile(fixture.archive) as packed:
            self.assertEqual(sorted(packed.namelist()), packed.namelist())
            self.assertTrue(all(row.date_time == (1980, 1, 1, 0, 0, 0) for row in packed.infolist()))

    def test_live_output_and_failed_scenario_are_visible_and_logged(self):
        process = Mock(stdout=io.StringIO("diagnostic from the scenario\n"))
        process.wait.return_value = 7
        process.poll.return_value = 7
        stdout, stderr, log = io.StringIO(), io.StringIO(), io.StringIO()
        with patch("run_suite.subprocess.Popen", return_value=process), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            with self.assertRaises(subprocess.CalledProcessError) as error:
                run_logged(["gradle", ":benchmarks:runReplayScenario"], Path.cwd(), log, "duplicate-replay")
        self.assertEqual(7, error.exception.returncode)
        self.assertIn("diagnostic from the scenario", stdout.getvalue())
        self.assertIn("duplicate-replay failed", stderr.getvalue())
        self.assertIn("diagnostic from the scenario", log.getvalue())
        process.terminate.assert_not_called()

    def test_failed_scenario_stops_before_remaining_tasks_and_packaging(self):
        failure = subprocess.CalledProcessError(1, "gradle", stderr="contract-unit-drift failed")
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, ""]), \
                 patch("run_suite.run_logged", side_effect=[None, None, failure]) as execute, \
                 patch("run_suite.verify_scenario"), patch("run_suite.collect_suite") as collect:
                with self.assertRaises(subprocess.CalledProcessError):
                    run_suite(directory)
                self.assertEqual(3, execute.call_count)
                self.assertEqual("contract-unit-drift", execute.call_args.args[-1])
                collect.assert_not_called()
            self.assertEqual([], list(Path(directory).glob("build/suite/*/suite.zip")))

    def test_cli_exits_nonzero_and_names_failed_scenario(self):
        stderr = io.StringIO()
        error = subprocess.CalledProcessError(1, "gradle", stderr="event-time-ordering failed")
        with patch("sys.argv", ["run_suite.py"]), patch("run_suite.run_suite", side_effect=error), redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as exit_result:
                main()
        self.assertEqual(1, exit_result.exception.code)
        self.assertIn("event-time-ordering failed", stderr.getvalue())
