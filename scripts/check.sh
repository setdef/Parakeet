#!/bin/sh
# Every check a commit must pass (AGENTS.md). The pre-commit hook runs it.
#
# Usage: scripts/check.sh [--full]
#
# The tests include the integration tests, so `inko` must be installed.
# --full adds the Plugin Verifier against the oldest and newest supported
# IDEs, which downloads them the first time and takes a few minutes. Run it
# before any release or session report.
set -eu

cd "$(dirname "$0")/.."

case "${1-}" in
    "") full=false ;;
    --full) full=true ;;
    *)
        echo "usage: scripts/check.sh [--full]" >&2
        exit 2
        ;;
esac

start=$(date +%s)

step() {
    printf '==> %s\n' "$1"
    shift
    "$@"
}

# --rerun: a cached test result can't stand in for this run, since the
# integration tests depend on the installed `inko`, which Gradle can't see.
step "tests (unit, integration and guardrails)" ./gradlew --quiet --console=plain test --rerun
step "plugin build" ./gradlew --quiet --console=plain buildPlugin

if [ "$full" = true ]; then
    step "plugin verification" ./gradlew --quiet --console=plain verifyPlugin
fi

printf '==> passed in %ss\n' "$(($(date +%s) - start))"
