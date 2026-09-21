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
from lead_review import LEAD_CHECKS, REVIEW_PATH
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
                (path / ".gitignore").write_text(".jdd-role\n")
                (path / "app.txt").write_text("initial implementation\n")
                for name in self.review_paths().values():
                    target = path / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("unit test code review fixture\n")
                self.share(role, ".")
            (path / ".jdd-role").write_text(role + "\n")
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

    @staticmethod
    def review_paths():
        return {"commerce": "commerce-app/App.java", "agent": "agent-app/App.java",
                "voc": "voc-app/App.java", "web": "web/page.tsx", "scenarios": "scenario-runner/Main.java",
                "runtime": "compose.yaml", "contracts": "docs/commerce-interface.md"}

    def prepare_review(self):
        repo = Repository(self.clones["commerce"])
        repo.sync()
        review = {"schemaVersion": 1, "reviewedCommit": repo.git("rev-parse", "HEAD"),
                  "contentSha256": repo.completion_fingerprint("HEAD"),
                  "areas": [{"id": area, "paths": [path], "summary": "unit test review fixture"}
                            for area, path in self.review_paths().items()],
                  "checks": [{"id": check, "status": "PASSED", "evidence": "unit test evidence fixture"}
                             for check in sorted(LEAD_CHECKS)],
                  "reproductions": [{"id": "VOC-%02d" % number, "runs": 20 if number == 7 else 3,
                                     "passed": 20 if number == 7 else 3, "evidence": "unit test repetitions fixture"}
                                    for number in range(1, 8)], "findings": []}
        (repo.root / REVIEW_PATH).write_text(json.dumps(review) + "\n")
        self.share("commerce", REVIEW_PATH)
        return repo

    def approve_lead(self, repo, push=True):
        with patch.object(repo, "verify_mvp", return_value=self.report("commerce")) as verify:
            repo.lead_approve()
            verify.assert_called_once_with()
        if push:
            self.share("commerce", "docs/status/lead.json")

    def test_missing_or_only_two_done_records_never_complete_the_team(self):
        repo = Repository(self.clones["commerce"])
        self.assertFalse(repo.roles_status())
        self.finish_role("commerce")
        self.finish_role("agent")
        self.assertFalse(repo.roles_status())
        self.assertIn("MISSING", self.output.getvalue())

    def test_three_independent_completions_and_status_only_commits_are_valid(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        self.assertTrue(repo.roles_status())
        repo.sync()
        records = [json.loads((repo.root / "docs/status" / (role + ".json")).read_text()) for role in TEAM_ROLES]
        self.assertEqual(len({record["verifiedCommit"] for record in records}), 3)
        self.assertEqual(len({record["contentSha256"] for record in records}), 1)
        (repo.root / "docs/status/commerce.md").write_text("Waiting for team completion.\n")
        self.share("commerce", "docs/status/commerce.md")
        repo.roles_check()
        self.assertFalse(repo.team_status())
        with self.assertRaisesRegex(WorkflowError, "lead approval"):
            repo.team_check()

    def test_local_unpublished_done_is_not_counted(self):
        self.finish_role("commerce")
        self.finish_role("agent")
        self.finish_role("voc", push=False)
        self.assertFalse(Repository(self.clones["voc"]).roles_status())
        self.assertIn("voc (김아름): MISSING", self.output.getvalue())

    def test_implementation_change_invalidates_every_previous_completion(self):
        self.finish_all()
        repo = Repository(self.clones["voc"])
        (repo.root / "app.txt").write_text("new implementation\n")
        self.share("voc", "app.txt")
        self.assertFalse(repo.roles_status())
        self.assertEqual(self.output.getvalue().count("STALE:"), 3)

    def test_goal_criteria_change_also_requires_reverification(self):
        self.finish_all()
        repo = Repository(self.clones["voc"])
        criteria = repo.root / "docs/goals/commerce.md"
        criteria.parent.mkdir(parents=True)
        criteria.write_text("An additional acceptance criterion.\n")
        self.share("voc", "docs/goals/commerce.md")
        self.assertFalse(repo.roles_status())

    def test_reopening_one_role_withdraws_team_completion(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        repo.sync()
        repo.role_reopen("commerce")
        self.share("commerce", "docs/status/commerce.json")
        self.assertFalse(repo.roles_status())
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

    def test_roles_check_requires_clean_up_to_date_local_main(self):
        self.finish_all()
        repo = Repository(self.clones["commerce"])
        with self.assertRaisesRegex(WorkflowError, "Sync main"):
            repo.roles_check()
        repo.sync()
        repo.roles_check()
        (repo.root / "app.txt").write_text("unshared work\n")
        with self.assertRaisesRegex(WorkflowError, "not clean"):
            repo.roles_check()

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
                self.assertFalse(repo.roles_status())

    def test_leader_approval_reruns_verification_and_only_counts_after_push(self):
        self.finish_all()
        repo = self.prepare_review()
        self.approve_lead(repo, push=False)
        self.assertFalse(repo.team_status())
        self.share("commerce", "docs/status/lead.json")
        repo.team_check()

    def test_failed_independent_verification_cannot_approve_the_project(self):
        self.finish_all()
        repo = self.prepare_review()
        with patch.object(repo, "verify_mvp", side_effect=WorkflowError("independent verification failed")):
            with self.assertRaisesRegex(WorkflowError, "independent verification failed"):
                repo.lead_approve()
        self.assertFalse((repo.root / "docs/status/lead.json").exists())
        self.assertFalse(repo.team_status())

    def test_leader_cannot_approve_without_all_three_role_completions(self):
        self.finish_role("commerce")
        repo = self.prepare_review()
        with patch.object(repo, "verify_mvp") as verify:
            with self.assertRaisesRegex(WorkflowError, "All three owners"):
                repo.lead_approve()
            verify.assert_not_called()

    def test_incomplete_review_or_open_findings_block_approval(self):
        self.finish_all()
        repo = self.prepare_review()
        path = repo.root / REVIEW_PATH
        original = json.loads(path.read_text())
        invalid = []
        area = copy.deepcopy(original)
        area["areas"].pop()
        invalid.append(area)
        missing_file = copy.deepcopy(original)
        missing_file["areas"][0]["paths"] = ["commerce-app/absent.java"]
        invalid.append(missing_file)
        check = copy.deepcopy(original)
        check["checks"][0]["status"] = "SKIPPED"
        invalid.append(check)
        repeats = copy.deepcopy(original)
        repeats["reproductions"][-1]["runs"] = 19
        repeats["reproductions"][-1]["passed"] = 19
        invalid.append(repeats)
        issue = copy.deepcopy(original)
        issue["findings"] = [{"id": "LEAD-001", "owner": "agent", "status": "OPEN", "description": "bug"}]
        invalid.append(issue)
        for number, record in enumerate(invalid):
            with self.subTest(record=number):
                path.write_text(json.dumps(record) + "\n")
                self.share("commerce", REVIEW_PATH)
                with patch.object(repo, "verify_mvp") as verify:
                    with self.assertRaises(ValueError):
                        repo.lead_approve()
                    verify.assert_not_called()

    def test_changed_review_invalidates_an_existing_approval(self):
        self.finish_all()
        repo = self.prepare_review()
        self.approve_lead(repo)
        path = repo.root / REVIEW_PATH
        review = json.loads(path.read_text())
        review["findings"] = [{"id": "LEAD-001", "owner": "voc", "status": "OPEN", "description": "new bug"}]
        path.write_text(json.dumps(review) + "\n")
        self.share("commerce", REVIEW_PATH)
        self.assertFalse(repo.team_status())
        self.assertIn("review evidence changed", self.output.getvalue())

    def test_replaced_role_declaration_requires_new_leader_approval(self):
        self.finish_all()
        repo = self.prepare_review()
        self.approve_lead(repo)
        peer = Repository(self.clones["agent"])
        peer.sync()
        path = peer.root / "docs/status/agent.json"
        record = json.loads(path.read_text())
        record["verifiedAt"] = "2026-09-21T01:02:03+00:00"
        path.write_text(json.dumps(record) + "\n")
        self.share("agent", "docs/status/agent.json")
        self.assertTrue(repo.roles_status())
        self.assertFalse(repo.team_status())
        self.assertIn("role completion records changed", self.output.getvalue())
        repo.sync()
        approval_path = repo.root / "docs/status/lead.json"
        approval = json.loads(approval_path.read_text())
        approval["roleRecords"]["agent"] = repo.git("rev-parse", "HEAD:docs/status/agent.json")
        approval_path.write_text(json.dumps(approval) + "\n")
        self.share("commerce", "docs/status/lead.json")
        # Relabeling an old approval cannot replace independent verification of the new declarations.
        self.assertFalse(repo.team_status())

    def test_leader_fix_requires_new_role_results_and_new_review(self):
        self.finish_all()
        repo = self.prepare_review()
        self.approve_lead(repo)
        (repo.root / "agent-app/App.java").write_text("fixed by development lead\n")
        self.share("commerce", "agent-app/App.java")
        self.assertFalse(repo.team_status())
        self.finish_all()
        self.assertTrue(repo.roles_status())
        self.assertFalse(repo.team_status())
        repo = self.prepare_review()
        review_path = repo.root / REVIEW_PATH
        review = json.loads(review_path.read_text())
        review["findings"] = [{"id": "LEAD-001", "owner": "agent", "status": "VERIFIED",
                               "description": "unit test defect fixture", "resolution": "agent-app/App.java fixed",
                               "verification": "unit test regression evidence fixture"}]
        review_path.write_text(json.dumps(review) + "\n")
        self.share("commerce", REVIEW_PATH)
        self.approve_lead(repo)
        repo.team_check()

    def test_leader_approval_can_be_withdrawn_without_code_changes(self):
        self.finish_all()
        repo = self.prepare_review()
        self.approve_lead(repo)
        repo.lead_reopen()
        self.share("commerce", "docs/status/lead.json")
        self.assertTrue(repo.roles_status())
        self.assertFalse(repo.team_status())

    def test_another_role_clone_cannot_accidentally_approve_as_lead(self):
        repo = Repository(self.clones["agent"])
        with self.assertRaisesRegex(WorkflowError, "commerce clone"):
            repo.lead_approve()

    def test_remote_change_during_leader_verification_prevents_approval(self):
        self.finish_all()
        repo = self.prepare_review()

        def racing_verification():
            data = self.report("commerce")
            peer = Repository(self.clones["voc"])
            peer.sync()
            (peer.root / "app.txt").write_text("new change during verification\n")
            self.share("voc", "app.txt")
            return data

        with patch.object(repo, "verify_mvp", side_effect=racing_verification):
            with self.assertRaisesRegex(WorkflowError, "main changed during leader"):
                repo.lead_approve()
        self.assertFalse((repo.root / "docs/status/lead.json").exists())

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
