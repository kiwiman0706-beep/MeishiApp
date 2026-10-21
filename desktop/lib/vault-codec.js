// Person record ⇄ Obsidian Markdown. Mirrors android/.../VaultCodec.java (docs/THETA_VAULT_SPEC.md):
// both platforms must read each other's files, and "rev" must be computed the same way.
"use strict";
const crypto = require("crypto");
const YAML = require("yaml");

const APP_DIR = "Meishi";
const FILES = APP_DIR + "/_files/";
const H_NOTES = "## メモ",
  H_MAIL = "## 年賀状・お便り・書類",
  H_AUDIO = "## 録音",
  H_TEXT = "## 名刺の全文";
const MANAGED = [H_NOTES, H_MAIL, H_AUDIO, H_TEXT];
const FREE_MARK = "<!-- ここから下は自由に書けます（アプリは変更しません） -->";
const ORDER = [
  "theta", "rev", "id", "name", "kana", "company", "department", "title", "email", "phone", "mobile", "fax",
  "postal", "address", "website", "genre", "hours", "holidays", "tags", "met", "met_place", "status",
  "contact_registered", "calendar_registered", "no_auto_contact", "import_key", "not_duplicate", "geo",
  "warnings", "created", "updated", "pages", "originals", "portrait", "logo", "logo_source", "audio", "greetings",
];
const OWN = new Set(ORDER);
const LISTS = ["email", "phone", "mobile", "fax"];
const FIELDS = [
  "name", "kana", "company", "department", "title", "email", "phone", "mobile", "fax", "postal", "address",
  "website", "tags", "metDate", "metPlace", "genre", "hours", "holidays",
];
const KIND_LABEL = {
  nenga: "年賀状", mochu: "喪中", kanchu: "寒中見舞い", shochu: "暑中見舞い",
  xmas: "クリスマスカード", letter: "手紙", other: "はがき", doc: "書類",
};
// Fields that only make sense on one device (contact row ids, queues): never written to the vault.
const LOCAL = [
  "contactRawId", "contactAccount", "contactPhoto", "queue", "queueError", "contactDuplicate",
  "suggestion", "contactUnlinked", "calendarEventId", "contactMerged", "logoTried", "kanaTried",
];

