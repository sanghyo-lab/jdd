#!/usr/bin/env python3
"""Exercise local worker ownership and restart recovery in a dedicated PostgreSQL test DB.

Starts temporary Agent JVMs with a disabled model. Does not reset the application DB.
Requires an existing Compose DB and a built agent-app/build/libs/app.jar.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.request import Request, urlopen
from uuid import uuid4

DATABASE = "jdd_agent_worker_test"
ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-dir", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--report-dir", type=Path, default=ROOT / "runtime/agent-worker-check")
    args = parser.parse_args()
    config = dict(line.split("=", 1) for line in (args.compose_dir / ".env").read_text().splitlines()
                  if line and not line.startswith("#") and "=" in line)
    compose = ["docker", "compose", "--env-file", ".env", "-p", args.project, "exec", "-T", "db",
               "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", config["POSTGRES_USER"]]

    def sql(statement, database=DATABASE):
        result = subprocess.run(compose + ["-d", database, "-At"], cwd=args.compose_dir,
                                input=statement, text=True, capture_output=True, check=True)
        return result.stdout.strip()

    if sql("SELECT 1 FROM pg_database WHERE datname = 'jdd_agent_worker_test'", "postgres") != "1":
        sql("CREATE DATABASE jdd_agent_worker_test OWNER jdd_agent", "postgres")
    args.report_dir.mkdir(parents=True, exist_ok=False)
    processes, logs = [], []
    safe_names = ("PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME")
    clean = {key: os.environ[key] for key in safe_names if key in os.environ}
    clean.update(DB_URL="jdbc:postgresql://127.0.0.1:" + config["POSTGRES_PORT"] + "/" + DATABASE + "?currentSchema=agent",
                 DB_USERNAME="jdd_agent", DB_PASSWORD=config["AGENT_DB_PASSWORD"],
                 SPRING_FLYWAY_CREATE_SCHEMAS="true", APP_BUILD_ID="local-worker-synthetic-check")
    java = str(Path(clean["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in clean else "java"

    def http(port, path, payload=None):
        req = Request("http://127.0.0.1:" + str(port) + path,
                      data=None if payload is None else json.dumps(payload).encode(),
                      headers={"Content-Type": "application/json"})
        with urlopen(req, timeout=3) as response:
            return response.status, json.load(response)

    def wait(check, description, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = check()
                if value:
                    return value
            except (OSError, TimeoutError):
                pass
            time.sleep(0.1)
        raise RuntimeError("Timed out: " + description)

    def start(label, enabled=True, maximum_wait="PT10M"):
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        env = {**clean, "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(port),
               "JDD_AGENT_WORKER_ENABLED": str(enabled).lower(), "JDD_AGENT_QUEUE_MAXIMUM_WAIT": maximum_wait}
        log = (args.report_dir / (label + ".log")).open("w")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(ROOT / "agent-app/build/libs/app.jar")],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        wait(lambda: http(port, "/actuator/health")[1].get("status") == "UP", label + " health")
        runtime = http(port, "/internal/runtime")[1]
        require(runtime["investigationModel"] == "DISABLED", "Refusing a configured model")
        return process, port

    def stop(process):
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def submit(port):
        payload = {"schemaVersion": "1.0", "ticketId": "worker-check-" + str(uuid4()), "ticketVersion": 1,
                   "requestKey": "check", "message": "Synthetic worker lifecycle check"}
        status, accepted = http(port, "/api/investigations", payload)
        require(status == 202, "Intake must return 202")
        return accepted["investigationId"], payload

    def terminal(port, investigation):
        def read():
            view = http(port, "/api/investigations/" + investigation)[1]
            return view if view["status"] == "FAILED" else None
        return wait(read, "terminal state")

    try:
        # Accept with a real short persisted deadline, then restart with a longer setting.
        expired_intake, expired_port = start("expired-intake", False, "PT1S")
        expired_id, expired_input = submit(expired_port)
        saved_deadline = sql("SELECT queued_deadline_at FROM agent.investigations WHERE investigation_id=" + literal(expired_id))
        stop(expired_intake)
        intake, port = start("intake", False)
        interrupted_id, interrupted_input = submit(port)
        queued_id, _ = submit(port)
        view = http(port, "/api/investigations/" + interrupted_id)[1]
        stop(intake)
        # Explicit synthetic crash snapshot, not evidence from a real commerce/model execution.
        stamp = datetime.now(timezone.utc).isoformat()
        evidence_id = str(uuid4())
        evidence = {"evidenceId": evidence_id, "type": "DATA", "summary": "Synthetic preserved observation",
                    "observedAt": stamp, "source": {"schema": "commerce", "table": "synthetic"}}
        detail = {**evidence, "content": {"columns": ["quantity"], "rows": [[1]]}, "truncated": False}
        view.update(status="RUNNING", updatedAt=stamp, evidence=[evidence], progress=[{
            "toolExecutionId": str(uuid4()), "toolName": "searchLogs", "status": "RUNNING", "startedAt": stamp,
            "finishedAt": None, "summary": "Synthetic interrupted tool", "evidenceIds": [], "error": None}])
        sql("UPDATE agent.investigations SET execution_status='RUNNING', execution_token='synthetic-crash', "
            "deadline_at=CURRENT_TIMESTAMP + INTERVAL '3 minutes', view_json=" + literal(json.dumps(view)) +
            " WHERE investigation_id=" + literal(interrupted_id) + ";\n"
            "INSERT INTO agent.investigation_evidence VALUES (" + literal(interrupted_id) + "," + literal(evidence_id) + "," + literal(json.dumps(detail)) + ")")
        owner, owner_port = start("owner")
        expired = terminal(owner_port, expired_id)
        require(expired["error"]["code"] == "INVESTIGATION_TIMEOUT" and "대기" in expired["error"]["message"],
                "Restart dispatched an expired queue request")
        require(expired["progress"] == [] and expired["report"] is None, "Expired queue request executed work")
        require(sql("SELECT queued_deadline_at FROM agent.investigations WHERE investigation_id=" + literal(expired_id)) == saved_deadline,
                "Restart or new queue settings extended a persisted deadline")
        require(http(owner_port, "/api/investigations", expired_input)[1] == {
                    "investigationId": expired_id, "ticketId": expired_input["ticketId"], "status": "FAILED"},
                "Replay replaced or restarted an expired investigation")
        recovered = terminal(owner_port, interrupted_id)
        require(recovered["error"]["code"] == "INTERRUPTED", "Restart did not interrupt a previous running job")
        require(recovered["progress"][0]["status"] == "FAILED", "Interrupted tool remained RUNNING")
        require(http(owner_port, "/api/investigations/" + interrupted_id + "/evidence/" + evidence_id)[1]["content"] == detail["content"],
                "Restart lost original evidence")
        require(terminal(owner_port, queued_id)["error"]["code"] == "LLM_CONFIGURATION_ERROR", "Queued job was not resumed")
        waiter, waiter_port = start("waiter")
        time.sleep(0.75)  # Allow at least one 500 ms dispatch tick in the second JVM.
        require("acquired ownership" not in (args.report_dir / "waiter.log").read_text(),
                "A second worker acquired ownership while the first JVM was active")
        require(http(waiter_port, "/api/investigations", interrupted_input)[1]["investigationId"] == interrupted_id,
                "Restart changed idempotency identity")
        # Ensure the second JVM did not enter recovery while the first owns the advisory lock.
        new_id, _ = submit(waiter_port)
        require(terminal(waiter_port, new_id)["error"]["code"] == "LLM_CONFIGURATION_ERROR", "Active owner did not dispatch")
        stop(owner)
        handoff_id, _ = submit(waiter_port)
        require(terminal(waiter_port, handoff_id)["error"]["code"] == "LLM_CONFIGURATION_ERROR", "Waiting JVM did not take ownership")
        require("acquired ownership" in (args.report_dir / "waiter.log").read_text(), "Ownership handoff was not observed")
        count = sql("SELECT count(*) FROM agent.model_calls WHERE investigation_id IN (" +
                    ",".join(literal(value) for value in (expired_id, interrupted_id, queued_id, new_id, handoff_id)) + ")")
        require(count == "0", "Disabled model unexpectedly reserved a paid call")
        report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "verificationProfile": "synthetic-worker-no-model",
                  "database": DATABASE, "investigationIds": [expired_id, interrupted_id, queued_id, new_id, handoff_id],
                  "expiredQueuedInvestigationId": expired_id, "persistedQueueDeadline": saved_deadline,
                  "evidenceId": evidence_id, "modelCalls": 0,
                  "checks": ["durable-queue", "restart-interrupted", "preserved-evidence", "same-key-after-restart",
                             "exclusive-worker-ownership", "ownership-handoff", "unconfigured-model-fails-closed",
                             "expired-queue-not-dispatched-after-restart", "queue-deadline-preserved-across-settings"]}
        (args.report_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        for process in processes:
            if process.poll() is None:
                stop(process)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
