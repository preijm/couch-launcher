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
