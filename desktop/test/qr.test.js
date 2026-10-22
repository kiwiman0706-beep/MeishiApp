// QR codes on cards (qr.js); QrTest.java runs the same cases on Android and draws the code in
// fixtures/qr-vcard.txt.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const qr = require("../lib/qr");

const VCARD =
  "BEGIN:VCARD\nVERSION:3.0\nN:山田;太郎;;;\nFN:山田 太郎\nORG:サンプル株式会社;営業部\nTITLE:部長\nTEL;TYPE=WORK:03-1234-5678\nTEL;TYPE=CELL:090-1111-2222\nTEL;TYPE=FAX:03-1234-5679\nEMAIL:taro-yamada-qn@example.co.jp\nURL:https://example.co.jp/\nADR;TYPE=WORK:;;丸の内1-1-1;千代田区;東京都;100-0005;Japan\nEND:VCARD";

const { card } = require("./fixtures/qr-card");

test("decodes a printed code among other ink", () => {
  assert.strictEqual(qr.decode(card()), VCARD);
  assert.strictEqual(qr.decode({ data: new Uint8ClampedArray(100 * 100 * 4).fill(255), width: 100, height: 100 }), "");
});

test("vCard, MECARD and mailto are understood", () => {
  const q = qr.parse(VCARD);
  assert.strictEqual(q.name, "山田 太郎");
  assert.strictEqual(q.company, "サンプル株式会社");
  assert.strictEqual(q.department, "営業部");
  assert.strictEqual(q.postal, "100-0005");
  assert.strictEqual(q.address, "東京都千代田区丸の内1-1-1");
  assert.deepStrictEqual(q.phones.map((p) => p.kind), ["phone", "mobile", "fax"]);
  assert.deepStrictEqual(q.urls, ["https://example.co.jp/"]);
  const me = qr.parse("MECARD:N:山田,太郎;TEL:0312345678;EMAIL:t@example.jp;;");
  assert.strictEqual(me.name, "山田 太郎");
  assert.deepStrictEqual(me.emails, ["t@example.jp"]);
  assert.deepStrictEqual(qr.parse("mailto:t@example.jp?subject=hi").emails, ["t@example.jp"]);
});

test("the code's exact characters win; gaps are filled; the code stays with the full text", () => {
  // Gemini misread one letter of the address; the phone it read in another form is kept.
  const r = { name: "山田 花子", email: "hanako-yamada-gn@example.co.jp", phone: "(06)0000-4444", mobile: "", fax: "", website: "", rawText: "サンプル薬品株式会社" };
  qr.apply(r, ["hanako-yamada-qn@example.co.jp"]);
  assert.strictEqual(r.email, "hanako-yamada-qn@example.co.jp");
  assert.ok(r.rawText.endsWith("[QRコード] hanako-yamada-qn@example.co.jp"));
  const v = { name: "山田 太郎", email: "other@x.jp", phone: "03 1234 5678", mobile: "", fax: "", website: "", title: "" };
  qr.apply(v, [VCARD]);
  assert.strictEqual(v.email, "other@x.jp\ntaro-yamada-qn@example.co.jp");
  assert.strictEqual(v.phone, "03 1234 5678");
  assert.strictEqual(v.mobile, "090-1111-2222");
  assert.strictEqual(v.fax, "03-1234-5679");
  assert.strictEqual(v.website, "https://example.co.jp/");
  assert.strictEqual(v.title, "部長");
  assert.strictEqual(qr.hint([]), "");
  assert.match(qr.hint(["x"]), /QR1: x/);
});
