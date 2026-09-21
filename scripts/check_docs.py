#!/usr/bin/env python3
"""Check repository-owned Markdown links, fences, and JSON examples."""
import json
from pathlib import Path
import re
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    paths = [root / "README.md", root / "AGENTS.md",
             *sorted((root / "docs").rglob("*.md")), *sorted((root / ".github").rglob("*.md"))]
    errors = []
    links = examples = 0
    for path in paths:
        if not path.exists():
            errors.append("Missing documentation: " + str(path.relative_to(root)))
            continue
        body = path.read_text()
        opened = None
        for number, line in enumerate(body.splitlines(), 1):
            fence = re.match(r"^\s*(`{3,}|~{3,})(.*)$", line)
            if fence:
                marker = fence.group(1)
                if opened is None:
                    opened = (marker, number)
                elif marker[0] == opened[0][0] and len(marker) >= len(opened[0]) and not fence.group(2).strip():
                    opened = None
        if opened:
            errors.append(str(path.relative_to(root)) + ": unclosed code fence")
        prose = re.sub(r"```.*?```", "", body, flags=re.S)
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", prose):
            if re.match(r"[a-zA-Z][\w+.-]*:", target) or target.startswith("#"):
                continue
            links += 1
            if not (path.parent / target.split("#")[0]).exists():
                errors.append(str(path.relative_to(root)) + ": missing link " + target)
        for sample in re.findall(r"^```json\s*\n(.*?)^```\s*$", body, flags=re.M | re.S):
            examples += 1
            try:
                json.loads(sample)
            except ValueError as error:
                errors.append(str(path.relative_to(root)) + ": invalid JSON: " + str(error))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("PASS: %d Markdown files, %d local links, %d JSON examples" % (len(paths), links, examples))
    return 0


if __name__ == "__main__":
    sys.exit(main())
