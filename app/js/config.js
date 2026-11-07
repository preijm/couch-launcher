/*
 * Tegel configuration — edit this file to change tiles and background.
 *
 * appId: the webOS app ID to launch. Get the real IDs from the TV with:
 *   ares-install -d tv --listfull
 * IDs marked "verify" are common defaults and still need checking on this TV.
 */
var TEGEL_CONFIG = {
  // Background image (path inside the app, or a URL). Empty string = plain gradient.
  background: 'assets/bg.jpg',

  apps: [
    { label: 'YouTube AdFree', appId: 'youtube.leanback.v4', glyph: '▶', color: '#c4302b' }, // verify
    { label: 'Netflix',        appId: 'netflix',             glyph: 'N',      color: '#8e0b12' }, // verify
    { label: 'Prime Video',    appId: 'amazon',              glyph: 'P',      color: '#1b6fa8' }, // verify
    { label: 'Disney+',        appId: 'com.disney.disneyplus-prod', glyph: 'D', color: '#16306b' }, // verify
    { label: 'Live TV',        appId: 'com.webos.app.livetv', glyph: 'TV',    color: '#3a4250' }, // verify
    { label: 'Instellingen',   appId: 'com.palm.app.settings', glyph: '⚙', color: '#2c3440' } // verify
  ],

  inputs: [
    { label: 'HDMI 1', appId: 'com.webos.app.hdmi1', glyph: '1', color: '#2d5b4a' },
    { label: 'HDMI 2', appId: 'com.webos.app.hdmi2', glyph: '2', color: '#2d5b4a' },
    { label: 'HDMI 3', appId: 'com.webos.app.hdmi3', glyph: '3', color: '#2d5b4a' },
    { label: 'HDMI 4', appId: 'com.webos.app.hdmi4', glyph: '4', color: '#2d5b4a' }
  ]
};
