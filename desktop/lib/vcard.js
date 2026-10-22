// Cards as vCard 3.0 (VCard.java writes the same bytes), the form LINE, Gmail and the phone's
// contacts app all take: one file holding one or many contacts.
"use strict";

const esc = (s) => String(s || "").replace(/\\/g, "\\\\").replace(/\r?\n/g, "\\n").replace(/[,;]/g, (c) => "\\" + c);
const lines = (s) => String(s || "").split("\n").map((x) => x.trim()).filter(Boolean);

// Lines longer than 75 bytes continue on the next line after a space (never inside a character).
function fold(line) {
  const out = [];
  let cur = "", bytes = 0;
  for (const ch of line) {
    const n = Buffer.byteLength(ch, "utf8");
    if (bytes + n > (out.length ? 74 : 75)) {
      out.push(cur);
      cur = "";
      bytes = 0;
    }
    cur += ch;
    bytes += n;
  }
  out.push(cur);
  return out.join("\r\n ");
}

function one(p) {
  const shop = p.kind === "shop";
  const name = String(p.name || "").trim();
  const parts = name.split(/[\s\u3000]+/).filter(Boolean);
  const kana = String(p.kana || "").trim().split(/[\s\u3000]+/).filter(Boolean);
  const v = ["BEGIN:VCARD", "VERSION:3.0"];
  if (shop || parts.length < 2) v.push(`N:${esc(name)};;;;`);
  else v.push(`N:${esc(parts[0])};${esc(parts.slice(1).join(" "))};;;`);
  v.push(`FN:${esc(name || p.company || "名称未登録")}`);
  if (!shop && kana.length) {
    v.push(`X-PHONETIC-LAST-NAME:${esc(kana[0])}`);
    if (kana.length > 1) v.push(`X-PHONETIC-FIRST-NAME:${esc(kana.slice(1).join(" "))}`);
  }
  const org = shop ? name : String(p.company || "").trim();
  if (org || p.department) v.push(`ORG:${esc(org)}${p.department ? ";" + esc(p.department) : ""}`);
  if (shop) v.push("X-ABShowAs:COMPANY");
  if (p.title) v.push(`TITLE:${esc(p.title)}`);
  for (const t of lines(p.phone)) v.push(`TEL;TYPE=WORK,VOICE:${esc(t)}`);
  for (const t of lines(p.mobile)) v.push(`TEL;TYPE=CELL:${esc(t)}`);
  for (const t of lines(p.fax)) v.push(`TEL;TYPE=WORK,FAX:${esc(t)}`);
  for (const e of lines(p.email)) v.push(`EMAIL;TYPE=INTERNET,WORK:${esc(e)}`);
  for (const u of lines(p.website)) v.push(`URL:${esc(u)}`);
  if (p.address || p.postal) v.push(`ADR;TYPE=WORK:;;${esc(p.address)};;;${esc(p.postal)};`);
  const note = [
    p.metDate || p.metPlace ? `名刺交換：${[p.metDate, p.metPlace].filter(Boolean).join(" ")}` : "",
    shop && p.genre ? `ジャンル：${p.genre}` : "",
    shop && p.hours ? `営業時間：${p.hours}` : "",
    shop && p.holidays ? `定休日：${p.holidays}` : "",
  ].filter(Boolean);
  if (note.length) v.push(`NOTE:${esc(note.join("\n"))}`);
  const tags = String(p.tags || "").split(/[、,，\s]+/).filter(Boolean);
  if (tags.length) v.push(`CATEGORIES:${tags.map(esc).join(",")}`);
  v.push("END:VCARD");
  return v.map(fold).join("\r\n") + "\r\n";
}

// people → the file's text.
function build(people) {
  return people.map(one).join("");
}

// The file's name as the receiver sees it.
function fileName(people) {
  if (people.length === 1) return (String(people[0].name || people[0].company || "名刺").trim().replace(/[\\/:*?"<>|\s]+/g, "_") || "名刺") + ".vcf";
  return `名刺_${people.length}件.vcf`;
}

module.exports = { build, fileName, fold };
