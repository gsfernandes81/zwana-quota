#!/bin/bash
# The checks: every module's self-test, one reporter, one copy.
#
#     .githooks/checks.sh        run everything
#     make check                 the same thing
#
# The pre-push hook runs this and refuses the push on a failure — a push is a
# deploy here: the phone pulls it and the nightly runner runs what landed.
#
# Offline: no network, no portal. This repo needs no sibling checkout —
# it is the one the others reach down into.
set -u

cd "$(git rev-parse --show-toplevel)" || exit 1

# One copy of what gets checked. The pytest shim (tests/test_selftests.py)
# asks for this with --list, so the two runners cannot drift apart.
MODULES="quota_widget"

if [ "${1:-}" = "--list" ]; then
    printf '%s\n' $MODULES
    exit 0
fi

if [ -z "${NO_COLOR:-}" ] && { [ -n "${FORCE_COLOR:-}" ] || [ -t 1 ]; }; then
    C_OK=$'\033[32m'; C_BAD=$'\033[1;31m'; C_OFF=$'\033[0m'
else
    C_OK=''; C_BAD=''; C_OFF=''
fi

rc=0
report() {
    local name="$1"; shift
    printf '  %-14s ' "$name"
    if out="$("$@" 2>&1)"; then
        printf '%sok%s\n' "$C_OK" "$C_OFF"
        return 0
    fi
    printf '%sFAILED%s\n' "$C_BAD" "$C_OFF"
    printf '%s\n' "$out" | tail -25
    echo ""
    printf '%s FAILED — re-run alone: %s\n' "$name" "$*" >&2
    rc=1
}

for module in $MODULES; do
    report "$module" python3 "$module.py" --self-test
done
exit $rc
