# make dev      uv sync: the locked pytest into .venv (and hatchling once,
#               where the repo builds) — a few hundred KB, then cached
# make test     the pytest suite
# make check    the same thing — .githooks/checks.sh is the one runner, so
#               what a push gets checked with is what you just ran; it uses
#               .venv through uv when there is one and plain python3
#               otherwise, because it also runs wherever the push happens
# make mutants poodle: change one thing in one module, run the suite, and see
#               whether it noticed. NOT part of make test -- a push is a deploy
#               and the pre-push checks have to stay quick. See poodle_config.py
# make lint     ruff — the one already on PATH if there is one (Termux ships
#               a native build; uv cannot install ruff on Android), else the
#               locked one from the lint group

dev:
	uv sync

test: check

check:
	bash .githooks/checks.sh

mutants:
	uv run --group mutants poodle

lint:
	@if command -v ruff >/dev/null 2>&1; then ruff check .; \
	else uv run --group lint ruff check .; fi

.PHONY: dev test check mutants lint
