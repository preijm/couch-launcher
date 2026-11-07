#!/usr/bin/env bash
# Builds our TizenTube Cobalt: the official release with one change, it loads
# youtube/theme.js (via jsDelivr from github.com/preijm/bank-launch-tv-yt)
# instead of TizenTube's own script. theme.js then loads TizenTube's script itself.
#
# Usage: scripts/tizentube.sh [version]      e.g. v2.0.3 (default: latest release)
# Output: build/tizentube/tizentube-bltv-<version>.apk   (install with: tv.sh yt-install)
#
# Signed with this PC's Android debug key, so it can't update over the official
# build: uninstall that first (YouTube sign-in is lost once).
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:-$(gh release view -R reisxd/TizenTubeCobalt --json tagName -q .tagName)}"
BT="$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/* | sort -V | tail -1)"
OUT=build/tizentube
mkdir -p "$OUT"
SRC="$OUT/cobalt-arm-$VER.apk"
[ -f "$SRC" ] || gh release download "$VER" -R reisxd/TizenTubeCobalt -p cobalt-arm.apk -O "$SRC"

python -I - "$SRC" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
OLD = b'https://cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js?v='
NEW = b'https://cdn.jsdelivr.net/gh/preijm/bank-launch-tv-yt@main/theme.js?v='
assert len(NEW) <= len(OLD)
NEW = NEW + b'\0' * (len(OLD) - len(NEW))  # C string: ends at the first NUL
patched = 0
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for info in zin.infolist():
        name = info.filename
        if name.startswith('META-INF/') and name.split('.')[-1] in ('SF', 'RSA', 'EC', 'DSA', 'MF'):
            continue  # old signature
        data = zin.read(name)
        if name.endswith('/libchrobalt.so'):
            n = data.count(OLD + b'\0')
            if n != 1:
                sys.exit(f'{name}: expected the script URL once, found {n}. TizenTube changed; check the patch.')
            data = data.replace(OLD + b'\0', NEW + b'\0')
            patched += 1
        zout.writestr(info, data, compress_type=info.compress_type)
if not patched:
    sys.exit('libchrobalt.so not found')
print(f'patched {patched} library')
PY

"$BT/zipalign.exe" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
APK="$OUT/tizentube-bltv-$VER.apk"
"$BT/apksigner.bat" sign --ks "$USERPROFILE/.android/debug.keystore" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$APK"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "$APK"
