#!/usr/bin/env bash
# Clones the real-world projects the RealWorld*IntegrationTest sweeps run on,
# each at the commit ci/real-world-projects.txt pins, into DIR/<owner>/<name>
# (the layout PARAKEET_FIXTURES names). For CI (build.yml); locally they're
# cloned beside this repository.
#
# Usage: ci/clone-real-world.sh DIR
set -euo pipefail

dir=${1:?usage: clone-real-world.sh DIR}
list="$(dirname "$0")/real-world-projects.txt"

grep -v '^#' "$list" | while read -r path repository commit; do
    [[ -z "$path" ]] && continue
    target="$dir/$path"
    mkdir -p "$target"
    git -C "$target" init --quiet
    git -C "$target" fetch --quiet --depth 1 "$repository" "$commit"
    git -C "$target" checkout --quiet --detach FETCH_HEAD
    echo "$path at $(git -C "$target" rev-parse --short HEAD)"
done
