#!/usr/bin/env bash
# Helper for Bank Launch TV. Usage: scripts/tv.sh <command>
#   connect <ip>   connect adb to the TV over the network (port 5555)
#   deploy         build debug APK, install and open it
#   shot [file]    screenshot the TV to shots/
#   packages       list installed third-party packages
#   home-off       disable the stock Google TV home (makes Bank Launch TV the home)
#   home-on        restore the stock Google TV home
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=nl.reijm.banklaunchtv
STOCK_HOME=(com.google.android.apps.tv.launcherx)

case "${1:-}" in
  connect)  "$ADB" connect "$2:5555" ;;
  deploy)   ./gradlew assembleDebug -q && "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk \
              && "$ADB" shell am start -n $PKG/.MainActivity ;;
  shot)     mkdir -p shots; f="shots/${2:-tv-$(date +%H%M%S)}.png"; "$ADB" exec-out screencap -p > "$f"; echo "$f" ;;
  packages) "$ADB" shell pm list packages -3 | sort ;;
  home-off) for p in "${STOCK_HOME[@]}"; do "$ADB" shell pm disable-user --user 0 "$p"; done ;;
  home-on)  for p in "${STOCK_HOME[@]}"; do "$ADB" shell pm enable "$p"; done ;;
  *) sed -n '2,10p' "$0" ;;
esac
