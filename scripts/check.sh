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

# Gradle needs Java 21 (mise.toml). Run it through mise when mise is
# installed, so the right Java is used even where mise isn't activated.
if command -v mise >/dev/null 2>&1; then
    run() { mise exec -- "$@"; }
else
    run() { "$@"; }
fi
java_version=$(run java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1 || true)
if [ "$java_version" != 21 ]; then
    echo "check.sh: Gradle needs Java 21, but found ${java_version:-no Java}." >&2
    echo "Install mise (https://mise.jdx.dev) and run 'mise install', or put a Java 21 first on PATH." >&2
    exit 1
fi

step() {
    printf '==> %s\n' "$1"
    shift
    "$@"
}

# --rerun: a cached test result can't stand in for this run, since the
# integration tests depend on the installed `inko`, which Gradle can't see.
step "tests (unit, integration and guardrails)" run ./gradlew --quiet --console=plain test --rerun
step "plugin build" run ./gradlew --quiet --console=plain buildPlugin

if [ "$full" = true ]; then
    step "plugin verification" run ./gradlew --quiet --console=plain verifyPlugin
fi

printf '==> passed in %ss\n' "$(($(date +%s) - start))"
