#!/usr/bin/env bash
set -euo pipefail

apk=${1:?usage: verify-apk-native.sh APK}
ndk_host=${ANDROID_NDK_HOME:?}/toolchains/llvm/prebuilt/linux-x86_64
readelf="$ndk_host/bin/llvm-readelf"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

unzip -q "$apk" 'lib/*' -d "$tmp"
for spec in 'armeabi-v7a:ARM:ELF32' 'arm64-v8a:AArch64:ELF64'; do
  IFS=: read -r abi machine elf_class <<<"$spec"
  for name in libruby19.so libruby31.so libmkxp-z-ruby19.so libmkxp-z-ruby31.so; do
    library="$tmp/lib/$abi/$name"
    test -f "$library"
    "$readelf" -h "$library" | grep -Eq "Class:[[:space:]]+$elf_class"
    "$readelf" -h "$library" | grep -Eq "Machine:[[:space:]]+$machine"
  done
  ruby19_needed=$("$readelf" -d "$tmp/lib/$abi/libmkxp-z-ruby19.so")
  grep -Eq '\(NEEDED\).*\[libruby19[.]so\]' <<<"$ruby19_needed"
  if grep -Eq '\(NEEDED\).*\[libruby19[.]so[.]' <<<"$ruby19_needed"; then
    echo "versioned Ruby 1.9 dependency in $abi mkxp binary" >&2
    exit 1
  fi
  ruby31_needed=$("$readelf" -d "$tmp/lib/$abi/libmkxp-z-ruby31.so")
  grep -Eq '\(NEEDED\).*\[libruby31[.]so\]' <<<"$ruby31_needed"
done
echo 'APK native-runtime preflight passed'
