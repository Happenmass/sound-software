#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
    printf 'Usage: %s codex-aarch64-unknown-linux-musl.zst output.apk\n' "$0" >&2
    exit 2
fi

source_dir="$(cd "$(dirname "$0")" && pwd)"
archive="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
output="$2"
sdk="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
tools="$sdk/build-tools/36.1.0"
android="$sdk/platforms/android-35/android.jar"
ndk="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64"
node_archive="$HOME/.cache/codex/dependencies/nodejs-mobile/24.22.1/full.zip"
debug_keystore="$HOME/.android/debug.keystore"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

verify_sha256() {
    local actual
    actual="$(shasum -a 256 "$1" | cut -c1-64)"
    if [[ "$actual" != "$2" ]]; then
        printf 'Unexpected SHA-256 for %s\n' "$1" >&2
        exit 1
    fi
}

verify_sha256 "$archive" e5e5c2b300a3e2b0e8a1f887e928a3ecc63d825efd5b0c9ecd83cda5e5a060b8
verify_sha256 "$node_archive" d6fd9f610fe5d81fede8f0d400c5a62723247725a19831835f15abf4f0604250

jni="$work/apk/lib/arm64-v8a"
mkdir -p "$jni" "$work/apk/assets" "$work/classes"
unzip -q "$node_archive" 'include/node/*' bin/arm64-v8a/libnode.so -d "$work"
cp "$work/bin/arm64-v8a/libnode.so" "$jni/"
cp "$ndk/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$jni/"
"$ndk/bin/llvm-strip" --strip-debug "$jni/libnode.so" "$jni/libc++_shared.so"
zstd -q -d "$archive" -o "$jni/libprobe_codex.so"
"$ndk/bin/aarch64-linux-android26-clang++" -std=c++20 -O2 -I "$work/include/node" \
    -Wl,-rpath,'$ORIGIN' "$source_dir/node-main.cc" "$jni/libnode.so" -o "$jni/libprobe_node.so"
"$ndk/bin/aarch64-linux-android26-clang" -Wall -Wextra -O2 "$source_dir/sandbox.c" -o "$jni/libprobe_sandbox.so"
cp "$source_dir/probe.cjs" "$work/apk/assets/"

javac --release 8 -Xlint:-options -classpath "$android" -d "$work/classes" "$source_dir/ProbeActivity.java"
"$tools/d8" --lib "$android" --min-api 26 --output "$work/apk" \
    "$work/classes/dev/soundsoftware/codexprobe/ProbeActivity.class"
"$tools/aapt2" link --manifest "$source_dir/AndroidManifest.xml" -I "$android" -A "$work/apk/assets" -o "$work/unsigned.apk"
(cd "$work/apk" && zip -q -0 "$work/unsigned.apk" classes.dex && zip -q -6 "$work/unsigned.apk" lib/arm64-v8a/*.so)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$debug_keystore" --ks-pass pass:android --out "$output" "$work/aligned.apk"
