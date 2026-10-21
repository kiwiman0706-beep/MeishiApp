"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { body } = require("../src/contacts");

test("names and readings split like Android; numbers by kind", () => {
  const b = body({ name: "山田　太郎", kana: "やまだ たろう", company: "サンプル株式会社", title: "部長", phone: "03-0000-0000", mobile: "090-0000-0000", fax: "03-1111-1111", email: "t@sample.jp", postal: "100-0001", address: "東京都千代田区\n1-1", website: "https://sample.jp", metDate: "2026-10-01" });
  assert.deepStrictEqual(b.names[0], { unstructuredName: "山田　太郎", familyName: "山田", givenName: "太郎", phoneticFamilyName: "やまだ", phoneticGivenName: "たろう" });
  assert.deepStrictEqual(b.phoneNumbers.map((x) => x.type), ["work", "mobile", "workFax"]);
  assert.strictEqual(b.addresses[0].streetAddress, "東京都千代田区 1-1");
  assert.match(b.biographies[0].value, /交換日: 2026-10-01/);
});

test("western names are given name first; half-width kana becomes full-width", () => {
  assert.deepStrictEqual(body({ name: "Taro Yamada" }).names[0], { unstructuredName: "Taro Yamada", givenName: "Taro", familyName: "Yamada" });
  assert.strictEqual(body({ name: "山田太郎", kana: "ﾔﾏﾀﾞﾀﾛｳ" }).names[0].phoneticFamilyName, "ヤマダタロウ");
});
