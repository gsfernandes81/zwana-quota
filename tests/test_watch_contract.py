"""The phone and the watch agree on the wire, which nothing else checks.

The watch app and the Android app are compiled in CI only, by separate jobs,
and a mismatch between them is silent: a renamed key or a changed id is not an
error anywhere, just a glance that stays blank (CLAUDE.md). So the sources are
read as text and held to each other here, where `make test` sees them.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "garmin" / "manifest.xml"
QUOTA_MC = ROOT / "garmin" / "source" / "Quota.mc"
GARMIN_KT = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "Garmin.kt"
CORE = ROOT / "android" / "core" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "core"
FACE_KT = CORE / "Face.kt"
WATCH_KT = CORE / "Watch.kt"
WATCH_SOURCES = sorted((ROOT / "garmin" / "source").glob("*.mc"))


def payload_block() -> str:
    text = FACE_KT.read_text()
    start = text.index("object WatchPayload")
    return text[start : text.index("\n}\n", start)]


def test_the_phone_sends_to_the_app_the_watch_installs():
    watch = re.search(r'<iq:application[^>]*\sid="([0-9a-f]{32})"', MANIFEST.read_text())
    phone = re.search(r'APP_ID\s*=\s*"([0-9a-f]{32})"', GARMIN_KT.read_text())
    assert watch and phone
    assert watch.group(1) == phone.group(1)


def session_block() -> str:
    text = WATCH_KT.read_text()
    start = text.index("object WatchSession")
    return text[start : text.index("\n}\n", start)]


def test_every_key_the_watch_reads_is_one_the_phone_sends():
    blocks = payload_block() + session_block()
    sent = set(re.findall(r'"(\w+)"\s+to\b', blocks)) | set(re.findall(r'out\["(\w+)"\]', blocks))
    source = "\n".join(p.read_text() for p in WATCH_SOURCES)
    # The first argument may be cast (`str(d as Dictionary, "paid")`).
    read = set(re.findall(r'\b(?:num|str|arr)\([^,()"]+,\s*"(\w+)"\)|\.get\("(\w+)"\)', source))
    read = {a or b for a, b in read}
    assert read, "found no keys in the watch's sources"
    assert read <= sent, f"read by the watch, never sent: {sorted(read - sent)}"


def test_the_watch_keeps_the_version_the_phone_sends():
    phone = re.search(r"const val VERSION\s*=\s*(\d+)", payload_block())
    watch = re.search(r"const VERSION\s*=\s*(\d+);", QUOTA_MC.read_text())
    assert phone and watch
    assert phone.group(1) == watch.group(1)
