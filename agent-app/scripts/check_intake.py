#!/usr/bin/env python3
"""Verify intake against a running Agent. Uses synthetic input; never invokes a model."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4


def request(base, path, payload=None):
    req = Request(base.rstrip("/") + "/api/investigations" + path,
                  data=None if payload is None else json.dumps(payload).encode(),
                  headers={"Content-Type": "application/json"})
    try:
        with urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify_saved(base, data):
    status, accepted = request(base, "", data["input"])
    require(status == 202 and accepted["investigationId"] == data["investigationId"],
            "Stored request did not retain its investigation ID")
    status, view = request(base, "/" + data["investigationId"])
    require(status == 200 and view["ticketId"] == data["input"]["ticketId"], "Stored view is missing")
    require(view["createdAt"] == data["createdAt"], "Stored creation time changed")
    return view


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--report", type=Path, default=Path("runtime/agent-intake.json"))
    parser.add_argument("--verify-existing", action="store_true", help="Recheck the saved report after an Agent restart")
    args = parser.parse_args()
    if args.verify_existing:
        data = json.loads(args.report.read_text())
        verify_saved(args.base_url, data)
        data["persistenceRecheckedAt"] = datetime.now(timezone.utc).isoformat()
        args.report.write_text(json.dumps(data, indent=2) + "\n")
        print("PASS: persisted request and investigation retained across restart")
        return

    payload = {"schemaVersion": "1.0", "ticketId": "intake-check-" + str(uuid4()),
               "ticketVersion": 1, "requestKey": "concurrent-check", "message": "Synthetic intake verification"}
    with ThreadPoolExecutor(max_workers=8) as executor:
        responses = list(executor.map(lambda _: request(args.base_url, "", payload), range(8)))
    require(all(status == 202 for status, _ in responses), "Concurrent intake failed")
    ids = {body["investigationId"] for _, body in responses}
    require(len(ids) == 1, "Concurrent intake created multiple investigations")
    investigation_id = ids.pop()

    status, view = request(args.base_url, "/" + investigation_id)
    require(status == 200 and view["schemaVersion"] == "1.0", "Investigation view failed")
    require(view["status"] == "QUEUED" and view["report"] is None and view["error"] is None,
            "Intake-only implementation must retain QUEUED state")
    require(view["progress"] == [] and view["evidence"] == [], "Intake must not invent evidence")
    status, normalized = request(args.base_url, "", {**payload, "context": {"orderId": None}})
    require(status == 202 and normalized["investigationId"] == investigation_id, "Null normalization failed")
    status, conflict = request(args.base_url, "", {**payload, "message": "Changed input"})
    require(status == 409 and conflict["code"] == "REQUEST_KEY_CONFLICT", "Conflict detection failed")
    status, missing = request(args.base_url, "/" + investigation_id + "/evidence/absent")
    require(status == 404 and missing["code"] == "NOT_FOUND", "Missing evidence did not return 404")
    status, invalid = request(args.base_url, "", {**payload, "schemaVersion": "2.0"})
    require(status == 400 and invalid["code"] == "INVALID_REQUEST", "Unsupported schema was accepted")
    for malformed in ({"context": {"orderId": 7}}, {"context": {"occurredAt": 1789948800}},
                      {"ticketVersion": 1.5}, {"requestKey": False}):
        status, invalid = request(args.base_url, "", {**payload, **malformed})
        require(status == 400 and invalid["code"] == "INVALID_REQUEST", "Invalid JSON scalar type was accepted")

    data = {"checkedAt": datetime.now(timezone.utc).isoformat(), "mode": "intake-only",
            "input": payload, "investigationId": investigation_id, "createdAt": view["createdAt"],
            "checks": ["concurrent-intake", "persisted-view", "null-normalization", "input-conflict",
                       "missing-evidence", "schema-validation", "strict-json-scalar-types"]}
    verify_saved(args.base_url, data)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(data, indent=2) + "\n")
    print("PASS: intake HTTP checks; no model or business analysis was executed")


if __name__ == "__main__":
    main()
