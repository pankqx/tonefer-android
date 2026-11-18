#!/bin/sh
# Copies the phone web page from the laptop repo (pankqx/tonefer) into the app, for Phone to phone.
# Usage: scripts/sync-web.sh ../tonefer
set -e
SRC="${1:-../tonefer}/web"
DST="$(dirname "$0")/../app/src/main/assets/web"
rm -rf "$DST"
mkdir -p "$DST/phone" "$DST/static/fonts"
cp "$SRC/phone/index.html" "$DST/phone/"
for f in common.js phone.js tonefer.css nacl-fast.min.js nacl-LICENSE.txt icon.svg manifest.json; do cp "$SRC/static/$f" "$DST/static/"; done
cp "$SRC"/static/fonts/* "$DST/static/fonts/"
echo "Copied phone page into $DST"
