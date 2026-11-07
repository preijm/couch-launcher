/* Tegel — main UI logic. ES5 only (old webOS Chromium). */
(function () {
  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 461, ESC: 27 };
  var DAYS = ['zondag', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag'];
  var MONTHS = ['januari', 'februari', 'maart', 'april', 'mei', 'juni', 'juli',
                'augustus', 'september', 'oktober', 'november', 'december'];

  var rows = [];          // [[tileEl, ...], ...]
  var pos = { r: 0, c: 0 };
  var toastTimer = null;

  function $(id) { return document.getElementById(id); }

  // ---------- background ----------
  function applyBackground() {
    var bg = TEGEL_CONFIG.background;
    if (bg) $('bg').style.backgroundImage = 'url("' + bg + '")';
  }

  // ---------- clock ----------
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function tick() {
    var d = new Date();
    $('clock').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
    $('date').textContent = DAYS[d.getDay()] + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()];
  }
  function startClock() {
    tick();
    // align to the next minute boundary, then every minute
    setTimeout(function () { tick(); setInterval(tick, 60000); }, (60 - new Date().getSeconds()) * 1000);
  }

  // ---------- tiles ----------
  function buildRow(containerId, items, rowIndex) {
    var el = $(containerId);
    var list = [];
    for (var i = 0; i < items.length; i++) {
      (function (item, colIndex) {
        var t = document.createElement('div');
        t.className = 'tile';
        t.style.backgroundColor = item.color || '#2c3440';
        t.innerHTML = '<span class="glyph"></span><span class="label"></span>';
        t.querySelector('.glyph').textContent = item.glyph || item.label.charAt(0);
        t.querySelector('.label').textContent = item.label;
        t.addEventListener('mouseover', function () { focus(rowIndex, colIndex); });
        t.addEventListener('click', function () { focus(rowIndex, colIndex); activate(item); });
        el.appendChild(t);
        list.push(t);
      })(items[i], i);
    }
    rows.push(list);
    // keep item data alongside elements
    list.items = items;
  }

  function focus(r, c) {
    if (!rows[r] || !rows[r].length) return;
    if (c >= rows[r].length) c = rows[r].length - 1;
    if (c < 0) c = 0;
    var prev = rows[pos.r] && rows[pos.r][pos.c];
    if (prev) prev.className = 'tile';
    pos.r = r; pos.c = c;
    rows[r][c].className = 'tile focused';
  }

  function activate(item) {
    toast('Openen: ' + item.label);
    Luna.launch(item.appId, function (res) {
      if (!res.returnValue) toast('Kan ' + item.label + ' niet openen (' + (res.errorText || res.errorCode || 'onbekend') + ')');
    });
  }

  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    t.className = 'show';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, 2500);
  }

  // ---------- remote ----------
  function onKey(e) {
    var k = e.keyCode;
    if (k === KEY.LEFT)  { focus(pos.r, pos.c - 1); }
    else if (k === KEY.RIGHT) { focus(pos.r, pos.c + 1); }
    else if (k === KEY.UP)    { if (pos.r > 0) focus(pos.r - 1, pos.c); }
    else if (k === KEY.DOWN)  { if (pos.r < rows.length - 1) focus(pos.r + 1, pos.c); }
    else if (k === KEY.ENTER) { activate(rows[pos.r].items[pos.c]); }
    else if (k === KEY.BACK || k === KEY.ESC) { /* home screen: stay put */ }
    else return;
    e.preventDefault();
  }

  function init() {
    applyBackground();
    startClock();
    buildRow('row-apps', TEGEL_CONFIG.apps, 0);
    buildRow('row-inputs', TEGEL_CONFIG.inputs, 1);
    focus(0, 0);
    document.addEventListener('keydown', onKey, false);
    // refresh clock when the app comes back to the foreground
    document.addEventListener('visibilitychange', function () { if (!document.hidden) tick(); }, false);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, false);
  else init();
})();
