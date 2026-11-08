#!/usr/bin/env bash
# Helper for Bank Launch TV. Usage: scripts/tv.sh <command>
#   connect <ip>   connect adb to the TV over the network (port 5555)
#   deploy         build debug APK, install and open it
#   shot [file]    screenshot the TV to shots/
#   packages       list installed third-party packages
#   home-off       make Bank Launch TV the Home screen (disables Google TV home only if needed)
#   home-on        undo: bring the Google TV home back
#   yt-publish     upload youtube/theme.js (our YouTube look) as t.js; reaches the TV after a rebuild
#   yt-install     install our TizenTube build (make it with scripts/tizentube.sh)
#   yt-restart     restart YouTube on the TV
#   speaker-on     let the home screen switch between the Bluetooth speaker and the TV
#   keys-on        make the remote's YouTube button open our YouTube (keys-off: undo)
#   data-backup    copy the saved watch time from the TV to backups/ and OneDrive
#   data-restore <file>  put a backup back on the TV (after a reinstall)
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
    # Upload youtube/theme.js to the public repo as t.js. The TV is pinned to one commit,
    # so it only gets this after: scripts/tizentube.sh && scripts/tv.sh yt-install
    sha=$(gh api repos/preijm/bank-launch-tv-yt/contents/t.js -q .sha 2>/dev/null || true)
    gh api -X PUT repos/preijm/bank-launch-tv-yt/contents/t.js -f message="${2:-Update the YouTube look}" \
      -f content="$(base64 -w0 youtube/theme.js)" ${sha:+-f sha="$sha"} -q .commit.sha
    echo "Published. To put it on the TV: scripts/tizentube.sh && scripts/tv.sh yt-install" ;;
  yt-install)
    apk=$(ls -t build/tizentube/tizentube-bltv-*.apk | head -1)
    if ! "$ADB" install -r "$apk"; then
      echo "Replacing the official TizenTube (different signature; YouTube sign-in is lost once)"
      "$ADB" uninstall $YT && "$ADB" install "$apk"
    fi ;;
  yt-restart)
    "$ADB" shell am force-stop $YT; "$ADB" shell monkey -p $YT -c android.intent.category.LAUNCHER 1 >/dev/null ;;
  speaker-on)
    "$ADB" shell pm grant $PKG android.permission.BLUETOOTH_CONNECT && echo "Speaker switch allowed" ;;
  keys-on)
    # Our accessibility service takes over the YouTube button (see RemoteKeys.kt).
    svc="$PKG/.RemoteKeys"
    cur=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
    case "$cur" in *"$svc"*) ;; null|"") cur="$svc" ;; *) cur="$cur:$svc" ;; esac
    "$ADB" shell settings put secure enabled_accessibility_services "$cur"
    "$ADB" shell settings put secure accessibility_enabled 1
    echo "YouTube button: on" ;;
  keys-off)
    svc="$PKG/.RemoteKeys"
    cur=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
    new=$(echo "$cur" | tr ':' '\n' | grep -vx "$svc" | paste -sd: - || true)
    "$ADB" shell settings put secure enabled_accessibility_services "${new:-null}"
    echo "YouTube button: back to the TV's own" ;;
  data-backup)
    # The TV keeps only ~10 days of detail; everything older lives in this one file.
    mkdir -p backups
    out="backups/watch-time-$(date +%Y-%m-%d).xml"
    "$ADB" exec-out run-as $PKG cat shared_prefs/usage_days.xml > "$out"
    grep -q '<map' "$out" && echo "Saved $out ($(grep -c 'name="d:' "$out") days)" || { echo "Backup failed"; rm -f "$out"; exit 1; }
    # A copy off this PC: OneDrive, next to the signing key.
    od="$USERPROFILE/OneDrive/Projects/Bank Launch TV/Backups"
    [ -d "$od" ] && cp "$out" "$od/" && echo "Copied to OneDrive" ;;
  data-restore)
    export MSYS_NO_PATHCONV=1  # Git Bash: keep /data/... as a TV path
    [ -f "${2:-}" ] || { echo "Usage: scripts/tv.sh data-restore backups/watch-time-<date>.xml"; exit 1; }
    "$ADB" shell am force-stop $PKG
    "$ADB" push "$2" /data/local/tmp/usage_days.xml >/dev/null
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/usage_days.xml shared_prefs/usage_days.xml'"
    "$ADB" shell rm /data/local/tmp/usage_days.xml
    "$ADB" shell am start -n $PKG/.MainActivity >/dev/null
    echo "Restored $2" ;;
  *) sed -n '2,15p' "$0" ;;
esac
