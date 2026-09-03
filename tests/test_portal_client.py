"""The portal client: credentials, the HTTP seam, the session cache.

Nothing here goes near ic.zwana.io — every test drives
:func:`zwana_quota.request` through a stand-in opener, or stubs ``request``
itself, which are the two seams the module already has.
"""

from __future__ import annotations

import http.cookiejar
import os
import shutil
import stat
import time
import urllib.error

import pytest
from conftest import FakeOpener, http_error, parse_size
from hypothesis import given
from hypothesis import strategies as st

import zwana_quota as zq

SECRET = "hunter2-correct-horse"


# --------------------------------------------------------------------------- #
# The address exists once
# --------------------------------------------------------------------------- #


def test_portal_url_is_the_api_url_without_the_api():
    """The tile's ``open`` mode asks for this rather than spelling it again."""
    assert zq.BASE_URL == zq.PORTAL_URL + "api/"
    assert zq.PORTAL_URL.startswith("https://")
    assert zq.PORTAL_URL.endswith("/")
    assert "api" not in zq.PORTAL_URL.removeprefix("https://")


def test_the_documented_endpoints_and_the_cookie_that_carries_the_session():
    """Findings that cost real time: the API is under /api/ on that host, and
    it is the cookie that authenticates, not the token it hands back."""
    assert zq.BASE_URL == "https://ic.zwana.io/api/"
    assert zq.SESSION_COOKIE == ".AspNetCore.Identity.Application"


def test_one_credit_is_four_hundred_mebibytes():
    """The conversion the README derives from the provider's own UnitCost."""
    assert zq.BYTES_PER_CREDIT == 400 * 1024**2
    assert round(1 / 2.38418579102e-09) == zq.BYTES_PER_CREDIT


# --------------------------------------------------------------------------- #
# Credentials
# --------------------------------------------------------------------------- #


