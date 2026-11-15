#!/bin/bash
set -euo pipefail

if [[ $# != 2 ]]; then
    echo "Usage: $0 <unsigned-release.apk> <signed-output.apk>" >&2
    exit 2
fi
input=$1
output=$2
if [[ ! -f "$input" || -e "$output" || -L "$output" ]]; then
    echo "Provide an existing input APK and a new output path." >&2
    exit 2
fi

sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$sdk/build-tools/36.1.0"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
expected_package=dev.soundsoftware.app.release
expected_certificate=4a4c42dba046be7d84992d4277fde00744884d13dfa3ad6ac439e901824b7a5e
package=$("$build_tools/aapt" dump badging "$input" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
if [[ "$package" != "$expected_package" ]]; then
    echo "Expected $expected_package. Build the release variant." >&2
    exit 1
fi

stage=$(mktemp -d "$(dirname "$output")/.sound-release.XXXXXX")
cleanup() {
    for file in "$stage/aligned.apk" "$stage/signed.apk"; do
        if [[ -f "$file" ]]; then unlink "$file"; fi
    done
    rmdir "$stage"
}
trap cleanup EXIT
"$build_tools/zipalign" -P 16 4 "$input" "$stage/aligned.apk"
"$build_tools/apksigner" sign --v4-signing-enabled false \
    --ks "$HOME/.config/sound-software/signing/release.p12" --ks-key-alias sound-software \
    --ks-pass file:/dev/fd/3 --out "$stage/signed.apk" "$stage/aligned.apk" \
    3< <(security find-generic-password -a Happenmass -s dev.soundsoftware.app.release.signing -w)

certificate=$("$build_tools/apksigner" verify --print-certs "$stage/signed.apk" |
    awk '/certificate SHA-256 digest/ {print $NF}')
if [[ "$certificate" != "$expected_certificate" ]]; then
    echo "Signing certificate differs from the public release." >&2
    exit 1
fi
"$build_tools/zipalign" -c -P 16 4 "$stage/signed.apk"
ln "$stage/signed.apk" "$output"
shasum -a 256 "$output"
