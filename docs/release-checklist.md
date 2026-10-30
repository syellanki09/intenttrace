# Research Preview Release Checklist

This is preparation for a future preview, not a release announcement. The current build remains `0.1.0-SNAPSHOT`. A maintainer chooses the version, release date, and publication timing after reviewing the evidence.

## Candidate and Metadata

- Select a reviewed source commit on main and record its full SHA. Avoid a temporary pull-request merge commit as the release candidate.
- Confirm the candidate's CI tests, scenario execution, JSONL verification, bundle verification, and artifact upload succeeded.
- Review [Unreleased changes](../CHANGELOG.md) and move them to a version/date section only when those values are settled.
- Update the root Gradle version and `CITATION.cff` version/date together in a reviewed metadata change. Preserve the author and license. Do not invent an ORCID, DOI, or external adoption claim.
- Keep scenario/configuration/schema versions separate from the software release version. They describe different contracts.
- Verify the metadata change itself passes CI before selecting the final release commit.

## Verify the Exact Source

Use a fresh checkout of the final candidate, with a clean working tree, JDK 17, and Python 3.9+. Follow the [reproduction guide](reproduction.md), then run:

```bash
git rev-parse HEAD
git status --short
./gradlew clean test :benchmarks:run --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_results.py benchmarks/build/results/stale-context/results.jsonl
python3 tools/bundle_results.py benchmarks/build/results/stale-context/results.jsonl --output benchmarks/build/results/stale-context-bundle.zip
python3 tools/verify_bundle.py benchmarks/build/results/stale-context-bundle.zip
```

- Confirm the full recorded source SHA matches the final candidate and `sourceDirty` is false.
- Confirm the control uses the latest eligible context and satisfies freshness, while injection uses the older context and violates freshness. Detection remains null in both paths.
- Confirm the bundled replay config reproduces the intended inputs, including custom configuration if used. Save a second output separately when comparing runs; the default runner replaces its output file.
- Retain build logs, runtime details, raw JSONL, bundle, and workflow run URL. CI artifact retention is 30 days, not permanent archival storage.

## Publication and Citation

- After final review, a maintainer creates the chosen tag at the verified final commit. Do not move a published tag to a different commit.
- Publish release notes describing the synthetic scenario, reproduction command, supported runtime, evidence, and limitations. Attach the verified bundle from that exact source.
- If creating an archival deposit, upload the actual release artifact and metadata. Record an identifier only after the archive confirms it; do not imply a DOI exists before that point.
- Check the public tag resolves to the intended source, the attached bundle verifies, and the citation metadata names the same software version and actual release date.
- If citation identifiers need a later source change, document that follow-up without silently retagging the released source.

## Acceptance Boundary

The preview demonstrates a reproducible synthetic stale-context integrity violation. It does not establish production prevalence, business impact, detection success, external adoption, or completion of the other failure classes. Require an independent clean-checkout reproduction before describing the preview as independently reproduced.
