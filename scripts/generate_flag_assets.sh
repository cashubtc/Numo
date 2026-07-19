#!/usr/bin/env bash
# Generates circular-croppable flag drawables for the currency selector.
#
# Downloads the public-domain SVG flag set from hampusborgos/country-flags,
# rasterizes each ISO-3166 alpha-2 flag (plus "eu") to a 192x192 center-cropped
# square, and emits lossy WebP files to app/src/main/res/drawable-nodpi/flag_xx.webp.
#
# 192px covers a 40dp avatar at xxxhdpi (160px) with headroom. Requires
# rsvg-convert, sips (macOS), and cwebp. Run from the repo root; commit outputs.
set -euo pipefail

SIZE=192
QUALITY=80
OUT="app/src/main/res/drawable-nodpi"
SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT

echo "Downloading flag SVGs..."
curl -sL https://github.com/hampusborgos/country-flags/archive/refs/heads/main.tar.gz \
  | tar xz -C "$SRC" --strip-components=1

mkdir -p "$OUT"
count=0
for f in "$SRC"/svg/??.svg; do
  code=$(basename "$f" .svg)
  png="$SRC/$code.png"
  rsvg-convert -h "$SIZE" "$f" -o "$png"
  # Taller-than-wide flags (e.g. np) come out narrower than SIZE when rendered
  # by height; re-render by width so the center crop always has full coverage.
  w=$(sips -g pixelWidth "$png" | awk '/pixelWidth/{print $2}')
  if [ "$w" -lt "$SIZE" ]; then
    rsvg-convert -w "$SIZE" "$f" -o "$png"
  fi
  sips -c "$SIZE" "$SIZE" "$png" --out "$png" >/dev/null
  cwebp -quiet -q "$QUALITY" "$png" -o "$OUT/flag_${code}.webp"
  count=$((count + 1))
done

echo "Generated $count flags in $OUT"
du -sh "$OUT"
