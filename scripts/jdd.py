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


class WorkflowError(RuntimeError):
    pass


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
        for role in ("commerce", "agent", "voc"):
            text = self.git("show", "origin/main:docs/status/" + role + ".md", check=False)
            print("\n" + text, flush=True)

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
            folder = self.root / prefix
            if folder.exists():
                for path in sorted(folder.rglob("*")):
                    if path.is_symlink():
                        raise WorkflowError("Source snapshots do not follow symlinks: " + str(path))
                    if path.is_file():
                        files.append(path)
        return files

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
                if path.is_symlink():
                    raise WorkflowError("Build inputs must not be symlinks: " + name)
                digest.update(name.encode() + b"\0" + path.read_bytes() + b"\0")
        commit = self.git("rev-parse", "HEAD")
        return commit, digest.hexdigest()

    def snapshot(self):
        self.setup()
        commit, content_hash = self.build_identity()
        build_id = commit[:12] + "-" + content_hash[:12]
        destination = self.root / "runtime/evidence/source" / build_id
        manifest_path = destination / "manifest.json"
        files = self.source_files()
        checksums = {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
        if manifest_path.exists():
            saved = json.loads(manifest_path.read_text())
            if saved["files"] != checksums or saved["contentSha256"] != content_hash:
                raise WorkflowError("An immutable source snapshot was changed: " + build_id)
            for name, checksum in checksums.items():
                if hashlib.sha256((destination / name).read_bytes()).hexdigest() != checksum:
                    raise WorkflowError("Snapshot content does not match its manifest: " + name)
        else:
            destination.mkdir(parents=True, exist_ok=False)
            for source in files:
                target = destination / source.relative_to(self.root)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            manifest = {"schemaVersion": "1.0", "buildId": build_id, "commitSha": commit,
                        "contentSha256": content_hash, "workingTreeDirty": bool(self.git("status", "--porcelain")),
                        "createdAt": datetime.now(timezone.utc).isoformat(),
                        "policyVersion": "demo-v1", "files": checksums}
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
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
        required = {"VOC-%02d" % number for number in range(1, 8)} | {"NORMAL", "NEEDS_INPUT", "IDEMPOTENCY", "RECOVERY"}
        cases = data.get("cases", [])
        case_ids = [case.get("id") for case in cases]
        if (data.get("buildId") != report["buildId"] or data.get("mode") != "live"
                or not data.get("model") or len(case_ids) != len(set(case_ids))
                or not required.issubset(set(case_ids))
                or any(case.get("status") != "PASSED" or case.get("mocked") is not False for case in cases)):
            raise WorkflowError("MVP verification needs current-build, live-model results for every required case.")
        print("PASS: current-build MVP scenarios (see runtime/scenarios.json).", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["setup", "up", "down", "smoke", "check", "verify",
                                          "verify-mvp", "sync", "publish", "status", "watch", "snapshot"])
    args = parser.parse_args()
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
