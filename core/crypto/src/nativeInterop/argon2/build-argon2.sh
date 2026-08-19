#!/usr/bin/env bash
#
# Build libargon2.a from the vendored phc-winner-argon2 reference sources for one
# Apple target, using the Xcode toolchain's clang + the requested SDK.
#
# This is the reproducible target for the iOS Argon2id cinterop (docs/FORMAT.md §3,
# cycle-2c spec D11): the static library is compiled from committed C source, never
# checked in as a prebuilt blob. The Gradle :core:crypto build invokes this per iOS
# arch and feeds the resulting .a to the `argon2` cinterop.
#
# Usage: build-argon2.sh <sdk> <target-triple> <out-dir>
#   sdk           iphoneos | iphonesimulator
#   target-triple e.g. arm64-apple-ios13.0  or  arm64-apple-ios13.0-simulator
#   out-dir       directory to write libargon2.a (and intermediate objects) into
set -euo pipefail

SDK="${1:?sdk required}"
TARGET="${2:?target triple required}"
OUT="${3:?output dir required}"

HERE="$(cd "$(dirname "$0")" && pwd)"
SYSROOT="$(xcrun --sdk "$SDK" --show-sdk-path)"
CC="$(xcrun --sdk "$SDK" -f clang)"
AR="$(xcrun --sdk "$SDK" -f ar)"

# Portable reference build set: SIMD opt.c is intentionally excluded in favour of the
# portable ref.c fill so the same source compiles for every Apple arch. The CLI, test,
# bench and genkat drivers are not vendored.
SRCS=(
  src/argon2.c
  src/core.c
  src/ref.c
  src/encoding.c
  src/thread.c
  src/blake2/blake2b.c
)

mkdir -p "$OUT/obj"
objs=()
for f in "${SRCS[@]}"; do
  o="$OUT/obj/$(echo "$f" | tr '/' '_').o"
  "$CC" -O2 -target "$TARGET" -isysroot "$SYSROOT" \
    -I"$HERE/include" -I"$HERE/src" -fPIC -c "$HERE/$f" -o "$o"
  objs+=("$o")
done

rm -f "$OUT/libargon2.a"
"$AR" rcs "$OUT/libargon2.a" "${objs[@]}"
echo "built $OUT/libargon2.a ($TARGET / $SDK)"
