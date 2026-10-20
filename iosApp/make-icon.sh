#!/bin/sh
# Renders the home-screen icon (ShopArchive/Assets.xcassets/AppIcon.appiconset) from ../composeApp/icons/shoparchive.svg, the art every platform shares:
# the receipt (not receipt and coin) centred and the art 72% of the width, as Android's adaptive icon. Needs rsvg-convert.
#   AppIcon.png         on opaque white, RGB without alpha (iOS rejects an alpha channel here)
#   AppIcon-Dark.png    on transparent (iOS adds the dark backdrop), the paper dark and the green the app's dark money green, so no white sheet glares
#   AppIcon-Tinted.png  light on transparent, the coin's rim and symbol black: iOS tints the grays
set -eu
cd "$(dirname "$0")"
out=ShopArchive/Assets.xcassets/AppIcon.appiconset
dir=$(mktemp -d)
# art BACKGROUND SED-SCRIPT: writes $dir/icon.svg, the shared art through the sed script, on the background colour unless that is empty
art() {
    {
        echo '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
        [ -z "$1" ] || echo "<rect width=\"1024\" height=\"1024\" fill=\"$1\"/>"
        echo '<g fill="none" transform="translate(166.4 146.24) scale(1.44)">'
        sed -e '/^<?xml/d' -e '/<svg/d' -e '/<\/svg>/d' -e "$2" ../composeApp/icons/shoparchive.svg
        echo '</g></svg>'
    } > "$dir/icon.svg"
}
art white 's/^$//'
rsvg-convert -b white "$dir/icon.svg" -o "$out/AppIcon.png"
art "" 's/fill="white" stroke="#1E8C45"/fill="#2C2C2E" stroke="#1E8C45"/;s/r="100" fill="white"/r="100" fill="#1C1C1E"/;s/#1E8C45/#34D399/g'
rsvg-convert "$dir/icon.svg" -o "$out/AppIcon-Dark.png"
art "" 's/r="100" fill="white"/r="100" fill="#000"/;s/stroke="white"/stroke="#000"/;s/fill="white" stroke="#1E8C45"/fill="none" stroke="#1E8C45"/;s/#1E8C45/white/g'
rsvg-convert "$dir/icon.svg" -o "$out/AppIcon-Tinted.png"
rm -r "$dir"
