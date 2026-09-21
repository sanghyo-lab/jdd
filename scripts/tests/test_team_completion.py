"""Exercise the GitHub completion protocol with three independent local clones.

Business verification is stubbed only inside these tests; no project DONE is created.
"""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jdd import MVP_CASES, Repository, TEAM_ROLES, WorkflowError, validate_mvp_result
from test_workflow import git


class TeamCompletionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="jdd-team-")
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        git(self.root, "init", "--bare", "--initial-branch=main", str(self.remote))
        self.clones = {}
        for role in TEAM_ROLES:
            path = self.root / role
            git(self.root, "clone", str(self.remote), str(path))
            git(path, "config", "user.name", "Completion Test")
            git(path, "config", "user.email", "completion@example.invalid")
            git(path, "config", "commit.gpgsign", "false")
            self.clones[role] = path
            if role == "commerce":
                (path / "app.txt").write_text("initial implementation\n")
                self.share(role, "app.txt")
        self.output = io.StringIO()
        self.quiet = contextlib.redirect_stdout(self.output)
        self.quiet.__enter__()

    def tearDown(self):
        self.quiet.__exit__(None, None, None)
        self.temp.cleanup()

    def share(self, role, name):
        path = self.clones[role]
        git(path, "add", "--", name)
        git(path, "commit", "-m", "test " + name)
        git(path, "push", "origin", "main")

    def report(self, role):
        commit = git(self.clones[role], "rev-parse", "HEAD")
        return {"buildId": commit[:12] + "-" + "a" * 12, "mode": "live", "model": "unit-test-stub",
                "cases": [{"id": name, "status": "PASSED", "mocked": False} for name in sorted(MVP_CASES)]}

    def finish_role(self, role, push=True):
        repo = Repository(self.clones[role])
        repo.sync()
        with patch.object(repo, "verify_mvp", return_value=self.report(role)) as verify:
            repo.role_done(role)
            verify.assert_called_once_with()
        if push:
            self.share(role, "docs/status/" + role + ".json")

    def finish_all(self):
        for role in TEAM_ROLES:
            self.finish_role(role)

    def test_missing_or_only_two_done_records_never_complete_the_team(self):
        repo = Repository(self.clones["commerce"])
        self.assertFalse(repo.team_status())
        self.finish_role("commerce")
        self.finish_role("agent")
        self.assertFalse(repo.team_status())
        self.assertIn("MISSING", self.output.getvalue())

    def test_three_independent_completions_and_status_only_commits_are_valid(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        self.assertTrue(repo.team_status())
        repo.sync()
        records = [json.loads((repo.root / "docs/status" / (role + ".json")).read_text()) for role in TEAM_ROLES]
        self.assertEqual(len({record["verifiedCommit"] for record in records}), 3)
        self.assertEqual(len({record["contentSha256"] for record in records}), 1)
        (repo.root / "docs/status/commerce.md").write_text("Waiting for team completion.\n")
        self.share("commerce", "docs/status/commerce.md")
        repo.team_check()

    def test_local_unpublished_done_is_not_counted(self):
        self.finish_role("commerce")
        self.finish_role("agent")
        self.finish_role("voc", push=False)
        self.assertFalse(Repository(self.clones["voc"]).team_status())
        self.assertIn("voc (김아름): MISSING", self.output.getvalue())

    def test_implementation_change_invalidates_every_previous_completion(self):
        self.finish_all()
        repo = Repository(self.clones["voc"])
        (repo.root / "app.txt").write_text("new implementation\n")
        self.share("voc", "app.txt")
        self.assertFalse(repo.team_status())
        self.assertEqual(self.output.getvalue().count("STALE:"), 3)

    def test_goal_criteria_change_also_requires_reverification(self):
        self.finish_all()
        repo = Repository(self.clones["voc"])
        criteria = repo.root / "docs/goals/commerce.md"
        criteria.parent.mkdir(parents=True)
        criteria.write_text("An additional acceptance criterion.\n")
        self.share("voc", "docs/goals/commerce.md")
        self.assertFalse(repo.team_status())

    def test_reopening_one_role_withdraws_team_completion(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        repo.sync()
        repo.role_reopen("commerce")
        self.share("commerce", "docs/status/commerce.json")
        self.assertFalse(repo.team_status())
        with self.assertRaisesRegex(WorkflowError, "All three owners"):
            repo.team_check()

    def test_failing_verification_cannot_write_done(self):
        repo = Repository(self.clones["commerce"])
        repo.role_reopen("commerce")
        self.share("commerce", "docs/status/commerce.json")
        path = repo.root / "docs/status/commerce.json"
        original = path.read_bytes()
        with patch.object(repo, "verify_mvp", side_effect=WorkflowError("live model failed")):
            with self.assertRaisesRegex(WorkflowError, "live model failed"):
                repo.role_done("commerce")
        self.assertEqual(path.read_bytes(), original)

    def test_unpublished_implementation_is_rejected_before_verification(self):
        repo = Repository(self.clones["commerce"])
        (repo.root / "app.txt").write_text("unfinished implementation\n")
        with patch.object(repo, "verify_mvp") as verify:
            with self.assertRaisesRegex(WorkflowError, "not clean"):
                repo.role_done("commerce")
            git(repo.root, "add", "--", "app.txt")
            git(repo.root, "commit", "-m", "local implementation")
            with self.assertRaisesRegex(WorkflowError, "Publish implementation"):
                repo.role_done("commerce")
            verify.assert_not_called()

    def test_team_check_requires_clean_up_to_date_local_main(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        with self.assertRaisesRegex(WorkflowError, "Sync main"):
            repo.team_check()
        repo.sync()
        repo.team_check()
        (repo.root / "app.txt").write_text("unshared work\n")
        with self.assertRaisesRegex(WorkflowError, "not clean"):
            repo.team_check()

    def test_invalid_or_mocked_attestations_fail_closed(self):
        self.finish_all()
        repo = Repository(self.clones["voc"])
        path = repo.root / "docs/status/voc.json"
        original = json.loads(path.read_text())
        invalid = []
        mocked = copy.deepcopy(original)
        mocked["verification"]["cases"][0]["mocked"] = True
        invalid.append(mocked)
        missing_case = copy.deepcopy(original)
        missing_case["verification"]["cases"].pop()
        invalid.append(missing_case)
        wrong_build = copy.deepcopy(original)
        wrong_build["verification"]["buildId"] = "b" * 12 + "-" + "a" * 12
        invalid.append(wrong_build)
        missing_commit = copy.deepcopy(original)
        missing_commit["verifiedCommit"] = "0" * 40
        invalid.append(missing_commit)
        wrong_owner = copy.deepcopy(original)
        wrong_owner["owner"] = "another role"
        invalid.extend([wrong_owner, [], {"schemaVersion": 999}])
        for number, record in enumerate(invalid):
            with self.subTest(record=number):
                path.write_text(json.dumps(record) + "\n")
                self.share("voc", "docs/status/voc.json")
                self.assertFalse(repo.team_status())

    def test_report_validation_rejects_missing_duplicate_and_non_live_results(self):
        good = self.report("commerce")
        validate_mvp_result(good, good["buildId"])
        invalid = [None, {"cases": None}]
        for field, value in [("mode", "mock"), ("model", ""), ("cases", good["cases"] * 2),
                             ("cases", [{"id": []}]), ("cases", [])]:
            data = copy.deepcopy(good)
            data[field] = value
            invalid.append(data)
        for number, data in enumerate(invalid):
            with self.subTest(report=number), self.assertRaises(WorkflowError):
                validate_mvp_result(data, good["buildId"])


if __name__ == "__main__":
    unittest.main()
