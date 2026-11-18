#!/usr/bin/env python3
"""Tests for tools/ci/prepush_guard.py (stdlib unittest, no network, no deps).

Run: python tools/ci/test_prepush_guard.py

Fake credentials are built at runtime by string concatenation so this test file
never contains a literal the guard (or a secret scanner) would match.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import prepush_guard as guard  # noqa: E402

GUARD = os.path.join(HERE, "prepush_guard.py")
HEADER = "-----BEGIN " + "OPENSSH PRIVATE" + " KEY-----"


def fake_secret(rule):
    """Build a throwaway credential that matches exactly one content rule."""
    if rule == "content-private-key":
        return HEADER
    if rule == "content-github-token":
        return "gh" + "p_" + "A" * 36
    if rule == "content-aws-access-key":
        return "AK" + "IA" + "Q" * 16
    if rule == "content-google-api-key":
        return "AI" + "za" + "C" * 35
    if rule == "content-slack-token":
        return "xo" + "xb-" + "1" * 12
    if rule == "content-api-key":
        return "s" + "k-" + "d" * 40
    raise AssertionError("unknown rule " + rule)


class CheckContentTest(unittest.TestCase):
    def assertBlocks(self, data, rule):
        hits = guard.check_content("fixture.txt", data if isinstance(data, bytes) else data.encode())
        rules = [hit[0] for hit in hits]
        self.assertIn(rule, rules, "expected %s in %r" % (rule, rules))
        return hits

    def test_each_secret_rule_blocks(self):
        for rule in ("content-private-key", "content-github-token",
                     "content-aws-access-key", "content-google-api-key",
                     "content-slack-token", "content-api-key"):
            with self.subTest(rule=rule):
                secret = fake_secret(rule)
                hits = self.assertBlocks("alpha\n" + secret + "\n", rule)
                self.assertIn((rule, 2), hits)
                # results carry no secret material at all
                self.assertNotIn(secret, repr(hits))

    def test_github_fine_grained_pat_blocks(self):
        token = "github_" + "pat_" + "B" * 55
        self.assertBlocks("x " + token, "content-github-token")

    def test_signing_password_blocks(self):
        self.assertBlocks("storePass" + "word = 'hu" + "nter2x'\n",
                          "content-signing-password")
        # a quoted literal is still blocked even though it looks like a call
        self.assertBlocks("keyPass" + 'word = "hu' + 'nter2x("\n', "content-signing-password")
        # .properties style unquoted literal
        self.assertBlocks("storePass" + "word=hu" + "nter2xyz\n", "content-signing-password")

    def test_signing_lookups_allowed(self):
        for text in (
            'storePassword = System.getenv("SM_PASS")',
            'keyPassword = System.getenv("SM_KEY_PASS")',
            'storePassword = project.findProperty("smStore")',
            'storePassword = properties["smStore"]',
            'storePassword = "$smStore"',
            # the repo's own app/build.gradle.kts helper (lookup call, not a literal)
            'storePassword = secret("STORE_PASSWORD")',
            'keyAlias = secret("KEY_ALIAS") ?: "scanwhirl"',
            'keyPassword = props.getProperty("key_password")',
        ):
            with self.subTest(text=text):
                hits = guard.check_content("app/build.gradle.kts", text.encode())
                self.assertEqual([], hits)

    def test_debug_credentials_allowed(self):
        text = "storePassword = 'android'\nkeyAlias = \"androiddebugkey\"\n"
        self.assertEqual([], guard.check_content("app/build.gradle", text.encode()))

    def test_clean_text_allowed(self):
        text = "No credentials here. sha256 hashes are hex, not keys.\n"
        self.assertEqual([], guard.check_content("README.md", text.encode()))

    def test_oversized_blob_is_not_decoded(self):
        blob = (HEADER + "\n").encode() + b"\0" * (guard.MAX_BLOB_BYTES + 1)
        self.assertEqual([], guard.check_content("huge.bin", blob))


class CheckPathTest(unittest.TestCase):
    def assertPathBlocked(self, path, rule, size=10):
        self.assertIn(rule, guard.check_path(path, size), "expected %s for %s" % (rule, path))

    def assertPathAllowed(self, path, size=10):
        self.assertEqual([], guard.check_path(path, size))

    def test_secret_files_blocked(self):
        self.assertPathBlocked("keystore.properties", "path-secret-file")
        self.assertPathBlocked("app/local.properties", "path-secret-file")
        self.assertPathBlocked("android/app/google-services.json", "path-secret-file")
        self.assertPathBlocked(".env", "path-secret-file")
        self.assertPathBlocked("config/.env.production", "path-secret-file")

    def test_signing_extensions_blocked(self):
        self.assertPathBlocked("release.jks", "path-forbidden-extension")
        self.assertPathBlocked("keys/UPPER.JKS", "path-forbidden-extension")
        self.assertPathBlocked("certs/upload.p12", "path-forbidden-extension")
        self.assertPathBlocked("dist/app-release.apk", "path-forbidden-extension")

    def test_keystore_directory_blocked(self):
        self.assertPathBlocked("keystore/scanwhirl.keystore", "path-forbidden-extension")
        self.assertPathBlocked("keystore/scanwhirl.txt", "path-keystore-dir")

    def test_private_directories_blocked(self):
        self.assertPathBlocked("qa/x.png", "path-private-dir")
        self.assertPathBlocked("foo/recordings/a.mp4", "path-forbidden-extension")
        self.assertPathBlocked("foo/recordings/a.txt", "path-private-dir")

    def test_binary_allowlist(self):
        self.assertPathAllowed("docs/design/home.png")
        self.assertPathAllowed("app/src/main/res/drawable/icon.webp")
        self.assertPathAllowed("app/src/test/resources/golden.jpg")
        self.assertPathAllowed("store_assets/screenshot-1.png")
        self.assertPathBlocked("app/src/main/java/com/scanwhirl/app/x.png",
                               "path-binary-outside-assets")
        self.assertPathBlocked("tools/design.png", "path-binary-outside-assets")

    def test_size_limit(self):
        self.assertIn("size-too-large", guard.check_path("docs/notes.md", 3 * 1024 * 1024))
        self.assertNotIn("size-too-large", guard.check_path("docs/notes.md", 1024))

    def test_clean_paths_allowed(self):
        self.assertPathAllowed("tools/ci/prepush_guard.py")
        self.assertPathAllowed("app/src/main/java/com/scanwhirl/app/MainActivity.kt")

    def test_windows_separators_normalized(self):
        self.assertPathBlocked("keystore\\scanwhirl.txt", "path-keystore-dir")


class GuardCliTest(unittest.TestCase):
    """End-to-end: real temporary git repository, real CLI invocation."""

    def setUp(self):
        if not shutil.which("git"):
            self.skipTest("git not available")
        self.repo = tempfile.mkdtemp(prefix="prepush-e2e-")
        self.addCleanup(shutil.rmtree, self.repo, True)
        self.git("init", "-q")
        self.git("config", "user.name", "Prepush Test")
        self.git("config", "user.email", "test@example.invalid")
        self.write("README.md", "clean base\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "clean base")
        base = self.git("rev-parse", "HEAD").stdout.decode().strip()
        self.git("update-ref", "refs/remotes/origin/integration", base)
        self.base = base

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo] + list(args), check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def write(self, rel, text, mode="w"):
        path = os.path.join(self.repo, *rel.split("/"))
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(path, mode) as handle:
            handle.write(text)

    def commit(self, message):
        self.git("add", "-A", "-f")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD").stdout.decode().strip()

    def guard(self, commit, *extra):
        return subprocess.run(
            [sys.executable, GUARD, "--repo", self.repo, "--commit", commit] + list(extra),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

    def test_fake_token_commit_blocks(self):
        token = fake_secret("content-github-token")
        self.write("fixtures/untracked-fixture.txt", "token = " + token + "\n")
        head = self.commit("add fixture token")
        result = self.guard(head)
        out = result.stdout.decode()
        self.assertEqual(1, result.returncode, out)
        self.assertIn("PREPUSH BLOCK content-github-token fixtures/untracked-fixture.txt:1", out)
        self.assertIn("PREPUSH FAIL violations=1", out)
        self.assertNotIn(token, out)

    def test_clean_commit_passes(self):
        self.write("docs/notes.md", "# notes\nnothing sensitive\n")
        head = self.commit("clean change")
        result = self.guard(head)
        out = result.stdout.decode()
        self.assertEqual(0, result.returncode, out)
        self.assertIn("PREPUSH PASS files=1", out)

    def test_allowlist_exempts_content_but_not_path_rules(self):
        token = fake_secret("content-aws-access-key")
        self.write("fixtures/allowlisted-fixture.txt", "key " + token + "\n")
        self.write("release.jks", "not a real keystore\n")
        self.write("tools/ci/prepush_allow.txt",
                   "# reviewed exceptions: content rules only\n"
                   "fixtures/allowlisted-fixture.txt\n"
                   "release.jks\n")
        head = self.commit("allowlisted fixtures")
        result = self.guard(head)
        out = result.stdout.decode()
        self.assertEqual(1, result.returncode, out)
        self.assertIn("PREPUSH BLOCK path-forbidden-extension release.jks", out)
        self.assertNotIn("content-aws-access-key", out)
        self.assertNotIn(token, out)

    def test_missing_base_ref_fails_closed(self):
        self.write("docs/extra.md", "text\n")
        head = self.commit("extra")
        result = self.guard(head, "--base", "origin/does-not-exist")
        out = result.stdout.decode()
        self.assertEqual(2, result.returncode, out)
        self.assertIn("PREPUSH FAIL error=no-base", out)

    def test_missing_origin_integration_fails_closed(self):
        self.git("update-ref", "-d", "refs/remotes/origin/integration")
        self.write("docs/extra.md", "text\n")
        head = self.commit("extra")
        result = self.guard(head)
        out = result.stdout.decode()
        self.assertEqual(2, result.returncode, out)
        self.assertIn("PREPUSH FAIL error=no-base", out)

    def test_deletions_are_not_scanned(self):
        token = fake_secret("content-api-key")
        self.write("fixtures/temporary.txt", token + "\n")
        self.commit("add file that will be deleted")
        os.remove(os.path.join(self.repo, "fixtures", "temporary.txt"))
        head = self.commit("delete it again")
        result = self.guard(head)
        out = result.stdout.decode()
        self.assertEqual(0, result.returncode, out)
        self.assertIn("PREPUSH PASS files=0", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
