#!/usr/bin/env bash
# The latest Cuttlebone release, for CI (build.yml), through its manifest.json
# (https://github.com/setdef/Cuttlebone/releases/latest/download/manifest.json),
# as Parakeet downloads it: the build for this OS and CPU, checked against the
# manifest's size and SHA-256, and run once to see it names the Inko it's for.
#
# Usage:
#   ci/install-cuttlebone.sh inko        prints the newest Inko version the latest
#                                        release has a build for, on this OS and CPU
#   ci/install-cuttlebone.sh install DIR installs the build for the installed `inko`
#                                        into DIR, and prints the executable's path
set -euo pipefail

manifest_url=${CUTTLEBONE_MANIFEST:-https://github.com/setdef/Cuttlebone/releases/latest/download/manifest.json}

case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    *) echo "install-cuttlebone.sh: no Cuttlebone builds for $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64 | amd64) arch=amd64 ;;
    *) echo "install-cuttlebone.sh: no Cuttlebone builds for $(uname -m)" >&2; exit 1 ;;
esac

manifest=$(curl --fail --silent --show-error --location --retry 3 "$manifest_url")
if [[ "$(jq -r .schema <<<"$manifest")" != 1 ]]; then
    echo "install-cuttlebone.sh: the manifest's schema isn't 1" >&2
    exit 1
fi
builds=$(jq --arg os "$os" --arg arch "$arch" '[.builds[] | select(.os == $os and .arch == $arch)]' <<<"$manifest")

case "${1-}" in
    inko)
        inko=$(jq -r '.[].inko' <<<"$builds" | sort --version-sort | tail -n 1)
        if [[ -z "$inko" ]]; then
            echo "install-cuttlebone.sh: Cuttlebone $(jq -r .cuttlebone <<<"$manifest") has no build for $os $arch" >&2
            exit 1
        fi
        echo "$inko"
        ;;
    install)
        dir=${2:?usage: install-cuttlebone.sh install DIR}
        inko=$(inko --version | sed -n 's/^inko \([^ ]*\).*/\1/p')
        build=$(jq --arg inko "$inko" '[.[] | select(.inko == $inko)] | first // empty' <<<"$builds")
        if [[ -z "$build" ]]; then
            echo "install-cuttlebone.sh: Cuttlebone $(jq -r .cuttlebone <<<"$manifest") has no build for Inko $inko on $os $arch" >&2
            exit 1
        fi
        url=$(jq -r .url <<<"$build")
        mkdir -p "$dir"
        archive="$dir/$(basename "$url")"
        curl --fail --silent --show-error --location --retry 3 --output "$archive" "$url"
        size=$(wc -c <"$archive" | tr -d ' ')
        if [[ "$size" != "$(jq -r .size <<<"$build")" ]]; then
            echo "install-cuttlebone.sh: $url is $size bytes, not the manifest's $(jq -r .size <<<"$build")" >&2
            exit 1
        fi
        if command -v sha256sum >/dev/null; then
            sum=$(sha256sum "$archive" | cut -d ' ' -f 1)
        else
            sum=$(shasum -a 256 "$archive" | cut -d ' ' -f 1)
        fi
        if [[ "$sum" != "$(jq -r .sha256 <<<"$build")" ]]; then
            echo "install-cuttlebone.sh: $url's SHA-256 is $sum, not the manifest's $(jq -r .sha256 <<<"$build")" >&2
            exit 1
        fi
        tar -xzf "$archive" -C "$dir"
        executable="$dir/$(jq -r .binary <<<"$build")"
        version=$("$executable" --version)
        if [[ "$version" != *"(Inko $inko)"* ]]; then
            echo "install-cuttlebone.sh: $executable --version printed \"$version\", not one for Inko $inko" >&2
            exit 1
        fi
        echo "$version" >&2
        echo "$executable"
        ;;
    *)
        echo "usage: install-cuttlebone.sh inko | install DIR" >&2
        exit 2
        ;;
esac
