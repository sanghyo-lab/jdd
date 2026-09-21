import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jdd import Repository, WorkflowError


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


class GitWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="jdd-workflow-")
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        git(self.root, "init", "--bare", "--initial-branch=main", str(self.remote))
        self.first = self.clone("first")
        self.commit(self.first, "shared.txt", "baseline\n")
        git(self.first, "push", "-u", "origin", "main")
        self.second = self.clone("second")

    def tearDown(self):
        self.temp.cleanup()

    def clone(self, name):
        path = self.root / name
        git(self.root, "clone", str(self.remote), str(path))
        git(path, "config", "user.name", "Workflow Test")
        git(path, "config", "user.email", "workflow@example.invalid")
        git(path, "config", "commit.gpgsign", "false")
        return path

    def commit(self, root, name, text):
        (root / name).write_text(text)
        git(root, "add", "--", name)
        git(root, "commit", "-m", "test " + name)

    def test_clean_clone_fast_forwards(self):
        self.commit(self.second, "agent.txt", "peer change\n")
        git(self.second, "push", "origin", "main")
        Repository(self.first).sync()
        self.assertEqual(git(self.first, "rev-parse", "HEAD"), git(self.second, "rev-parse", "HEAD"))

    def test_unpublished_commit_is_rebased_without_losing_either_role(self):
        self.commit(self.first, "commerce.txt", "own change\n")
        self.commit(self.second, "agent.txt", "peer change\n")
        git(self.second, "push", "origin", "main")
        Repository(self.first).sync()
        self.assertEqual((self.first / "commerce.txt").read_text(), "own change\n")
        self.assertEqual((self.first / "agent.txt").read_text(), "peer change\n")
        self.assertEqual(git(self.first, "rev-list", "--count", "origin/main..HEAD"), "1")

    def test_dirty_tree_refuses_sync_and_preserves_work(self):
        (self.first / "shared.txt").write_text("unfinished edit\n")
        with self.assertRaisesRegex(WorkflowError, "not clean"):
            Repository(self.first).sync()
        self.assertEqual((self.first / "shared.txt").read_text(), "unfinished edit\n")

    def test_conflict_is_left_for_agent_resolution(self):
        self.commit(self.first, "shared.txt", "commerce version\n")
        self.commit(self.second, "shared.txt", "agent version\n")
        git(self.second, "push", "origin", "main")
        with self.assertRaisesRegex(WorkflowError, "conflict resolution"):
            Repository(self.first).sync()
        self.assertTrue((self.first / ".git/rebase-merge").exists())
        body = (self.first / "shared.txt").read_text()
        self.assertIn("commerce version", body)
        self.assertIn("agent version", body)

    def test_publish_rechecks_after_a_concurrent_push(self):
        self.commit(self.first, "commerce.txt", "own change\n")
        owner = self

        class RacingRepository(Repository):
            validations = 0

            def verify(self):
                self.validations += 1
                if self.validations == 1:
                    owner.commit(owner.second, "agent.txt", "arrived during verification\n")
                    git(owner.second, "push", "origin", "main")

        repo = RacingRepository(self.first)
        repo.publish()
        self.assertEqual(repo.validations, 2)
        self.assertEqual(git(self.first, "rev-parse", "HEAD"), git(self.remote, "rev-parse", "main"))
        self.assertTrue((self.first / "agent.txt").exists())

    def test_failed_validation_never_pushes(self):
        self.commit(self.first, "commerce.txt", "broken change\n")
        original = git(self.remote, "rev-parse", "main")

        class FailingRepository(Repository):
            def verify(self):
                raise WorkflowError("verification failed")

        with self.assertRaisesRegex(WorkflowError, "verification failed"):
            FailingRepository(self.first).publish()
        self.assertEqual(git(self.remote, "rev-parse", "main"), original)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="jdd-snapshot-")
        self.root = Path(self.temp.name)
        git(self.root, "init", "--initial-branch=main")
        git(self.root, "config", "user.name", "Snapshot Test")
        git(self.root, "config", "user.email", "snapshot@example.invalid")
        git(self.root, "config", "commit.gpgsign", "false")
        (self.root / ".gitignore").write_text(".env\nruntime/\n")
        (self.root / ".env.example").write_text("POSTGRES_PASSWORD=replace-by-setup\n")
        self.policy = self.root / "docs/business-policy.md"
        self.policy.parent.mkdir()
        self.policy.write_text("# 정상 정책\n적용 버전은 `demo-v1`이다.\n재고는 음수가 될 수 없다.\n", encoding="utf-8")
        self.source = self.root / "commerce-core/src/main/java/com/jdd/Stock.java"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("class Stock {}\n")
        answers = self.root / "commerce-core/src/test/java/Answer.java"
        answers.parent.mkdir(parents=True)
        answers.write_text("expected bug location\n")
        git(self.root, "add", ".")
        git(self.root, "commit", "-m", "fixture")
        self.repo = Repository(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_snapshot_excludes_answers_and_keeps_old_build_immutable(self):
        with contextlib.redirect_stdout(io.StringIO()):
            first = self.repo.snapshot()
            self.source.write_text("class Stock { int quantity; }\n")
            second = self.repo.snapshot()
        self.assertNotEqual(first, second)
        base = self.root / "runtime/evidence/source" / first
        self.assertEqual((base / self.source.relative_to(self.root)).read_text(), "class Stock {}\n")
        self.assertFalse((base / "commerce-core/src/test").exists())

    def test_tampered_snapshot_fails_instead_of_being_overwritten(self):
        with contextlib.redirect_stdout(io.StringIO()):
            build = self.repo.snapshot()
            saved = self.root / "runtime/evidence/source" / build / self.source.relative_to(self.root)
            saved.write_text("tampered\n")
            with self.assertRaisesRegex(WorkflowError, "does not match"):
                self.repo.snapshot()

    def test_setup_never_rotates_existing_database_credentials(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.repo.setup()
            original = (self.root / ".env").read_bytes()
            self.repo.setup()
        self.assertEqual((self.root / ".env").read_bytes(), original)

    def test_source_symlink_cannot_include_outside_content(self):
        outside = self.root / "answer.txt"
        outside.write_text("not runtime source")
        (self.source.parent / "linked.java").symlink_to(outside)
        with self.assertRaisesRegex(WorkflowError, "symlink"):
            self.repo.source_files()

    def snapshot(self):
        with contextlib.redirect_stdout(io.StringIO()):
            build = self.repo.snapshot()
        base = self.root / "runtime/evidence/source" / build
        return base, json.loads((base / "manifest.json").read_text(encoding="utf-8"))

    def test_policy_is_archived_byte_for_byte_with_portable_source_paths(self):
        original = self.policy.read_bytes().replace(b"\n", b"\r\n")
        self.policy.write_bytes(original)
        base, manifest = self.snapshot()
        self.assertEqual(manifest["policy"], {"version": "demo-v1", "path": "policy/business-policy.md",
                                            "sha256": hashlib.sha256(original).hexdigest()})
        self.assertEqual(manifest["policyVersion"], "demo-v1")
        self.assertEqual((base / manifest["policy"]["path"]).read_bytes(), original)
        self.assertEqual(list(manifest["files"]), ["commerce-core/src/main/java/com/jdd/Stock.java"])
        self.assertNotIn("policy/business-policy.md", manifest["files"])
        saved_manifest = (base / "manifest.json").read_bytes()
        again, _ = self.snapshot()
        self.assertEqual(again, base)
        self.assertEqual((base / "manifest.json").read_bytes(), saved_manifest)

    def test_new_policy_never_replaces_a_previous_build_policy(self):
        first, original = self.snapshot()
        first_bytes = (first / "policy/business-policy.md").read_bytes()
        self.policy.write_text("# 새 정책\n적용 버전은 `demo-v2`이다.\n다른 업무 규칙.\n", encoding="utf-8")
        second, changed = self.snapshot()
        self.assertNotEqual(first, second)
        self.assertEqual((first / "policy/business-policy.md").read_bytes(), first_bytes)
        self.assertEqual(changed["policyVersion"], "demo-v2")
        self.assertNotEqual(original["policy"]["sha256"], changed["policy"]["sha256"])
        self.assertEqual((second / "policy/business-policy.md").read_bytes(), self.policy.read_bytes())

    def test_tampered_policy_or_manifest_is_rejected_without_repair(self):
        base, manifest = self.snapshot()
        policy = base / "policy/business-policy.md"
        policy.write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(WorkflowError, "does not match"):
            self.snapshot()
        self.assertEqual(policy.read_text(), "tampered")
        policy.write_bytes(self.policy.read_bytes())
        mutations = [{"version": "other"}, {"path": "../../docs/business-policy.md"}, {"sha256": "0" * 64}]
        for change in mutations:
            with self.subTest(change=change):
                altered = {**manifest, "policy": {**manifest["policy"], **change}}
                encoded = json.dumps(altered).encode()
                (base / "manifest.json").write_bytes(encoded)
                with self.assertRaises(WorkflowError):
                    self.snapshot()
                self.assertEqual((base / "manifest.json").read_bytes(), encoded)
        for key in ("buildId", "commitSha", "policyVersion"):
            with self.subTest(key=key):
                (base / "manifest.json").write_text(json.dumps({**manifest, key: "wrong"}))
                with self.assertRaises(WorkflowError):
                    self.snapshot()

    def test_missing_legacy_policy_is_not_retroactively_created(self):
        base, manifest = self.snapshot()
        del manifest["policy"]
        (base / "manifest.json").write_text(json.dumps(manifest))
        (base / "policy/business-policy.md").unlink()
        with self.assertRaisesRegex(WorkflowError, "archived policy"):
            self.snapshot()
        self.assertFalse((base / "policy/business-policy.md").exists())
        self.assertNotIn("policy", json.loads((base / "manifest.json").read_text()))

    def test_invalid_policy_version_or_utf8_is_not_snapshotted(self):
        for content in (b"no version", "적용 버전은 `a`\n적용 버전은 `b`".encode(), b"\xff"):
            with self.subTest(content=content):
                self.policy.write_bytes(content)
                with self.assertRaisesRegex(WorkflowError, "policy"):
                    self.snapshot()
        self.assertFalse((self.root / "runtime/evidence/source").exists())

    def test_policy_and_snapshot_parent_symlinks_are_rejected(self):
        original = self.policy.read_bytes()
        outside = self.root / "outside-policy.md"
        outside.write_bytes(original)
        self.policy.unlink()
        self.policy.symlink_to(outside)
        with self.assertRaisesRegex(WorkflowError, "symlink"):
            self.snapshot()
        self.policy.unlink()
        self.policy.write_bytes(original)
        base, _ = self.snapshot()
        (base / "policy/business-policy.md").unlink()
        (base / "policy").rmdir()
        (base / "policy").symlink_to(self.policy.parent, target_is_directory=True)
        with self.assertRaisesRegex(WorkflowError, "symlink"):
            self.snapshot()
        self.assertEqual(self.policy.read_bytes(), original)

    def test_source_root_symlink_is_not_followed(self):
        linked = self.root / "commerce-app/src/main/java"
        linked.parent.mkdir(parents=True)
        linked.symlink_to(self.source.parent, target_is_directory=True)
        with self.assertRaisesRegex(WorkflowError, "symlink"):
            self.repo.source_files()


if __name__ == "__main__":
    unittest.main()
