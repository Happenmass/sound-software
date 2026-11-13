#!/bin/sh
# Turns the Blender renders (build_device_models.py stills + spin) into what the app ships:
#   res/drawable-nodpi/{device,bean}_{white,black}_front.webp   transparent stills
#   res/raw/pairing_spin_{white,black}.webp                     transparent looping turntable
# and a transparent VP9 WebM master of each turntable in tools/3d/renders/ for other uses.
set -eu
IN=${1:-${TMPDIR:-/tmp}/device_renders}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RES=$ROOT/app/src/main/res
TMP=$(mktemp -d)
mkdir -p "$RES/raw" "$ROOT/tools/3d/renders"

# The floor shadow runs to the frame edge; fade alpha over the outer 10% so no square edge shows.
FADE="geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*min(1,min(min(X,W-X),min(Y,H-Y))/(0.1*W))'"

for f in white black; do
  for s in device:960 bean:720; do
    ffmpeg -v error -y -i "$IN/${s%:*}_${f}_front.png" -vf "$FADE,scale=${s#*:}:-1:flags=lanczos" "$TMP/still.png"
    cwebp -quiet -q 88 -alpha_q 100 "$TMP/still.png" -o "$RES/drawable-nodpi/${s%:*}_${f}_front.webp"
  done

  # 24 fps loop, 96 frames = one 4 s revolution.
  ffmpeg -v error -y -framerate 24 -i "$IN/spin/$f/%03d.png" -vf "$FADE,scale=480:480:flags=lanczos" "$TMP/$f-%03d.png"
  # WebP durations are integer milliseconds: 42/41/42 sums to exactly 4s over 96 frames.
  set -- -loop 0 -lossy -q 72 -mixed
  i=0
  for frame in "$TMP/$f"-*.png; do
    duration=42
    if [ $((i % 3)) -eq 1 ]; then duration=41; fi
    set -- "$@" -d "$duration" "$frame"
    i=$((i + 1))
  done
  [ "$i" -eq 96 ] || { echo "Expected 96 new frames for $f, found $i" >&2; exit 1; }
  img2webp "$@" -o "$RES/raw/pairing_spin_$f.webp" >/dev/null
  ffmpeg -v error -y -framerate 24 -i "$IN/spin/$f/%03d.png" -vf "$FADE" -c:v libvpx-vp9 -pix_fmt yuva420p -b:v 0 -crf 28 -row-mt 1 \
    "$ROOT/tools/3d/renders/pairing_spin_$f.webm"
done
rm -rf "$TMP"
ls -la "$RES/raw" "$RES/drawable-nodpi" "$ROOT/tools/3d/renders"
