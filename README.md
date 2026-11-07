# Tegel

A custom tile home screen for an LG webOS TV (`nl.reijm.tegel`). It shows app tiles, HDMI inputs, a clock and a custom background. The app runs on a TV in Developer Mode, so no root is needed.

- Configure tiles and the background in `app/js/config.js`.
- Find real app IDs with `npm run apps`.

## Commands
| | |
|---|---|
| `npm run serve` | preview in a desktop browser (launches are mocked) |
| `npm run deploy` | package + install + launch on the TV |
| `npm run inspect` | remote DevTools for the app on the TV |
| `npm run info` | TV / webOS info |

One-time setup is `npm i -g @webos-tools/cli`, then register the TV as device `tv`:
`ares-setup-device --add tv -i "{'host':'<IP>','port':'9922','username':'prisoner'}"` then `ares-novacom --device tv --getkey`.
