// Record rules shared with Android (Store.java / Nenga.java / Backup.java), ported for desktop.
"use strict";
const crypto = require("crypto");
const V = require("./vault-codec");

function empty() {
  const now = Date.now();
  const p = { id: crypto.randomUUID(), created: now, updated: now, status: "new", pages: [], sources: [], notes: [], audio: [] };
  for (const k of V.FIELDS) p[k] = "";
  return p;
}

// Store.edit: card fields, status, kind, and settling a pending Gemini result.
function edit(p, input) {
  for (const k of V.FIELDS) if (k in input) p[k] = input[k] == null ? "" : String(input[k]);
  if ("status" in input) p.status = String(input.status);
  if ("kind" in input) {
    if (input.kind === "shop") p.kind = "shop";
    else if (p.kind === "shop") delete p.kind;
  }
  if (input.acceptSuggestion && p.suggestion) {
    p.rawText = p.suggestion.rawText || "";
    p.warnings = p.suggestion.warnings || "";
    delete p.suggestion;
  }
  return p;
}

// Store.mergePeople: fill gaps, append lists, keep the target's review state.
function merge(target, source) {
  for (const k of V.FIELDS) if (!target[k] && source[k]) target[k] = source[k];
  for (const k of ["pages", "sources", "notes", "audio", "greetings"]) {
    if (!Array.isArray(source[k])) continue;
    target[k] = [...(target[k] || []), ...source[k]];
  }
  for (const k of ["portrait", "logo"]) if (!target[k] && source[k]) target[k] = source[k];
  if (!target.status) target.status = "new";
  delete target.suggestion;
  delete target.contactDuplicate;
  return target;
}

// Gemini result applied directly (Pipeline.apply).
function applyResult(p, r) {
  for (const k of V.FIELDS) if (String(r[k] || "").trim()) p[k] = String(r[k]);
  if (r.kind === "shop") p.kind = "shop";
  else if ("kind" in r) delete p.kind;
  p.rawText = r.rawText || "";
  p.warnings = r.warnings || "";
  if (!p.metDate) p.metDate = V.iso(p.created || Date.now()).slice(0, 10);
  delete p.suggestion;
  p.status = String(p.name || "").trim() && !String(p.warnings || "").trim() ? "confirmed" : "review";
  return p;
}

// ---- New Year cards (Nenga.java) ---------------------------------------------------------
const INBOX = "nengaInbox";
const NENGA_KINDS = ["nenga", "mochu", "kanchu"];
const KINDS = ["nenga", "mochu", "kanchu", "shochu", "xmas", "letter", "other", "doc"];
const nfkc = (s) => String(s || "").normalize("NFKC");
const nname = (s) => nfkc(s).replace(/[\s・･]/g, "").toLowerCase();
const naddr = (s) =>
  nfkc(s)
    .replace(/〒?\d{3}-?\d{4}/g, "")
    .replace(/\s/g, "")
    .replace(/[‐‑‒–—―−ー－]/g, "-")
    .replace(/(丁目|番地|番|の)/g, "-")
    .replace(/号/g, "")
    .replace(/-+/g, "-")
    .replace(/-$/, "");

function matchSender(people, r) {
  const names = new Set(String(r.senders || "").split("\n").map(nname).filter((x) => x.length >= 2));
  const addr = naddr(r.address);
  const byName = [], byAddr = [];
  for (const p of people) {
    if (p.kind === INBOX || p.kind === "shop") continue;
    if (names.has(nname(p.name))) byName.push(p);
    if (addr.length >= 8 && addr === naddr(p.address)) byAddr.push(p);
  }
  if (byName.length === 1) return byName[0].id;
  if (byName.length > 1) {
    const both = byName.filter((p) => byAddr.includes(p));
    return both.length === 1 ? both[0].id : null;
  }
  return byAddr.length === 1 ? byAddr[0].id : null;
}

function season(ms) {
  const d = new Date(ms + 9 * 3600 * 1000);
  return d.getUTCMonth() >= 9 ? d.getUTCFullYear() + 1 : d.getUTCFullYear();
}
const today = (ms) => V.iso(ms).slice(0, 10);

