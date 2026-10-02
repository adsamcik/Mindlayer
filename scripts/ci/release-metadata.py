"""Require release source/version/changelog alignment before publishing."""

import argparse
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    version = args.version.removeprefix("v")
    source = Path("build.gradle.kts").read_text(encoding="utf-8")
    declared = re.search(r'^val publishVersion = .*\?: "([^"]+)"$', source, re.MULTILINE)
    if declared is None or declared.group(1) != version:
        raise SystemExit("Release tag must match the version committed in build.gradle.kts")
    changelog = Path("CHANGELOG.md").read_text(encoding="utf-8")
    heading = re.search(
        rf"^## \[{re.escape(version)}\] — \d{{4}}-\d{{2}}-\d{{2}}$",
        changelog,
        re.MULTILINE,
    )
    if heading is None:
        raise SystemExit("Release requires a dated changelog entry for the exact version")
    remainder = changelog[heading.end():]
    next_heading = re.search(r"^## ", remainder, re.MULTILINE)
    entry = remainder[:next_heading.start()] if next_heading else remainder
    if "### " not in entry or not re.search(r"^- \S", entry, re.MULTILINE):
        raise SystemExit("Release changelog must contain complete user-facing notes")
    if args.notes:
        args.notes.parent.mkdir(parents=True, exist_ok=True)
        args.notes.write_text(
            f"# Mindlayer {version}\n" + entry.rstrip() + "\n\n"
            "## SDK coordinates\n\n"
            f"Use `com.adsamcik.mindlayer:shared:{version}`, `sdk:{version}`, "
            f"`sdk-camerax:{version}`, and `sdk-camera-launcher:{version}` together.\n\n"
            "## Service builds\n\n"
            f"`mindlayer-service-debug-v{version}.apk` is a code-only, debug-signed "
            "sideload build. Retain or install models separately with "
            "`tools/dev-models/push-models.*`. Production Play bundles are signed "
            "and built locally; see `docs/project/RELEASE.md`.\n",
            encoding="utf-8",
        )
    print(f"Release metadata aligned: {version}")


if __name__ == "__main__":
    main()
