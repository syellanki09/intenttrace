# Maintainer Setup for Library Publication and Archival

These one-time steps belong to the repository owner. Nothing in the repository
performs them, and none of them publishes anything by itself. Maven Central
publication and Zenodo archival are independent: either can proceed without the
other.

## Maven Central

The detector libraries publish as:

| Artifact | Coordinates |
| --- | --- |
| Detector core | `io.github.syellanki09:intenttrace-detector` |
| OpenTelemetry adapter | `io.github.syellanki09:intenttrace-detector-otel` |

Kotlin packages remain `io.intenttrace.*`; only the Maven group changes.

1. Sign in at [central.sonatype.com](https://central.sonatype.com) with the
   GitHub account `syellanki09`. Signing in with GitHub verifies the
   `io.github.syellanki09` namespace automatically. Confirm it shows as verified
   under **Namespaces**.
2. Under **View Account > Generate User Token**, create a publishing token. It
   has a username part and a password part. Keep both; the portal shows them once.
3. Create a signing key on your own computer and publish its public half so
   Central can verify signatures:

   ```bash
   gpg --quick-generate-key "Srinivas Kalyan Yellanki <your-public-email>" ed25519 sign 2y
   gpg --list-secret-keys --keyid-format long
   gpg --keyserver keys.openpgp.org --send-keys KEY_ID
   gpg --armor --export-secret-keys KEY_ID > intenttrace-signing.asc
   ```

   keys.openpgp.org emails a link to confirm the address on the key; confirm it
   so the key is searchable. Store a revocation certificate somewhere safe.
4. In the repository, open **Settings > Environments**, create an environment
   named `maven-central`, and optionally add yourself as a required reviewer so
   every release run waits for your approval. Add these environment secrets:

   | Secret | Value |
   | --- | --- |
   | `INTENTTRACE_SIGNING_KEY` | Full contents of `intenttrace-signing.asc` |
   | `INTENTTRACE_SIGNING_PASSWORD` | The key's passphrase |
   | `CENTRAL_TOKEN_USERNAME` | Token username from step 2 |
   | `CENTRAL_TOKEN_PASSWORD` | Token password from step 2 |

   Then delete the exported `intenttrace-signing.asc` file.

## Zenodo

1. Sign in at [zenodo.org](https://zenodo.org) with GitHub.
2. Open **GitHub** in the account menu, sync, and switch on
   `syellanki09/intenttrace`.
3. From then on, each published GitHub release creates a Zenodo version and
   DOI. Draft releases and tags alone do not. After publishing preview 2,
   confirm on Zenodo that the deposit appeared before relying on it.
4. After Zenodo mints the DOI, record it without retagging:

   ```bash
   python3 tools/release_metadata.py doi 10.5281/zenodo.NNNNNNN
   python3 tools/release_metadata.py check
   ```

   Commit that metadata change through a normal pull request. CITATION.cff
   already lets people cite the software; the DOI adds a persistent archival
   identifier.

Zenodo copies the release's source archive; review its generated record
(authors, title, license, keywords) after the first deposit and correct it there.

## Release Day

1. Set the version and the actual date together, in one reviewed change:

   ```bash
   python3 tools/release_metadata.py set --version 0.1.0-preview.2 --date YYYY-MM-DD
   python3 tools/release_metadata.py check
   ```

   The tool refuses future dates, an existing version, and an empty Unreleased
   changelog section. It updates Gradle, CITATION.cff and CHANGELOG.md together,
   and removes any DOI recorded for the previous release, since that DOI does
   not identify the new one.
2. Merge that change, confirm CI passes on the merge commit, and tag exactly
   that commit: `git tag -a v0.1.0-preview.2 SHA -m "IntentTrace 0.1.0-preview.2"`.
3. Run **Actions > Release artifacts** with the tag. Leave **upload** off first:
   the run validates the tag commit, reruns the full suite, signs and checks the
   artifacts, builds the outside consumer example from them, and retains the
   bundle and release attachments for 30 days.
4. Run it again with **upload** on. The bundle reaches the Central Portal as a
   deployment that is validated but unpublished. Review it under
   **Deployments**, then press **Publish**. Publication to Maven Central cannot
   be undone.
5. Publish the GitHub prerelease with the reviewed notes and the attachments
   from the same run. With Zenodo switched on, this creates the DOI.
