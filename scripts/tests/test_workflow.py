import contextlib
import io
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


if __name__ == "__main__":
    unittest.main()
