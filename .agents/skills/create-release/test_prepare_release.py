"""Unit tests for create-release release preparation logic."""

import unittest
from datetime import date
from prepare_release import (
    CommitInfo,
    SemVer,
    parse_conventional_commit,
    recommend_bump,
    format_release_notes,
    filter_stable_tags,
    update_changelog_content,
    calculate_next_version,
)


class TestReleasePreparation(unittest.TestCase):
    def test_filter_stable_tags_ignores_nightly(self):
        tags = [
            "nightly-20261107",
            "nightly-20261113",
            "beta",
            "v0.1.0",
            "v0.2.0",
            "v1.0.0-rc1",
        ]
        stable = filter_stable_tags(tags)
        self.assertNotIn("nightly-20261107", stable)
        self.assertNotIn("nightly-20261113", stable)
        self.assertIn("beta", stable)
        self.assertIn("v0.1.0", stable)
        self.assertIn("v0.2.0", stable)
        self.assertIn("v1.0.0-rc1", stable)

    def test_filter_stable_tags_orders_latest_first(self):
        tags = ["beta", "v0.1.0", "v0.2.0", "v0.1.5"]
        stable = filter_stable_tags(tags)
        self.assertEqual(stable[0], "v0.2.0")

    def test_filter_stable_tags_numeric_ordering(self):
        tags = ["v0.2.0", "v0.9.0", "v0.10.0", "v0.1.0"]
        stable = filter_stable_tags(tags)
        self.assertEqual(stable[0], "v0.10.0")
        self.assertEqual(stable[1], "v0.9.0")

    def test_filter_stable_tags_handles_only_beta(self):
        tags = ["nightly-20261112", "beta", "nightly-20261113"]
        stable = filter_stable_tags(tags)
        self.assertEqual(stable, ["beta"])

    def test_parse_conventional_commit_feat(self):
        commit = parse_conventional_commit(
            sha="abc1234",
            subject="feat(widget): scrollable departures list (#31)",
            body="",
        )
        self.assertIsNotNone(commit)
        self.assertEqual(commit.type, "feat")
        self.assertEqual(commit.scope, "widget")
        self.assertEqual(commit.description, "scrollable departures list (#31)")
        self.assertFalse(commit.is_breaking)

    def test_parse_conventional_commit_fix(self):
        commit = parse_conventional_commit(
            sha="def5678",
            subject="fix: handle null stop coordinate fallback",
            body="",
        )
        self.assertIsNotNone(commit)
        self.assertEqual(commit.type, "fix")
        self.assertIsNone(commit.scope)
        self.assertEqual(commit.description, "handle null stop coordinate fallback")
        self.assertFalse(commit.is_breaking)

    def test_parse_conventional_commit_breaking_exclamation(self):
        commit = parse_conventional_commit(
            sha="1112223",
            subject="feat(api)!: migrate to Golemio v3 schema",
            body="New schema requires mandatory API token.",
        )
        self.assertIsNotNone(commit)
        self.assertEqual(commit.type, "feat")
        self.assertTrue(commit.is_breaking)

    def test_parse_conventional_commit_breaking_footer(self):
        commit = parse_conventional_commit(
            sha="4445556",
            subject="refactor(engine): redesign countdown intake interface",
            body="Some text\n\nBREAKING CHANGE: ArrivalsEngine now requires injected Clock.",
        )
        self.assertIsNotNone(commit)
        self.assertTrue(commit.is_breaking)

    def test_recommend_bump_patch(self):
        commits = [
            CommitInfo("1", "fix", "data", "fix query error", False),
            CommitInfo("2", "perf", "widget", "optimize item render", False),
        ]
        self.assertEqual(recommend_bump(commits), "patch")

    def test_recommend_bump_minor(self):
        commits = [
            CommitInfo("1", "fix", "data", "fix query error", False),
            CommitInfo("2", "feat", "widget", "add platform picker", False),
        ]
        self.assertEqual(recommend_bump(commits), "minor")

    def test_recommend_bump_major_on_breaking(self):
        commits = [
            CommitInfo("1", "fix", "data", "fix query error", False),
            CommitInfo("2", "feat", "widget", "breaking api change", True),
        ]
        self.assertEqual(recommend_bump(commits), "major")

    def test_parse_conventional_commit_invalid(self):
        commit = parse_conventional_commit(
            sha="9998887",
            subject="Merge pull request #45 from feature/branch",
            body="",
        )
        self.assertIsNone(commit)

    def test_recommend_bump_empty_commits(self):
        self.assertEqual(recommend_bump([]), "patch")

    def test_format_release_notes_empty(self):
        self.assertEqual(format_release_notes([]), "")

    def test_calculate_next_version(self):
        base = SemVer(0, 1, 0)
        self.assertEqual(calculate_next_version(base, "patch"), SemVer(0, 1, 1))
        self.assertEqual(calculate_next_version(base, "minor"), SemVer(0, 2, 0))
        self.assertEqual(calculate_next_version(base, "major"), SemVer(1, 0, 0))

    def test_format_release_notes_keep_a_changelog_categories(self):
        commits = [
            CommitInfo("1", "feat", "widget", "scrollable platform picker (#30)", False),
            CommitInfo("2", "fix", "api", "handle null timestamp string (#2)", False),
            CommitInfo("3", "perf", "cache", "fast geospatial lookup (#26)", False),
            CommitInfo("4", "refactor", "domain", "collapse countdown ticking into ArrivalsEngine (#25)", False),
        ]
        notes = format_release_notes(commits)
        self.assertIn("### Added", notes)
        self.assertIn("- **widget**: scrollable platform picker (#30)", notes)
        self.assertIn("### Fixed", notes)
        self.assertIn("- **api**: handle null timestamp string (#2)", notes)
        self.assertIn("### Changed", notes)
        self.assertIn("- **cache**: fast geospatial lookup (#26)", notes)
        self.assertIn("- **domain**: collapse countdown ticking into ArrivalsEngine (#25)", notes)

    def test_update_changelog_content_inserts_release_section(self):
        original = (
            "# Changelog\n\n"
            "All notable changes to this project will be documented in this file.\n\n"
            "## [Unreleased]\n\n"
            "### Added\n"
            "- New experimental feature\n\n"
            "## [0.1.0] - 2026-11-01\n\n"
            "### Added\n"
            "- Initial beta release\n"
        )
        new_notes = "### Added\n- New experimental feature\n- Added release automation"
        today = date.today().isoformat()
        updated = update_changelog_content(
            original_content=original,
            version="0.2.0",
            release_notes=new_notes,
            release_date=today,
            repo_url="https://github.com/RiegelZdenek/arrivals",
            prev_tag="v0.1.0",
        )

        self.assertIn(f"## [0.2.0] - {today}", updated)
        self.assertIn("## [Unreleased]", updated)
        self.assertIn(f"[0.2.0]: https://github.com/RiegelZdenek/arrivals/compare/v0.1.0...v0.2.0", updated)
        self.assertIn("## [0.1.0] - 2026-11-01", updated)


if __name__ == "__main__":
    unittest.main()
