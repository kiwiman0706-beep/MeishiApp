// vCard files (vcard.js); VCardTest.java checks Android writes the same bytes.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const vcard = require("../lib/vcard");

const PEOPLE = (module.exports.PEOPLE = [
  { name: "山田 太郎", kana: "やまだ たろう", company: "サンプル商事株式会社", department: "営業本部 大阪支店", title: "", phone: "(06)0000-1111", mobile: "090-0000-2222", fax: "(06)0000-3333", email: "taro-yamada-qn@example.co.jp", website: "", postal: "541-0000", address: "大阪市中央区本町1-2-3", tags: "取引先、展示会", metDate: "2026-10-01", metPlace: "大阪, 本町" },
  { kind: "shop", name: "炭火焼 さくら", phone: "06-0000-0000", genre: "焼き鳥", hours: "17:00-23:00", holidays: "月曜", website: "https://example.jp/sakura" },
]);

test("cards become a vCard 3.0 file the contacts apps read (same bytes as Android)", () => {
  const text = vcard.build(PEOPLE);
  const expected = fs.readFileSync(path.join(__dirname, "fixtures", "contacts.vcf"), "utf8");
  assert.strictEqual(text, expected);
  assert.match(text, /^BEGIN:VCARD\r\nVERSION:3\.0\r\nN:山田;太郎;;;\r\n/);
  assert.match(text, /TEL;TYPE=CELL:090-0000-2222\r\n/);
  assert.match(text, /ADR;TYPE=WORK:;;大阪市中央区本町1-2-3;;;541-0000;\r\n/);
  assert.match(text, /NOTE:名刺交換：2026-10-01 大阪\\, 本町/);
  assert.match(text, /X-ABShowAs:COMPANY/);
  for (const line of text.split("\r\n")) assert.ok(Buffer.byteLength(line) <= 75, line);
  assert.strictEqual((text.match(/BEGIN:VCARD/g) || []).length, 2);
});

test("long lines fold between characters; file names", () => {
  const long = "NOTE:" + "あ".repeat(60);
  const f = vcard.fold(long);
  assert.strictEqual(f.replace(/\r\n /g, ""), long);
  for (const l of f.split("\r\n")) assert.ok(Buffer.byteLength(l) <= 75);
  assert.strictEqual(vcard.fileName([PEOPLE[0]]), "山田_太郎.vcf");
  assert.strictEqual(vcard.fileName(PEOPLE), "名刺_2件.vcf");
});
