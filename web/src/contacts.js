// A card as a Google contact (People API), the same content Contacts.java writes on Android:
// family / given names with their readings, company, numbers, addresses, the exchange note.
"use strict";

const lines = (s) => String(s || "").split(/[\n,;]/).map((x) => x.trim()).filter(Boolean);
const cjk = (s) => /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u.test(s);
const parts = (s) => {
  const a = String(s || "").trim().split(/[ 　]+/);
  return a.length >= 2 ? [a[0], a.slice(1).join(" ")] : [String(s || "").trim(), ""];
};

function body(p) {
  const name = String(p.name || "").trim();
  const kana = String(p.kana || "").normalize("NFKC").trim();
  const n = { unstructuredName: name };
  const [a, b] = parts(name);
  const japanese = cjk(name);
  if (b) Object.assign(n, japanese ? { familyName: a, givenName: b } : { givenName: a, familyName: b });
  else if (name) n[japanese ? "familyName" : "givenName"] = name;
  if (kana) {
    const [k1, k2] = parts(kana);
    n.phoneticFamilyName = k1;
    if (k2) n.phoneticGivenName = k2;
  }
  const out = { names: [n] };
  if (p.company || p.department || p.title) out.organizations = [{ name: p.company || "", department: p.department || "", title: p.title || "", type: "work" }];
  const phones = [
    ...lines(p.phone).map((v) => ({ value: v, type: "work" })),
    ...lines(p.mobile).map((v) => ({ value: v, type: "mobile" })),
    ...lines(p.fax).map((v) => ({ value: v, type: "workFax" })),
  ];
  if (phones.length) out.phoneNumbers = phones;
  const emails = lines(p.email).map((v) => ({ value: v, type: "work" }));
  if (emails.length) out.emailAddresses = emails;
  if (p.address || p.postal) out.addresses = [{ streetAddress: String(p.address || "").replace(/\n/g, " "), postalCode: p.postal || "", type: "work" }];
  if (p.website) out.urls = lines(p.website).map((v) => ({ value: v, type: "work" }));
  out.biographies = [{ value: `Theta Meishi Hatchから登録\n交換日: ${p.metDate || ""}\n交換場所: ${p.metPlace || ""}`, contentType: "TEXT_PLAIN" }];
  return out;
}

module.exports = { body };
