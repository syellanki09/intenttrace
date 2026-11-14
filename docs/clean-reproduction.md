# Cold-Cache Preview Reproduction

The **Clean Preview Reproduction** workflow verifies that a reviewed source revision can build and run without restored Gradle dependency or wrapper caches. It prepares evidence for preview review; it does not publish a release or establish independent external adoption.

## Run a Candidate

After this workflow is merged, open the repository's Actions tab, select **Clean Preview Reproduction**, choose **Run workflow**, and select the branch or tag to test. The selected revision must contain the workflow. Before publication, compare the workflow's checked-out source identity against the intended final candidate.

The workflow also runs when its own definition or this guide changes in a PR, and again on main after such a merge. Ordinary feature CI remains cached for speed; candidate cold-cache validation can be dispatched when needed.

## What It Checks

- A clean Git checkout and an initially absent, isolated `GRADLE_USER_HOME`.
- JDK 17 build, the full Kotlin test suite, and all four demonstrations.
- Python regression tests, independent verification of all four JSONL formats, stale-context bundle verification, and a combined 12-case suite pack.
- Evidence generation from the exact checked-out commit, without writing build logs into the working tree before metadata capture.

There is deliberately no Gradle setup/cache-restoration action in this workflow. Wrapper distributions and dependencies must resolve into the fresh temporary Gradle home. This does not imply a network-free or fully hermetic build: it uses the hosted Ubuntu image, an available JDK, and public dependency services. Failures to download dependencies are reported as failures rather than evidence that the scenario ran.

## Review the Evidence

A successful run retains `clean-preview-<workflow-sha>` for 30 days, containing `suite.zip`, `suite-report.json`, and `source-commit.txt`. The suite ZIP contains a nested five-case preview pack, replay and event-time evidence, the build log, and reproduction instructions. The source recorded in the report and source text must match. Verify the entire archive with `python3 tools/verify_suite.py /path/to/suite.zip`. For a PR run, this may be GitHub's temporary tested merge commit; do not confuse that with a final main-branch release candidate.

Failure diagnostics are retained separately for seven days when available; they are not labeled successful reproduction evidence. Inspect the job log if failure occurred before diagnostics could be created.

The workflow uses the same `python3 tools/run_suite.py` command as the [suite walkthrough](scenario-suite.md), so all 12 cases receive independent verification here. No tag, citation date, DOI, or permanent archival artifact is created by this workflow.
