#!/usr/bin/env python3
"""Companion Release Preparation Tool for Arrivals.

Analyzes Conventional Commits since the last stable release,
recommends the next SemVer version, formats release notes according
to Keep a Changelog, and manages changelog updates and release tagging.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import subprocess
import sys
from datetime import date
from typing import List, Optional, Tuple


@dataclasses.dataclass(frozen=True)
class SemVer:
    major: int
    minor: int
    patch: int
    prerelease: Optional[str] = None

    def __str__(self) -> str:
        base = f"{self.major}.{self.minor}.{self.patch}"
        if self.prerelease:
            return f"{base}-{self.prerelease}"
        return base

    @property
    def tag(self) -> str:
        return f"v{self}"

    def tuple_key(self) -> Tuple[int, int, int]:
        return (self.major, self.minor, self.patch)


@dataclasses.dataclass
class CommitInfo:
    sha: str
    type: str
    scope: Optional[str]
    description: str
    is_breaking: bool
    raw_subject: str = ""
    body: str = ""


CONVENTIONAL_COMMIT_REGEX = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<description>.+)$"
)

BREAKING_CHANGE_BODY_REGEX = re.compile(
    r"(?:^|\n)\s*BREAKING[- ]CHANGE:\s*(.+)", re.IGNORECASE
)


def filter_stable_tags(tags: List[str]) -> List[str]:
    """Filter out ephemeral nightly tags and sort stable tags descending."""
    stable_candidates: List[str] = []
    for tag in tags:
        tag = tag.strip()
        if not tag or tag.startswith("nightly-"):
            continue
        if tag == "beta" or re.match(r"^v?\d+\.\d+\.\d+", tag):
            stable_candidates.append(tag)

    def sort_key(tag_name: str) -> Tuple[int, int, int, int]:
        clean = tag_name.lstrip("v")
        m = re.match(r"^(\d+)\.(\d+)\.(\d+)", clean)
        if m:
            return (1, int(m.group(1)), int(m.group(2)), int(m.group(3)))
        if "beta" in tag_name:
            return (0, 0, 1, 0)
        return (-1, 0, 0, 0)

    stable_candidates.sort(key=sort_key, reverse=True)
    return stable_candidates


def parse_tag_version(tag: Optional[str]) -> SemVer:
    """Parse a git tag into a SemVer object with fallback."""
    if not tag or tag == "beta":
        return SemVer(0, 1, 0, prerelease="beta" if tag == "beta" else None)
    clean = tag.lstrip("v")
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$", clean)
    if m:
        return SemVer(int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4))
    return SemVer(0, 1, 0)


def parse_conventional_commit(sha: str, subject: str, body: str = "") -> Optional[CommitInfo]:
    """Parse a git commit message into a structured CommitInfo object."""
    match = CONVENTIONAL_COMMIT_REGEX.match(subject.strip())
    if not match:
        return None

    commit_type = match.group("type").lower()
    scope = match.group("scope")
    breaking_indicator = bool(match.group("breaking"))
    description = match.group("description").strip()

    has_breaking_body = bool(BREAKING_CHANGE_BODY_REGEX.search(body))
    is_breaking = breaking_indicator or has_breaking_body

    return CommitInfo(
        sha=sha,
        type=commit_type,
        scope=scope,
        description=description,
        is_breaking=is_breaking,
        raw_subject=subject,
        body=body,
    )


def recommend_bump(commits: List[CommitInfo]) -> str:
    """Recommend next SemVer bump (major, minor, patch) based on commits."""
    if any(c.is_breaking for c in commits):
        return "major"
    if any(c.type == "feat" for c in commits):
        return "minor"
    return "patch"


def calculate_next_version(base: SemVer, bump: str) -> SemVer:
    """Calculate the next SemVer version given a base version and bump type."""
    if bump == "major":
        return SemVer(base.major + 1, 0, 0)
    elif bump == "minor":
        return SemVer(base.major, base.minor + 1, 0)
    elif bump == "patch":
        return SemVer(base.major, base.minor, base.patch + 1)
    else:
        raise ValueError(f"Unknown bump type: {bump}")


def format_release_notes(commits: List[CommitInfo]) -> str:
    """Group commits into Keep a Changelog categories and format markdown notes."""
    categories: dict[str, list[str]] = {
        "Added": [],
        "Changed": [],
        "Fixed": [],
        "Removed": [],
    }

    for c in commits:
        bullet_item = ""
        prefix = f"**{c.scope}**: " if c.scope else ""
        if c.is_breaking:
            prefix = f"**BREAKING** {prefix}"
        bullet_item = f"- {prefix}{c.description}"

        if c.type in ("feat", "feature"):
            categories["Added"].append(bullet_item)
        elif c.type in ("fix", "bugfix"):
            categories["Fixed"].append(bullet_item)
        elif c.type in ("revert", "remove", "removed"):
            categories["Removed"].append(bullet_item)
        else:
            categories["Changed"].append(bullet_item)

    lines: List[str] = []
    for cat_name in ("Added", "Changed", "Fixed", "Removed"):
        items = categories[cat_name]
        if items:
            lines.append(f"### {cat_name}")
            lines.extend(items)
            lines.append("")

    return "\n".join(lines).strip()


def run_git_command(args: List[str], cwd: Optional[str] = None) -> str:
    """Run a git command and return stripped stdout."""
    res = subprocess.run(
        ["git"] + args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=True,
    )
    return res.stdout.strip()


def get_git_tags(repo_dir: Optional[str] = None) -> List[str]:
    """Retrieve all git tags in the repository."""
    try:
        output = run_git_command(["tag", "-l"], cwd=repo_dir)
        return [t.strip() for t in output.splitlines() if t.strip()]
    except subprocess.CalledProcessError:
        return []


def get_commits_since_tag(base_tag: Optional[str], repo_dir: Optional[str] = None) -> List[CommitInfo]:
    """Extract and parse conventional commits between base_tag and HEAD."""
    git_range = f"{base_tag}..HEAD" if base_tag else "HEAD"
    # Format: Hash \x1f Subject \x1f Body \x1e
    format_arg = "--format=%H%x1f%s%x1f%b%x1e"
    try:
        output = run_git_command(["log", git_range, format_arg], cwd=repo_dir)
    except subprocess.CalledProcessError as e:
        print(f"Warning: git log failed for range '{git_range}': {e}", file=sys.stderr)
        return []

    commits: List[CommitInfo] = []
    raw_records = output.split("\x1e")
    for rec in raw_records:
        rec = rec.strip()
        if not rec:
            continue
        parts = rec.split("\x1f")
        sha = parts[0].strip() if len(parts) > 0 else ""
        subject = parts[1].strip() if len(parts) > 1 else ""
        body = parts[2].strip() if len(parts) > 2 else ""

        parsed = parse_conventional_commit(sha, subject, body)
        if parsed:
            commits.append(parsed)
        elif subject and not subject.startswith("Merge "):
            # Fallback for non-merge commits without conventional prefix
            commits.append(
                CommitInfo(
                    sha=sha,
                    type="chore",
                    scope=None,
                    description=subject,
                    is_breaking=False,
                    raw_subject=subject,
                    body=body,
                )
            )

    return commits


def update_changelog_content(
    original_content: str,
    version: str,
    release_notes: str,
    release_date: str,
    repo_url: str = "",
    prev_tag: str = "",
) -> str:
    """Insert a new release section into Keep a Changelog content and update links."""
    clean_ver = version.lstrip("v")
    new_release_header = f"## [{clean_ver}] - {release_date}"
    new_section = f"{new_release_header}\n\n{release_notes}\n"

    unreleased_pattern = r"(##\s+\[Unreleased\]\s*\n)(.*?)(?=\n##\s+\[|\Z)"
    match = re.search(unreleased_pattern, original_content, flags=re.DOTALL)

    if match:
        # Clear unreleased section or keep empty placeholder
        updated = (
            original_content[: match.start(1)]
            + "## [Unreleased]\n\n"
            + new_section
            + "\n"
            + original_content[match.end(2) :].lstrip("\n")
        )
    else:
        # Prepend new section after main title if unreleased header is missing
        header_pattern = r"(# Changelog\s*\n.*?\n)(##\s+\[)"
        m2 = re.search(header_pattern, original_content, flags=re.DOTALL)
        if m2:
            updated = (
                original_content[: m2.start(2)]
                + "## [Unreleased]\n\n"
                + new_section
                + "\n"
                + original_content[m2.start(2) :]
            )
        else:
            updated = f"# Changelog\n\n## [Unreleased]\n\n{new_section}\n{original_content}"

    # Update bottom comparison links if present or repo_url provided
    if repo_url:
        tag_current = f"v{clean_ver}"
        link_unreleased = f"[Unreleased]: {repo_url}/compare/{tag_current}...HEAD"
        link_current = (
            f"[{clean_ver}]: {repo_url}/compare/{prev_tag}...{tag_current}"
            if prev_tag
            else f"[{clean_ver}]: {repo_url}/releases/tag/{tag_current}"
        )

        # Replace existing [Unreleased] link if present
        if re.search(r"^\[Unreleased\]:", updated, flags=re.MULTILINE):
            updated = re.sub(
                r"^\[Unreleased\]:.*$",
                link_unreleased + "\n" + link_current,
                updated,
                flags=re.MULTILINE,
            )
        else:
            updated = updated.rstrip() + f"\n\n{link_unreleased}\n{link_current}\n"

    return updated


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Companion release preparation script for Arrivals."
    )
    parser.add_argument(
        "--repo-dir",
        default=".",
        help="Repository root directory (defaults to current dir)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Inspect commits and print proposed release notes without modifying files.",
    )
    parser.add_argument(
        "--bump",
        choices=["auto", "major", "minor", "patch"],
        default="auto",
        help="SemVer bump type (auto-calculates from commits by default)",
    )
    parser.add_argument(
        "--release-version",
        help="Explicitly override release version (e.g. 0.2.0 or v0.2.0)",
    )
    parser.add_argument(
        "--update-changelog",
        action="store_true",
        help="Write new release section to CHANGELOG.md",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Full release execution: update changelog, git commit chore(release), tag, and prompt push.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output result as JSON object",
    )

    args = parser.parse_args()
    repo_dir = os.path.abspath(args.repo_dir)

    all_tags = get_git_tags(repo_dir)
    stable_tags = filter_stable_tags(all_tags)
    base_tag = stable_tags[0] if stable_tags else None
    base_ver = parse_tag_version(base_tag)

    commits = get_commits_since_tag(base_tag, repo_dir)
    recommended = recommend_bump(commits) if commits else "patch"
    selected_bump = recommended if args.bump == "auto" else args.bump

    if args.release_version:
        clean = args.release_version.lstrip("v")
        m = re.match(r"^(\d+)\.(\d+)\.(\d+)", clean)
        if not m:
            print(f"Error: Invalid version format '{args.release_version}'. Must be SemVer X.Y.Z", file=sys.stderr)
            return 1
        next_ver = SemVer(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    else:
        next_ver = calculate_next_version(base_ver, selected_bump)

    release_notes = format_release_notes(commits)
    today_str = date.today().isoformat()

    # Remote URL detection
    repo_url = ""
    try:
        remote_out = run_git_command(["remote", "get-url", "origin"], cwd=repo_dir)
        m_url = re.search(r"github\.com[:/]([^/]+/[^/.]+)(?:\.git)?", remote_out)
        if m_url:
            repo_url = f"https://github.com/{m_url.group(1)}"
    except Exception:
        pass

    if args.json:
        result = {
            "base_tag": base_tag,
            "base_version": str(base_ver),
            "bump": selected_bump,
            "recommended_bump": recommended,
            "release_version": str(next_ver),
            "tag": next_ver.tag,
            "commit_count": len(commits),
            "release_notes": release_notes,
            "date": today_str,
        }
        print(json.dumps(result, indent=2))
        return 0

    print("==================================================")
    print(f" Arrivals Release Preparation: {next_ver.tag}")
    print("==================================================")
    print(f"Base stable tag: {base_tag or '(None)'} (v{base_ver})")
    print(f"Commits analyzed: {len(commits)}")
    print(f"Recommended bump: {recommended} -> Selected: {selected_bump}")
    print(f"Next Version:    {next_ver} (Tag: {next_ver.tag})")
    print("--------------------------------------------------")
    print("Draft Release Notes (Keep a Changelog):")
    print("--------------------------------------------------")
    print(release_notes if release_notes else "(No conventional commit entries detected)")
    print("==================================================")

    if args.dry_run and not (args.update_changelog or args.execute):
        print("\nDry-run complete. No files were modified.")
        return 0

    changelog_path = os.path.join(repo_dir, "CHANGELOG.md")
    if args.update_changelog or args.execute:
        if not os.path.exists(changelog_path):
            print(f"Creating baseline {changelog_path}...")
            initial_content = "# Changelog\n\n## [Unreleased]\n\n"
        else:
            with open(changelog_path, "r", encoding="utf-8") as f:
                initial_content = f.read()

        updated_changelog = update_changelog_content(
            original_content=initial_content,
            version=str(next_ver),
            release_notes=release_notes,
            release_date=today_str,
            repo_url=repo_url,
            prev_tag=base_tag or "",
        )

        with open(changelog_path, "w", encoding="utf-8") as f:
            f.write(updated_changelog)
        print(f"\nUpdated {changelog_path} with release [{next_ver}] - {today_str}.")

    if args.execute:
        print("\nCreating release commit and tag...")
        run_git_command(["add", "CHANGELOG.md"], cwd=repo_dir)
        commit_msg = f"chore(release): prepare {next_ver.tag}"
        run_git_command(["commit", "-m", commit_msg], cwd=repo_dir)
        print(f"Committed: {commit_msg}")

        tag_name = next_ver.tag
        run_git_command(["tag", "-a", tag_name, "-m", f"Release {tag_name}"], cwd=repo_dir)
        print(f"Created annotated tag: {tag_name}")

        print("\nRelease prepared successfully!")
        print("To trigger the GitHub Actions release pipeline:")
        print(f"  git push origin HEAD --follow-tags")
        print(f"  gh run watch")
        print(f"  gh release view {tag_name}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
