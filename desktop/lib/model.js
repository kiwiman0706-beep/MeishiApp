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

module.exports = { empty, edit, merge, applyResult, INBOX, NENGA_KINDS, matchSender, season, greeting, hasGreeting, today, csv, nengaCsv };