// ---- time (Asia/Tokyo, like the Java side) ----------------------------------------
const JST = 9 * 3600 * 1000;
const pad = (n, w = 2) => String(n).padStart(w, "0");
function iso(ms) {
  const d = new Date(ms + JST);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}+09:00`;
}
function noteTime(ms) {
  return iso(ms).slice(0, 16).replace("T", " ");
}
function ms(v, fallback) {
  if (typeof v === "number") return v;
  if (v == null) return fallback;
  let s = String(v).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) s += "T00:00:00+09:00";
  else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(s)) s += "+09:00";
  const t = Date.parse(s);
  return Number.isNaN(t) ? fallback : t;
}

// ---- media ------------------------------------------------------------------------
const pathOf = (local) => FILES + local;
function local(p) {
  if (p == null) return null;
  let s = String(p).trim().replace(/\\/g, "/");
  if (s.startsWith("[[") && s.endsWith("]]")) s = s.slice(2, -2);
  if (s.includes("|")) s = s.slice(0, s.indexOf("|"));
  const name = s.slice(s.lastIndexOf("/") + 1);
  return /^[A-Za-z0-9_.-]+$/.test(name) && !name.includes("..") ? name : null;
}

// Java's UUID.nameUUIDFromBytes (MD5, version 3), so both platforms derive the same note ids.
function nameUUID(text) {
  const b = crypto.createHash("md5").update(Buffer.from(text, "utf8")).digest();
  b[6] = (b[6] & 0x0f) | 0x30;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const split = (s, re) => String(s || "").split(re).map((x) => x.trim()).filter(Boolean);
const put = (m, k, v) => {
  if (v != null && v !== "") m[k] = v;
};
const blockId = (id) => String(id || "").replace(/[^A-Za-z0-9-]/g, "") || "n";
const cell = (s) => String(s).replace(/\|/g, "｜").replace(/\n/g, " ");

function rev(text) {
  return crypto.createHash("sha256").update(Buffer.from(text, "utf8")).digest("hex").slice(0, 12);
}

function dump(fm) {
  return YAML.stringify(fm, { lineWidth: 0, indentSeq: false, defaultStringType: "PLAIN", aliasDuplicateObjects: false });
}

function render(p) {
  const fm = {};
  const shop = p.kind === "shop";
  fm.theta = shop ? "meishi/shop@1" : "meishi/person@1";
  fm.id = p.id;
  put(fm, "name", p.name);
  put(fm, "kana", p.kana);
  if (p.company) fm.company = `[[${p.company}]]`;
  for (const k of ["department", "title"]) put(fm, k, p[k]);
  for (const k of LISTS) {
    const v = split(p[k], /\n/);
    if (v.length) fm[k] = v;
  }
  for (const k of ["postal", "address", "website", "genre", "hours", "holidays"]) put(fm, k, p[k]);
  const tags = split(p.tags, /[、,，\n]/);
  if (tags.length) fm.tags = tags;
  put(fm, "met", p.metDate);
  put(fm, "met_place", p.metPlace);
  put(fm, "status", p.status);
  if (p.contactRegistered) fm.contact_registered = true;
  if (p.calendarRegistered) fm.calendar_registered = true;
  if (p.noAutoContact) fm.no_auto_contact = true;
  put(fm, "import_key", p.importKey);
  if (Array.isArray(p.notDuplicate) && p.notDuplicate.length) fm.not_duplicate = [...p.notDuplicate];
  if (p.geo) {
    const g = {};
    if (p.geo.lat != null) g.lat = p.geo.lat;
    if (p.geo.lng != null) g.lng = p.geo.lng;
    g.address = p.geo.q || "";
    fm.geo = g;
  }
  put(fm, "warnings", p.warnings);
  fm.created = iso(p.created || Date.now());
  fm.updated = iso(p.updated || Date.now());
  const pages = (p.pages || []).map((x) => pathOf(x.file));
  if (pages.length) fm.pages = pages;
  const originals = (p.sources || []).map((s) => {
    const m = { file: pathOf(s.file) };
    if (s.name) m.name = s.name;
    if (s.hash) m.sha256 = s.hash;
    return m;
  });
  if (originals.length) fm.originals = originals;
  for (const k of ["portrait", "logo"]) if (p[k]) fm[k] = pathOf(p[k]);
  if (p.logo && p.logoSource) fm.logo_source = p.logoSource;
  const audio = (p.audio || [])
    .filter((a) => a.state !== "recording")
    .map((a) => ({ file: pathOf(a.file), time: iso(a.time || 0), seconds: a.seconds | 0 }));
  if (audio.length) fm.audio = audio;
  const gr = p.greetings || [];
  const greetings = gr.map((g) => {
    const m = { id: g.id || "", year: g.year | 0, dir: g.dir || "" };
    put(m, "date", g.date);
    m.kind = g.kind || "nenga";
    put(m, "title", g.title);
    put(m, "senders", g.senders);
    put(m, "message", g.message);
    const files = [], orig = [];
    for (const f of g.files || []) (f.original ? orig : files).push(pathOf(f.file));
    if (files.length) m.files = files;
    if (orig.length) m.originals = orig;
    return m;
  });
  if (greetings.length) fm.greetings = greetings;
  for (const [k, v] of Object.entries(p.vaultExtra || {})) if (!OWN.has(k)) fm[k] = v;

  let b = "---\n" + dump(fm) + "---\n\n";
  if (pages.length) b += pages.map((x) => `![[${x}|360]]`).join(" ") + "\n\n";
  b += H_NOTES + "\n";
  for (const n of [...(p.notes || [])].sort((a, c) => (a.time || 0) - (c.time || 0))) {
    const lines = String(n.text || "").split("\n");
    b += "- " + noteTime(n.time || 0) + " " + lines[0];
    for (const l of lines.slice(1)) b += "\n  " + l;
    b += " ^" + blockId(n.id) + "\n";
  }
  b += "\n";
  if (greetings.length) {
    b += H_MAIL + "\n| 年 | 日付 | 届いた/送った | 種類 | 件名・差出人 |\n|---|---|---|---|---|\n";
    for (const g of [...gr].sort((x, y) => String(y.date || "").localeCompare(String(x.date || ""))))
      b += `| ${g.year | 0} | ${g.date || ""} | ${g.dir === "sent" ? "送った" : "届いた"} | ${KIND_LABEL[g.kind || "nenga"] || "お便り"} | ${cell(g.title ? g.title : String(g.senders || "").replace(/\n/g, "・"))} |\n`;
    b += "\n";
  }
  if (audio.length) {
    b += H_AUDIO + "\n";
    for (const a of audio)
      b += `- ![[${a.file}]] ${a.time.slice(0, 16).replace("T", " ")}（${Math.floor(a.seconds / 60)}分${a.seconds % 60}秒）\n`;
    b += "\n";
  }
  if (String(p.rawText || "").trim())
    b += H_TEXT + "\n```text\n" + String(p.rawText).replace(/```/g, "ˋˋˋ").trim() + "\n```\n\n";
  b += FREE_MARK + "\n";
  if (String(p.vaultFree || "").trim()) b += "\n" + String(p.vaultFree).trim() + "\n";
  return b.replace("\n", "\nrev: " + rev(b) + "\n");
}

function editedOutside(md) {
  md = md.replace(/\r\n/g, "\n");
  const m = /\nrev: ([0-9a-f]+)\n/.exec(md);
  if (!m) return true;
  return m[1] !== rev(md.slice(0, m.index) + "\n" + md.slice(m.index + m[0].length));
}

const str = (v) => {
  if (v == null) return "";
  if (Array.isArray(v)) return v.filter((x) => x != null && String(x).trim()).map((x) => String(x).trim()).join("\n");
  return String(v).trim();
};
const unlink = (s) => {
  s = s.trim();
  if (s.startsWith("[[") && s.endsWith("]]")) s = s.slice(2, -2);
  if (s.includes("|")) s = s.slice(s.indexOf("|") + 1);
  return s;
};
const NOTE = /^[-*]\s+(\d{4}-\d{2}-\d{2})(?:[ T](\d{1,2}:\d{2}))?\s*(.*)$/;
const BLOCK = /\s\^([A-Za-z0-9-]+)\s*$/;

function parse(md, allowNewId = false) {
  md = md.replace(/\r\n/g, "\n");
  if (md.startsWith("﻿")) md = md.slice(1);
  if (!md.startsWith("---\n")) throw new Error("frontmatterがありません");
  const end = md.indexOf("\n---", 4);
  if (end < 0) throw new Error("frontmatterが閉じていません");
  const yaml = md.slice(4, end + 1);
  const bodyStart = md.indexOf("\n", end + 4);
  const body = bodyStart < 0 ? "" : md.slice(bodyStart + 1);
  const fm = YAML.parse(yaml) || {};
  if (typeof fm !== "object" || Array.isArray(fm)) throw new Error("frontmatterの形式が不正です");
  let id = str(fm.id);
  const newId = !id && allowNewId;
  if (newId) id = crypto.randomUUID();
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) throw new Error("idが不正です");
  const p = { id };
  if (newId) p.vaultNewId = true;
  if (String(fm.theta || "").startsWith("meishi/shop")) p.kind = "shop";
  for (const k of FIELDS) p[k] = "";
  p.name = str(fm.name);
  p.kana = str(fm.kana);
  p.company = unlink(str(fm.company));
  for (const k of ["department", "title", "postal", "address", "website", "genre", "hours", "holidays"]) p[k] = str(fm[k]);
  for (const k of LISTS) p[k] = str(fm[k]);
  p.tags = str(fm.tags).replace(/\n/g, "、");
  const met = str(fm.met);
  p.metDate = /^\d{4}-\d{2}-\d{2}/.test(met) ? met.slice(0, 10) : "";
  p.metPlace = str(fm.met_place);
  const st = str(fm.status);
  p.status = ["new", "review", "confirmed"].includes(st) ? st : p.name ? "confirmed" : "new";
  if (fm.contact_registered === true) p.contactRegistered = true;
  if (fm.calendar_registered === true) p.calendarRegistered = true;
  if (fm.no_auto_contact === true) p.noAutoContact = true;
  if (str(fm.import_key)) p.importKey = str(fm.import_key);
  if (Array.isArray(fm.not_duplicate)) p.notDuplicate = fm.not_duplicate.filter((x) => x != null).map(String);
  if (fm.geo && typeof fm.geo === "object") {
    p.geo = { q: str(fm.geo.address) };
    if (typeof fm.geo.lat === "number" && typeof fm.geo.lng === "number") Object.assign(p.geo, { lat: fm.geo.lat, lng: fm.geo.lng });
  }
  if (str(fm.warnings)) p.warnings = str(fm.warnings);
  const now = Date.now();
  p.created = ms(fm.created, now);
  p.updated = ms(fm.updated, p.created);
  p.pages = (Array.isArray(fm.pages) ? fm.pages : []).map(local).filter(Boolean).map((l) => ({ file: l, name: l }));
  p.sources = (Array.isArray(fm.originals) ? fm.originals : [])
    .map((o) => (o && typeof o === "object" ? o : { file: o }))
    .map((o) => {
      const l = local(o.file);
      if (!l) return null;
      const s = { file: l, name: str(o.name) || l, mime: l.endsWith(".pdf") ? "application/pdf" : "image/jpeg" };
      if (str(o.sha256)) s.hash = str(o.sha256);
      return s;
    })
    .filter(Boolean);
  for (const k of ["portrait", "logo"]) {
    const l = local(fm[k]);
    if (l) p[k] = l;
  }
  if (p.logo && typeof fm.logo_source === "string") p.logoSource = fm.logo_source;
  p.audio = (Array.isArray(fm.audio) ? fm.audio : [])
    .filter((m) => m && typeof m === "object")
    .map((m) => {
      const l = local(m.file);
      return l && { id: l, file: l, time: ms(m.time, now), seconds: typeof m.seconds === "number" ? m.seconds | 0 : 0, state: "saved" };
    })
    .filter(Boolean);
  const greetings = (Array.isArray(fm.greetings) ? fm.greetings : [])
    .filter((m) => m && typeof m === "object")
    .map((m) => {
      const g = {
        id: str(m.id) || crypto.randomUUID(),
        year: typeof m.year === "number" ? m.year | 0 : 0,
        dir: str(m.dir) === "sent" ? "sent" : "received",
      };
      const date = str(m.date);
      g.date = date.length >= 10 ? date.slice(0, 10) : date;
      g.kind = str(m.kind) || "nenga";
      for (const k of ["title", "senders", "message"]) if (str(m[k])) g[k] = str(m[k]);
      g.files = [];
      for (const key of ["files", "originals"])
        for (const f of Array.isArray(m[key]) ? m[key] : []) {
          const l = local(f);
          if (l) g.files.push(key === "originals" ? { file: l, name: l, original: true } : { file: l, name: l });
        }
      return g;
    });
  if (greetings.length) p.greetings = greetings;
  const extra = {};
  for (const [k, v] of Object.entries(fm)) if (!OWN.has(k)) extra[k] = v;
  if (Object.keys(extra).length) p.vaultExtra = extra;

  const notes = [];
  let free = "",
    section = null,
    text = null,
    afterMark = false,
    last = null;
  for (const line of body.split("\n")) {
    if (afterMark) {
      free += line + "\n";
      continue;
    }
    if (line.trim() === FREE_MARK) {
      afterMark = true;
      continue;
    }
    if (line.startsWith("## ")) {
      section = MANAGED.includes(line.trim()) ? line.trim() : null;
      last = null;
      if (section == null) free += line + "\n";
      continue;
    }
    if (section == null) {
      if (!(line.trim().startsWith("![[") && line.includes(FILES)) && !(line.trim() === "" && free === "")) free += line + "\n";
      continue;
    }
    if (section === H_NOTES) {
      if (line.startsWith("- ") || line.startsWith("* ")) {
        let m = NOTE.exec(line.trim());
        if (!m) {
          const t = line.slice(2).trim();
          if (!t) continue;
          m = NOTE.exec("- " + noteTime(now) + " " + t);
        }
        let t = m[3];
        let bid = null;
        const bm = BLOCK.exec(t);
        if (bm) {
          bid = bm[1];
          t = t.slice(0, bm.index);
        }
        const hm = m[2] == null ? "00:00" : m[2].length === 4 ? "0" + m[2] : m[2];
        const time = ms(m[1] + "T" + hm, now);
        last = { id: bid != null ? bid : nameUUID(String(time) + t), time, text: t.trim() };
        notes.push(last);
      } else if (last && line.startsWith("  ")) {
        let t = line.slice(2);
        const bm = BLOCK.exec(t);
        if (bm) {
          last.id = bm[1];
          t = t.slice(0, bm.index);
        }
        last.text += "\n" + t;
      } else if (line.trim()) {
        free += line + "\n";
        last = null;
      }
    } else if (section === H_TEXT) {
      if (line.startsWith("```")) {
        text = text == null ? "" : text;
        continue;
      }
      if (text != null) text += line + "\n";
    }
  }
  p.notes = notes;
  if (text != null && text.trim()) p.rawText = text.trim();
  if (free.trim()) p.vaultFree = free.trim();
  return p;
}

function fileName(p) {
  let n = String(p.name || "").trim() || "名称未登録";
  const c = String(p.company || "").trim();
  if (c && p.kind !== "shop") n += "（" + c + "）";
  n = n.replace(/[\\/:*?"<>|#^[\]\n\r\t]/g, " ").replace(/\s+/g, " ").trim();
  if (n.startsWith(".")) n = "_" + n;
  if (n.length > 80) n = n.slice(0, 80).trim();
  return n + ".md";
}
const folder = (p) => (p.kind === "shop" ? "Shops" : "People");

module.exports = { APP_DIR, FILES, FIELDS, LOCAL, render, parse, fileName, folder, editedOutside, rev, iso, ms, nameUUID, local };
