// Settings shared across the Theta Hatch series (docs/THETA_SHARED_SETTINGS.md), kept in the vault
// at .theta/settings.json. SeriesSettings.java does the same on Android.
//
// Each app keeps locally: the last known document, which items it follows ("series" or "this app
// only"), and whether the first-launch carry-over was decided. On each sync: merge (newer `at` wins
// per item), offer other apps' values once, publish its own values for items the series lacks, and
// apply followed items that changed. Secrets are never written or read here.
"use strict";

const LABELS = {
  "gemini.model": "Geminiのモデル",
  "gemini.format": "Geminiの呼び出し形式",
  "google.account": "Googleアカウント",
  "calendar": "Googleカレンダーの登録先",
  "google.cloudReady": "Google Cloudの初期設定",
  "google.webClientId": "Web版のOAuthクライアントID",
};
const SECRET = /key|token|secret|password/i;
const ME = "meishi";

const empty = () => ({ spec: 1, updated: "", series: {}, apps: {} });
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const blank = (v) => v === undefined || v === null || v === "" || (typeof v === "object" && !Object.keys(v).length);

// The file's text → document (null when absent; "broken" when it is not readable JSON).
function parse(text) {
  if (text === null || text === undefined || text === "") return null;
  let j;
  try {
    j = JSON.parse(text);
  } catch {
    return "broken";
  }
  if (!j || typeof j !== "object" || Array.isArray(j)) return "broken";
  const doc = { ...j, spec: j.spec || 1, series: {}, apps: j.apps && typeof j.apps === "object" ? j.apps : {} };
  for (const [k, e] of Object.entries(j.series || {})) {
    if (SECRET.test(k) || !e || typeof e !== "object" || !("value" in e)) continue; // never secrets
    doc.series[k] = { value: e.value, by: String(e.by || ""), at: String(e.at || "") };
  }
  return doc;
}

// Per item, the newer `at` wins; unknown top-level keys and other apps' sections are kept.
function merge(a, b) {
  const out = { ...a, ...b, series: { ...a.series }, apps: { ...a.apps } };
  for (const [k, e] of Object.entries(b.series || {})) if (!out.series[k] || e.at > out.series[k].at) out.series[k] = e;
  for (const [app, v] of Object.entries(b.apps || {})) out.apps[app] = { ...(a.apps || {})[app], ...v };
  out.updated = [a.updated || "", b.updated || ""].sort().pop();
  return out;
}

function initial() {
  return { doc: empty(), follow: {}, decided: false };
}

// One sync. local: { doc, follow, decided } (as stored); text: the file's content or null;
// current: this app's values by key ({} entries left out when unset); me: app name.
// → { local, write: text | null, apply: {key: value}, offers: [{key, label, value, by, at}] }
function sync(local, text, current, me, now = new Date().toISOString()) {
  local = JSON.parse(JSON.stringify(local || initial()));
  const remote = parse(text);
  if (remote === "broken") return { local, write: null, apply: {}, offers: [], broken: true };
  const doc = merge(remote || empty(), local.doc || empty());
  const offers = [];
  if (!local.decided) {
    for (const [k, e] of Object.entries(doc.series))
      if (e.by !== me && !(k in local.follow) && LABELS[k] && !same(e.value, current[k]))
        offers.push({ key: k, label: LABELS[k], value: e.value, by: e.by, at: e.at });
    if (!offers.length) local.decided = true;
  }
  // Items the series does not have yet: this app's values become the series ones.
  for (const [k, v] of Object.entries(current))
    if (!blank(v) && !doc.series[k] && local.follow[k] !== false && !offers.some((o) => o.key === k)) {
      doc.series[k] = { value: v, by: me, at: now };
      local.follow[k] = true;
    }
  const apply = {};
  for (const [k, e] of Object.entries(doc.series)) if (local.follow[k] === true && !same(e.value, current[k])) apply[k] = e.value;
  const changed = !remote || !same(remote.series, doc.series) || !same(remote.apps, doc.apps);
  if (changed) doc.updated = now;
  local.doc = doc;
  return { local, write: changed ? JSON.stringify(doc, null, 2) + "\n" : null, apply, offers };
}

// A setting changed in this app: when it follows the series, the series gets the new value
// (written at the next sync). "This app only" items stay out of the series, and so does
// everything before the first sync's carry-over is decided (the offer comes first).
function changed(local, key, value, me, now = new Date().toISOString()) {
  local = JSON.parse(JSON.stringify(local || initial()));
  if (!local.decided || local.follow[key] === false || !LABELS[key] || blank(value)) return local;
  local.doc.series[key] = { value, by: me, at: now };
  local.follow[key] = true;
  return local;
}

// The first-launch choice: offered items in `keys` follow the series, the rest stay this app's.
function adopt(local, offers, keys) {
  local = JSON.parse(JSON.stringify(local || initial()));
  for (const o of offers) local.follow[o.key] = keys.includes(o.key);
  local.decided = true;
  return local;
}

function setFollow(local, key, on) {
  local = JSON.parse(JSON.stringify(local || initial()));
  local.follow[key] = !!on;
  return local;
}

// For the settings screen: every item the series knows or this app has.
function view(local, current) {
  const series = (local && local.doc && local.doc.series) || {};
  const keys = [...new Set([...Object.keys(series), ...Object.keys(current)])].filter((k) => LABELS[k]);
  return keys.map((k) => ({
    key: k,
    label: LABELS[k],
    value: series[k] ? series[k].value : current[k],
    mine: current[k],
    by: series[k] ? series[k].by : "",
    follow: local && local.follow ? local.follow[k] !== false : true,
  }));
}

module.exports = { ME, LABELS, parse, merge, sync, changed, adopt, setFollow, view, initial, empty };
