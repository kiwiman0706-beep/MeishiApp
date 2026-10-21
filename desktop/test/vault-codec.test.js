const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const path = require("path");
const V = require("../lib/vault-codec");

// Same record as VaultCodecTest.sample() on Android.
function sample() {
  const p = { id: "2b6f3c1e-aaaa-bbbb-cccc-000000000001" };
  for (const k of V.FIELDS) p[k] = "";
  Object.assign(p, {
    name: "山田 太郎", kana: "やまだ たろう", company: "サンプル株式会社", title: "部長",
    email: "taro@example.com\ntaro2@example.com", mobile: "090-1234-5678", address: "東京都千代田区丸の内1-1-1",
    tags: "取引先、展示会", metDate: "2024-03-05", metPlace: "展示会: 東京ビッグサイト", status: "confirmed",
    contactRegistered: true, created: 1709600400123, updated: 1760000000456,
    geo: { lat: 35.681, lng: 139.767, q: "東京都千代田区丸の内1-1-1" },
    rawText: "山田 太郎\nサンプル株式会社\n部長", contactRawId: 42, queue: "contact",
    logo: "lg1.jpg", logoSource: "web:example.com",
    pages: [{ file: "a1-0.jpg" }, { file: "a1-1.jpg" }],
    sources: [{ file: "a1.pdf", name: "scan.pdf", hash: "abc" }],
    notes: [
      { id: "note-1", time: 1709600400000, text: "展示会でご挨拶" },
      { id: "note-2", time: 1709700000000, text: "見積もり送付\n来週フォロー" },
    ],
    audio: [{ file: "r1.m4a", time: 1709700000000, seconds: 85, state: "saved" }],
    greetings: [{ id: "g1", year: 2027, dir: "received", date: "2027-01-02", kind: "nenga", senders: "山田 太郎\n山田 花子", files: [{ file: "n1.jpg" }] }],
  });
  return p;
}
const fixtures = path.join(__dirname, "fixtures");

test("reads what Android writes", () => {
  const md = fs.readFileSync(path.join(fixtures, "android-rendered.md"), "utf8");
  assert.equal(V.editedOutside(md), false, "Android rev must verify on desktop");
  const p = V.parse(md);
  const s = sample();
  for (const k of ["name", "kana", "company", "title", "email", "mobile", "address", "tags", "metDate", "metPlace", "rawText"])
    assert.equal(p[k], s[k], k);
  assert.equal(p.created, s.created);
  assert.equal(p.updated, s.updated);
  assert.equal(p.contactRegistered, true);
  assert.equal(p.geo.lat, 35.681);
  assert.deepEqual(p.pages.map((x) => x.file), ["a1-0.jpg", "a1-1.jpg"]);
  assert.equal(p.notes[1].id, "note-2");
  assert.equal(p.notes[1].text, "見積もり送付\n来週フォロー");
  assert.equal(p.greetings[0].senders, "山田 太郎\n山田 花子");
  assert.equal(p.audio[0].seconds, 85);
  assert.ok(!("contactRawId" in p) && !("queue" in p));
});

test("writes what Android reads (fixture for CrossPlatformTest)", () => {
  const md = V.render(sample());
  assert.ok(!md.includes("contactRawId"));
  assert.equal(V.editedOutside(md), false);
  fs.writeFileSync(path.join(fixtures, "desktop-rendered.md"), md);
  // stable: render(parse(render(x))) === render(x)
  assert.equal(V.render(V.parse(md)), md);
});

test("obsidian edits, foreign keys and prose survive", () => {
  let md = V.render(sample());
  md = md.replace("name: 山田 太郎", "name: 山田 太一\naliases: [やまちゃん]") + "\n## 自分用\nメモ書き\n";
  assert.equal(V.editedOutside(md), true);
  const p = V.parse(md);
  assert.equal(p.name, "山田 太一");
  assert.deepEqual(p.vaultExtra.aliases, ["やまちゃん"]);
  assert.match(p.vaultFree, /## 自分用/);
  const again = V.render(p);
  assert.match(again, /aliases:/);
  assert.match(again, /## 自分用/);
});

test("note ids for hand-added lines match Java's nameUUIDFromBytes", () => {
  // UUID.nameUUIDFromBytes("1709600400000展示会".getBytes(UTF-8)) on the JVM
  assert.equal(V.nameUUID("hello"), "5d41402a-bc4b-3a76-b971-9d911017c592");
});

test("file names", () => {
  assert.equal(V.fileName({ name: "a/b", company: "x:y" }), "a b（x y）.md");
  assert.equal(V.fileName({ name: "さくら", company: "X", kind: "shop" }), "さくら.md");
  assert.equal(V.local("Meishi/_files/名刺.jpg"), null);
});
