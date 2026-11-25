# Preview 2 Candidate Review

This checklist prepares `v0.1.0-preview.2` for a maintainer decision. It does not
authorize a tag, release, archival deposit, or external outreach.

## Verified Implementation Baseline

- Main implementation commit: `3ab594aafb3d6e0a5280cb6e24104e519c924ac2`
  (detector library, [PR 33](https://github.com/syellanki09/intenttrace/pull/33)),
  on top of `5a09a824e97d79b8b1bf13c96b7ae2abf4b1b075`
  ([PR 31](https://github.com/syellanki09/intenttrace/pull/31)).
- [Baseline CI](https://github.com/syellanki09/intenttrace/actions/runs/45051554399)
  succeeded for `3ab594a`. The cold-cache reproduction last ran for `5a09a82`
  ([run](https://github.com/syellanki09/intenttrace/actions/runs/44032733778));
  rerun it for the final SHA.
- Baseline checks do not validate later metadata or documentation commits.
- That baseline runs four families and 12 cases. The six-family, 19-case suite
  and the 16-case agreement run arrived later with the missing-context and
  conflicting-values scenarios, so validate those counts on the final source.

## Settle Metadata Before Final Validation

- [ ] Review the [draft release notes](v0.1.0-preview.2.md) against current main.
- [ ] Confirm the final version with the maintainer.
- [ ] Set the Gradle version, citation version, release URL, and actual publication
  date together in a reviewed metadata change. Preserve author and license.
- [ ] Move the appropriate Unreleased changelog entries to the confirmed version
  and date. Preserve the historical preview-1 description.
- [ ] Do not reuse preview-1's date, fabricate a future date, or add an unconfirmed
  DOI, ORCID, archival identifier, adoption claim, or external-validation claim.
- [ ] Use `python3 tools/release_metadata.py set --version VERSION --date YYYY-MM-DD`
  to make those changes together, then `python3 tools/release_metadata.py check`.
- [ ] Have the maintainer commit and review those changes before selecting the
  final release SHA. Keep that SHA distinct from the implementation baseline.

## Validate the Final Source

Use a clean checkout of the final reviewed source with JDK 17 and Python 3.9+:

```bash
git rev-parse HEAD
git status --short
python3 tools/run_suite.py
```

The command prints the unique output directory. Substitute its actual path below:

```bash
python3 tools/verify_suite.py /path/to/run/suite.zip
```

- [ ] Full Kotlin and Python suites pass; record actual test counts from the logs.
- [ ] Manifest source is the full final SHA and `sourceDirty` is the JSON boolean
  `false`. All six independent verifiers pass.
- [ ] Raw record counts are 2, 3, 3, 4, 3, and 4 in the manifest's scenario order.
- [ ] All expected statuses match the draft notes and all detection values are null.
- [ ] Run a second reproduction from that same clean source. Compare raw evidence,
  manifests, and logical results; allow environment metadata and logs to vary.
- [ ] Ordinary CI and the cold-cache workflow pass for the exact final SHA. Review
  their artifact manifests and retained logs; record the final run URLs.

## Prepare the Exact Attachments

The full validation command can prepare attachments directly:

```bash
python3 tools/run_suite.py --release-assets build/release-assets
```

Ordinary and cold-cache CI use the same export path and check `SHA256SUMS`.
Use a new directory for another local run. For a suite that was already produced,
use the standalone command below without rerunning the experiments.

Use the verified suite from the final-source run and require that exact full SHA:

```bash
python3 tools/prepare_release_assets.py /path/to/run/suite.zip \
  --source-commit FULL_FINAL_SHA --output /path/to/new/attachments
```

The tool independently verifies all 19 cases, requires complete suite report 3,
checks the source SHA, preserves the original ZIP and its exact manifest/report
bytes, and generates `SHA256SUMS`. It creates the new attachment directory only
after validation succeeds and refuses to overwrite an existing directory. It does
not change Git, assign a software version/date, create a tag, or publish anything.

From the resulting attachment directory, check the actual bytes:

```bash
shasum -a 256 -c SHA256SUMS
```

- [ ] Offline suite verification succeeds after copying the attachments.
- [ ] Source identity agrees across the manifest, suite report, raw evidence,
  final Git source, and selected CI execution.
- [ ] Inspect ZIP member names, report schema 2, manifest schema 1, raw record
  counts, and README instructions. Use the current verifier for indexed suites.
- [ ] Keep the artifact checksum file with those attachments; do not substitute
  earlier feature-branch or baseline evidence after the final commit changes.
- [ ] Preserve the reviewed evidence outside temporary CI retention. Retention of
  30 days is not permanent archival storage.

## Library Artifacts

One-time account, key and secret setup is in [maintainer setup](maintainer-setup.md).

- [ ] Run **Release artifacts** for the tag with upload off. It refuses a tag
  whose metadata disagrees, reruns the full suite, signs the artifacts, verifies
  every signature, and builds the outside consumer example from them.
- [ ] Inspect the retained bundle: two artifacts, each with jar, sources, javadoc,
  POM, and `.asc`, `.md5` and `.sha1` files; coordinates under
  `io.github.syellanki09` and the release version.
- [ ] With authorization, rerun with upload on and review the Central Portal
  deployment before pressing Publish. Maven Central publication is permanent.
- [ ] After publication, build `examples/detector-consumer` with no extra
  repository to confirm the public coordinates resolve.

## Archival

- [ ] Zenodo's GitHub integration is switched on for the repository before the
  GitHub release is published.
- [ ] After the deposit appears, record its DOI with
  `python3 tools/release_metadata.py doi DOI` in a reviewed change. Do not retag.

## Maintainer Publication Decision

- [ ] Obtain explicit authorization for tagging and publication.
- [ ] Tag the exact final verified SHA; never move an already published tag.
- [ ] Publish the approved notes and exact verified attachments as a prerelease.
- [ ] Verify the public tag, metadata, attachment hashes, and offline suite check.
- [ ] Consider archival work and independent human reproduction separately.
  Record an archival identifier only after a deposit is explicitly authorized
  and confirmed; do not describe CI as independent external reproduction.

Pending fields intentionally remain unset: final release commit, publication
date, final validation-run URLs, attachment checksums, and any archival identifier.
