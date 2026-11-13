#!/usr/bin/env bash
set -euo pipefail

# Pinned runtime only. Model downloads require a separate, explicit setup step.
sherpa_version='2.0.1'
sherpa_sha='a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
sherpa_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${sherpa_version}/sherpa-onnx-${sherpa_version}.aar"
sherpa_target="${SOUND_SHERPA_AAR:-${HOME}/.cache/codex/dependencies/sherpa-onnx/${sherpa_version}/sherpa-onnx-${sherpa_version}.aar}"

checksum() {
    if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
    else sha256sum "$1" | awk '{print $1}'
    fi
}

if [[ -f "$sherpa_target" ]] && [[ "$(checksum "$sherpa_target")" == "$sherpa_sha" ]]; then
    printf 'Verified sherpa-onnx %s: %s\n' "$sherpa_version" "$sherpa_target"
    exit 0
fi

mkdir -p "$(dirname "$sherpa_target")"
sherpa_temporary="$(mktemp "${sherpa_target}.download.XXXXXX")"
trap 'rm -f "$sherpa_temporary"' EXIT
curl --fail --location --retry 2 --proto '=https' --tlsv1.2 "$sherpa_url" --output "$sherpa_temporary"
if [[ "$(checksum "$sherpa_temporary")" != "$sherpa_sha" ]]; then
    printf 'AAR checksum mismatch; refusing to install.\n' >&2
    exit 1
fi
mv -f "$sherpa_temporary" "$sherpa_target"
printf 'Installed verified sherpa-onnx %s: %s\n' "$sherpa_version" "$sherpa_target"
printf 'For a custom path build with -PsherpaAar=<absolute path>. No model weights were downloaded.\n'
