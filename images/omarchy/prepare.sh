#!/bin/bash
# Runs on the CI host before the container. Downloads the omarchy-android release bundle,
# checks the pinned sha256 and the bundle manifest, and leaves its rootfs as
# $1/rootfs.tar.xz, which build-image.sh imports as the base image (base_image "local:rootfs").
set -euo pipefail
out="$1"
url='https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar'
sha='7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2'
version='0.1.1'
mkdir -p "$out"
curl --fail --location --retry 5 --retry-all-errors --retry-delay 10 --silent --show-error -o "$out/bundle.tar" "$url"
echo "$sha  $out/bundle.tar" | sha256sum -c -
manifest="$(tar -xOf "$out/bundle.tar" ./BUNDLE-MANIFEST)"
echo "$manifest"
grep -qx 'format=1' <<<"$manifest" || { echo "unsupported bundle format" >&2; exit 1; }
grep -qx 'architecture=aarch64' <<<"$manifest" || { echo "bundle is not aarch64" >&2; exit 1; }
grep -qx "version=$version" <<<"$manifest" || { echo "unexpected bundle version" >&2; exit 1; }
tar -xOf "$out/bundle.tar" ./rootfs.tar.xz > "$out/rootfs.tar.xz"
rm -f "$out/bundle.tar"
ls -l "$out"
