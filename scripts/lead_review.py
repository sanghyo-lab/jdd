"""Validate the development lead's published review evidence, not code correctness itself."""
import re


LEAD_OWNER = "이상효"
REVIEW_PATH = "docs/status/lead-review.json"
LEAD_AREAS = {
    "commerce": ("commerce-app/", "commerce-core/", "commerce-infra/"),
    "agent": ("agent-app/", "agent-core/", "agent-infra/"),
    "voc": ("voc-app/", "voc-core/", "voc-infra/"),
    "web": ("web/",),
    "scenarios": ("scenario-runner/", "fixtures/commerce/"),
    "runtime": ("compose.yaml", "Dockerfile", "infra/", "scripts/", "build.gradle", "settings.gradle"),
    "contracts": ("docs/commerce-interface.md", "docs/integration-contract.md", "docs/business-policy.md",
                  "docs/team-completion.md"),
}
LEAD_CHECKS = {"requirements", "normal-and-errors", "evidence-integrity", "data-isolation",
               "retries-and-recovery", "ui-flow", "repeatability"}


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def indexed(items, required, label):
    if not isinstance(items, list) or any(not isinstance(item, dict) for item in items):
        raise ValueError("Leader review needs " + label + " entries.")
    ids = [item.get("id") for item in items]
    if (any(not isinstance(key, str) for key in ids) or len(ids) != len(set(ids))
            or set(ids) != set(required)):
        raise ValueError("Leader review needs every required " + label + " exactly once.")
    return {item["id"]: item for item in items}


def validate_lead_review(repo, data, tip):
    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        raise ValueError("Missing or unsupported leader review.")
    commit = data.get("reviewedCommit")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Leader review needs the inspected commit.")
    if repo.run(["git", "merge-base", "--is-ancestor", commit, tip], capture=True, check=False).returncode:
        raise ValueError("The reviewed commit is not in current main history.")
    fingerprint = repo.completion_fingerprint(tip)
    if data.get("contentSha256") != fingerprint or repo.completion_fingerprint(commit) != fingerprint:
        raise ValueError("Leader review is stale; inspect the current implementation and criteria.")
    for area, entry in indexed(data.get("areas"), LEAD_AREAS, "code review area").items():
        paths = entry.get("paths")
        if not nonempty(entry.get("summary")) or not isinstance(paths, list) or not paths:
            raise ValueError("Leader review needs inspected paths and conclusions for " + area)
        for path in paths:
            if (not isinstance(path, str)
                    or not any(path.startswith(prefix) if prefix.endswith("/") else path == prefix
                               for prefix in LEAD_AREAS[area])
                    or repo.git("cat-file", "-t", commit + ":" + path, check=False) != "blob"):
                raise ValueError("Leader review references an untracked or out-of-area file: " + str(path))
    for check in indexed(data.get("checks"), LEAD_CHECKS, "validation").values():
        if check.get("status") != "PASSED" or not nonempty(check.get("evidence")):
            raise ValueError("Leader validation has no passing evidence: " + check["id"])
    cases = {"VOC-%02d" % number for number in range(1, 8)}
    for case in indexed(data.get("reproductions"), cases, "reproduction").values():
        runs = case.get("runs")
        passed = case.get("passed")
        minimum = 20 if case["id"] == "VOC-07" else 3
        if (type(runs) is not int or type(passed) is not int or runs < minimum or passed != runs
                or not nonempty(case.get("evidence"))):
            raise ValueError("Leader reproduction is incomplete or has failures: " + case["id"])
    findings = data.get("findings")
    if not isinstance(findings, list):
        raise ValueError("Leader review must list its findings, including an empty list when none were found.")
    seen = set()
    for finding in findings:
        if not isinstance(finding, dict) or not nonempty(finding.get("id")) or finding["id"] in seen:
            raise ValueError("Leader findings need unique identifiers.")
        seen.add(finding["id"])
        if (finding.get("owner") not in ("commerce", "agent", "voc") or finding.get("status") != "VERIFIED"
                or any(not nonempty(finding.get(key)) for key in ("description", "resolution", "verification"))):
            raise ValueError("An unresolved or unverified leader finding blocks completion: " + finding["id"])
