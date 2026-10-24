#!/usr/bin/env bash
# Builds proot (Termux fork) and its loader for Android arm64 with the NDK.
# Both are installed into app/src/main/jniLibs/arm64-v8a as lib*.so so that the
# package manager extracts them into nativeLibraryDir, the only app-owned place
# Android (targetSdk >= 29) still allows to execve() from.
#
# Usage: native/proot/build.sh [OUT_DIR]
# Env:   ANDROID_NDK_HOME (or ANDROID_HOME with ndk/<NDK_VERSION>)
set -euo pipefail

PROOT_COMMIT="b27d81a68d765f3462e807f4a14e01a03f8e89ea"
PROOT_VERSION="5.1.107-la"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
API=26

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="${1:-$root/app/src/main/jniLibs/arm64-v8a}"
work="${WORK_DIR:-$root/build/native-proot}"

ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/opt/android-sdk}/ndk/$NDK_VERSION}"
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$tc/clang" ]] || { echo "NDK not found at $ndk" >&2; exit 1; }

export CC="$tc/aarch64-linux-android$API-clang"
export AR="$tc/llvm-ar"
export STRIP="$tc/llvm-strip"
export OBJCOPY="$tc/llvm-objcopy"
export OBJDUMP="$tc/llvm-objdump"
export RANLIB="$tc/llvm-ranlib"

command -v gawk >/dev/null || { echo "gawk is required (loader-info.awk uses strtonum)" >&2; exit 1; }
mkdir -p "$work/bin" "$out"
ln -sf "$(command -v gawk)" "$work/bin/awk"
export PATH="$work/bin:$PATH"
cd "$work"

# --- talloc (static) ---------------------------------------------------------
if [[ ! -f talloc-install/lib/libtalloc.a ]]; then
  curl -fsSL -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
  echo "$TALLOC_SHA256  talloc.tar.gz" | sha256sum -c -
  rm -rf "talloc-$TALLOC_VERSION" && tar xf talloc.tar.gz
  pushd "talloc-$TALLOC_VERSION" >/dev/null
  # Answers for waf's cross-compilation checks (same as termux-packages).
  cat > cross-answers.txt <<'ANS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANS
  ./configure --prefix="$work/talloc-install" --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt >/dev/null
  make -j"$(nproc)" >/dev/null
  mkdir -p "$work/talloc-install/lib" "$work/talloc-install/include"
  "$AR" rcs "$work/talloc-install/lib/libtalloc.a" bin/default/talloc.c.5.o bin/default/lib/replace/replace.c.1.o bin/default/lib/replace/closefrom.c.1.o
  cp talloc.h "$work/talloc-install/include/"
  popd >/dev/null
fi

# --- proot -------------------------------------------------------------------
if [[ ! -d proot/.git ]]; then
  git init -q proot
  git -C proot remote add origin https://github.com/termux/proot.git
fi
git -C proot fetch -q --depth 1 origin "$PROOT_COMMIT"
git -C proot checkout -q --force FETCH_HEAD
git -C proot clean -qfdx

# Pass flags through the environment (not as make arguments) so that the
# makefile's own "CFLAGS += ..." lines still apply.
CFLAGS="-O2 -I$work/talloc-install/include -DARG_MAX=131072 -Wno-error=implicit-function-declaration" \
CPPFLAGS="-DVERSION=\\\"$PROOT_VERSION\\\"" \
LDFLAGS="-L$work/talloc-install/lib -Wl,-z,max-page-size=16384" \
PROOT_UNBUNDLE_LOADER=/nonexistent/use-PROOT_LOADER \
  make -C proot/src -j"$(nproc)" V=0 proot loader/loader

install -m755 proot/src/proot "$out/libproot.so"
install -m755 proot/src/loader/loader "$out/libproot-loader.so"
"$STRIP" "$out/libproot.so" "$out/libproot-loader.so"
echo "proot installed into $out:"
ls -l "$out/libproot.so" "$out/libproot-loader.so"
