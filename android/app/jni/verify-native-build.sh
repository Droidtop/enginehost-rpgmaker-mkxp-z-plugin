#!/usr/bin/env bash
set -euo pipefail

abi=${1:?usage: verify-native-build.sh ABI}
case "$abi" in
  armeabi-v7a) expected_machine='ARM'; expected_class='ELF32' ;;
  arm64-v8a) expected_machine='AArch64'; expected_class='ELF64' ;;
  *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
esac

ndk_host=${ANDROID_NDK_HOME:?}/toolchains/llvm/prebuilt/linux-x86_64
readelf="$ndk_host/bin/llvm-readelf"
root="build-$abi"
libraries=(
  "$root/lib/libopenal.so"
  "$root/lib/libruby31.so"
  "$root/ruby19/lib/libruby19.so"
)

for library in "${libraries[@]}"; do
  test -e "$library"
  header=$($readelf -h "$library")
  grep -Eq "Class:[[:space:]]+$expected_class" <<<"$header"
  grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header"
  dynamic=$($readelf -d "$library")
  if grep -Eq 'libc[.]so[.]6|ld-linux|libruby-3[.]2|x86_64-linux-gnu' <<<"$dynamic"; then
    echo "host dependency leaked into $library" >&2
    exit 1
  fi
done

ruby19_dynamic=$($readelf -d "$root/ruby19/lib/libruby19.so")
grep -Eq '\(SONAME\).*\[libruby19[.]so\]' <<<"$ruby19_dynamic"
if grep -Eq '\(SONAME\).*\[libruby19[.]so[.]' <<<"$ruby19_dynamic"; then
  echo 'Ruby 1.9 has a versioned SONAME that Android will not package' >&2
  exit 1
fi

grep -Eq '^CC = .*/bin/(armv7a-linux-androideabi|aarch64-linux-android)[0-9]+-clang$' ruby19/Makefile
grep -Eq '^arch = (armv7a-linux-androideabi|aarch64-linux-android)$' ruby19/Makefile
echo "native preflight passed for $abi"
