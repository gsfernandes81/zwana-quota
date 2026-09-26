#!/usr/bin/env python3
"""Print part of the portal's own JavaScript. Read only, no login.

For reading exactly how the portal's web app does something before this repo
copies it -- the request its data switch sends, say. The script files are the
same ones any browser downloads from the portal; nothing is signed in to and
no API is called.

    python3 tools/portal_source.py app/scripts/controllers/mainCtrl.js --lines 300-470
    python3 tools/portal_source.py --grep Started        # every app script
    python3 tools/portal_source.py FILE --grep 'Started|updateUserInfo' --context 25

Output also goes to ~/zwana-portal-source.txt, to send back whole.
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import zwana_quota as z  # noqa: E402

OUT = Path.home() / "zwana-portal-source.txt"


def get(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=z.TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8", errors="replace")


def app_scripts() -> list[str]:
    """The portal's own scripts (app/scripts/...), not its libraries."""
    page = get(z.PORTAL_URL)
    found = re.findall(r"<script[^>]+src=[\"']([^\"']+)[\"']", page, re.I)
    return sorted({s for s in found if s.startswith("app/scripts/")})


def numbered(lines: list[str], lo: int, hi: int) -> str:
    return "\n".join(f"{n + 1:5} {lines[n]}" for n in range(lo, hi))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("file", nargs="?", help="e.g. app/scripts/controllers/mainCtrl.js (default: all app scripts)")
    parser.add_argument("--lines", help="a range such as 300-470")
    parser.add_argument("--grep", help="a regular expression; prints each match with --context lines")
    parser.add_argument("--context", type=int, default=15, help="lines either side of a match (default 15)")
    args = parser.parse_args(argv)
    if not args.lines and not args.grep:
        parser.error("give --lines or --grep")

    try:
        files = [args.file] if args.file else app_scripts()
    except (urllib.error.URLError, OSError) as exc:
        print(f"cannot reach {z.PORTAL_URL}: {exc} -- on the vessel's Wi-Fi?", file=sys.stderr)
        return 1

    out: list[str] = []
    for name in files:
        try:
            lines = get(urllib.parse.urljoin(z.PORTAL_URL, name)).splitlines()
        except (urllib.error.URLError, OSError) as exc:
            out.append(f"### {name}\n(failed: {exc})\n")
            continue
        blocks = []
        if args.lines:
            lo, _, hi = args.lines.partition("-")
            blocks.append(numbered(lines, max(0, int(lo) - 1), min(len(lines), int(hi or lo))))
        if args.grep:
            pattern = re.compile(args.grep)
            spans: list[list[int]] = []
            for i, line in enumerate(lines):
                if pattern.search(line):
                    lo, hi = max(0, i - args.context), min(len(lines), i + args.context + 1)
                    if spans and lo <= spans[-1][1]:
                        spans[-1][1] = hi
                    else:
                        spans.append([lo, hi])
            blocks += [numbered(lines, lo, hi) for lo, hi in spans]
        if blocks:
            out.append(f"### {name}\n" + "\n        ...\n".join(blocks) + "\n")

    text = "\n".join(out) or "(nothing matched)"
    print(text)
    OUT.write_text(text)
    print(f"\n(also written to {OUT})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
