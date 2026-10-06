#!/usr/bin/env python3
"""Generate GitHub Pages JSON endpoints consumed by ReVanced Manager."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
from urllib.parse import urljoin

REPO = Path(__file__).resolve().parent.parent
BLOB_ROOT = "https://github.com/hegelty/millie-on-kitkat/blob/main/"


def parse_history(markdown):
    headings = list(re.finditer(r"^## (\d+\.\d+\.\d+) \((\d{4}-\d{2}-\d{2})\)[ \t]*$", markdown, re.M))
    history = []
    for index, heading in enumerate(headings):
        version, date = heading.groups()
        created_at = datetime.strptime(date, "%Y-%m-%d").isoformat()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(markdown)
        body = markdown[heading.end():end].strip()
        # Manager renders Markdown outside the repository, so relative links need a base URL.
        body = re.sub(r"\]\(([^)]+)\)", lambda match: "](" + urljoin(BLOB_ROOT, match[1]) + ")", body)
        if not body:
            raise ValueError(f"Empty changelog for {version}")
        history.append({"version": version, "created_at": created_at, "description": body})
    versions = [tuple(map(int, item["version"].split("."))) for item in history]
    if not history or versions != sorted(set(versions), reverse=True):
        raise ValueError("Changelog versions must be unique and newest first")
    return history


def generate(repo=REPO, version=None, check=False):
    feed = json.loads((repo / "patches.json").read_text(encoding="utf-8"))
    history = parse_history((repo / "CHANGELOG.md").read_text(encoding="utf-8"))
    if history[0]["version"] != feed["version"] or (version and version != feed["version"]):
        raise ValueError("Bundle, feed and latest changelog versions must match")
    if history[0]["created_at"][:10] != feed["created_at"][:10]:
        raise ValueError("Feed and latest changelog dates must match")
    feed["description"] = history[0]["description"]
    outputs = {"docs/patches.json": feed, "docs/v5/patches/history": history}
    for name, data in outputs.items():
        path = repo / name
        content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                raise ValueError(f"Stale site data: {name}. Run python3 revanced/generate_site.py")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    print(f"{'Checked' if check else 'Generated'} Pages feed and {len(history)} changelog entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if committed Pages data is stale")
    args = parser.parse_args()
    generate(check=args.check)
