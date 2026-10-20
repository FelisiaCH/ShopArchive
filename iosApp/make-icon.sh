#!/bin/sh
# Renders the home-screen icon (AppIcon60x60@2x/@3x.png in ShopArchive/) from ../composeApp/icons/shoparchive.svg, the art every platform shares:
# white background, no alpha, the receipt (not receipt and coin) centred and the art 62% of the width, as Android's adaptive icon. Needs rsvg-convert.
set -eu
cd "$(dirname "$0")"
dir=$(mktemp -d)
{
    echo '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024"><rect width="1024" height="1024" fill="white"/>'
    echo '<g fill="none" transform="translate(214.4 197.04) scale(1.24)">'
    sed -e '/^<?xml/d' -e '/<svg/d' -e '/<\/svg>/d' ../composeApp/icons/shoparchive.svg
    echo '</g></svg>'
} > "$dir/icon.svg"
rsvg-convert -b white -w 120 -h 120 "$dir/icon.svg" -o ShopArchive/AppIcon60x60@2x.png
rsvg-convert -b white -w 180 -h 180 "$dir/icon.svg" -o ShopArchive/AppIcon60x60@3x.png
rm -r "$dir"
