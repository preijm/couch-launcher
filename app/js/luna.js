/*
 * Thin wrapper around webOS's native PalmServiceBridge (no webOSTV.js needed).
 * Outside the TV (desktop browser) it falls back to a mock that logs calls.
 * Written in ES5 on purpose: older webOS versions run an old Chromium.
 */
var Luna = (function () {
  var live = []; // keep bridges referenced until they answer, or they get GC'd

  function call(uri, params, cb) {
    if (typeof PalmServiceBridge === 'undefined') {
      console.log('[luna mock]', uri, JSON.stringify(params || {}));
      if (cb) setTimeout(function () { cb({ returnValue: true, mock: true }); }, 50);
      return;
    }
    var bridge = new PalmServiceBridge();
    live.push(bridge);
    bridge.onservicecallback = function (msg) {
      var idx = live.indexOf(bridge);
      if (idx !== -1) live.splice(idx, 1);
      var res;
      try { res = JSON.parse(msg); } catch (e) { res = { returnValue: false, errorText: String(msg) }; }
      if (cb) cb(res);
    };
    bridge.call(uri, JSON.stringify(params || {}));
  }

  function launch(appId, cb) {
    call('luna://com.webos.applicationManager/launch', { id: appId }, cb);
  }

  return { call: call, launch: launch, isTV: typeof PalmServiceBridge !== 'undefined' };
})();
