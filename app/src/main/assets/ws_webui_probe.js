"use strict";
/*
 * WebUI probe harness — injected into the REAL WebUI SPA (served by the machine)
 * by DebugWebUiProbeScreen after the WebView navigates to it.
 *
 * It does NOT click anything (zero side effects). It:
 *   1. hooks fetch/XHR/WebSocket to capture any future API traffic + responses,
 *   2. enumerates every button/clickable element (label only),
 *   3. regexes the SPA's JS bundle(s) for /api/... endpoint templates,
 *   4. for each endpoint our app does NOT yet cover AND that is not a settings
 *      endpoint, replays a read-only GET and captures the response shape.
 *
 * Results stream back via GagMateBridge.log / GagMateBridge.done.
 */

function log(s) { try { GagMateBridge.log(String(s)); } catch (e) {} }
function done(json) { try { GagMateBridge.done(String(json)); } catch (e) {} }

// Endpoints our app already talks to (REST). WS g_prof/d_prof/d_prof_dict/
// c_upd_act_prof_id are covered separately by the WS experiment tool.
const COVERED = [
  "/api/profiles/all",
  "/api/profile",
  "/api/shots",
  "/api/shot",
  "/api/system"
];

// Endpoint path keywords that belong to "settings" — never simulated.
const SETTINGS_KW = [
  "setting", "config", "network", "wifi", "wlan", "ble", "bluetooth",
  "pid", "calibrat", "preferences", "admin", "password", "ota", "factory"
];

// Button text/ancestor keywords that mark a settings control (listed, not clicked).
const SETTINGS_BTN_KW = [
  "设置", "setting", "config", "network", "wifi", "wi-fi", "bluetooth",
  "calibrat", "pid", "pref", "admin", "theme", "language"
];

const TRUNC = 1500;

function isSettingsPath(p) {
  const l = p.toLowerCase();
  return SETTINGS_KW.some(k => l.indexOf(k) >= 0);
}
function isCovered(p) {
  const l = p.toLowerCase();
  return COVERED.some(c => l.indexOf(c.toLowerCase()) >= 0);
}
function isSettingsBtn(text) {
  if (!text) return false;
  const l = text.toLowerCase();
  return SETTINGS_BTN_KW.some(k => l.indexOf(k) >= 0);
}

// ── network capture hooks (for any calls WE trigger) ──
const __captured = [];
(function installHooks() {
  try {
    const origFetch = window.fetch ? window.fetch.bind(window) : null;
    if (origFetch) {
      window.fetch = async function () {
        const args = arguments;
        const url = typeof args[0] === "string" ? args[0] : (args[0] && args[0].url) || "";
        const method = (args[1] && args[1].method) || "GET";
        try {
          const resp = await origFetch.apply(window, args);
          const clone = resp.clone();
          let body = "";
          try { body = await clone.text(); } catch (e) {}
          __captured.push({ url: url, method: method, status: resp.status, body: body.slice(0, TRUNC) });
          return resp;
        } catch (e) {
          __captured.push({ url: url, method: method, error: String(e) });
          throw e;
        }
      };
    }
    const O = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__m = m; this.__u = u; return O.apply(this, arguments);
    };
    const S = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function (b) {
      const self = this;
      this.addEventListener("load", function () {
        try { __captured.push({ url: self.__u, method: self.__m, status: self.status, body: (self.responseText || "").slice(0, TRUNC) }); } catch (e) {}
      });
      return S.apply(this, arguments);
    };
  } catch (e) { log("hook install error: " + e); }
})();

function nearestSection(el) {
  let cur = el;
  for (let i = 0; i < 6 && cur; i++) {
    const h = cur.querySelector && (cur.querySelector("h1,h2,h3,h4,legend,[role=heading]"));
    if (h && h.textContent) return h.textContent.trim().slice(0, 40);
    if (cur.getAttribute && (cur.getAttribute("aria-label") || cur.getAttribute("title"))) {
      return (cur.getAttribute("aria-label") || cur.getAttribute("title")).slice(0, 40);
    }
    cur = cur.parentElement;
  }
  return "";
}