def env_file(path, body: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    path.chmod(0o600)
    return path


@given(
    user=st.text(st.characters(min_codepoint=33, max_codepoint=126,
                               blacklist_characters="='\"#"), min_size=1, max_size=20),
    password=st.text(st.characters(min_codepoint=33, max_codepoint=126,
                                   blacklist_characters="='\""), min_size=1,
                     max_size=40),
)
def test_credentials_round_trip_through_the_env_file(tmp_path_factory, user, password):
    path = env_file(
        tmp_path_factory.mktemp("env") / ".env",
        f"zwana_username={user}\nzwana_password={password}\n",
    )
    assert zq.load_credentials(path) == (user, password)


def test_env_file_ignores_blanks_comments_and_junk(tmp_path):
    path = env_file(
        tmp_path / ".env",
        "\n"
        "# a comment\n"
        "   \n"
        "not a key value line\n"
        "  ZWANA_USERNAME = crew  \n"
        "zwana_password='" + SECRET + "'\n",
    )
    assert zq.load_credentials(path) == ("crew", SECRET)


def test_environment_beats_the_file(tmp_path, monkeypatch):
    path = env_file(tmp_path / ".env", "zwana_username=file\nzwana_password=filepw\n")
    monkeypatch.setenv("zwana_username", "env")
    monkeypatch.setenv("zwana_password", "envpw")
    assert zq.load_credentials(path) == ("env", "envpw")


def test_missing_credentials_name_the_file_and_not_the_password(tmp_path):
    path = env_file(tmp_path / ".env", f"zwana_password={SECRET}\n")
    with pytest.raises(zq.PortalError) as caught:
        zq.load_credentials(path)
    assert str(path) in str(caught.value)
    assert SECRET not in str(caught.value)


def test_absent_env_file_is_not_an_error_when_the_environment_has_it(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("zwana_username", "crew")
    monkeypatch.setenv("zwana_password", SECRET)
    assert zq.load_credentials(tmp_path / "nothing-here") == ("crew", SECRET)


@pytest.mark.parametrize("mode", [0o644, 0o640, 0o604])
def test_world_readable_secrets_are_complained_about(tmp_path, capsys, mode):
    path = env_file(tmp_path / ".env", "zwana_username=a\nzwana_password=b\n")
    path.chmod(mode)
    zq.load_credentials(path)
    assert str(path) in capsys.readouterr().err


def test_a_private_env_file_is_read_silently(tmp_path, capsys):
    path = env_file(tmp_path / ".env", "zwana_username=a\nzwana_password=b\n")
    zq.load_credentials(path)
    assert capsys.readouterr().err == ""


# --------------------------------------------------------------------------- #
# request(): the one place HTTP happens
# --------------------------------------------------------------------------- #


def opener(monkeypatch, answer):
    fake = FakeOpener(answer)
    monkeypatch.setattr(zq, "_opener", fake)
    return fake


def test_get_decodes_json_and_asks_for_it(monkeypatch):
    fake = opener(monkeypatch, b'{"Balance": 1.5}')
    assert zq.request("Balance/GetForCurrentUser") == {"Balance": 1.5}
    req = fake.requests[0]
    assert req.full_url == zq.BASE_URL + "Balance/GetForCurrentUser"
    assert req.get_method() == "GET"
    assert req.data is None
    assert fake.timeout == zq.TIMEOUT_SECONDS


def test_a_payload_makes_it_a_json_post(monkeypatch):
    fake = opener(monkeypatch, b"{}")
    zq.request("account/token", payload={"password": SECRET})
    req = fake.requests[0]
    assert req.get_method() == "POST"
    assert req.data == b'{"password": "%s"}' % SECRET.encode()
    assert req.get_header("Content-type") == "application/json"


def test_an_empty_body_is_no_document_rather_than_a_parse_error(monkeypatch):
    opener(monkeypatch, b"   \n ")
    assert zq.request("x") is None


def test_html_instead_of_json_is_a_portal_error_carrying_the_start_of_it(monkeypatch):
    opener(monkeypatch, b"<!doctype html><title>login</title>")
    with pytest.raises(zq.PortalError, match="expected JSON"):
        zq.request("x")


@pytest.mark.parametrize("code", [301, 302, 303, 307, 308])
def test_a_redirect_is_not_authenticated_rather_than_a_parse_error(monkeypatch, code):
    opener(monkeypatch, http_error(code))
    with pytest.raises(zq.NotAuthenticated):
        zq.request("Balance/GetForCurrentUser")


@pytest.mark.parametrize("code", [400, 403, 404, 500, 503])
def test_other_http_errors_are_portal_errors_and_not_login_failures(monkeypatch, code):
    opener(monkeypatch, http_error(code, b"detail from the portal"))
    with pytest.raises(zq.PortalError) as caught:
        zq.request("x")
    assert not isinstance(caught.value, zq.NotAuthenticated)
    assert str(code) in str(caught.value)
    assert "detail from the portal" in str(caught.value)


def test_a_failed_login_never_echoes_the_password_back(monkeypatch):
    opener(monkeypatch, http_error(400, b"invalid_grant"))
    with pytest.raises(zq.PortalError) as caught:
        zq.request("account/token", payload={"password": SECRET})
    assert SECRET not in str(caught.value)


def test_an_unreachable_portal_is_a_portal_error(monkeypatch):
    opener(monkeypatch, urllib.error.URLError("no route to host"))
    with pytest.raises(zq.PortalError, match="cannot reach portal"):
        zq.request("x")


def test_the_redirect_handler_refuses_to_follow(monkeypatch):
    """The 302 has to reach ``request`` to be turned into NotAuthenticated."""
    handler = zq.NoRedirect()
    assert handler.redirect_request(None, None, 302, "Found", {}, "/Account/Login") is None


# --------------------------------------------------------------------------- #
# fetch(): one retry, and only one
# --------------------------------------------------------------------------- #


def test_a_stale_session_is_logged_in_again_once(monkeypatch, tmp_path):
    seen: list[str] = []

    def request(path, payload=None):
        seen.append(path)
        if len(seen) == 1:
            raise zq.NotAuthenticated("stale")
        return {"ok": True}

    monkeypatch.setattr(zq, "request", request)
    monkeypatch.setattr(zq, "log_in", lambda env: seen.append("login"))
    assert zq.fetch("Balance/GetForCurrentUser", tmp_path / ".env") == {"ok": True}
    assert seen == ["Balance/GetForCurrentUser", "login", "Balance/GetForCurrentUser"]


def test_a_session_that_stays_stale_is_not_retried_forever(monkeypatch, tmp_path):
    tries: list[str] = []

    def request(path, payload=None):
        tries.append(path)
        raise zq.NotAuthenticated("stale")

    monkeypatch.setattr(zq, "request", request)
    monkeypatch.setattr(zq, "log_in", lambda env: None)
    with pytest.raises(zq.NotAuthenticated):
        zq.fetch("x", tmp_path / ".env")
    assert len(tries) == 2


def test_allow_login_false_never_logs_in(monkeypatch, tmp_path):
    monkeypatch.setattr(zq, "request", lambda p, payload=None: (_ for _ in ()).throw(
        zq.NotAuthenticated("stale")))
    monkeypatch.setattr(zq, "log_in", lambda env: pytest.fail("must not log in"))
    with pytest.raises(zq.NotAuthenticated):
        zq.fetch("x", tmp_path / ".env", allow_login=False)


# --------------------------------------------------------------------------- #
# The session cache
# --------------------------------------------------------------------------- #


def cookie(name: str = zq.SESSION_COOKIE, value: str = "abc", expires=None):
    """A session cookie as the portal sends it: no expiry, and *discard* —
    which is exactly the kind a jar drops unless told to keep it."""
    return http.cookiejar.Cookie(
        0, name, value, None, False, "ic.zwana.io", False, False, "/", True,
        True, expires, expires is None, None, None, {},
    )


def test_a_saved_session_comes_back_and_is_private(monkeypatch):
    zq._jar.set_cookie(cookie())
    was = os.umask(0o022)  # what MozillaCookieJar.save() would otherwise obey
    try:
        zq.save_session()
    finally:
        os.umask(was)
    assert stat.S_IMODE(zq.COOKIE_FILE.stat().st_mode) == 0o600
    assert stat.S_IMODE(zq.CACHE_DIR.stat().st_mode) == 0o700

    monkeypatch.setattr(
        zq, "_jar", http.cookiejar.MozillaCookieJar(str(zq.COOKIE_FILE))
    )
    assert zq.load_session() is True


def test_no_cookie_file_is_no_session():
    assert zq.load_session() is False


def test_a_corrupt_cookie_file_is_no_session():
    zq.COOKIE_FILE.write_text("this is not a cookie jar\n")
    assert zq.load_session() is False


def test_a_jar_without_the_session_cookie_is_no_session(monkeypatch):
    zq._jar.set_cookie(cookie(name="some_other_cookie"))
    zq.save_session()
    monkeypatch.setattr(
        zq, "_jar", http.cookiejar.MozillaCookieJar(str(zq.COOKIE_FILE))
    )
    assert zq.load_session() is False


def test_logging_in_without_a_session_cookie_is_a_failure(monkeypatch):
    monkeypatch.setattr(zq, "load_credentials", lambda env: ("crew", SECRET))
    monkeypatch.setattr(zq, "request", lambda path, payload=None: {"access_token": "x"})
    with pytest.raises(zq.PortalError, match="no session cookie"):
        zq.log_in(zq.DEFAULT_ENV)


def test_logging_in_posts_the_password_grant_and_saves_the_cookie(monkeypatch):
    sent: list[tuple] = []

    def request(path, payload=None):
        sent.append((path, payload))
        zq._jar.set_cookie(cookie())
        return {"token_type": "password", "access_token": "ignored"}

    monkeypatch.setattr(zq, "load_credentials", lambda env: ("crew", SECRET))
    monkeypatch.setattr(zq, "request", request)
    zq.log_in(zq.DEFAULT_ENV)
    assert sent == [
        ("account/token",
         {"grant_type": "password", "username": "crew", "password": SECRET}),
    ]
    assert zq.COOKIE_FILE.exists()


def test_logging_in_drops_whatever_was_in_the_jar_first(monkeypatch):
    zq._jar.set_cookie(cookie(name="stale_cookie"))

    def request(path, payload=None):
        assert [c.name for c in zq._jar] == []
        zq._jar.set_cookie(cookie())

    monkeypatch.setattr(zq, "load_credentials", lambda env: ("crew", SECRET))
    monkeypatch.setattr(zq, "request", request)
    zq.log_in(zq.DEFAULT_ENV)


# --------------------------------------------------------------------------- #
# bytes_per_credit / available_bytes
# --------------------------------------------------------------------------- #


def answers(monkeypatch, **by_path):
    def fetch(path, env, allow_login=True):
        if path not in by_path:
            raise zq.PortalError(f"unexpected {path}")
        value = by_path[path]
        if isinstance(value, Exception):
            raise value
        return value

    monkeypatch.setattr(zq, "fetch", fetch)


HISTORY = "Allocation/GetHistoryForCurrentUser"
BALANCE = "Balance/GetForCurrentUser"


def entry(unit_cost, byte_type=True):
    provider = {"IsByteType": byte_type}
    if unit_cost is not None:
        provider["UnitCost"] = unit_cost
    return {"UserProvider": {"Provider": provider}}


def test_unit_cost_is_read_from_the_provider(monkeypatch, tmp_path):
    answers(monkeypatch, **{HISTORY: [entry(1 / 1_000_000)]})
    assert zq.bytes_per_credit(tmp_path) == 1_000_000


@pytest.mark.parametrize(
    "history",
    [
        [],
        None,
        "not a list",
        [entry(None)],
        [entry(0)],
        [entry(-1e-9)],
        [entry(2.38418579102e-09, byte_type=False)],
        [{}],
        [{"UserProvider": None}],
    ],
    ids=["empty", "null", "wrong type", "no cost", "zero cost", "negative cost",
         "seconds not bytes", "bare entry", "no provider"],
)
def test_an_unusable_history_falls_back_to_the_documented_rate(
    monkeypatch, tmp_path, history
):
    answers(monkeypatch, **{HISTORY: history})
    assert zq.bytes_per_credit(tmp_path) == zq.BYTES_PER_CREDIT


def test_a_portal_error_reading_the_history_falls_back(monkeypatch, tmp_path):
    answers(monkeypatch, **{HISTORY: zq.PortalError("down")})
    assert zq.bytes_per_credit(tmp_path) == zq.BYTES_PER_CREDIT


@given(credits=st.floats(0, 1000, allow_nan=False, allow_infinity=False))
def test_available_bytes_is_credits_times_the_rate(credits):
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(zq, "load_session", lambda: True)
        patch.setattr(zq, "log_in", lambda env: pytest.fail("session was usable"))
        patch.setattr(
            zq, "fetch",
            lambda path, env, allow_login=True: (
                {"Balance": credits} if path == BALANCE else []
            ),
        )
        got_credits, got_bytes = zq.available_bytes()
    assert got_credits == pytest.approx(credits)
    assert got_bytes == int(credits * zq.BYTES_PER_CREDIT)
    assert got_bytes >= 0


def test_available_bytes_logs_in_when_there_is_no_session(monkeypatch):
    logged: list[int] = []
    monkeypatch.setattr(zq, "load_session", lambda: False)
    monkeypatch.setattr(zq, "log_in", lambda env: logged.append(1))
    monkeypatch.setattr(
        zq, "fetch",
        lambda path, env, allow_login=True: ({"Balance": 1.0} if path == BALANCE else []),
    )
    zq.available_bytes()
    assert logged == [1]


@pytest.mark.parametrize(
    "balance", [None, [], {"Balance": "lots"}, {"Balance": None}, {}],
    ids=["null", "list", "string", "none", "empty"],
)
def test_a_balance_without_a_number_is_refused_rather_than_guessed(monkeypatch, balance):
    monkeypatch.setattr(zq, "load_session", lambda: True)
    monkeypatch.setattr(
        zq, "fetch", lambda path, env, allow_login=True: balance
    )
    with pytest.raises(zq.PortalError, match="numeric Balance"):
        zq.available_bytes()


# --------------------------------------------------------------------------- #
# human_bytes
# --------------------------------------------------------------------------- #


@given(value=st.integers(0, 2**60))
def test_human_bytes_never_lies_about_the_figure(value):
    text = zq.human_bytes(value)
    read, tolerance = parse_size(text)
    assert abs(read - value) <= tolerance


@given(value=st.integers(0, 2**60))
def test_human_bytes_keeps_the_mantissa_under_a_thousand_and_a_bit(value):
    text = zq.human_bytes(value)
    number, _, unit = text.rpartition(" ")
    mantissa = float(number.replace(",", ""))
    assert unit in ("B", "KiB", "MiB", "GiB", "TiB")
    # 1024 exactly is reachable by rounding up, at 1023.995 of a unit.
    assert mantissa <= 1024 or unit == "TiB"
    assert ("." in number) == (unit != "B")


@pytest.mark.parametrize(
    "value, unit",
    [(0, "B"), (1023, "B"), (1024, "KiB"), (1024**2 - 1, "KiB"),
     (1024**2, "MiB"), (1024**3, "GiB"), (1024**4, "TiB"), (1024**5, "TiB")],
)
def test_human_bytes_changes_unit_at_the_power_of_two(value, unit):
    assert zq.human_bytes(value).endswith(unit)


def test_human_bytes_signs_survive():
    assert zq.human_bytes(-2048).startswith("-2.00 KiB")


def test_an_expired_session_is_not_a_session(monkeypatch):
    """Written past the saver on purpose: the question is what the *loader*
    does with an expired cookie sitting on disk, however it got there."""
    stale_jar = http.cookiejar.MozillaCookieJar(str(zq.COOKIE_FILE))
    stale_jar.set_cookie(cookie(expires=int(time.time()) - 3600))
    zq.CACHE_DIR.mkdir(parents=True, exist_ok=True)
    stale_jar.save(ignore_discard=True, ignore_expires=True)
    monkeypatch.setattr(
        zq, "_jar", http.cookiejar.MozillaCookieJar(str(zq.COOKIE_FILE))
    )
    assert zq.load_session() is False


def test_a_session_can_be_saved_before_the_cache_directory_exists():
    shutil.rmtree(zq.CACHE_DIR.parent)
    zq._jar.set_cookie(cookie())
    zq.save_session()
    assert zq.COOKIE_FILE.exists()


def test_the_read_is_bounded_so_a_hung_portal_is_not_a_hung_widget():
    """The tile's own deadline is set against this one; see tasker/zwana-tile."""
    assert zq.TIMEOUT_SECONDS == 30


def test_a_body_that_is_not_utf8_is_reported_rather_than_raised(monkeypatch):
    opener(monkeypatch, b"\xff\xfe not json at all")
    with pytest.raises(zq.PortalError, match="expected JSON"):
        zq.request("x")


def test_a_huge_error_page_is_cut_down_rather_than_pasted_whole(monkeypatch):
    opener(monkeypatch, http_error(500, b"x" * 100_000))
    with pytest.raises(zq.PortalError) as caught:
        zq.request("x")
    assert len(str(caught.value)) < 1_000


def test_a_huge_wrong_body_is_cut_down_too(monkeypatch):
    opener(monkeypatch, b"<html>" + b"y" * 100_000)
    with pytest.raises(zq.PortalError) as caught:
        zq.request("x")
    assert len(str(caught.value)) < 1_000


def test_the_verbose_report_prints_the_connection_status(capsys):
    zq.summarise(
        {"Balance": 1.0}, {"Connected": True, "Since": "yesterday"},
        verbose=True, per_credit=zq.BYTES_PER_CREDIT,
    )
    out = capsys.readouterr().out
    assert "Connected" in out
    assert "Since" in out


def test_no_status_to_report_prints_no_status_section(capsys):
    zq.summarise({"Balance": 1.0}, None, verbose=True, per_credit=1)
    assert "Connection status" not in capsys.readouterr().out


def test_the_command_line_reads_the_real_argv_when_it_is_given_none(monkeypatch):
    monkeypatch.setattr(zq.sys, "argv", ["zwana_quota.py", "--json", "--login"])
    args = zq.parse_args()
    assert (args.json, args.login) == (True, True)
