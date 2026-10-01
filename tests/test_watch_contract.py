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
ASK_MC = ROOT / "garmin" / "source" / "Ask.mc"
REFRESH_KT = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "Refresh.kt"
GARMIN_KT = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "Garmin.kt"
CORE = ROOT / "android" / "core" / "src" / "main" / "kotlin" / "io" / "github" / "gsfernandes81" / "zwanaquota" / "core"
FACE_KT = CORE / "Face.kt"
PORTAL_KT = CORE / "Portal.kt"
WATCH_KT = CORE / "Watch.kt"
ANSWERS_KT = CORE / "Answers.kt"
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


def test_the_watch_reads_every_key_the_phone_sends_and_no_other():
    blocks = payload_block() + session_block()
    sent = set(re.findall(r'"(\w+)"\s+to\b', blocks)) | set(re.findall(r'out\["(\w+)"\]', blocks))
    source = "\n".join(p.read_text() for p in WATCH_SOURCES)
    # The first argument may be cast (`str(d as Dictionary, "paid")`).
    read = set(re.findall(r'\b(?:num|str|arr)\([^,()"]+,\s*"(\w+)"\)|\.get\("(\w+)"\)', source))
    read = {a or b for a, b in read}
    assert read, "found no keys in the watch's sources"
    assert read <= sent, f"read by the watch, never sent: {sorted(read - sent)}"
    # And nothing sent that the watch does not read: a key nobody reads is
    # bytes over Bluetooth on every send, and a second spelling to keep true.
    assert sent <= read, f"sent by the phone, never read: {sorted(sent - read)}"


def test_the_watch_keeps_the_version_the_phone_sends():
    phone = re.search(r"const val VERSION\s*=\s*(\d+)", payload_block())
    watch = re.search(r"const VERSION\s*=\s*(\d+);", QUOTA_MC.read_text())
    assert phone and watch
    assert phone.group(1) == watch.group(1)


def number(pattern: str, path: Path) -> int:
    found = re.search(pattern, path.read_text())
    assert found, f"{pattern} not in {path.name}"
    return int(found.group(1).replace("_", ""))


def test_the_phones_switch_deadline_and_send_wait_fit_inside_the_watchs_wait():
    # The phone makes a switch within WATCH_SWITCH_SECONDS of hearing it; the
    # watch gives up WAIT_SWITCH after sending. The rest is for the message to
    # reach the phone and for the answer to come back, which takes at least
    # the send's own wait.
    deadline = number(r"const val WATCH_SWITCH_SECONDS\s*=\s*(\d+)L", REFRESH_KT)
    send = number(r"const val SEND_SECONDS\s*=\s*(\d+)L", GARMIN_KT)
    wait = number(r"const WAIT_SWITCH\s*=\s*(\d+);", ASK_MC)
    assert deadline + send < wait


def test_the_phone_keeps_a_request_for_a_reading_while_the_watch_waits_on_it():
    # A request the phone gave up on sooner would be told "too late" while the
    # watch still waited for a reading.
    pending = number(r"const val PENDING_MILLIS\s*=\s*([\d_]+)L", ANSWERS_KT)
    wait = number(r"const WAIT\s*=\s*(\d+);", ASK_MC)
    assert pending >= wait * 1000


def test_a_failed_read_for_the_watch_is_answered_while_the_watch_still_waits():
    # A read stops at the first request that fails, so a portal that does not
    # answer costs one timeout; the send back is bounded by its own wait (the
    # watch has the message before the phone has the receipt). Garmin
    # Connect is already ready on this path: the listener that heard the ask
    # made it so.
    timeout = number(r"const val TIMEOUT_SECONDS\s*=\s*(\d+)", PORTAL_KT)
    send = number(r"const val SEND_SECONDS\s*=\s*(\d+)L", GARMIN_KT)
    wait = number(r"const WAIT\s*=\s*(\d+);", ASK_MC)
    assert timeout + send < wait