function enumerateButtons() {
  const sel = "button, a[class*=btn], [role=button], input[type=button], input[type=submit], .MuiButton-root, .btn";
  const els = Array.from(document.querySelectorAll(sel));
  const out = [];
  const seen = new Set();
  els.forEach(el => {
    let label = (el.getAttribute && (el.getAttribute("aria-label") || el.getAttribute("title"))) || "";
    if (!label && el.value) label = el.value;
    if (!label && el.textContent) label = el.textContent.trim().replace(/\s+/g, " ");
    label = (label || "").slice(0, 60);
    const id = (el.id || "").slice(0, 40);
    const cls = (el.className && el.className.toString ? el.className.toString() : "").slice(0, 60);
    const sec = nearestSection(el);
    const key = label + "|" + id + "|" + cls;
    if (seen.has(key)) return;
    seen.add(key);
    const isSet = isSettingsBtn(label) || isSettingsBtn(sec);
    out.push({ tag: el.tagName, label: label, id: id, classes: cls, section: sec, isSettings: isSet });
  });
  return out;
}

async function fetchText(url) {
  try {
    const r = await fetch(url, { cache: "no-store" });
    return await r.text();
  } catch (e) { return ""; }
}

function extractEndpoints(text) {
  if (!text) return [];
  const re = /\/api\/[A-Za-z0-9_.\-$\{\}]+(?:\/\{[^}]+\})*/g;
  const found = [];
  let m;
  while ((m = re.exec(text)) !== null) found.push(m[0]);
  return found;
}

function normalizeForSim(tpl) {
  // turn path templates into a concrete GET path for a read probe
  return tpl.replace(/\{[^}]+\}/g, "1").split("?")[0];
}

async function runProbe(cfg) {
  const BASE = cfg.base.replace(/\/+$/, "");
  log("=== WebUI Probe === base=" + BASE);
  const buttons = enumerateButtons();
  log("BUTTONS: " + buttons.length + " found");
  buttons.forEach((b, i) => {
    log("  [" + (b.isSettings ? "SETTINGS" : "action") + "] " +
        (b.label || "(no label)") + "  <" + b.tag + (b.id ? " id=" + b.id : "") + ">" +
        (b.section ? "  @section='" + b.section + "'" : ""));
  });

  // gather script sources
  const scriptSrcs = Array.from(document.scripts)
    .map(s => s.src)
    .filter(s => s && s.indexOf(BASE) === 0);
  log("SPA scripts: " + scriptSrcs.length);

  let html = await fetchText(BASE + "/");
  let endpoints = extractEndpoints(html);
  for (const src of scriptSrcs) {
    const js = await fetchText(src);
    endpoints = endpoints.concat(extractEndpoints(js));
  }
  // dedupe
  const uniq = Array.from(new Set(endpoints)).sort();
  log("ENDPOINTS discovered: " + uniq.length);

  const discovered = [];
  let coveredCount = 0, settingsCount = 0, simCount = 0;
  for (const tpl of uniq) {
    const norm = tpl.split("?")[0];
    const covered = isCovered(norm);
    const settings = isSettingsPath(norm);
    if (covered) coveredCount++;
    if (settings) settingsCount++;
    let sample = null;
    if (!covered && !settings) {
      const path = normalizeForSim(tpl);
      const url = path.indexOf("http") === 0 ? path : (BASE + path);
      try {
        const r = await fetch(url, { method: "GET", cache: "no-store" });
        const body = await r.text();
        sample = { status: r.status, body: body.slice(0, TRUNC) };
        simCount++;
        log("  SIM GET " + path + " -> " + r.status + " (" + body.length + "B)");
      } catch (e) {
        sample = { error: String(e) };
        log("  SIM GET " + path + " -> ERR " + e);
      }
    }
    discovered.push({
      template: tpl,
      covered: covered,
      isSettings: settings,
      simulated: sample !== null,
      sample: sample
    });
  }

  log("SUMMARY covered=" + coveredCount + " settings(excluded)=" + settingsCount +
      " simulated(getData)=" + simCount + " total=" + uniq.length);

  const summary = {
    base: BASE,
    buttonCount: buttons.length,
    settingsButtons: buttons.filter(b => b.isSettings).length,
    discoveredCount: uniq.length,
    coveredCount: coveredCount,
    settingsCount: settingsCount,
    simulatedCount: simCount,
    buttons: buttons,
    discovered: discovered,
    conclusion: simCount > 0
      ? ("发现 " + simCount + " 个本 App 未覆盖的可读端点，已模拟 GET 取回数据（见每条 sample）。设置类端点已排除、未改动。")
      : (coveredCount === uniq.length
          ? "本 App 已覆盖 WebUI 发现的所有数据端点（设置类除外）。"
          : "未发现需要补充的可读端点（未覆盖项均为设置类或已被排除）。")
  };
  log("SUMMARY: " + JSON.stringify(summary));
  done(JSON.stringify(summary));
}
window.runProbe = runProbe;
