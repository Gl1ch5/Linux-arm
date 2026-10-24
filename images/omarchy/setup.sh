#!/bin/bash
# Omarchy image: Arch Linux ARM + Hyprland (Omarchy), nested in Weston, GPU through KGSL.
#
# Runs inside the arm64 container of base_image (danhunsaker/archlinuxarm, pinned by digest).
# The root filesystem of this container is then replaced by the rootfs of the verified
# omarchy-android release bundle (BlackFireAlex/omarchy-android, MIT). That rootfs is built
# on the same Arch Linux ARM base layer, so extracting it over / yields the intended system.
# Its Weston module is a Termux/bionic build and cannot run in this glibc guest, so Arch's
# stock weston is installed on top of it.
#
# Integrity: the bundle is checked against the sha256 pinned from manifest/release.lock of
# omarchy-android. That hash covers every file inside the bundle, including rootfs.tar.xz.
set -euxo pipefail
export LC_ALL=C

# Pinned release: omarchy-android manifest/release.lock, tag v0.1.1.
BUNDLE_URL='https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar'
BUNDLE_SHA256='7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2'
BUNDLE_VERSION='0.1.1'
# Member names inside the bundle, exactly as tar lists them.
BUNDLE_MANIFEST_MEMBER='./BUNDLE-MANIFEST'
ROOTFS_MEMBER='./rootfs.tar.xz'
PACMAN_CONF=/etc/pacman.conf

log() { printf '[omarchy-image] %s\n' "$*"; }
die() { printf '[omarchy-image] ERROR: %s\n' "$*"; exit 1; }

# Retries a command with a growing pause: GitHub downloads and Arch mirrors are flaky.
retry() {
  local attempts="$1" n
  shift
  for n in $(seq 1 "$attempts"); do
    if "$@"; then
      return 0
    fi
    log "attempt $n of $attempts failed: $*"
    sleep $((n * 15))
  done
  return 1
}

# Same knobs as the release builder. pacman's own Landlock/seccomp download sandbox
# cannot nest inside Docker, so it is disabled. The container itself is the sandbox.
harden_pacman_conf() {
  [[ -f "$PACMAN_CONF" ]] || die "missing $PACMAN_CONF"
  grep -q '^\[options\]$' "$PACMAN_CONF" || die "$PACMAN_CONF has no [options] section"
  sed -i -e '/^DisableSandbox\(Filesystem\|Syscalls\)$/d' "$PACMAN_CONF"
  sed -i -e '/^\[options\]$/a DisableSandboxSyscalls' -e '/^\[options\]$/a DisableSandboxFilesystem' "$PACMAN_CONF"
  sed -i -e '/^DownloadUser/d' -e '/^\[options\]$/a DownloadUser = root' "$PACMAN_CONF"
  sed -i -e '/^ParallelDownloads/d' -e '/^\[options\]$/a ParallelDownloads = 4' "$PACMAN_CONF"
}

[[ "$(uname -m)" == aarch64 ]] || die "the image must be built natively on arm64 (uname -m: $(uname -m))"
df -h / || true

# 1. Tools needed to fetch and unpack the bundle. The base image ships pacman and xz.
harden_pacman_conf
retry 3 pacman -Sy --noconfirm --needed curl ca-certificates ca-certificates-mozilla

# 2. Download the bundle and check its pinned sha256 before anything is extracted.
work="$(mktemp -d /var/tmp/omarchy-image.XXXXXX)"
bundle="$work/bundle.tar"
retry 3 curl --fail --location --silent --show-error \
  --retry 5 --retry-all-errors --retry-delay 10 --connect-timeout 30 \
  --output "$bundle" "$BUNDLE_URL" || die "download failed: $BUNDLE_URL"
echo "$BUNDLE_SHA256  $bundle" | sha256sum -c - || die "bundle sha256 does not match the pinned release"
log "bundle sha256 verified"

# 3. Check the bundle manifest: format, architecture and version.
manifest="$(tar -xOf "$bundle" "$BUNDLE_MANIFEST_MEMBER")" || die "bundle has no $BUNDLE_MANIFEST_MEMBER"
manifest_value() { awk -F= -v key="$1" '$1 == key { print $2; exit }' <<<"$manifest"; }
[[ "$(manifest_value format)" == 1 ]] || die "unsupported bundle format: $(manifest_value format)"
[[ "$(manifest_value architecture)" == aarch64 ]] || die "bundle is not aarch64"
[[ "$(manifest_value version)" == "$BUNDLE_VERSION" ]] || die "bundle version is $(manifest_value version), expected $BUNDLE_VERSION"

