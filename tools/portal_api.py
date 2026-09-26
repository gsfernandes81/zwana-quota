#!/usr/bin/env python3
"""List the API calls the portal's own web app makes, without making any.

The portal at ic.zwana.io is an AngularJS app (README, "The portal API"), and
everything it can do -- start or stop the internet session, list the devices
on the account -- is a call written into its JavaScript. This downloads that
JavaScript the way a browser would, and prints every API path in it with the
HTTP method used and the code around it, so a new feature can be built on
what the portal actually does rather than on a guess.

It only ever GETs the page and its script files. It never calls an API
endpoint, logs in, or sends the credentials, so it cannot connect or
disconnect anything. Run it on the vessel's Wi-Fi (the portal is not
reachable from anywhere else) and send back the file it writes.

    python3 tools/portal_api.py                  # writes ~/zwana-portal-api.txt
    python3 tools/portal_api.py --grep device    # only calls mentioning "device"
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

OUT = Path.home() / "zwana-portal-api.txt"

#: Script tags, same origin only: third-party libraries have nothing to say
#: about this portal's API.
SCRIPT = re.compile(r"<script[^>]+src=[\"']([^\"']+)[\"']", re.I)

#: A quoted API path: `"api/Balance/GetForCurrentUser"`, `'/api/x/y'`, or the
#: bare `Controller/Action` form the client builds URLs from.
PATH = re.compile(r"[\"'`](/?(?:api/)?[A-Z][A-Za-z0-9]*/[A-Za-z0-9_/{}.:+-]*)[\"'`]")

#: How an AngularJS client names the method it calls with.
METHOD = re.compile(r"\.(get|post|put|delete|patch)\s*\(|method\s*:\s*[\"'](\w+)[\"']", re.I)


def get(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=z.TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8", errors="replace")


def calls(source: str, name: str) -> list[tuple[str, str, str]]:
    """Every API path in *source*, the method nearest before it, and its context."""
    found = []
    for match in PATH.finditer(source):
        path = match.group(1)
        # Asset paths look the same shape; the API's are Controller/Action.
        if re.search(r"\.(js|css|html|png|svg|ico|woff2?)$", path, re.I):
            continue
        before = source[max(0, match.start() - 160) : match.start()]
        methods = METHOD.findall(before)
        method = (methods[-1][0] or methods[-1][1]).upper() if methods else "?"
        start, end = max(0, match.start() - 220), min(len(source), match.end() + 220)
        context = " ".join(source[start:end].split())
        found.append((method, path, f"{name}: {context}"))
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--grep", help="only calls whose path or context contains this")
    parser.add_argument("--out", type=Path, default=OUT, help="where to write (default: %(default)s)")
    args = parser.parse_args(argv)

    base = z.PORTAL_URL
    try:
        page = get(base)
    except (urllib.error.URLError, OSError) as exc:
        print(f"cannot reach {base}: {exc} -- on the vessel's Wi-Fi?", file=sys.stderr)
        return 1

    host = urllib.parse.urlparse(base).netloc
    sources = [("index.html", page)]
    for src in SCRIPT.findall(page):
        url = urllib.parse.urljoin(base, src)
        if urllib.parse.urlparse(url).netloc != host:
            continue
        try:
            sources.append((src, get(url)))
        except (urllib.error.URLError, OSError) as exc:
            print(f"skipped {src}: {exc}", file=sys.stderr)

    seen: dict[tuple[str, str], str] = {}
    for name, source in sources:
        for method, path, context in calls(source, name):
            if args.grep and args.grep.lower() not in (path + context).lower():
                continue
            seen.setdefault((path, method), context)

    lines = [
        f"# {len(seen)} API calls in {len(sources)} files from {base}",
        "# method  path  --  where it appears",
        "",
    ]
    for (path, method), context in sorted(seen.items()):
        lines += [f"{method:6}  {path}", f"        {context[:600]}", ""]
    args.out.write_text("\n".join(lines))
    print(f"{len(seen)} calls from {len(sources)} files written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
