#!/usr/bin/env bash
# Installs Inko VERSION with ivm, makes it ivm's default, and puts ivm's bin
# directory on the GitHub Actions PATH.
#
# ivm builds Inko from source (needing Rust and LLVM 18), so build
# dependencies are only installed when VERSION isn't already installed (e.g.
# restored from the cache). Mirrors inko-lang/inko's own ci/mac.sh and the
# Ubuntu instructions in Inko's installation guide.
set -euo pipefail

version="${1:?usage: install-inko.sh VERSION}"

if ! command -v ivm >/dev/null; then
    echo "::group::Installing ivm"
    cargo install ivm --locked
    echo "::endgroup::"
fi

if [[ ! -x "$(ivm show data)/installed/${version}/bin/inko" ]]; then
    echo "::group::Installing Inko build dependencies"
    case "$(uname -s)" in
        Linux)
            sudo apt-get update --quiet
            sudo apt-get install --yes --quiet build-essential llvm-18 llvm-18-dev \
                libstdc++-12-dev libclang-common-18-dev zlib1g-dev libpolly-18-dev libzstd-dev
            ;;
        Darwin)
            brew install llvm@18
            ;;
    esac
    echo "::endgroup::"

    echo "::group::Installing Inko ${version}"
    ivm install "${version}"
    echo "::endgroup::"
fi

ivm default "${version}"
ivm show bin >> "${GITHUB_PATH}"
"$(ivm show bin)/inko" --version
