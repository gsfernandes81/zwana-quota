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
FACE_KT = ROOT / "android" / "core" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "core" / "Face.kt"


def payload_block() -> str:
    text = FACE_KT.read_text()
    start = text.index("object WatchPayload")
    return text[start : text.index("\n}\n", start)]


def test_the_phone_sends_to_the_app_the_watch_installs():
    watch = re.search(r'<iq:application[^>]*\sid="([0-9a-f]{32})"', MANIFEST.read_text())
    phone = re.search(r'APP_ID\s*=\s*"([0-9a-f]{32})"', GARMIN_KT.read_text())
    assert watch and phone
    assert watch.group(1) == phone.group(1)


def test_every_key_the_watch_reads_is_one_the_phone_sends():
    sent = set(re.findall(r'"(\w+)"\s+to\b', payload_block()))
    read = set(re.findall(r'(?:num|str)\(\w+,\s*"(\w+)"\)|\.get\("(\w+)"\)', QUOTA_MC.read_text()))
    read = {a or b for a, b in read}
    assert read, "found no keys in Quota.mc"
    assert read <= sent, f"read by the watch, never sent: {sorted(read - sent)}"


def test_the_watch_keeps_the_version_the_phone_sends():
    phone = re.search(r"const val VERSION\s*=\s*(\d+)", payload_block())
    watch = re.search(r"const VERSION\s*=\s*(\d+);", QUOTA_MC.read_text())
    assert phone and watch
    assert phone.group(1) == watch.group(1)
