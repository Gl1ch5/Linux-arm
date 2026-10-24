#!/bin/bash
# Builds one image: images/<id>/setup.sh runs in an arm64 container of the image's
# base_image, the container filesystem is exported, cleaned and packed as
# rootfs.tar.zst together with <id>.meta.json (the image's images.json entry).
#
# Usage: images/common/build-image.sh <id> <out-dir> <download-base-url>
set -euo pipefail
id="$1"; out="$2"; base_url="$3"
here="$(cd "$(dirname "$0")/.." && pwd)"
dir="$here/$id"
meta_in="$dir/image.json"
base_image="$(jq -r .base_image "$meta_in")"
mkdir -p "$out"
work="$(mktemp -d)"
name="la-build-$id"

# Optional host-side step (downloads etc.), its output is mounted at /la-build/cache.
cache="$work/cache"
mkdir -p "$cache"
if [ -f "$dir/prepare.sh" ]; then
    echo "Running prepare.sh…"
    bash "$dir/prepare.sh" "$cache"
fi

docker rm -f "$name" >/dev/null 2>&1 || true
docker pull --platform linux/arm64 "$base_image"
docker create --platform linux/arm64 --name "$name" \
    -v "$dir:/la-build/image:ro" -v "$here/common:/la-build/common:ro" -v "$cache:/la-build/cache:ro" \
    "$base_image" /bin/sh -c 'if command -v bash >/dev/null; then exec bash /la-build/image/setup.sh; else exec sh /la-build/image/setup.sh; fi' >/dev/null
docker start -a "$name"

echo "Exporting filesystem…"
mkdir -p "$work/rootfs"
docker export "$name" | sudo tar -C "$work/rootfs" -xpf - --numeric-owner
docker rm "$name" >/dev/null
# Docker bind-mounts resolv.conf/hosts/hostname; the app writes its own on install.
sudo rm -rf "$work/rootfs/.dockerenv" "$work/rootfs/la-build" "$work/rootfs/etc/resolv.conf"
sudo find "$work/rootfs/dev" -mindepth 1 -delete

installed_size="$(sudo du -sb "$work/rootfs" | cut -f1)"
archive="$out/$id.rootfs.tar.zst"
echo "Packing ($((installed_size / 1048576)) MiB unpacked)…"
# Hard links become separate files: Android apps cannot create hard links.
sudo tar -C "$work/rootfs" --numeric-owner --hard-dereference -cpf - . | zstd -19 -T0 -q -o "$archive"
sudo rm -rf "$work"
sha256="$(sha256sum "$archive" | cut -d' ' -f1)"
archive_size="$(stat -c %s "$archive")"
version="$(date -u +%Y.%m.%d)-${GITHUB_RUN_NUMBER:-local}"

jq --arg url "$base_url/$id.rootfs.tar.zst" --arg sha "$sha256" --arg ver "$version" \
   --argjson asize "$archive_size" --argjson isize "$installed_size" \
   --arg preview "$( [ -f "$dir/preview.png" ] && echo "$base_url/$id.preview.png" || echo "" )" \
   'del(.base_image) + {version: $ver, url: $url, sha256: $sha, archive_size: $asize, installed_size: $isize}
    + (if $preview != "" then {preview_url: $preview} else {} end)' \
   "$meta_in" > "$out/$id.meta.json"
[ -f "$dir/preview.png" ] && cp "$dir/preview.png" "$out/$id.preview.png"
cat "$out/$id.meta.json"
