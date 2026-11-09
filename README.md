# Couch Launcher

A calm home screen (launcher) for a Sony BRAVIA with Google TV (Android TV OS 12). No ads, no recommendation rows: just a clock, your few favourite apps and how much you watch. It's the screen you start from on the couch, and it was mostly built from there too: by voice, watching the home screen change.

It's a native Android app (Kotlin, no extra libraries), installed over the network with adb. The TV doesn't need to be rooted.

## What it does
- **Your apps, big and simple.** YouTube, Netflix, NOS, NPO Start and Prime Video by default; add or remove apps on the TV with "+ Add app".
- **Watch time.** Per app this week, this month and this year, plus "≈ 2h 10m per day · 8% less than last week". Open the week card for *Your TV time*: weeks and months, each bar split by app, and what changed per app since the bar before. The TV itself only keeps about 10 days of detail, so the app saves every day itself.
- **Speaker switch.** One button to switch between a Bluetooth speaker (e.g. a Sonos) and the TV speakers.
- **Remote buttons.** The YouTube button opens YouTube without ads (see below); green opens NOS, yellow opens NPO Start. While you watch TV channels the colour buttons keep doing teletext.

## Set it up on your own TV
You need a Windows PC with [Android Studio](https://developer.android.com/studio) (for the SDK and adb) and Git Bash.

1. On the TV: *Settings → System → About*, press *Build* 7 times. Then *Developer options → USB debugging* on (and *Network debugging* if your TV has it). Note the TV's IP address.
2. In Git Bash, in this folder, connect to your TV. Its address is remembered in `.tv` (not in git):
   ```bash
   scripts/tv.sh connect 192.168.x.x      # accept the prompt on the TV
   ```
3. Build, install and turn things on:
   ```bash
   scripts/tv.sh deploy       # build + install + open (also allows reading watch time)
   scripts/tv.sh home-off     # make it the Home screen (home-on: undo)
   scripts/tv.sh speaker-on   # allow the speaker switch
   scripts/tv.sh keys-on      # YouTube / green / yellow buttons (keys-off: undo)
   scripts/tv.sh yt-install   # YouTube without ads (see below)
   scripts/tv.sh debloat      # switch off trackers, ads and junk (see below)
   ```
4. Your app list and colours: `app/src/main/java/nl/reijm/banklaunchtv/Config.kt`.

Other commands: `scripts/tv.sh` with no arguments lists them all (screenshots, backups).

## Keep these safe
- **The signing key**, `C:\Users\<you>\.android\debug.keystore`. Couch Launcher is signed with it. Lose it and updates can't be installed over the app; you'd have to uninstall it first, which wipes the watch-time history (restore it from a backup).
- **Watch time**: `scripts/tv.sh data-backup` copies it from the TV to `backups/` (and to OneDrive if that folder exists, see the script). After a reinstall: `scripts/tv.sh data-restore backups/watch-time-<date>.xml`.

## Trackers and junk off
`scripts/tv.sh debloat` switches off what the TV runs behind your back: Samba TV (which recognises what's on screen and reports what you watch), Sony and Google telemetry, promo rows, apps pushed onto the TV, shop demo mode and preloaded apps. The list is [`scripts/debloat.txt`](scripts/debloat.txt), taken from [Clean Your TV](https://github.com/Gathin23/cleanyourtv)'s tested list for the BRAVIA 4K VH2; for another TV, check their list for your model.

Nothing is uninstalled: `scripts/tv.sh debloat-undo` switches everything back on. A firmware update can turn things back on; `scripts/tv.sh debloat-check` shows what came back, and `debloat` switches it off again.

## YouTube without ads
The YouTube tile and button open [TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt), YouTube's TV app without ads. Install it once; it updates itself after that:

```bash
scripts/tv.sh yt-install
```

In TizenTube's own settings you can turn off Shorts, pick a black background and trim the side menu.
