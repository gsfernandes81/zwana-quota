"""Mutation testing config: `make mutants`, never `make test`.

Poodle copies the tree into `.poodle-temp/`, changes one thing in one of the
two modules, and runs the suite against it. A mutant the suite still passes is
a behaviour nothing pins — which is the question being asked here, since
coverage says only that a line ran.

It is out of `make test` and out of the pre-push hook on purpose: a push here
is a deploy, and the checks have to stay quick enough to run before every one.

The modules sit at the repo root rather than under `src/`, which is why
`source_folders` is spelled out — poodle's default is `["src", "lib"]` and
would find nothing to mutate.
"""

# The flat layout: the repo root is the source folder, and only the two
# modules are mutated (not the tests, and not this file).
source_folders = ["."]
only_files = ["quota_widget.py", "zwana_quota.py"]
file_filters = ["test_*.py", "*_test.py", "conftest.py", "poodle_config.py"]

# Every worker gets its own copy of the tree, so keep the copy small and keep
# anything with state out of it — a cache or a cookie jar copied in would be
# read by the run it is meant to be hidden from.
file_copy_filters = [
    ".venv/**",
    ".git/**",
    ".poodle-temp/**",
    ".pytest_cache/**",
    ".ruff_cache/**",
    ".hypothesis/**",
    "__pycache__/**",
    "*.pyc",
    "tasker/**",
    "android/**",
    "garmin/**",
    ".env",
    # Signing keys may live in the checkout (gitignored); no worker needs one.
    "*.p12",
    "*.pfx",
    "*.der",
    "*.pem",
    "*.jks",
    "*.keystore",
    "*.b64",
]

# The suite runs in about five seconds; the timeout is the clean run times the
# multiplier, floored here so a phone under thermal throttling does not report
# its own slowness as a killed mutant.
min_timeout = 60
timeout_multiplier = 10

max_workers = 3

# `-x` because a mutant only has to be caught once, and the suite is run once
# per mutant. The conftest notices it is running inside `.poodle-temp` and
# turns the property tests down to a smaller, derandomised sample for the same
# reason — a mutation run is a few hundred suites, not one.
runner_opts = {"command_line": "python -m pytest -x -q -p no:cacheprovider"}
