#!/usr/bin/env python3
"""Fail-closed pre-push guard: stop risky content from becoming public.

The GitHub repository is PUBLIC, so every commit that reaches origin (lane CI
snapshots to ci/<lane>, then the lead's integration pushes) is world-readable.
This script inspects exactly the blobs a push would add or modify and refuses
(exit 1) on secret files, signing material, media/binaries outside reviewed
asset folders, oversized blobs and credential-looking text. When it cannot be
sure (no base, unreadable blob, unexpected error) it fails closed with exit 2
and nothing should be pushed.

Usage:
  python tools/ci/prepush_guard.py --repo <path> --commit <sha> [--base <ref>]

  --base defaults to `git merge-base <commit> origin/integration`. There is no
  "scan nothing" fallback: if the base cannot be resolved the guard exits 2.

Content-only exemptions live in tools/ci/prepush_allow.txt: one exact
repo-relative path per line, blank lines and '#' comments ignored. A missing
file means no exemptions. An exemption only silences CONTENT rules; path,
extension and size rules always apply.

Output: one `PREPUSH BLOCK <rule> <path>[:<line>]` line per violation, then
`PREPUSH PASS files=<n>` (exit 0) or `PREPUSH FAIL violations=<n>` (exit 1).
Errors print `PREPUSH FAIL error=<kind>` and exit 2. Matched secret values are
never printed, only the rule name and line number.

Self-test: python tools/ci/test_prepush_guard.py
"""
import argparse
import os
import re
import subprocess
import sys

MAX_BLOB_BYTES = 2 * 1024 * 1024
DEFAULT_BASE_REF = "origin/integration"
ALLOW_FILE = os.path.join("tools", "ci", "prepush_allow.txt")

# --- path rules (never exemptible) -----------------------------------------
DENY_BASENAMES = frozenset({
    "local.properties",
    "keystore.properties",
    "google-services.json",
    ".env",
})
DENY_EXTENSIONS = frozenset({
    ".jks", ".keystore", ".p12", ".pfx", ".pepk", ".pem", ".key",
    ".apk", ".aab", ".mp4", ".mov", ".mkv", ".avi", ".3gp", ".heic",
})
PRIVATE_DIR_SEGMENTS = frozenset({"qa", "recordings"})

# Binary/media blobs are fine only inside reviewed asset folders.
BINARY_EXTENSIONS = frozenset({
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".pdf", ".zip", ".bin",
    ".tflite", ".onnx", ".ttf", ".otf",
})
BINARY_ALLOWED_PREFIXES = (
    "app/src/main/res/",
    "app/src/main/assets/",
    "app/src/test/resources/",
    "app/src/androidTest/",
    "docs/design/",
    "store_assets/",
)

