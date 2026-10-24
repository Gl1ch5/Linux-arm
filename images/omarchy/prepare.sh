#!/bin/bash
# Runs on the CI host before the container: downloads the omarchy-android release bundle
# into $1 (mounted read-only at /la-build/cache). setup.sh verifies the pinned sha256.
set -euo pipefail
out="$1"
url='https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar'
sha='7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2'
mkdir -p "$out"
curl --fail --location --retry 5 --retry-all-errors --retry-delay 10 --silent --show-error -o "$out/bundle.tar" "$url"
echo "$sha  $out/bundle.tar" | sha256sum -c -
