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

# 1-4. prepare.sh (on the CI host) downloaded the pinned omarchy-android bundle, verified
#      its sha256 and manifest, and the bundle's rootfs.tar.xz became this container's
#      image (docker import). So this script already runs inside the Omarchy rootfs.

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

# 6. Weston (x11 backend) is not in the bundle, so install Arch's package. Arch does not
#    support partial upgrades (-Sy + -S pulls new libraries next to old ones), so do a full
#    -Syu; the patched Hyprland/Aquamarine/Mesa live in /opt with their own libraries.
harden_pacman_conf
retry 3 pacman -Sy --noconfirm --needed archlinux-keyring
retry 3 pacman -Syu --noconfirm --needed weston xorg-xwininfo xkeyboard-config
command -v weston >/dev/null || die "weston was not installed"
command -v xwininfo >/dev/null || die "xwininfo was not installed"

# The app's X server reads keymaps from the rootfs at usr/share/X11/xkb, from outside the
# guest, so an absolute symlink there (newer xkeyboard-config points it at
# /usr/share/xkeyboard-config-2) would resolve against Android's root. Make it relative.
xkb=/usr/share/X11/xkb
[[ -e "$xkb/rules/evdev" ]] || die "XKB data is missing ($xkb)"
if [[ -L "$xkb" && "$(readlink "$xkb")" == /* ]]; then
  ln -sfn "$(realpath --relative-to=/usr/share/X11 "$(readlink -f "$xkb")")" "$xkb"
fi
log "XKB: $xkb -> $(readlink "$xkb" || echo directory)"

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
bash /la-build/common/cleanup.sh