# --- content rules (exemptible via tools/ci/prepush_allow.txt) -------------
CONTENT_RULES = (
    ("content-private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("content-github-token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}")),
    ("content-github-token", re.compile(r"github_pat_[A-Za-z0-9_]{50,}")),
    ("content-aws-access-key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("content-google-api-key", re.compile(r"AIza[0-9A-Za-z_\-]{35}")),
    ("content-slack-token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("content-api-key", re.compile(r"sk-[A-Za-z0-9_\-]{32,}")),
)
SIGNING_RULE = "content-signing-password"
SIGNING_RE = re.compile(
    r"(storePassword|keyPassword|keyAlias)\s*[=:]\s*(['\"]?)([^\s'\"$]{4,})"
)
# Values that are Gradle/env lookups are configuration, not secrets.
GRADLE_LOOKUP_MARKERS = ("System.getenv", "project.findProperty", "properties[", "$")
# An unquoted value that is a function call (the value regex stops at its quote), e.g. secret( / getProperty(.
CALL_RE = re.compile(r"^[A-Za-z_][\w.]*\($")
# The well-known public debug credentials.
DEBUG_SIGNING_VALUES = frozenset({"android", "androiddebugkey"})


def _normalize(path):
    p = path.replace("\\", "/").strip()
    while p.startswith("./"):
        p = p[2:]
    return p.strip("/")


def _extension(name):
    return os.path.splitext(name.lower())[1]


def check_path(path, size=None):
    """Return the list of rule names violated by a path (and blob size)."""
    norm = _normalize(path)
    segments = [s for s in norm.split("/") if s]
    if not segments:
        return []
    name = segments[-1].lower()
    ext = _extension(name)
    rules = []

    if name in DENY_BASENAMES or name.startswith(".env."):
        rules.append("path-secret-file")
    if ext in DENY_EXTENSIONS:
        rules.append("path-forbidden-extension")
    if any(s.lower() == "keystore" for s in segments):
        rules.append("path-keystore-dir")
    if any(s.lower() in PRIVATE_DIR_SEGMENTS for s in segments):
        rules.append("path-private-dir")
    if ext in BINARY_EXTENSIONS and not norm.lower().startswith(BINARY_ALLOWED_PREFIXES):
        rules.append("path-binary-outside-assets")
    if size is not None and size > MAX_BLOB_BYTES:
        rules.append("size-too-large")
    return rules


def _line_number(text, position):
    return text.count("\n", 0, position) + 1


def check_content(path, data):
    """Return sorted [(rule, line)] hits for a blob. Never returns the match."""
    if len(data) > MAX_BLOB_BYTES:
        return []  # oversized blobs are rejected by the size rule instead
    text = data.decode("utf-8", "ignore")
    hits = set()
    for rule, pattern in CONTENT_RULES:
        for match in pattern.finditer(text):
            hits.add((rule, _line_number(text, match.start())))
    for line, value, quoted in _signing_assignments(text):
        if value in DEBUG_SIGNING_VALUES:
            continue
        if any(marker in value for marker in GRADLE_LOOKUP_MARKERS):
            continue
        if not quoted and CALL_RE.match(value):  # e.g. secret("STORE_PASSWORD"): a lookup call, not a literal
            continue
        hits.add((SIGNING_RULE, line))
    return sorted(hits, key=lambda hit: (hit[1], hit[0]))


def _signing_assignments(text):
    """Yield (line, value, quoted) for storePassword/keyPassword/keyAlias assignments."""
    for match in SIGNING_RE.finditer(text):
        yield _line_number(text, match.start()), match.group(3), bool(match.group(2))


def load_allowlist(repo):
    """Return the set of repo-relative paths exempted from CONTENT rules only."""
    path = os.path.join(repo, ALLOW_FILE)
    allowed = set()
    if not os.path.isfile(path):
        return allowed  # missing file = no exemptions
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle.read().splitlines():
            entry = line.strip()
            if entry and not entry.startswith("#"):
                allowed.add(_normalize(entry))
    return allowed


# --- git plumbing -----------------------------------------------------------
def _git(repo, *args):
    return subprocess.run(
        ["git", "-C", repo] + list(args),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def resolve_commit(repo, ref):
    result = _git(repo, "rev-parse", "--verify", "--quiet", ref + "^{commit}")
    if result.returncode != 0:
        return None
    sha = result.stdout.decode("ascii", "replace").strip()
    return sha or None


def resolve_base(repo, commit, base_ref):
    if base_ref:
        return resolve_commit(repo, base_ref)
    result = _git(repo, "merge-base", commit, DEFAULT_BASE_REF)
    if result.returncode != 0:
        return None
    return result.stdout.decode("ascii", "replace").strip() or None


def changed_paths(repo, base, commit):
    """Paths added/modified/copied between base and commit (deletions ignored)."""
    result = _git(repo, "diff-tree", "-r", "--no-renames", "--name-status", "-z", base, commit)
    if result.returncode != 0:
        return None
    fields = result.stdout.split(b"\0")
    paths = []
    index = 0
    while index < len(fields):
        status = fields[index].decode("ascii", "replace").strip()
        if not status:
            index += 1
            continue
        path = fields[index + 1].decode("utf-8", "replace") if index + 1 < len(fields) else ""
        index += 2
        if status[:1] in ("A", "M", "C", "T") and path:
            paths.append(path)
    return paths


def blob_size(repo, commit, path):
    result = _git(repo, "cat-file", "-s", "%s:%s" % (commit, path))
    if result.returncode != 0:
        return None
    try:
        return int(result.stdout.decode("ascii", "replace").strip())
    except ValueError:
        return None


def blob_bytes(repo, commit, path):
    result = _git(repo, "cat-file", "-p", "%s:%s" % (commit, path))
    if result.returncode != 0:
        return None
    return result.stdout


def scan(repo, commit, base_ref=None):
    """Return (exit_code, lines). lines already carry the PREPUSH vocabulary."""
    base = resolve_base(repo, commit, base_ref)
    if base is None:
        return 2, [
            "PREPUSH ERROR base not found for %s (%s); refusing to scan nothing"
            % (commit, base_ref or DEFAULT_BASE_REF),
            "PREPUSH FAIL error=no-base",
        ]
    paths = changed_paths(repo, base, commit)
    if paths is None:
        return 2, ["PREPUSH ERROR git diff-tree failed", "PREPUSH FAIL error=diff-failed"]
    allowed = load_allowlist(repo)

    violations = []
    for path in paths:
        size = blob_size(repo, commit, path)
        if size is None:
            return 2, [
                "PREPUSH ERROR cannot read size of %s at %s" % (path, commit),
                "PREPUSH FAIL error=blob-unreadable",
            ]
        for rule in check_path(path, size):
            violations.append((path, rule, None))
        if size > MAX_BLOB_BYTES or _normalize(path) in allowed:
            continue
        data = blob_bytes(repo, commit, path)
        if data is None:
            return 2, [
                "PREPUSH ERROR cannot read %s at %s" % (path, commit),
                "PREPUSH FAIL error=blob-unreadable",
            ]
        for rule, line in check_content(path, data):
            violations.append((path, rule, line))

    lines = []
    for path, rule, line in sorted(violations, key=lambda v: (v[0], v[1], v[2] or 0)):
        where = "%s:%d" % (path, line) if line else path
        lines.append("PREPUSH BLOCK %s %s" % (rule, where))
    if violations:
        lines.append("PREPUSH FAIL violations=%d" % len(violations))
        return 1, lines
    lines.append("PREPUSH PASS files=%d" % len(paths))
    return 0, lines


def main(argv):
    parser = argparse.ArgumentParser(description="Fail-closed pre-push content guard.")
    parser.add_argument("--repo", required=True, help="path to the git worktree")
    parser.add_argument("--commit", required=True, help="commit that would be pushed")
    parser.add_argument("--base", default=None, help="base ref (default: merge-base with origin/integration)")
    args = parser.parse_args(argv[1:])

    try:
        code, lines = scan(args.repo, args.commit, args.base)
    except Exception as exc:  # noqa: BLE001 - fail closed on anything unexpected
        print("PREPUSH FAIL error=%s" % type(exc).__name__)
        return 2
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
