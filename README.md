# Bank Launch TV

My own home screen (launcher) for my Sony BRAVIA (Google TV, Android TV OS 12). It shows big tiles for my favourite apps and the HDMI inputs, plus a clock and a custom background, without ads or recommendation rows. It's the screen you start from on the couch (de bank).

It's a native Android app (Kotlin, no extra libraries) that I install over the network with adb. The TV doesn't need to be rooted.

## Customise
- Favourite apps go in `app/src/main/java/nl/reijm/banklaunchtv/Config.kt`. Apps that aren't installed are skipped.
- The background is `app/src/main/res/drawable-nodpi/bg.jpg`.

## Use
```bash
scripts/tv.sh connect 192.168.x.x   # once: Developer options -> USB debugging on, accept prompt on TV
scripts/tv.sh deploy                # build + install + open
scripts/tv.sh shot                  # screenshot of the TV
scripts/tv.sh home-off              # make Bank Launch TV the Home screen (disables Google TV home)
scripts/tv.sh home-on               # undo: bring the Google TV home back
```

## YouTube

The YouTube tile opens our own build of [TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt) (YouTube's TV app without ads).
It is the official release with one change: it loads [`youtube/theme.js`](youtube/theme.js) instead of TizenTube's own script.
`theme.js` sets a few TizenTube settings (black background, no Shorts, updater off), loads the real TizenTube script, and adds our styling.

- Change the look: edit `youtube/theme.js`, then `scripts/tv.sh yt-publish`, `scripts/tizentube.sh` and `scripts/tv.sh yt-install`.
  It is served as `t.js` from the public repo [preijm/bank-launch-tv-yt](https://github.com/preijm/bank-launch-tv-yt) via jsDelivr.
  The build is pinned to one commit of that file, so nothing changes on the TV until you rebuild and reinstall.
- New TizenTube version: `scripts/tizentube.sh` (downloads, patches and signs it), then `scripts/tv.sh yt-install`.
  TizenTube's own updater is off, because our build is signed with this PC's debug key.
