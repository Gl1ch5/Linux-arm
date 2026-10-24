#!/bin/sh
# Builds Mesa Turnip for KGSL (Adreno on the Android kernel) plus Zink and the GL/EGL
# frontends (GBM needs DRM, which a KGSL-only build leaves out), installed under the prefix /opt/gpu-driver/usr: the app mounts the
# unpacked archive at /opt/gpu-driver inside the guest.
#
# Runs inside debian:trixie (glibc) or alpine (musl) arm64 containers.
# Usage: build.sh <glibc|musl> <mesa-src> <out-dir> <channel> <version> <commit>
set -eu
libc="$1"; src="$2"; out="$3"; channel="$4"; version="$5"; commit="$6"
here="$(cd "$(dirname "$0")" && pwd)"
prefix=/opt/gpu-driver/usr

echo "::group::Install build dependencies ($libc)"
if [ "$libc" = glibc ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends \
        build-essential git ca-certificates pkg-config ninja-build ccache python3 python3-venv \
        python3-mako python3-yaml python3-packaging bison flex glslang-tools zstd file jq \
        libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev libelf-dev libarchive-dev libxml2-dev \
        libx11-dev libxext-dev libxfixes-dev libxcb1-dev libx11-xcb-dev libxcb-dri3-dev \
        libxcb-present-dev libxcb-randr0-dev libxcb-shm0-dev libxcb-sync-dev libxcb-xfixes0-dev \
        libxcb-glx0-dev libxshmfence-dev libxxf86vm-dev libxrandr-dev \
        libwayland-dev libwayland-egl-backend-dev wayland-protocols libglvnd-dev >/dev/null
    python3 -m venv /opt/meson-venv
    /opt/meson-venv/bin/pip install -q meson
    meson=/opt/meson-venv/bin/meson
    glvnd=enabled
else
    apk add --no-cache \
        build-base git ca-certificates pkgconf ninja ccache python3 py3-mako py3-yaml py3-packaging \
        py3-pip bison flex glslang zstd file jq linux-headers \
        libdrm-dev expat-dev zlib-dev zstd-dev elfutils-dev libarchive-dev libxml2-dev \
        libx11-dev libxext-dev libxfixes-dev libxcb-dev libxshmfence-dev libxxf86vm-dev libxrandr-dev \
        wayland-dev wayland-protocols >/dev/null
    python3 -m venv /opt/meson-venv
    /opt/meson-venv/bin/pip install -q meson
    meson=/opt/meson-venv/bin/meson
    # Alpine's own Mesa does not use libglvnd; ship libGL/libEGL directly.
    glvnd=disabled
fi
echo "::endgroup::"

export CCACHE_DIR="${CCACHE_DIR:-/ccache}"
export CCACHE_MAXSIZE=1G
ccache -z >/dev/null || true

cd "$src"
for p in "$here"/patches/common/*.patch "$here"/patches/"$channel"/*.patch; do
    [ -f "$p" ] || continue
    echo "Applying $(basename "$p")"
    git apply --verbose "$p"
done

build=/tmp/mesa-build
rm -rf "$build"
echo "::group::meson setup"
CC="ccache gcc" CXX="ccache g++" "$meson" setup "$build" \
    --prefix="$prefix" --libdir=lib --buildtype=release -Db_ndebug=true -Dstrip=true \
    -Dplatforms=x11,wayland \
    -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=kgsl \
    -Dgallium-drivers=zink \
    -Dglx=dri -Degl=enabled -Dgbm=disabled -Dopengl=true -Dgles1=disabled -Dgles2=enabled \
    -Dglvnd="$glvnd" \
    -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dbuild-tests=false -Dvulkan-layers= -Dvideo-codecs= -Dgallium-va=disabled
echo "::endgroup::"
echo "::group::build"
ninja -C "$build"
echo "::endgroup::"
ccache -s || true

stage=/tmp/stage
rm -rf "$stage"
DESTDIR="$stage" ninja -C "$build" install >/dev/null
root="$stage/opt/gpu-driver"
# Only the driver itself: drop headers, pkg-config files and any tools pulled in by subprojects.
rm -rf "$root/usr/include" "$root/usr/lib/pkgconfig" "$root/usr/share/pkgconfig" "$root/usr/bin"
mkdir -p "$root/usr/share/licenses/mesa"
cp docs/license.rst "$root/usr/share/licenses/mesa/" 2>/dev/null || true

icd="$(ls "$root"/usr/share/vulkan/icd.d/*.json | head -n1)"
echo "Vulkan ICD: $icd"; cat "$icd"
[ -f "$root/usr/lib/libvulkan_freedreno.so" ] || { echo "libvulkan_freedreno.so missing" >&2; exit 1; }

date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n --arg channel "$channel" --arg version "$version" --arg commit "$commit" --arg date "$date" --arg libc "$libc" \
    '{channel: $channel, version: $version, mesa_commit: $commit, date: $date, libc: $libc,
      kmd: "kgsl", vulkan: "turnip", gl: "zink", prefix: "/opt/gpu-driver/usr"}' > "$root/manifest.json"

name="turnip-$channel-$version-$libc-aarch64.tar.zst"
mkdir -p "$out"
tar -C "$root" --numeric-owner -cf - . | zstd -19 -T0 -q -o "$out/$name"
(cd "$out" && sha256sum "$name" > "$name.sha256")
echo "Built $out/$name ($(du -h "$out/$name" | cut -f1))"
find "$root" -name '*.so*' -type f | xargs file | sed "s|$root||"