# 4. Replace the root filesystem. The rootfs is streamed straight from the bundle into /,
#    so the uncompressed copy never exists twice on disk.
#    --anchored: exclusions match from the archive root only (usr/share/dev is kept).
#    Default overwrite: GNU tar unlinks each existing file before creating it, so running
#    binaries and libraries are replaced without "text file busy" errors.
#    Docker-managed /etc/hosts, /etc/hostname, /etc/resolv.conf and the mount points are kept.
log "replacing the root filesystem with the Arch Linux ARM rootfs of $BUNDLE_VERSION"
tar -xOf "$bundle" "$ROOTFS_MEMBER" \
  | tar -C / -xJpf - --numeric-owner --anchored \
      --exclude=dev --exclude=./dev \
      --exclude=proc --exclude=./proc \
      --exclude=sys --exclude=./sys \
      --exclude=la-build --exclude=./la-build \
      --exclude=.dockerenv --exclude=./.dockerenv \
      --exclude=etc/hosts --exclude=./etc/hosts \
      --exclude=etc/hostname --exclude=./etc/hostname \
      --exclude=etc/resolv.conf --exclude=./etc/resolv.conf \
  || die "extracting the rootfs failed; the build output must be discarded"
rm -f "$bundle"
ldconfig

# 5. Check that the rootfs is the expected one before building on top of it.
for path in \
  /etc/omarchy-android-release \
  /usr/share/omarchy/LICENSE \
  /home/omarchy/.config/hypr/hyprland.lua \
  /opt/omarchy-android/hyprland/bin/Hyprland \
  /opt/omarchy-android/aquamarine/lib/libaquamarine.so \
  /opt/omarchy-android/mesa/freedreno_icd.json \
  /opt/omarchy-android/mesa/root/usr/lib/libvulkan_freedreno.so \
  /opt/omarchy-android/mesa/root/usr/lib/dri/kgsl_dri.so; do
  [[ -e "$path" ]] || die "rootfs is incomplete: $path is missing"
done
[[ -x /opt/omarchy-android/hyprland/bin/Hyprland ]] || die "Hyprland is not executable"

# 6. Weston (x11 backend) is not in the bundle, so install Arch's package. -S without -u:
#    only missing packages are added. The keyring is refreshed first so signatures verify.
harden_pacman_conf
retry 3 pacman -Sy --noconfirm --needed archlinux-keyring
retry 3 pacman -S --noconfirm --needed weston xorg-xwininfo
command -v weston >/dev/null || die "weston was not installed"
command -v xwininfo >/dev/null || die "xwininfo was not installed"

# The x11 backend and kiosk shell must exist, or Weston cannot run the session.
x11_backend="$(find /usr/lib -name 'x11-backend.so' 2>/dev/null | head -n 1 || true)"
kiosk_shell="$(find /usr/lib -name 'kiosk-shell.so' 2>/dev/null | head -n 1 || true)"
[[ -n "$x11_backend" ]] || die "Weston x11-backend.so is missing"
[[ -n "$kiosk_shell" ]] || die "Weston kiosk-shell.so is missing"
log "Weston modules: $x11_backend, $kiosk_shell"

# Every shared library of the compositor stack must resolve.
check_libs() {
  local binary="$1" missing
  missing="$(LD_LIBRARY_PATH="/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib" \
    ldd "$binary" 2>&1 | grep 'not found' || true)"
  [[ -z "$missing" ]] || die "unresolved libraries for $binary: $missing"
}
check_libs /opt/omarchy-android/hyprland/bin/Hyprland
check_libs "$(command -v weston)"
check_libs "$(command -v xwininfo)"
log "shared libraries resolve"

# 7. Install the session launcher.
install -D -m 0755 /la-build/image/la-omarchy-session /usr/local/bin/la-omarchy-session
[[ -x /usr/local/bin/la-omarchy-session ]] || die "la-omarchy-session was not installed"

# 8. Clean the staging directory, then the shared cleanup, which must run last.
rm -rf "$work"
bash /la-build/common/cleanup.sh
