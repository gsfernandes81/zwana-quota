#!/usr/bin/env python3
"""Show what the portal's device and session calls actually return. Read only.

tools/portal_api.py listed the calls the portal's web app can make. This one
looks at the ones a widget toggle and a device list would stand on:

1. it GETs the read calls -- joined devices, this device's IP, the session
   status, the account's provider and its options -- with the same saved
   login zwana_quota.py uses, and records the JSON they return;
2. it pulls the portal's own code around joinDevice / removeDevice and the
   internet options, so it is clear what the portal's "connect" and
   "disconnect" do before anything here copies them.

Apart from signing in when the saved session has expired -- the same login
every quota read already does -- it sends no POST, PUT, PATCH or DELETE:
nothing is joined, removed or changed, and the internet session is left
exactly as it was. Anything that looks like
a token, a password or a hash is replaced by "<hidden>" before it is written.
Run it on the vessel's Wi-Fi and send back the file:

    python3 tools/portal_probe.py                # writes ~/zwana-portal-probe.txt
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import zwana_quota as z  # noqa: E402

OUT = Path.home() / "zwana-portal-probe.txt"

#: GETs only. Each is what the portal's own app calls to show a page.
READS = [
    "Device/GetJoinedDevices",
    "UserProvider/GetClientIP",
    "UserProvider/GetStatus",
    "UserProvider/GetActive",
    "UserProvider/ListForCurrentUser",
    "UserProviderOptions/ListForCurrentUser",
    "Provider/ProviderConnectivityStatus",
    "Balance/GetForCurrentUser",
    "account/getcurrent",
]

#: The code worth reading: who calls the device and options services, and how.
CODE_FILES = [
    "app/scripts/services/deviceService.js",
    "app/scripts/services/internetOptionsService.js",
]
CODE_WORDS = re.compile(
    r"joinDevice|removeDevice|getJoinedDevices|getCurrentDeviceIp|"
    r"JoinDevice|RemoveDevice|UpdateForCurrentUser|GetStatus|ProviderConnectivityStatus",
)

SECRET = re.compile(r"token|password|passwd|hash|secret|stamp|salt", re.I)


def hide(value):
    """The document with anything secret-looking replaced."""
    if isinstance(value, dict):
        return {k: "<hidden>" if SECRET.search(str(k)) else hide(v) for k, v in value.items()}
    if isinstance(value, list):
        return [hide(v) for v in value]
    return value


def fetch_text(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=z.TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8", errors="replace")


def excerpts(source: str, width: int = 18) -> list[str]:
    """Each place CODE_WORDS appears, with *width* lines either side, merged."""
    lines = source.splitlines()
    hits = [i for i, line in enumerate(lines) if CODE_WORDS.search(line)]
    spans: list[list[int]] = []
    for i in hits:
        lo, hi = max(0, i - width), min(len(lines), i + width + 1)
        if spans and lo <= spans[-1][1]:
            spans[-1][1] = hi
        else:
            spans.append([lo, hi])
    return ["\n".join(f"{n + 1:5} {lines[n]}" for n in range(lo, hi)) for lo, hi in spans]


def main() -> int:
    out: list[str] = [f"# read-only probe of {z.PORTAL_URL}", ""]

    out.append("## API reads (GET only)")
    try:
        if not z.load_session():
            z.log_in(z.DEFAULT_ENV)
    except z.PortalError as exc:
        print(f"cannot sign in: {exc}", file=sys.stderr)
        return 1
    for path in READS:
        try:
            body = z.fetch(path, z.DEFAULT_ENV)
            text = json.dumps(hide(body), indent=2, sort_keys=True)
        except z.PortalError as exc:
            text = f"(failed: {exc})"
        out += [f"### GET {path}", text, ""]

    out.append("## The portal's own code")
    page = fetch_text(z.PORTAL_URL)
    scripts = [
        urllib.parse.urljoin(z.PORTAL_URL, s)
        for s in re.findall(r"<script[^>]+src=[\"']([^\"']+)[\"']", page, re.I)
        if s.startswith("app/scripts/")
    ]
    for url in sorted(set(scripts) | {urllib.parse.urljoin(z.PORTAL_URL, f) for f in CODE_FILES}):
        try:
            source = fetch_text(url)
        except (urllib.error.URLError, OSError) as exc:
            out += [f"### {url}", f"(failed: {exc})", ""]
            continue
        found = excerpts(source)
        if found:
            out.append(f"### {url.removeprefix(z.PORTAL_URL)}")
            out += [block + "\n        ..." for block in found]
            out.append("")

    OUT.write_text("\n".join(out))
    print(f"written to {OUT} ({len(out)} lines); nothing on the portal was changed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