function greeting(temp, r) {
  let kind = KINDS.includes(r.kind) ? r.kind : "nenga";
  const created = temp.created || Date.now();
  let year = NENGA_KINDS.includes(kind) ? temp.nengaYear || season(created) : new Date(created + 9 * 3600 * 1000).getUTCFullYear();
  if (/^\d{4}$/.test(r.year || "") && +r.year >= 1990 && +r.year <= 2100) year = +r.year;
  return {
    id: crypto.randomUUID(),
    year,
    dir: "received",
    date: today(created),
    kind,
    files: (temp.pages || []).map((x) => ({ file: x.file, name: x.name || x.file })),
    senders: r.senders || "",
    message: r.message || "",
  };
}

const hasGreeting = (p, year, dir) =>
  (p.greetings || []).some((g) => g.year === year && g.dir === dir && NENGA_KINDS.includes(g.kind || "nenga"));

// ---- CSV ------------------------------------------------------------------------------
const cell = (s) => {
  s = String(s == null ? "" : s);
  if (/^\s*[=+@-]/.test(s)) s = "'" + s;
  return '"' + s.replace(/"/g, '""') + '"';
};
const HEAD = ["氏名", "ふりがな", "会社・組織", "部署", "肩書き", "メール", "電話", "携帯", "FAX", "郵便番号", "住所", "Web", "タグ", "交換日", "交換場所", "ジャンル・業種", "営業時間", "定休日"];

function csv(people) {
  let out = "﻿" + [...HEAD, "メモ", "画像ファイル", "録音ファイル", "確認状態", "種類", "年賀状"].map(cell).join(",") + "\r\n";
  for (const p of people) {
    if (p.kind === INBOX) continue;
    const notes = (p.notes || []).map((n) => V.iso(n.time || 0).slice(0, 16).replace("T", " ") + " " + n.text).join("\n");
    const greet = (p.greetings || []).map((g) => `${g.year} ${g.dir === "sent" ? "送" : "届"} ${g.date || ""}`).sort().join(" / ");
    out +=
      [...V.FIELDS.map((k) => p[k]), notes, (p.pages || []).map((x) => x.file).join("\n"), (p.audio || []).map((x) => x.file).join("\n"), p.status, p.kind === "shop" ? "お店" : "人", greet]
        .map(cell)
        .join(",") + "\r\n";
  }
  return out;
}

function nengaCsv(people, year) {
  let out = "﻿氏名,ふりがな,会社・組織,郵便番号,住所," + `${year}年 送った,${year}年 届いた,${year - 1}年 送った,${year - 1}年 届いた,喪中\r\n`;
  for (const p of people) {
    if (p.kind === INBOX || p.kind === "shop") continue;
    const any = [year, year - 1].some((y) => hasGreeting(p, y, "sent") || hasGreeting(p, y, "received"));
    if (!any) continue;
    const mochu = (p.greetings || []).some((g) => g.kind === "mochu" && g.year >= year - 1);
    out +=
      [p.name, p.kana, p.company, p.postal, String(p.address || "").replace(/\n/g, " ")].map(cell).join(",") +
      "," + [hasGreeting(p, year, "sent"), hasGreeting(p, year, "received"), hasGreeting(p, year - 1, "sent"), hasGreeting(p, year - 1, "received")].map((b) => (b ? "○" : "")).join(",") +
      "," + (mochu ? "喪中" : "") + "\r\n";
  }
  return out;
}

// ---- companies and logos (Company.java / Logo.java) -------------------------------------
const FORMS =
  /株式会社|有限会社|合同会社|合資会社|合名会社|一般社団法人|一般財団法人|公益社団法人|公益財団法人|特定非営利活動法人|npo法人|医療法人社団|医療法人財団|医療法人|社会福祉法人|学校法人|宗教法人|独立行政法人|\(株\)|\(有\)|\(同\)|\(社\)|\(財\)/g;
function companyKey(p) {
  let c = p.company;
  if (!String(c || "").trim() && p.kind === "shop") c = p.name;
  return nfkc(c)
    .toLowerCase()
    .replace(FORMS, "")
    .replace(/\b(co\.?,?\s*ltd\.?|inc\.?|corp\.?|corporation|llc|ltd\.?|k\.k\.)/g, "")
    .replace(/[\s・.,'"()\-]/g, "");
}

// "ymin,xmin,ymax,xmax" on a 0–1000 grid → { x, y, width, height } in pixels with a margin, or null.
function logoRect(text, w, h) {
  const v = String(text || "").replace(/[[\]\s]/g, "").split(",");
  if (v.length !== 4 || v.some((x) => !/^-?\d+$/.test(x))) return null;
  const [y0, x0, y1, x1] = v.map((x) => Math.max(0, Math.min(1000, +x)));
  const bh = y1 - y0, bw = x1 - x0;
  if (bh < 15 || bw < 15 || bh * bw > 600000) return null;
  const pad = Math.floor(Math.max(bh, bw) / 12);
  const t = Math.max(0, y0 - pad), l = Math.max(0, x0 - pad), b = Math.min(1000, y1 + pad), r = Math.min(1000, x1 + pad);
  const x = Math.floor((l * w) / 1000), y = Math.floor((t * h) / 1000);
  return { x, y, width: Math.floor((r * w) / 1000) - x, height: Math.floor((b * h) / 1000) - y };
}

// Gemini's box is often a little off (a logo cut at one edge, extra margin). Corrected with the
// image itself (Logo.java has the same): px(x, y) → [r, g, b]. The background is the colour most
// common on the border of a search window around the box; "ink" is anything clearly different.
// Each side grows while ink touches it (the logo continues), then empty margin is trimmed and an
// even margin added. Gaps of background stop the growth, so neighbouring text stays out.
function refineLogoRect(px, w, h, r) {
  if (!r) return r;
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  // How far it may grow: the box's own size on each side (at least 8% of the card).
  const mx = Math.max(r.width, w * 0.08), my = Math.max(r.height, h * 0.08);
  const win = {
    x0: clamp(Math.floor(r.x - mx), 0, w - 1),
    y0: clamp(Math.floor(r.y - my), 0, h - 1),
    x1: clamp(Math.ceil(r.x + r.width + mx), 1, w),
    y1: clamp(Math.ceil(r.y + r.height + my), 1, h),
  };
  // Background: the most common (quantised) colour on the window's border.
  const counts = new Map();
  const sample = (x, y) => {
    const c = px(x, y);
    const k = (c[0] >> 4) * 256 + (c[1] >> 4) * 16 + (c[2] >> 4);
    counts.set(k, (counts.get(k) || 0) + 1);
  };
  for (let x = win.x0; x < win.x1; x += 2) (sample(x, win.y0), sample(x, win.y1 - 1));
  for (let y = win.y0; y < win.y1; y += 2) (sample(win.x0, y), sample(win.x1 - 1, y));
  const top = [...counts].sort((a, b) => b[1] - a[1])[0][0];
  const bg = [((top >> 8) & 15) * 16 + 8, ((top >> 4) & 15) * 16 + 8, (top & 15) * 16 + 8];
  const ink = (x, y) => {
    const c = px(x, y);
    return Math.abs(c[0] - bg[0]) + Math.abs(c[1] - bg[1]) + Math.abs(c[2] - bg[2]) > 90;
  };
  // Ink pixels in a strip (every other pixel along it).
  const rowInk = (y, x0, x1) => {
    let n = 0;
    for (let x = x0; x < x1; x += 2) if (ink(x, y)) n++;
    return n;
  };
  const colInk = (x, y0, y1) => {
    let n = 0;
    for (let y = y0; y < y1; y += 2) if (ink(x, y)) n++;
    return n;
  };
  let x0 = Math.floor(r.x), y0 = Math.floor(r.y), x1 = Math.ceil(r.x + r.width), y1 = Math.ceil(r.y + r.height);
  const step = Math.max(2, Math.round(Math.min(w, h) * 0.006));
  for (let i = 0; i < 400; i++) {
    let grew = false;
    if (y0 - step >= win.y0 && rowInk(y0 - step, x0, x1) >= 2) (y0 -= step), (grew = true);
    if (y1 + step <= win.y1 && rowInk(y1 + step - 1, x0, x1) >= 2) (y1 += step), (grew = true);
    if (x0 - step >= win.x0 && colInk(x0 - step, y0, y1) >= 2) (x0 -= step), (grew = true);
    if (x1 + step <= win.x1 && colInk(x1 + step - 1, y0, y1) >= 2) (x1 += step), (grew = true);
    if (!grew) break;
  }
  // Trim empty margins.
  while (y0 < y1 - 4 && rowInk(y0, x0, x1) === 0) y0++;
  while (y1 > y0 + 4 && rowInk(y1 - 1, x0, x1) === 0) y1--;
  while (x0 < x1 - 4 && colInk(x0, y0, y1) === 0) x0++;
  while (x1 > x0 + 4 && colInk(x1 - 1, y0, y1) === 0) x1--;
  if (x1 - x0 < 8 || y1 - y0 < 8) return r; // nothing recognisable: keep Gemini's box
  const pad = Math.round(Math.max(x1 - x0, y1 - y0) * 0.06) + 2;
  x0 = clamp(x0 - pad, 0, w);
  y0 = clamp(y0 - pad, 0, h);
  x1 = clamp(x1 + pad, 0, w);
  y1 = clamp(y1 + pad, 0, h);
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

// Logos cut with the corrected method are "card2"; plain "card" ones (0.15.3 and earlier) are
// offered again by the upgrade of old cards: one card per company.
const CUT = "card2";
function recutPicks(people) {
  const best = new Map();
  for (const p of people) {
    const k = companyKey(p);
    if (!k || p.logoSource !== "card" || !(p.pages || []).length || p.queue) continue;
    if (!best.has(k) || p.created > best.get(k).created) best.set(k, p);
  }
  return [...best.values()].map((p) => p.id);
}

// One card per company without a logo: the newest with an image, not tried before.
function logoPicks(people) {
  const best = new Map(), has = new Set();
  for (const p of people) {
    if (p.kind === INBOX) continue;
    const k = companyKey(p);
    if (!k) continue;
    if (p.logo) has.add(k);
    if (p.logoTried || p.queue) continue;
    // Prefer a card with an image (its logo can be cut), then the newest.
    const cur = best.get(k), img = (x) => ((x.pages || []).length ? 1 : 0);
    if (!cur || img(p) > img(cur) || (img(p) === img(cur) && p.created > cur.created)) best.set(k, p);
  }
  return [...best].filter(([k]) => !has.has(k)).map(([, p]) => p.id);
}

// ---- sync (Sync.java) ---------------------------------------------------------------------
// Both sides changed: the newer edit wins field by field where it has a value; lists are unioned
// so nothing captured on either side is lost. Device-only fields stay the local ones.
function syncMerge(local, remote) {
  const remoteNewer = (remote.updated || 0) > (local.updated || 0);
  const base = JSON.parse(JSON.stringify(remoteNewer ? remote : local));
  const other = remoteNewer ? local : remote;
  for (const k of V.FIELDS) if (!String(base[k] || "") && String(other[k] || "")) base[k] = other[k];
  for (const k of ["portrait", "logo", "rawText", "warnings"])
    if (!base[k] && other[k]) {
      base[k] = other[k];
      if (k === "logo") base.logoSource = other.logoSource;
    }
  const union = (key, id) => {
    const seen = new Set((base[key] || []).map((x) => x[id]));
    base[key] = [...(base[key] || []), ...(other[key] || []).filter((x) => !seen.has(x[id]))];
  };
  union("notes", "id");
  union("audio", "file");
  union("pages", "file");
  union("sources", "file");
  union("greetings", "id");
  if (base.notDuplicate || other.notDuplicate) base.notDuplicate = [...new Set([...(base.notDuplicate || []), ...(other.notDuplicate || [])])];
  for (const k of V.LOCAL) {
    if (local[k] !== undefined) base[k] = local[k];
    else delete base[k];
  }
  base.updated = Math.max(local.updated || 0, remote.updated || 0);
  return base;
}

// Files a card refers to (Sync.media): what has to travel with its note.
function media(p) {
  const out = new Set();
  for (const k of ["portrait", "logo"]) if (p[k]) out.add(p[k]);
  for (const k of ["pages", "sources", "audio"])
    for (const o of p[k] || []) if (o.file && !(k === "audio" && o.state === "recording")) out.add(o.file);
  for (const g of p.greetings || []) for (const f of g.files || []) if (f.file) out.add(f.file);
  return out;
}

module.exports = {
  refineLogoRect,
  CUT,
  recutPicks,
  syncMerge,
  media,
  companyKey,
  logoRect,
  logoPicks, empty, edit, merge, applyResult, INBOX, NENGA_KINDS, matchSender, season, greeting, hasGreeting, today, csv, nengaCsv };
