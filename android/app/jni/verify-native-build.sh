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

# Report what was actually seen. A bare `test`/`grep -q` failing here aborts
# the job with no output, and the next round is guesswork.
fail() {
  echo "verify-native-build: $*" >&2
  exit 1
}

for library in "${libraries[@]}"; do
  test -e "$library" || fail "missing $library"
  header=$($readelf -h "$library")
  grep -Eq "Class:[[:space:]]+$expected_class" <<<"$header" \
    || fail "$library is not $expected_class"
  grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header" \
    || fail "$library is not $expected_machine"
  dynamic=$($readelf -d "$library")
  if grep -Eq 'libc[.]so[.]6|ld-linux|libruby-3[.]2|x86_64-linux-gnu' <<<"$dynamic"; then
    fail "host dependency leaked into $library"
  fi
done

# Both Ruby runtimes must carry a flat SONAME. Android's package format has no
# versioned shared objects, and every consumer records the SONAME verbatim, so
# a versioned one here becomes an unresolvable DT_NEEDED in libmkxp-z-*.so.
check_flat_soname() {
  library=$1
  expected=$2
  dynamic=$($readelf -d "$library")
  soname=$(sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p' <<<"$dynamic")
  [ "$soname" = "$expected" ] \
    || fail "$library has SONAME '${soname:-<none>}', expected '$expected'"
}
check_flat_soname "$root/ruby19/lib/libruby19.so" libruby19.so
check_flat_soname "$root/lib/libruby31.so" libruby31.so

grep -Eq '^CC = .*/bin/(armv7a-linux-androideabi|aarch64-linux-android)[0-9]+-clang$' ruby19/Makefile \
  || fail "ruby19/Makefile CC is not the NDK cross compiler"
grep -Eq '^arch = (armv7a-linux-androideabi|aarch64-linux-android)$' ruby19/Makefile \
  || fail "ruby19/Makefile arch is not an Android triple"
echo "native preflight passed for $abi"

# Ruby 1.9 must have built the extensions RGSS games require by name. When
# one is missing, its mkmf.log is the only record of why, and `make clean-ruby`
# erases it before the next ABI builds, so print it here or lose it.
ruby19_ext_dir=$(find "$root/ruby19/lib/ruby/1.9.1" -mindepth 1 -maxdepth 1 -type d -name '*android*' | head -n 1)
test -n "$ruby19_ext_dir" || fail "no Ruby 1.9 architecture directory under $root/ruby19/lib/ruby/1.9.1"
missing=()
for extension in zlib socket syslog stringio strscan; do
  test -f "$ruby19_ext_dir/$extension.so" || missing+=("$extension")
done
if ((${#missing[@]})); then
  for extension in "${missing[@]}"; do
    log="ruby19/ext/$extension/mkmf.log"
    echo "==== $log ===="
    if test -f "$log"; then tail -n 80 "$log"; else echo "(no mkmf.log: extconf never ran)"; fi
  done
  echo "==== ruby19/ext/zlib listing ===="; ls -la ruby19/ext/zlib || true
  echo "==== ruby19 .ext/$abi ===="; find ruby19/.ext -maxdepth 2 -name '*.so' | sort | head -60
  fail "Ruby 1.9 built without: ${missing[*]}"
fi
