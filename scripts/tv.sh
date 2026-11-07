#!/usr/bin/env bash
# Helper for Bank Launch TV. Usage: scripts/tv.sh <command>
#   connect <ip>   connect adb to the TV over the network (port 5555)
#   deploy         build debug APK, install and open it
#   shot [file]    screenshot the TV to shots/
#   packages       list installed third-party packages
#   home-off       make Bank Launch TV the Home screen (disables Google TV home only if needed)
#   home-on        undo: bring the Google TV home back
#   yt-publish     upload youtube/theme.js (our YouTube look) and clear the CDN cache
#   yt-install     install our TizenTube build (make it with scripts/tizentube.sh)
#   yt-restart     restart YouTube on the TV
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=nl.reijm.banklaunchtv
YT=io.gh.reisxd.tizentube.cobalt
STOCK_HOME=(com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith)
TV="${TV:-192.168.x.x:5555}"
export ANDROID_SERIAL="${ANDROID_SERIAL:-$TV}"
top() { "$ADB" shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"; }
is_ours() { top | grep -q "$PKG"; }

case "${1:-}" in
  connect)  "$ADB" connect "$2:5555" ;;
  deploy)   ./gradlew assembleDebug -q && "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk \
              && "$ADB" shell appops set $PKG GET_USAGE_STATS allow \
              && "$ADB" shell am start -n $PKG/.MainActivity ;;
  shot)     mkdir -p shots; f="shots/${2:-tv-$(date +%H%M%S)}.png"; "$ADB" exec-out screencap -p > "$f"; echo "$f" ;;
  packages) "$ADB" shell pm list packages -3 | sort ;;
  home-off)
    "$ADB" shell cmd package set-home-activity "$PKG/.MainActivity" || true
    "$ADB" shell input keyevent 3; sleep 3
    for p in "${STOCK_HOME[@]}"; do
      is_ours && break
      echo "Home still goes elsewhere; disabling $p"
      "$ADB" shell pm disable-user --user 0 "$p"
      "$ADB" shell input keyevent 3; sleep 3
    done
    is_ours && echo "Bank Launch TV is now the Home screen." || { echo "Not switched:"; top; } ;;
  home-on)
    for p in "${STOCK_HOME[@]}"; do "$ADB" shell pm enable "$p"; done
    "$ADB" shell input keyevent 3 ;;
  yt-publish)
    # Upload youtube/theme.js to the public repo and clear jsDelivr's cache of it.
    sha=$(gh api repos/preijm/bank-launch-tv-yt/contents/theme.js -q .sha)
    gh api -X PUT repos/preijm/bank-launch-tv-yt/contents/theme.js -f message="${2:-Update theme.js}" \
      -f content="$(base64 -w0 youtube/theme.js)" -f sha="$sha" -q .commit.sha
    curl -s "https://purge.jsdelivr.net/gh/preijm/bank-launch-tv-yt@main/theme.js" >/dev/null
    echo "Published. Restart YouTube on the TV: tv.sh yt-restart" ;;
  yt-install)
    apk=$(ls -t build/tizentube/tizentube-bltv-*.apk | head -1)
    if ! "$ADB" install -r "$apk"; then
      echo "Replacing the official TizenTube (different signature; YouTube sign-in is lost once)"
      "$ADB" uninstall $YT && "$ADB" install "$apk"
    fi ;;
  yt-restart)
    "$ADB" shell am force-stop $YT; "$ADB" shell monkey -p $YT -c android.intent.category.LAUNCHER 1 >/dev/null ;;
  *) sed -n '2,11p' "$0" ;;
esac
