---
name: create-release
description: Automate production release execution. Inspects Conventional Commits since the latest stable tag, recommends SemVer bump, updates CHANGELOG.md, commits, tags, pushes, and monitors release CI.
---

# Create Release Skill

Automate the full end-to-end production release workflow for the Arrivals Android application:
1. Identify the latest stable release tag, ignoring ephemeral nightly builds.
2. Inspect Conventional Commits between base tag and HEAD to compute SemVer bump (`major`, `minor`, `patch`).
3. Generate Keep a Changelog formatted release notes (`Added`, `Changed`, `Fixed`, `Removed`).
4. Present proposed version and notes to the user for explicit confirmation.
5. Update `CHANGELOG.md`, create release commit (`chore(release): prepare vX.Y.Z`), cut annotated git tag (`vX.Y.Z`).
6. Push commit and tag to remote, watch triggered GitHub Actions release pipeline, and output final release URL.

## Prerequisites

- Clean working directory on the target integration/release branch.
- Pre-release unit tests pass: `./gradlew testDebugUnitTest`.
- GitHub CLI installed and authenticated: `gh auth status`.
- Python 3.9+ available for companion release helper (`prepare_release.py`).

## Workflow Steps

### Step 1: Pre-Release Verification

Run the test suite to ensure the build is green before initiating release preparation:

```bash
./gradlew testDebugUnitTest
```

**Completion criterion:** Test suite completes with `BUILD SUCCESSFUL` and 0 failures.

---

### Step 2: Release Inspection & Dry Run

Run the companion release helper script in `--dry-run` mode (or `--json` for programmatic parsing):

```bash
python .agents/skills/create-release/prepare_release.py --dry-run
```

The script performs:
- **Base Tag Detection:** Scans `git tag -l`, excludes ephemeral `nightly-*` tags, and selects the most recent stable tag (`v*.*.*` or `beta`).
- **Commit Inspection:** Parses `git log <base_tag>..HEAD` looking for Conventional Commits:
  - `feat`: new feature -> maps to `Added`
  - `fix`: bug fix -> maps to `Fixed`
  - `perf`, `refactor`, `chore`, `docs`, `style`: -> maps to `Changed`
  - `revert`, `remove`: -> maps to `Removed`
  - Breaking changes (`!` indicator or `BREAKING CHANGE:` footer) -> maps to `major` bump.
- **SemVer Recommendation:** Computes next version:
  - Breaking change detected: `major` bump (`(MAJOR+1).0.0`)
  - Any `feat` commits: `minor` bump (`MAJOR.(MINOR+1).0`)
  - Only `fix`/`refactor`/`perf`/`chore`: `patch` bump (`MAJOR.MINOR.(PATCH+1)`)
- **Changelog Formatting:** Assembles release notes adhering to the [Keep a Changelog](https://keepachangelog.com/) standard.

**Completion criterion:** Agent presents the proposed version (e.g. `v0.2.0`) and draft release notes to the user.

---

### Step 3: User Confirmation

Present the proposed version and drafted release notes to the user:
- Ask the user to confirm the recommended SemVer bump or supply an override (e.g. `--bump patch` or `--release-version 1.0.0`).
- Wait for user confirmation before executing git write operations.

**Completion criterion:** User approves release version and notes.

---

### Step 4: Execute Release Preparation

Once approved, execute release preparation with the companion helper:

```bash
# Auto bump:
python .agents/skills/create-release/prepare_release.py --execute

# Or with explicit bump override:
python .agents/skills/create-release/prepare_release.py --bump minor --execute

# Or with specific version tag:
python .agents/skills/create-release/prepare_release.py --release-version 0.2.0 --execute
```

This command automatically:
1. Updates `CHANGELOG.md` with section `## [X.Y.Z] - YYYY-MM-DD` and empties `## [Unreleased]`.
2. Stages `CHANGELOG.md` and commits `chore(release): prepare vX.Y.Z`.
3. Creates annotated git tag: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`.

**Completion criterion:** Release commit and annotated tag exist in local git history.

---

### Step 5: Push & Monitor GitHub Actions Release Pipeline

Push the release commit and tag to trigger `.github/workflows/release.yml`:

```bash
git push origin HEAD --follow-tags
```

Monitor the remote release workflow execution:

```bash
gh run watch
```

Once the workflow finishes, verify and view the published release:

```bash
gh release view vX.Y.Z
```

**Completion criterion:** GitHub Actions release workflow succeeds and release page URL is presented with universal APK and AAB assets attached.

---

## Reference & Troubleshooting

### Script Options

```text
usage: prepare_release.py [-h] [--repo-dir REPO_DIR] [--dry-run]
                          [--bump {auto,major,minor,patch}]
                          [--release-version RELEASE_VERSION]
                          [--update-changelog] [--execute] [--json]

Options:
  --dry-run             Inspect commits and print notes without modifying files.
  --bump {auto,major,minor,patch}
                        SemVer bump mode (default: auto).
  --release-version VERSION
                        Explicit SemVer version override (e.g. 0.2.0 or v0.2.0).
  --update-changelog    Write new release section to CHANGELOG.md only.
  --execute             Full automated commit and tag execution.
  --json                Output parsed release data as JSON.
```

### Manual Fallback

If running without the script:
1. `git log beta..HEAD --oneline`
2. Update `CHANGELOG.md` following Keep a Changelog.
3. `git commit -am "chore(release): prepare vX.Y.Z"`
4. `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
5. `git push origin HEAD --follow-tags`
6. `gh run watch`
