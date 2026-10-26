#!/bin/sh
# Every check a commit must pass (AGENTS.md). The pre-commit hook runs it.
#
# Usage: scripts/check.sh [--full]
#
# The tests include the integration tests, so `inko` must be installed.
# Cuttlebone's integration tests are skipped when there's no `cuttlebone`,
# except with --full, which requires it.
# --full adds the real-world projects' tests (RealWorld*IntegrationTest:
# Inko projects cloned beside this repository, or under PARAKEET_FIXTURES,
# each skipped with a message when it isn't there) and the Plugin Verifier
# against the oldest and newest supported IDEs, which downloads them the
# first time and takes a few minutes. Run it before any release or session
# report.
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

# Cuttlebone's integration tests need a real `cuttlebone`: $CUTTLEBONE, else
# the one on PATH, else where `cargo install` puts it (as Parakeet looks).
cuttlebone=${CUTTLEBONE-}
if [ -z "$cuttlebone" ]; then
    cuttlebone=$(command -v cuttlebone || true)
fi
if [ -z "$cuttlebone" ] && [ -x "$HOME/.cargo/bin/cuttlebone" ]; then
    cuttlebone="$HOME/.cargo/bin/cuttlebone"
fi
cuttlebone_flag=
if [ -n "$cuttlebone" ]; then
    export CUTTLEBONE="$cuttlebone"
    echo "==> using $("$cuttlebone" --version 2>/dev/null || echo "$cuttlebone") for Cuttlebone's integration tests"
elif [ "$full" = true ]; then
    echo "check.sh: --full needs cuttlebone for Cuttlebone's integration tests, and none was found." >&2
    echo "Install it, put it on PATH, or set CUTTLEBONE to its path." >&2
    exit 1
else
    echo "==> skipping Cuttlebone's integration tests: no cuttlebone on PATH (set CUTTLEBONE to its path to run them; --full requires it)"
    cuttlebone_flag=-PnoCuttlebone
fi

# --rerun: a cached test result can't stand in for this run, since the
# integration tests depend on the installed `inko` and `cuttlebone`, which Gradle can't see.
step "tests (unit, integration and guardrails)" run ./gradlew --quiet --console=plain test --rerun $cuttlebone_flag
# The typing replay, in an IDE of its own (build.gradle.kts): it needs Cuttlebone.
if [ -z "$cuttlebone_flag" ]; then
    step "typing replay against Cuttlebone" run ./gradlew --quiet --console=plain typingReplay --rerun
fi
step "plugin build" run ./gradlew --quiet --console=plain buildPlugin

if [ "$full" = true ]; then
    step "real-world projects against Cuttlebone" run ./gradlew --quiet --console=plain test -PrealWorld --tests '*.RealWorld*IntegrationTest' --rerun
    step "plugin verification" run ./gradlew --quiet --console=plain verifyPlugin
fi

printf '==> passed in %ss\n' "$(($(date +%s) - start))"
