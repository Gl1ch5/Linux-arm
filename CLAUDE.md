# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin + Compose, package `io.github.gl1ch5.linuxarm`) that runs full Linux desktops "in one tap" without Termux: an embedded Termux:X11 X server plus a proot'ed rootfs, with Adreno GPU acceleration through Mesa Turnip over KGSL (zink for GL). Main target: OnePlus 15R / Snapdragon 8 Gen 5 / Adreno 829, Android 16, arm64 only. APK, rootfs images and GPU drivers are all built in GitHub Actions and published as GitHub releases that the app downloads.

User-facing text (UI strings, README, `docs/*.md`, release notes) is in **Russian**; code comments and commit messages are in English.

## Commands

Requirements: JDK 21, Android SDK (compileSdk 37, NDK 29.0.14206865), `gawk`.

```sh
git submodule update --init --recursive   # third_party/termux-x11
native/proot/build.sh                     # NDK build of proot -> app jniLibs (libproot.so, libproot-loader.so)
./gradlew :app:assembleDebug              # what CI runs; output linux-arm-debug.apk
./gradlew --offline :app:compileDebugKotlin   # quickest compile check
```

There are no unit tests and lint does not fail the build (`abortOnError = false`). Verify a change by compiling it. Behaviour on a device can only be checked by the user, through the zip from the in-app "Логи → Поделиться" (`app.log`, `session-<id>.log`, `xserver.log`, `logcat.log`, `diagnostics.log`, `device.txt`).

Image scripts are shell: check them with `bash -n` / `shellcheck`. One image can be built locally on an arm64 host with Docker: `images/common/build-image.sh <id> <out-dir> <download-base-url>`.

## CI and releases

- `build-apk.yml`: on every push it builds a debug APK and replaces the rolling `apk-latest` release, which is the app's "Тестовые" update channel. A tag `v*` (or a manual run) produces a signed release for "Стабильные". The app's updater polls `version.json` in these releases.
- `build-images.yml`: runs on changes under `images/**` or manually (input: image IDs separated by spaces). It builds natively on `ubuntu-24.04-arm` and merges entries into `images.json` in the `images` release. Images are auto-discovered from `images/*/image.json`.
- `build-turnip.yml`: daily Mesa builds (stable/nightly × glibc/musl) via `drivers/turnip/build.sh`, with optional patches in `drivers/turnip/patches/{common,stable,nightly}/`. The index is `drivers.json` in the `turnip-index` release.

Debug builds are signed with the committed public `app/debug.keystore`, so every CI APK installs over the previous one. Do not add an `applicationIdSuffix`: Termux:X11 bakes the host applicationId into its BuildConfig and sends its broadcasts to exactly that package.

## Architecture

**Why proot is in jniLibs.** The app targets SDK 35, where `execve()` from writable app storage is forbidden. proot and its loader ship as `lib*.so` with `jniLibs.useLegacyPackaging = true`, and are executed from `nativeLibraryDir`. Guest binaries are mapped by the proot loader (`PROOT_LOADER`), not exec'd, so the restriction does not hit them.

**Processes.**
- **Main app process** runs the UI and `runtime/SessionService`. That is a `specialUse` foreground service that holds a wake lock while a session runs, and it is the parent of every proot process.
- **`:xserver` process** runs `x11/XServerService`, which hosts libXlorie from the `lorie` module (Termux:X11 submodule, included via `settings.gradle.kts`). `App.onCreate` returns early in this process. The X socket is created in `rootfs/tmp/.X11-unix`, so the guest sees the usual `/tmp/.X11-unix/X0`.

**Session start** (`runtime/SessionManager.startLocked`):
1. Copies `app/src/main/assets/session.sh` and `pulse.pa` into the guest at `/usr/local/lib/linux-arm/`. They are overwritten on every start.
2. Resolves the GPU driver. When `turnipPreflight` is on, runs `vulkaninfo --summary` in the guest first. On any failure it falls back to llvmpipe and tells the user why.
3. Starts the X server and opens `DisplayActivity`.
4. Runs `proot … /bin/sh session.sh`. `LA_LAUNCH` holds the image's `launch_command`, or the user's custom launch command if one is set.

`session.sh` starts the system dbus and PulseAudio (a pipe sink read by `runtime/AudioBridge`), then the desktop under `dbus-run-session`.

**Guest environment.**
- `Proot.baseGuestEnv` sets the base variables. `GpuEnv` adds the Turnip/zink variables. Per-distro `extraEnv` comes from `DistroConfig`.
- A GPU driver is never installed into a distro. Each driver version is unpacked to `files/drivers/<id>` and bind-mounted at `/opt/gpu-driver`.
- `FakeProc` binds replacements over `/proc` files that Android hides.

**Display.** `display/DisplayActivity` extends Termux:X11's `com.termux.x11.MainActivity`, which keeps its rendering and touch/trackpad input. On top it adds:
- three- and four-finger gestures, intercepted in `dispatchTouchEvent`;
- a Compose overlay (`DisplayOverlay`) with configurable buttons and a menu.

Actions are strings: `keys:CTRL+ALT+T`, `cmd:<shell>` (run in the guest via `SessionManager.launchInGuest`) and `app:<AppAction>`. They are defined in `controls/`.

Touching `DisplayActivity` (or anything that loads Termux:X11 classes) off the main thread crashes: their static initializer creates a `Handler`. Post to the main looper.

**Android killing the desktop.** Android 12+ kills child ("phantom") processes beyond 32, oldest first, which takes proot and the whole desktop down. Pieces:
- `core/PhantomProcesses`: detects the restriction, offers the battery-optimization exemption, and turns the restriction off by itself if `WRITE_SECURE_SETTINGS` was granted through adb.
- `core/ProcessGuard`: counts the app's child processes and closes expendable desktop helpers before the limit is reached.
- The base guest environment also disables the AT-SPI bus and gvfs volume monitors.

Keep new images lean in process count.

**Other modules.**
- `catalog/`: `images.json` from releases.
- `distro/`: install, extract (`.tar.zst`, hard links become copies because apps cannot create them) and per-distro config.
- `driver/`: Turnip download, import and ELF checks. AdrenoTools zips built for bionic are rejected.
- `update/`: in-app APK updater.
- `apps/`: home-screen shortcuts that start a distro plus a program.

## Images

`images/<id>/` contains:
- `image.json`: `id`, `name`, `description` (in Russian), `libc`, `session_type`, `launch_command`, `accent`, and `base_image`. `base_image` is a docker image, or `local:rootfs` when `prepare.sh` produces the base filesystem.
- `setup.sh`: runs inside an arm64 container of `base_image`.
- Optional: `prepare.sh` (host side; its output is mounted at `/la-build/cache`), a session script, and `preview.png`.

`images/common/` holds the shared build script and `cleanup.sh`. Notes per desktop are in `docs/` (GNOME, Omarchy: Hyprland nested in Weston inside X11).

Constraints inside proot: no systemd, no real root, no setuid, no DRI3 (expect "DRI3 not available"), and Chromium-based browsers need `--no-sandbox`.
