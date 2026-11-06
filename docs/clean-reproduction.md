# Cold-Cache Preview Reproduction

The **Clean Preview Reproduction** workflow verifies that a reviewed source revision can build and run without restored Gradle dependency or wrapper caches. It prepares evidence for preview review; it does not publish a release or establish independent external adoption.

## Run a Candidate

After this workflow is merged, open the repository's Actions tab, select **Clean Preview Reproduction**, choose **Run workflow**, and select the branch or tag to test. The selected revision must contain the workflow. Before publication, compare the workflow's checked-out source identity against the intended final candidate.

The workflow also runs when its own definition or this guide changes in a PR, and again on main after such a merge. Ordinary feature CI remains cached for speed; candidate cold-cache validation can be dispatched when needed.

## What It Checks

- A clean Git checkout and an initially absent, isolated `GRADLE_USER_HOME`.
- JDK 17 build, the full Kotlin test suite, and both existing demonstrations.
- Python regression tests, independent stale-context JSONL verification, packaging, and offline bundle verification.
- Evidence generation from the exact checked-out commit, without writing build logs into the working tree before metadata capture.

There is deliberately no Gradle setup/cache-restoration action in this workflow. Wrapper distributions and dependencies must resolve into the fresh temporary Gradle home. This does not imply a network-free or fully hermetic build: it uses the hosted Ubuntu image, an available JDK, and public dependency services. Failures to download dependencies are reported as failures rather than evidence that the scenario ran.

## Review the Evidence

A successful run retains `clean-preview-<workflow-sha>` for 30 days, containing the stale-context bundle, the full build log, and `source-commit.txt`. Verify the ZIP with the existing bundle verifier. The source recorded in the bundle and source text must match. For a PR run, this may be GitHub's temporary tested merge commit; do not confuse that with a final main-branch release candidate.

Failure diagnostics are retained separately for seven days when available; they are not labeled successful reproduction evidence. Inspect the job log if failure occurred before diagnostics could be created.

The contract demonstration is smoke-tested here, but this workflow's retained bundle remains the stale-context bundle. Contract-specific retained evidence is managed by the ordinary CI workflow when available at the tested revision. No tag, citation date, DOI, or permanent archival artifact is created by this workflow.
