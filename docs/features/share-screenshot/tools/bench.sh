#!/usr/bin/env bash
# Encode the synthetic long-screenshot sequence into every candidate container
# and report file sizes.  usage: bench.sh <W> <H> <NF> <NANIM> <tag>
set -e
PY="/c/Users/ywwynm/miniconda3/envs/everythingdone/python.exe"
DIR="$(dirname "$0")"
W=$1; H=$2; NF=$3; NANIM=$4; TAG=$5
OUT="$DIR/out_$TAG"
mkdir -p "$OUT"
RAWIN="-f rawvideo -pix_fmt rgb24 -s ${W}x${H} -r 25 -i -"
Q="-hide_banner -loglevel error -y"

gen() { "$PY" "$DIR/gen_longshot.py" "$W" "$H" "$NF" "$NANIM"; }

# 0. baseline: today's share = one still JPEG
gen | ffmpeg $Q $RAWIN -frames:v 1 -q:v 3 "$OUT/still.jpg"

# 1. full-res GIF, optimal global palette + frame-diff (ffmpeg default transdiff)
gen | ffmpeg $Q $RAWIN -vf "palettegen=stats_mode=diff" "$OUT/pal.png"
gen | ffmpeg $Q $RAWIN -i "$OUT/pal.png" \
  -lavfi "[0:v][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUT/full.gif"

# 2. half-width GIF (540)
HW=$((W/2)); HH=$((H/2)); HH=$(( (HH/2)*2 ))
gen | ffmpeg $Q $RAWIN -vf "scale=$HW:-2,palettegen=stats_mode=diff" "$OUT/pal_half.png"
gen | ffmpeg $Q $RAWIN -i "$OUT/pal_half.png" \
  -lavfi "[0:v]scale=$HW:-2[s];[s][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUT/half.gif"

# 3. H.264 mp4 (needs even dims)
EW=$(( (W/2)*2 )); EH=$(( (H/2)*2 ))
gen | ffmpeg $Q $RAWIN -vf "scale=$EW:$EH" -c:v libx264 -preset medium -crf 23 \
  -pix_fmt yuv420p -movflags +faststart "$OUT/h264.mp4"

# 4. animated webp (lossy q=75)
gen | ffmpeg $Q $RAWIN -c:v libwebp_anim -lossless 0 -q:v 75 -loop 0 "$OUT/anim.webp" \
  || gen | ffmpeg $Q $RAWIN -c:v libwebp -lossless 0 -q:v 75 -loop 0 "$OUT/anim.webp"

echo "=== $TAG  ${W}x${H}  ${NF}f  animated_cells=$NANIM ==="
ls -l "$OUT" | awk 'NR>1 {printf "%-16s %10.2f MB\n", $9, $5/1048576}'
