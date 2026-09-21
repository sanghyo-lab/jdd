#!/usr/bin/env python3
"""Shared local stack and main-branch workflow. Python 3.9+, standard library only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
from urllib.request import urlopen

from lead_review import LEAD_OWNER, REVIEW_PATH, validate_lead_review


class WorkflowError(RuntimeError):
    pass


TEAM_ROLES = {"commerce": "이상효", "agent": "한재홍", "voc": "김아름"}
MVP_CASES = {"VOC-%02d" % number for number in range(1, 8)} | {"NORMAL", "NEEDS_INPUT", "IDEMPOTENCY", "RECOVERY"}


def validate_mvp_result(data, build_id):
    if not isinstance(data, dict):
        raise WorkflowError("MVP report must be an object.")
    cases = data.get("cases")
    if not isinstance(cases, list) or any(not isinstance(case, dict) for case in cases):
        raise WorkflowError("MVP report must contain scenario results.")
    case_ids = [case.get("id") for case in cases]
    if (data.get("buildId") != build_id or data.get("mode") != "live"
            or not isinstance(data.get("model"), str) or not data["model"].strip()
            or any(not isinstance(case_id, str) for case_id in case_ids)
            or len(case_ids) != len(set(case_ids)) or not MVP_CASES.issubset(set(case_ids))
            or any(case.get("status") != "PASSED" or case.get("mocked") is not False for case in cases)):
        raise WorkflowError("MVP verification needs current-build, live-model results for every required case.")


class Repository:
    def __init__(self, root):
        self.root = Path(root).resolve()

    def run(self, args, capture=False, check=True, env=None):
        result = subprocess.run(
            [str(arg) for arg in args], cwd=self.root, text=True,
            stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.PIPE if capture else None, env=env)
        if check and result.returncode:
            # Commands never carry authentication values in their arguments.
            raise WorkflowError("Command failed: " + " ".join(str(arg) for arg in args)
                                + ("\n" + result.stderr.strip() if capture else ""))
        return result

    def git(self, *args, check=True):
        return self.run(["git", *args], capture=True, check=check).stdout.strip()

    def require_clean_main(self):
        if self.git("branch", "--show-current") != "main":
            raise WorkflowError("Use this clone's main branch.")
        for marker in ("rebase-merge", "rebase-apply", "MERGE_HEAD", "CHERRY_PICK_HEAD"):
            path = Path(self.git("rev-parse", "--git-path", marker))
            if not path.is_absolute():
                path = self.root / path
            if path.exists():
                raise WorkflowError("An unfinished Git operation must be resolved first: " + marker)
        if self.git("status", "--porcelain"):
            raise WorkflowError("Working tree is not clean. Preserve and explicitly commit your files first; no automatic stash/reset is used.")

    def sync(self):
        self.require_clean_main()
        self.git("fetch", "origin", "main")
        if not self.git("rev-parse", "--verify", "origin/main", check=False):
            raise WorkflowError("origin/main is missing.")
        ahead, behind = (int(value) for value in self.git(
            "rev-list", "--left-right", "--count", "HEAD...origin/main").split())
        if behind and ahead:
            result = self.run(["git", "rebase", "origin/main"], capture=True, check=False)
            if result.returncode:
                # Preserve the conflict for the development agent to resolve. Never discard either side.
                raise WorkflowError("Rebase needs conflict resolution. Inspect git status, resolve both changes, "
                                    "git add the resolved paths, then git rebase --continue. Nothing was reset.")
        elif behind:
            self.git("merge", "--ff-only", "origin/main")
        print("main synchronized:", self.git("rev-parse", "--short", "HEAD"), flush=True)

    def remote_status(self):
        # Fetch does not replace working files; safe to inspect between edit steps.
        self.git("fetch", "origin", "main")
        print(self.git("log", "-5", "--format=%h %s", "origin/main"), flush=True)
        for role in ("commerce", "agent", "voc", "lead"):
            text = self.git("show", "origin/main:docs/status/" + role + ".md", check=False)
            print("\n" + text, flush=True)
        self.team_status(fetch=False)

    def completion_fingerprint(self, ref):
        # Status-only commits must not invalidate the other two people's attestations.
        # Everything else, including contracts, goal criteria and tests, is included.
        entries = self.run(["git", "ls-tree", "-r", "-z", "--full-tree", ref], capture=True).stdout
        digest = hashlib.sha256()
        for entry in entries.split("\0"):
            if entry and not entry.split("\t", 1)[1].startswith("docs/status/"):
                digest.update(entry.encode() + b"\0")
        return digest.hexdigest()

    def completion_problem(self, record, role, tip, fingerprint):
        if not isinstance(record, dict) or record.get("schemaVersion") != 1:
            return "INVALID: unsupported completion record"
        owner = LEAD_OWNER if role == "lead" else TEAM_ROLES[role]
        if record.get("role") != role or record.get("owner") != owner:
            return "INVALID: role/owner mismatch"
        if record.get("status") == "IN_PROGRESS":
            return "IN_PROGRESS"
        expected_status = "APPROVED" if role == "lead" else "DONE"
        if record.get("status") != expected_status:
            return "INVALID: expected IN_PROGRESS or " + expected_status
        commit = record.get("verifiedCommit")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            return "INVALID: missing verified commit"
        ancestor = self.run(["git", "merge-base", "--is-ancestor", commit, tip], capture=True, check=False)
        if ancestor.returncode:
            return "INVALID: verified commit is not in remote main history"
        if record.get("contentSha256") != fingerprint:
            return "STALE: implementation or completion criteria changed; verify again"
        if self.completion_fingerprint(commit) != fingerprint:
            return "INVALID: verified commit does not contain the reported content"
        try:
            stamp = datetime.fromisoformat(record.get("verifiedAt", ""))
            if stamp.tzinfo is None:
                return "INVALID: verification time must include a timezone"
            verification = record.get("verification")
            if not isinstance(verification, dict) or verification.get("command") != "./scripts/dev verify-mvp":
                return "INVALID: missing MVP verification"
            build_id = verification.get("buildId")
            if not isinstance(build_id, str) or not re.fullmatch(commit[:12] + r"-[0-9a-f]{12}", build_id):
                return "INVALID: verification build does not match the verified commit"
            validate_mvp_result(verification, build_id)
            if role == "lead":
                expected_records = {name: self.git("rev-parse", tip + ":docs/status/" + name + ".json", check=False)
                                    for name in TEAM_ROLES}
                verified_records = {name: self.git("rev-parse", commit + ":docs/status/" + name + ".json", check=False)
                                    for name in TEAM_ROLES}
                if record.get("roleRecords") != expected_records or expected_records != verified_records:
                    return "STALE: role completion records changed after leader verification"
                review_blob = self.git("rev-parse", tip + ":" + REVIEW_PATH, check=False)
                if (record.get("reviewBlob") != review_blob
                        or review_blob != self.git("rev-parse", commit + ":" + REVIEW_PATH, check=False)):
                    return "STALE: leader review evidence changed after approval"
                validate_lead_review(self, json.loads(self.git("show", tip + ":" + REVIEW_PATH)), commit)
        except (TypeError, ValueError, WorkflowError) as error:
            return "INVALID: " + str(error)
        return None

    def team_status(self, fetch=True):
        return self.completion_status(fetch, include_lead=True)

    def roles_status(self, fetch=True):
        return self.completion_status(fetch, include_lead=False)

    def completion_status(self, fetch, include_lead):
        if fetch:
            self.git("fetch", "origin", "main")
        # Read every record from one immutable remote commit, never local unpushed files.
        tip = self.git("rev-parse", "origin/main")
        fingerprint = self.completion_fingerprint(tip)
        complete = True
        print("Team completion on origin/main:", tip[:12], flush=True)
        print("contentSha256:", fingerprint, flush=True)
        owners = {**TEAM_ROLES, **({"lead": LEAD_OWNER} if include_lead else {})}
        for role, owner in owners.items():
            raw = self.git("show", tip + ":docs/status/" + role + ".json", check=False)
            try:
                problem = self.completion_problem(json.loads(raw), role, tip, fingerprint) if raw else "MISSING"
            except (ValueError, WorkflowError) as error:
                problem = "INVALID: " + str(error)
            complete = complete and problem is None
            print("  " + role + " (" + owner + "): " + (problem or ("APPROVED" if role == "lead" else "DONE")), flush=True)
        success = "TEAM_COMPLETE" if include_lead else "ROLES_READY: development lead review is still required."
        print(success if complete else "TEAM_INCOMPLETE: keep the role goal active.", flush=True)
        return complete

    def team_check(self):
        self.check_completion(include_lead=True)

    def roles_check(self):
        self.check_completion(include_lead=False)

    def check_completion(self, include_lead):
        self.require_clean_main()
        complete = self.completion_status(fetch=True, include_lead=include_lead)
        if self.git("rev-parse", "HEAD") != self.git("rev-parse", "origin/main"):
            raise WorkflowError("Sync main and publish any local commits before ending the goal.")
        if not complete:
            raise WorkflowError("All three owners must publish valid DONE records for the same current content."
                                + (" Independent development lead approval is also required." if include_lead else ""))

    def role_done(self, role):
        self.sync()
        commit = self.git("rev-parse", "HEAD")
        if commit != self.git("rev-parse", "origin/main"):
            raise WorkflowError("Publish implementation commits before recording role completion.")
        data = self.verify_mvp()
        self.require_clean_main()
        if self.git("rev-parse", "HEAD") != commit:
            raise WorkflowError("HEAD changed during verification; verify again.")
        self.write_role_record(role, self.completion_record(role, commit, data))
        print("Commit and publish your completion record. Keep the goal active through development lead review until team-check passes.", flush=True)

    def completion_record(self, role, commit, data):
        verification = {key: data[key] for key in ("buildId", "mode", "model")}
        verification["command"] = "./scripts/dev verify-mvp"
        # Only a sanitized result summary is shared. Raw logs, prompts and DB rows stay local.
        verification["cases"] = [{key: case[key] for key in ("id", "status", "mocked")} for case in data["cases"]]
        return {"schemaVersion": 1, "role": role, "owner": LEAD_OWNER if role == "lead" else TEAM_ROLES[role],
                "status": "APPROVED" if role == "lead" else "DONE", "verifiedCommit": commit,
                "contentSha256": self.completion_fingerprint(commit),
                "verifiedAt": datetime.now(timezone.utc).isoformat(), "verification": verification}

    def require_lead(self):
        identity = self.root / ".jdd-role"
        if not identity.exists() or identity.read_text().strip() != "commerce":
            raise WorkflowError("Leader actions belong to 이상효's commerce clone; its local .jdd-role must be commerce.")

    def lead_approve(self):
        self.require_lead()
        self.sync()
        self.roles_check()
        commit = self.git("rev-parse", "HEAD")
        review = json.loads(self.git("show", commit + ":" + REVIEW_PATH))
        validate_lead_review(self, review, commit)
        # Run the full pipeline here. A peer's DONE or an old runtime JSON is insufficient.
        data = self.verify_mvp()
        self.require_clean_main()
        self.git("fetch", "origin", "main")
        if self.git("rev-parse", "HEAD") != commit or self.git("rev-parse", "origin/main") != commit:
            raise WorkflowError("main changed during leader verification; sync and revalidate before approving.")
        record = self.completion_record("lead", commit, data)
        record["reviewBlob"] = self.git("rev-parse", commit + ":" + REVIEW_PATH)
        record["roleRecords"] = {role: self.git("rev-parse", commit + ":docs/status/" + role + ".json")
                                 for role in TEAM_ROLES}
        self.write_role_record("lead", record)
        print("Publish the leader approval, then run scripts/dev team-check before ending any goal.", flush=True)

    def lead_reopen(self):
        self.require_lead()
        self.write_role_record("lead", {"schemaVersion": 1, "role": "lead", "owner": LEAD_OWNER,
                                       "status": "IN_PROGRESS", "verifiedCommit": None, "contentSha256": None,
                                       "verifiedAt": None, "verification": None, "reviewBlob": None, "roleRecords": {}})
        print("Leader approval withdrawn locally; publish the finding and revised review record.", flush=True)

    def role_reopen(self, role):
        self.write_role_record(role, {"schemaVersion": 1, "role": role, "owner": TEAM_ROLES[role],
                                      "status": "IN_PROGRESS", "verifiedCommit": None, "contentSha256": None,
                                      "verifiedAt": None, "verification": None})
        print("Completion withdrawn locally; record the reason in your status Markdown and publish.", flush=True)

    def write_role_record(self, role, record):
        path = self.root / "docs/status" / (role + ".json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
        print("Updated", str(path.relative_to(self.root)), flush=True)

    def setup(self):
        target = self.root / ".env"
        if not target.exists():
            text = (self.root / ".env.example").read_text()
            for key in ("POSTGRES_PASSWORD", "COMMERCE_DB_PASSWORD", "AGENT_DB_PASSWORD",
                        "VOC_DB_PASSWORD", "EVIDENCE_DB_PASSWORD"):
                text = re.sub(r"^" + key + r"=.*$", key + "=" + secrets.token_hex(24), text, flags=re.M)
            # Never replace credentials for an existing DB volume.
            with target.open("x") as stream:
                os.chmod(target, 0o600)
                stream.write(text)
            print("Created local .env (credentials are not printed or committed).", flush=True)
        else:
            print("Using existing .env.", flush=True)
        (self.root / "runtime").mkdir(exist_ok=True)

    def read_environment(self):
        values = {}
        for path in (self.root / ".env", self.root / "runtime/build.env"):
            if path.exists():
                for line in path.read_text().splitlines():
                    if line and not line.startswith("#") and "=" in line:
                        key, value = line.split("=", 1)
                        values[key] = value.strip().strip('"').strip("'")
        return {"COMPOSE_PROGRESS": "plain", "BUILDKIT_PROGRESS": "plain", **values, **os.environ}

    def compose_command(self):
        if shutil.which("docker"):
            result = self.run(["docker", "compose", "version"], capture=True, check=False)
            if result.returncode == 0:
                return ["docker", "compose"]
        if shutil.which("docker-compose"):
            return ["docker-compose"]
        raise WorkflowError("Docker Compose is missing. Install Docker Desktop or Docker Engine + Compose; see docs/local-development.md.")

    def compose(self, *args):
        return self.run([*self.compose_command(), "--env-file", ".env", "-f", "compose.yaml", *args],
                        env=self.read_environment())

    def source_files(self):
        files = []
        for prefix in ("commerce-app/src/main/java", "commerce-core/src/main/java",
                       "commerce-infra/src/main/java", "commerce-infra/src/main/resources/db/migration"):
            folder = self.checked_path(prefix)
            if folder.exists():
                for path in sorted(folder.rglob("*")):
                    self.checked_path(path.relative_to(self.root).as_posix())
                    if path.is_file():
                        files.append(path)
        return files

    def checked_path(self, relative):
        # Check every component, including directory symlinks/Windows junctions.
        if (not relative or relative.startswith("/") or "\\" in relative or ":" in relative
                or any(part in ("", ".", "..") for part in relative.split("/"))):
            raise WorkflowError("Invalid snapshot path: " + relative)
        path = self.root
        for part in relative.split("/"):
            path = path / part
            if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
                raise WorkflowError("Source snapshots do not follow symlinks or junctions: " + relative)
        return path

    def policy_snapshot(self):
        path = self.checked_path("docs/business-policy.md")
        try:
            content = path.read_bytes()
            text = content.decode("utf-8")
        except (OSError, UnicodeError) as error:
            raise WorkflowError("The business policy must be an available UTF-8 file.") from error
        versions = re.findall(r"적용 버전은 `([^`\r\n]+)`", text)
        if (len(content) > 1024 * 1024 or len(versions) != 1
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", versions[0])):
            raise WorkflowError("The business policy needs one valid version and must fit the evidence size limit.")
        return content, {"version": versions[0], "path": "policy/business-policy.md",
                         "sha256": hashlib.sha256(content).hexdigest()}

    def build_identity(self):
        output = self.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], capture=True).stdout
        digest = hashlib.sha256()
        for name in sorted(set(output.split("\0")) - {""}):
            if name.startswith("docs/") and name != "docs/business-policy.md":
                continue
            if name == "AGENTS.md":
                continue
            path = self.root / name
            if path.is_file():
                self.checked_path(name)
                digest.update(name.encode() + b"\0" + path.read_bytes() + b"\0")
        commit = self.git("rev-parse", "HEAD")
        return commit, digest.hexdigest()

    def snapshot(self):
        self.setup()
        commit, content_hash = self.build_identity()
        policy_content, policy = self.policy_snapshot()
        build_id = commit[:12] + "-" + content_hash[:12]
        prefix = "runtime/evidence/source/" + build_id
        destination = self.checked_path(prefix)
        manifest_path = self.checked_path(prefix + "/manifest.json")
        source_content = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.source_files()}
        checksums = {name: hashlib.sha256(content).hexdigest() for name, content in source_content.items()}
        if manifest_path.exists():
            saved = json.loads(manifest_path.read_text(encoding="utf-8"))
            if "policy" not in saved:
                raise WorkflowError("Snapshot has no archived policy; create a new build. Existing snapshot was not changed.")
            expected = {"schemaVersion": "1.0", "buildId": build_id, "commitSha": commit,
                        "files": checksums, "contentSha256": content_hash,
                        "policyVersion": policy["version"], "policy": policy}
            if any(saved.get(key) != value for key, value in expected.items()):
                raise WorkflowError("An immutable source snapshot was changed: " + build_id)
            for name, checksum in {**checksums, policy["path"]: policy["sha256"]}.items():
                path = self.checked_path(prefix + "/" + name)
                if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
                    raise WorkflowError("Snapshot content does not match its manifest: " + name)
        else:
            if destination.exists():
                raise WorkflowError("An incomplete source snapshot exists; it was not overwritten: " + build_id)
            destination.mkdir(parents=True, exist_ok=False)
            for name, content in {**source_content, policy["path"]: policy_content}.items():
                target = self.checked_path(prefix + "/" + name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            if self.build_identity() != (commit, content_hash):
                raise WorkflowError("Build inputs changed during snapshot creation; retry from a stable worktree.")
            manifest = {"schemaVersion": "1.0", "buildId": build_id, "commitSha": commit,
                        "contentSha256": content_hash, "workingTreeDirty": bool(self.git("status", "--porcelain")),
                        "createdAt": datetime.now(timezone.utc).isoformat(),
                        "policyVersion": policy["version"], "policy": policy, "files": checksums}
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        log_root = self.root / "runtime/evidence/logs/commerce"
        log_dir = log_root / build_id
        log_dir.mkdir(parents=True, exist_ok=True)
        # Only ignored local demo log directories are writable by the container's UID.
        os.chmod(log_root, 0o777)
        os.chmod(log_dir, 0o777)
        (self.root / "runtime/build.env").write_text("APP_BUILD_ID=" + build_id + "\nAPP_COMMIT_SHA=" + commit + "\n")
        print("Prepared source snapshot:", build_id, flush=True)
        return build_id

    def up(self):
        self.snapshot()
        self.compose("up", "--build", "--detach", "--wait", "--wait-timeout", "240")

    def get_json(self, url):
        with urlopen(url, timeout=15) as response:
            return json.load(response)

    def smoke(self):
        env = self.read_environment()
        expected = env.get("APP_BUILD_ID")
        if not expected:
            raise WorkflowError("Start the stack with scripts/dev up first.")
        results = {}
        for role, default_port in (("commerce", "8080"), ("agent", "8081"), ("voc", "8082")):
            base = "http://127.0.0.1:" + env.get(role.upper() + "_PORT", default_port)
            health = self.get_json(base + "/actuator/health")
            runtime = self.get_json(base + "/internal/runtime")
            if health["status"] != "UP" or runtime["buildId"] != expected or runtime["service"] != role + "-app":
                raise WorkflowError("Service is unhealthy or runs a different build: " + role)
            results[role] = runtime
        agent = self.get_json("http://127.0.0.1:" + env.get("AGENT_PORT", "8081") + "/internal/dependencies")
        for field, value in (("canReadCommerce", True), ("canWriteCommerce", False), ("canCreateCommerce", False),
                             ("sourceManifestAvailable", True), ("logDirectoryAvailable", True), ("policyAvailable", True)):
            if agent.get(field) is not value:
                raise WorkflowError("Agent dependency/privilege check failed: " + field)
        voc = self.get_json("http://127.0.0.1:" + env.get("VOC_PORT", "8082") + "/internal/dependencies")
        if voc != {"agentHttpStatus": 200, "commerceHttpStatus": 200}:
            raise WorkflowError("VOC cannot reach its local Agent and commerce.")
        report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "buildId": expected,
                  "services": results, "agentDependencies": agent, "vocDependencies": voc}
        (self.root / "runtime/smoke.json").write_text(json.dumps(report, indent=2) + "\n")
        print("PASS: 3 apps, own DB migrations, VOC HTTP links, Agent SELECT-only access and evidence mounts.", flush=True)
        print("This verifies the shared runtime, not the business MVP.", flush=True)
        return report

    def check(self):
        self.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"])
        self.run([sys.executable, "scripts/check_docs.py"])
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        self.run([wrapper, "--no-daemon", "check"])
        if (self.root / "web/package.json").exists():
            subprocess.run(["npm", "ci"], cwd=self.root / "web", check=True)
            subprocess.run(["npm", "run", "build"], cwd=self.root / "web", check=True)

    def verify(self):
        self.check()
        self.up()
        return self.smoke()

    def publish(self):
        self.require_clean_main()
        for attempt in range(3):
            self.sync()
            self.verify()
            self.require_clean_main()
            result = self.run(["git", "push", "origin", "main"], capture=True, check=False)
            if result.returncode == 0:
                print(result.stderr.strip(), flush=True)
                print("Published verified main:", self.git("rev-parse", "--short", "HEAD"), flush=True)
                return
            # Revalidate after a race. Authentication/rules failures are not fixed by repeating a push.
            self.git("fetch", "origin", "main")
            behind = int(self.git("rev-list", "--count", "HEAD..origin/main"))
            if not behind:
                raise WorkflowError("Push failed without a new remote commit:\n" + result.stderr.strip())
            print("main advanced during verification; integrating and revalidating.", flush=True)
        raise WorkflowError("main changed during 3 publish attempts. Changes remain committed locally; retry publish.")

    def verify_mvp(self):
        report = self.verify()
        if any(not service.get("businessReady") for service in report["services"].values()):
            raise WorkflowError("Business MVP is not implemented in all three apps yet.")
        path = self.root / "runtime/scenarios.json"
        path.unlink(missing_ok=True)
        self.run(["./gradlew", "--no-daemon", ":scenario-runner:run",
                  "--args=--report runtime/scenarios.json"])
        data = json.loads(path.read_text())
        validate_mvp_result(data, report["buildId"])
        print("PASS: current-build MVP scenarios (see runtime/scenarios.json).", flush=True)
        return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["setup", "up", "down", "smoke", "check", "verify",
                                          "verify-mvp", "sync", "publish", "status", "watch", "snapshot",
                                          "team-status", "team-check", "role-done", "role-reopen",
                                          "roles-status", "roles-check", "lead-approve", "lead-reopen"])
    parser.add_argument("role", nargs="?", choices=list(TEAM_ROLES))
    args = parser.parse_args()
    if (args.command in ("role-done", "role-reopen")) != (args.role is not None):
        parser.error("Supply a role only for role-done or role-reopen.")
    repo = Repository(Path(__file__).resolve().parents[1])
    try:
        if args.command == "down":
            repo.compose("down")  # Data volumes are intentionally preserved.
        elif args.command == "status":
            repo.remote_status()
        elif args.command == "watch":
            while True:
                repo.remote_status()
                time.sleep(60)
        elif args.command in ("role-done", "role-reopen"):
            getattr(repo, args.command.replace("-", "_"))(args.role)
        else:
            getattr(repo, args.command.replace("-", "_"))()
    except (WorkflowError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print("ERROR:", error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
