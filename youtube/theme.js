/*
 * Bank Launch TV look for YouTube (TizenTube Cobalt).
 *
 * Our patched TizenTube Cobalt loads this file instead of TizenTube's own script.
 * It sets a few TizenTube settings, loads the real TizenTube script (so ad blocking
 * keeps updating by itself), and adds our styling on top.
 */
(function () {
  var ACCENT = '#FF6B5E'; // YouTube coral from the home screen
  var LOOK_VERSION = 1;   // raise to re-apply the defaults below once

  // 1. TizenTube settings. Must be written before TizenTube's script reads them.
  try {
    var KEY = 'ytaf-configuration';
    var cfg = {};
    try { cfg = JSON.parse(localStorage[KEY]) || {}; } catch (e) { cfg = {}; }

    // Always: our build is signed differently, so TizenTube's updater can't install
    // official builds over it. Updates go through scripts/tizentube.sh instead.
    cfg.enableUpdater = false;

    // Once (per LOOK_VERSION), so changes made in TizenTube's settings stick.
    if (Number(localStorage['bltv-look']) !== LOOK_VERSION) {
      cfg.routeColor = '#000000';
      cfg.focusContainerColor = '#000000';
      cfg.enableShorts = false;
      cfg.showWelcomeToast = false;
      localStorage['bltv-look'] = String(LOOK_VERSION);
    }
    localStorage[KEY] = JSON.stringify(cfg);
  } catch (e) {
    console.warn('[bltv] settings failed', e);
  }

  // 2. The real TizenTube script, always the latest version.
  var s = document.createElement('script');
  s.src = 'https://cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js?v=' + Date.now();
  (document.head || document.documentElement).appendChild(s);

  // 3. Our look.
  var css = [
    'html, body, #container, ytlr-app, ytlr-browse { background-color: #000 !important; }',
    // Accent: progress bar, focus rings and selected states in YouTube's TV app.
    ':root { --bltv-accent: ' + ACCENT + '; }',
    '.ytLrProgressBarPlayed, .ytLrProgressBarSliderBase, ytlr-progress-bar [class*="played"] { background-color: var(--bltv-accent) !important; }',
    '.ytLrThumbnailOverlayResumePlaybackProgress, ytlr-thumbnail-overlay-resume-playback-renderer [class*="progress"] { background-color: var(--bltv-accent) !important; }',
  ].join('\n');

  function addStyle() {
    if (document.getElementById('bltv-style')) return;
    var st = document.createElement('style');
    st.id = 'bltv-style';
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  }
  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);
  console.info('[bltv] look v' + LOOK_VERSION + ' loaded'); // visible in adb logcat
})();
