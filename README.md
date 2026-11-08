# Bank Launch TV

A calm home screen (launcher) for a Sony BRAVIA with Google TV (Android TV OS 12). No ads, no recommendation rows: just a clock, your few favourite apps and how much you watch. It's the screen you start from on the couch (de bank).

It's a native Android app (Kotlin, no extra libraries), installed over the network with adb. The TV doesn't need to be rooted.

## What it does
- **Your apps, big and simple.** YouTube, Netflix, NOS, NPO Start and Prime Video by default; add or remove apps on the TV with "+ Add app".
- **Watch time.** Per app this week, this month and this year, plus "≈ 2h 10m per day · 8% less than last week". Open the week card for *Your TV time*: weeks and months, each bar split by app, and what changed per app since the bar before. The TV itself only keeps about 10 days of detail, so the app saves every day itself.
- **Speaker switch.** One button to switch between a Bluetooth speaker (e.g. a Sonos) and the TV speakers.
- **Remote buttons.** The YouTube button opens YouTube without ads (see below); green opens NOS, yellow opens NPO Start. While you watch TV channels the colour buttons keep doing teletext.

## Set it up on your own TV
You need a Windows PC with [Android Studio](https://developer.android.com/studio) (for the SDK and adb) and Git Bash.

1. On the TV: *Settings → System → About*, press *Build* 7 times. Then *Developer options → USB debugging* on (and *Network debugging* if your TV has it). Note the TV's IP address.
2. In Git Bash, in this folder, point the scripts at your TV (or change `TV=` at the top of `scripts/tv.sh`):
   ```bash
   export TV=192.168.x.x:5555
   scripts/tv.sh connect 192.168.x.x      # accept the prompt on the TV
   ```
3. Build, install and turn things on:
   ```bash
   scripts/tv.sh deploy       # build + install + open (also allows reading watch time)
   scripts/tv.sh home-off     # make it the Home screen (home-on: undo)
   scripts/tv.sh speaker-on   # allow the speaker switch
   scripts/tv.sh keys-on      # YouTube / green / yellow buttons (keys-off: undo)
   ```
4. Your app list and colours: `app/src/main/java/nl/reijm/banklaunchtv/Config.kt`.

Other commands: `scripts/tv.sh` with no arguments lists them all (screenshots, backups, YouTube).

## Keep these safe
- **The signing key**, `C:\Users\<you>\.android\debug.keystore`. Everything you install is signed with it. Lose it and updates can't be installed over the apps; you'd have to uninstall them, which wipes the watch-time history and your YouTube sign-in.
- **Watch time**: `scripts/tv.sh data-backup` copies it from the TV to `backups/` (and to OneDrive if that folder exists, see the script). After a reinstall: `scripts/tv.sh data-restore backups/watch-time-<date>.xml`.

## YouTube without ads
The YouTube tile and button open our own build of [TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt) (YouTube's TV app without ads). It is the official release with one change: it loads [`youtube/theme.js`](youtube/theme.js) instead of TizenTube's own script. That file sets a few TizenTube settings (black background, no Shorts, updater off), loads the real TizenTube script, and adds the styling.

```bash
scripts/tizentube.sh      # download the latest release, patch it, sign it with your key
scripts/tv.sh yt-install  # install it (the first time replaces the official TizenTube; sign in again once)
```

- Change the look: edit `youtube/theme.js`, then `scripts/tv.sh yt-publish`, `scripts/tizentube.sh` and `scripts/tv.sh yt-install`. It's served as `t.js` from the public repo [preijm/bank-launch-tv-yt](https://github.com/preijm/bank-launch-tv-yt) via jsDelivr. The build is pinned to one commit of that file, so nothing changes on the TV until you rebuild and reinstall.
- Without your own copy of that repo you can keep using it as is: `scripts/tizentube.sh` pins the latest `t.js` there.
