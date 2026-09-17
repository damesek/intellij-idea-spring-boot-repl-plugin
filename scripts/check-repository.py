#!/usr/bin/env python3
"""Check maintained links, release versions and tracked build debris without network access."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def check(release_tag=None):
    errors = []
    version = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
    if release_tag is not None and release_tag != f"v{version}":
        errors.append(f"Release tag {release_tag!r} must match v{version}")
    for module in ("sb-repl-agent", "sb-repl-bridge"):
        pom = ET.parse(ROOT / module / "pom.xml").getroot()
        if pom.findtext("m:version", namespaces=NS) != version:
            errors.append(f"{module}/pom.xml must declare version {version}")

    tracked = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).decode().split("\0")
    files = sorted({ROOT / name for name in tracked if name and (ROOT / name).is_file()})
    links = 0
    for path in files:
        relative = path.relative_to(ROOT)
        if path.name.endswith((".versionsBackup", ".orig", ".rej")):
            errors.append(f"Build/merge backup must not be tracked: {relative}")
        if path.suffix != ".md":
            continue
        # Ignore fenced code and inline code: examples may contain Markdown-like expressions.
        source = re.sub(r"^```[^\n]*\n.*?^```[^\n]*$", "", path.read_text(), flags=re.M | re.S)
        source = re.sub(r"`[^`\n]*`", "", source)
        for match in re.finditer(r"\]\(([^\s)]+)\)", source):
            target = urlsplit(match[1].strip("<>"))
            if target.scheme or target.netloc or not target.path:
                continue
            destination = (path.parent / unquote(target.path)).resolve()
            links += 1
            if not destination.is_relative_to(ROOT) or not destination.exists():
                errors.append(f"{relative}: missing local link {match[1]}")
    return errors, len(files), links


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-tag", help="Require the tag to match the checked-in release version")
    args = parser.parse_args()
    failures, count, links = check(args.release_tag)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        raise SystemExit(1)
    print(f"Repository verified: {count} maintained files, {links} local documentation links.")
