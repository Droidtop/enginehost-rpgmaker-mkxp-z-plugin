#!/usr/bin/env bash
set -euo pipefail

apk=${1:?usage: verify-apk-native.sh APK}
ndk_host=${ANDROID_NDK_HOME:?}/toolchains/llvm/prebuilt/linux-x86_64
readelf="$ndk_host/bin/llvm-readelf"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Every check below reports what it actually saw. A bare `test`/`grep -q`
# failing here aborts a ~25 minute CI run with no output at all, which makes
# the next round guesswork.
fail() {
  echo "verify-apk-native: $*" >&2
  echo "--- native libraries in $apk ---" >&2
  (cd "$tmp" && find lib -type f | sort) >&2 || echo "(no lib/ directory)" >&2
  echo "--- SONAME / NEEDED of each mkxp and ruby library ---" >&2
  for so in "$tmp"/lib/*/lib{ruby,mkxp-z-ruby}*.so; do
    [ -f "$so" ] || continue
    echo "${so#$tmp/}:" >&2
    "$readelf" -d "$so" | grep -E '\(SONAME\)|\(NEEDED\)' >&2 || true
  done
  exit 1
}

unzip -q "$apk" 'lib/*' -d "$tmp"
echo "native libraries in $(basename "$apk"):"
(cd "$tmp" && find lib -type f | sort)

for spec in 'armeabi-v7a:ARM:ELF32' 'arm64-v8a:AArch64:ELF64'; do
  IFS=: read -r abi machine elf_class <<<"$spec"
  for name in libruby19.so libruby31.so libmkxp-z-ruby19.so libmkxp-z-ruby31.so; do
    library="$tmp/lib/$abi/$name"
    test -f "$library" || fail "missing lib/$abi/$name"
    header=$("$readelf" -h "$library")
    grep -Eq "Class:[[:space:]]+$elf_class" <<<"$header" \
      || fail "lib/$abi/$name is not $elf_class"
    grep -Eq "Machine:[[:space:]]+$machine" <<<"$header" \
      || fail "lib/$abi/$name is not $machine"
  done
  ruby19_needed=$("$readelf" -d "$tmp/lib/$abi/libmkxp-z-ruby19.so")
  grep -Eq '\(NEEDED\).*\[libruby19[.]so\]' <<<"$ruby19_needed" \
    || fail "$abi libmkxp-z-ruby19.so does not link libruby19.so"
  if grep -Eq '\(NEEDED\).*\[libruby19[.]so[.]' <<<"$ruby19_needed"; then
    fail "versioned Ruby 1.9 dependency in $abi mkxp binary"
  fi
  ruby31_needed=$("$readelf" -d "$tmp/lib/$abi/libmkxp-z-ruby31.so")
  grep -Eq '\(NEEDED\).*\[libruby31[.]so\]' <<<"$ruby31_needed" \
    || fail "$abi libmkxp-z-ruby31.so does not link libruby31.so"
done
echo 'APK native-runtime preflight passed'
