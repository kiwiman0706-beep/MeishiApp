// The web version's Drive sync (drive-vault.js) against an in-memory Google Drive: two browsers,
// plus edits made directly in the vault (as the phone, the PC or Obsidian would).
"use strict";
require("./setup");
const test = require("node:test");
const assert = require("node:assert");
const crypto = require("crypto");
const { MemoryDb } = require("../src/idb");
const { WebStore } = require("../src/web-store");
const { DriveVault } = require("../src/drive-vault");
const V = require("../../desktop/lib/vault-codec");
const model = require("../../desktop/lib/model");

const FOLDER = "application/vnd.google-apps.folder";

class FakeDrive {
  constructor() {
    this.files = new Map();
    this.n = 0;
  }
  md5(b) {
    return crypto.createHash("md5").update(Buffer.from(b)).digest("hex");
  }
  // The query shapes drive-vault.js uses.
  async list(q) {
    const name = /name='((?:[^'\\]|\\.)*)'/.exec(q)?.[1]?.replace(/\\'/g, "'");
    const parent = /'([^']+)' in parents/.exec(q)[1];
    const folder = q.includes(`mimeType='${FOLDER}'`);
    return [...this.files.values()]
      .filter((f) => !f.trashed && (f.parents || []).includes(parent) && (!name || f.name === name) && (!folder || f.mimeType === FOLDER))
      .map((f) => ({ id: f.id, name: f.name, mimeType: f.mimeType, parents: [...f.parents], md5Checksum: f.md5 }));
  }
  async download(id) {
    return new Uint8Array(this.files.get(id).content);
  }
  async create(meta, bytes) {
    const id = "f" + ++this.n;
    const content = bytes ? Buffer.from(bytes) : null;
    this.files.set(id, { id, name: meta.name, parents: [...meta.parents], mimeType: meta.mimeType || "application/octet-stream", content, md5: content && this.md5(content) });
    return { id, md5Checksum: content && this.md5(content) };
  }
  async update(id, meta, bytes, mime, move) {
    const f = this.files.get(id);
    if (meta && meta.name) f.name = meta.name;
    if (bytes) {
      f.content = Buffer.from(bytes);
      f.md5 = this.md5(f.content);
    }
    if (move) f.parents = [...f.parents.filter((p) => !move.remove.split(",").includes(p)), move.add];
    return { id, md5Checksum: f.md5 };
  }
  // Helpers for the "other device" side.
  folderId(path) {
    let cur = "root";
    for (const part of path.split("/")) cur = [...this.files.values()].find((f) => f.name === part && f.parents.includes(cur) && f.mimeType === FOLDER && !f.trashed)?.id;
    return cur;
  }
  notesIn(path) {
    const id = this.folderId(path);
    return [...this.files.values()].filter((f) => f.parents.includes(id) && f.name.endsWith(".md") && !f.trashed);
  }
  text(f) {
    return f.content.toString("utf8");
  }
  write(f, text) {
    f.content = Buffer.from(text);
    f.md5 = this.md5(f.content);
  }
}

async function browser(drive) {
  const db = new MemoryDb();
  const store = await new WebStore(db).load();
  return { db, store, vault: new DriveVault(store, db, drive) };
}

function card(name, extra = {}) {
  const p = { ...model.empty(), name, status: "confirmed", ...extra };
  p.notes = p.notes || [];
  return p;
}

test("cards and their images reach a second browser; unchanged runs send nothing", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  const p = card("山田 太郎", { company: "サンプル株式会社", pages: [{ file: "img1.jpg", name: "front" }] });
  await a.store.writeMedia("img1.jpg", Buffer.from("JPEG1"));
  a.store.put(p);
  const r1 = await a.vault.run();
  assert.strictEqual(r1.up, 1);
  assert.strictEqual(drive.notesIn("Theta Vault/Meishi/People").length, 1);
  assert.match(drive.notesIn("Theta Vault/Meishi/People")[0].name, /山田 太郎（サンプル株式会社）\.md/);
  const r2 = await a.vault.run();
  assert.deepStrictEqual([r2.up, r2.down], [0, 0]);

  const b = await browser(drive);
  const r3 = await b.vault.run();
  assert.strictEqual(r3.down, 1);
  assert.strictEqual(b.store.person(p.id).company, "サンプル株式会社");
  assert.strictEqual(String(await b.store.readMedia("img1.jpg")), "JPEG1"); // fetched on first use
  const r4 = await b.vault.run();
  assert.deepStrictEqual([r4.up, r4.down], [0, 0]);
});

test("edits from the phone / Obsidian come in; both sides edited → merged, notes kept from both", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  const p = card("佐藤 花子", { company: "サンプル商事", updated: 1000 });
  a.store.put(p, false);
  await a.vault.run();
  // Obsidian adds a note line and fills the title.
  const f = drive.notesIn("Theta Vault/Meishi/People")[0];
  const remote = V.parse(drive.text(f));
  remote.title = "部長";
  remote.notes.push({ id: "n-remote", time: 5000, text: "Obsidianで追記" });
  remote.updated = 3000;
  drive.write(f, V.render(remote));
  // Meanwhile this browser adds its own note.
  const local = a.store.person(p.id);
  local.notes.push({ id: "n-local", time: 4000, text: "ブラウザで追記" });
  local.updated = 2000;
  a.store.put(local, false);
  const r = await a.vault.run();
  assert.strictEqual(r.merged, 1);
  const m = a.store.person(p.id);
  assert.strictEqual(m.title, "部長");
  assert.deepStrictEqual(m.notes.map((n) => n.id).sort(), ["n-local", "n-remote"]);
  // The merge went back up.
  const back = V.parse(drive.text(drive.notesIn("Theta Vault/Meishi/People")[0]));
  assert.deepStrictEqual(back.notes.map((n) => n.id).sort(), ["n-local", "n-remote"]);
});

test("delete here → vault trash; deleted elsewhere → gone here; edit beats delete", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  const b = await browser(drive);
  const x = card("消す人"), y = card("向こうで消す人"), z = card("残る人");
  for (const p of [x, y, z]) a.store.put(p);
  await a.vault.run();
  await b.vault.run();
  assert.strictEqual(b.store.people().length, 3);

  a.store.remove(x.id);
  await a.vault.run();
  assert.strictEqual(drive.notesIn("Theta Vault/.theta/trash").length, 1);
  await b.vault.run();
  assert.ok(!b.store.byId.has(x.id), "deletion reaches the other browser");

  // Deleted in the vault (moved to the trash by the phone), but edited in browser B meanwhile.
  const f = drive.notesIn("Theta Vault/Meishi/People").find((n) => n.name.startsWith("向こうで消す人"));
  f.parents = [drive.folderId("Theta Vault/.theta/trash")];
  const edited = b.store.person(y.id);
  edited.memo = "x";
  edited.title = "編集した";
  b.store.put(edited);
  await b.vault.run();
  assert.strictEqual(b.store.person(y.id).title, "編集した", "the edit survives");
  assert.ok(drive.notesIn("Theta Vault/Meishi/People").some((n) => n.name.startsWith("向こうで消す人")), "and goes up again");
  await a.vault.run();
  assert.strictEqual(a.store.person(y.id).title, "編集した");
});

test("many notes missing at once: nothing is deleted", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  for (let i = 0; i < 12; i++) a.store.put(card("人" + i));
  await a.vault.run();
  for (const f of drive.notesIn("Theta Vault/Meishi/People")) f.trashed = true; // e.g. the folder was moved away
  await assert.rejects(a.vault.run(), /同期を止めました/);
  assert.strictEqual(a.store.people().length, 12);
});

test("hand-written Obsidian note gets an id once; shops go to Shops; inbox items stay local", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  await a.vault.run();
  const people = drive.folderId("Theta Vault/Meishi/People");
  await drive.create({ name: "手書き.md", parents: [people], mimeType: "text/markdown" }, Buffer.from("---\nname: 手書き 一郎\ncompany: 例\n---\n\nメモ\n"));
  await a.vault.run();
  const h = a.store.people().find((p) => p.name === "手書き 一郎");
  assert.ok(h);
  const written = drive.notesIn("Theta Vault/Meishi/People").find((n) => n.name.includes("手書き"));
  assert.match(drive.text(written), new RegExp("id: " + h.id));
  await a.vault.run();
  assert.strictEqual(a.store.people().filter((p) => p.name === "手書き 一郎").length, 1);

  const shop = card("やきとり大吉", { kind: "shop" });
  a.store.put(shop);
  a.store.put(card("年賀状の仮", { kind: model.INBOX }));
  await a.vault.run();
  assert.strictEqual(drive.notesIn("Theta Vault/Meishi/Shops").length, 1);
  assert.ok(!drive.notesIn("Theta Vault/Meishi/People").some((n) => n.name.includes("年賀状の仮")));
});

test("reads what Android writes (same note text), and Android reads ours", async () => {
  const fs = require("fs"), path = require("path");
  const md = fs.readFileSync(path.join(__dirname, "../../desktop/test/fixtures/android-rendered.md"), "utf8");
  const drive = new FakeDrive();
  const a = await browser(drive);
  await a.vault.run();
  await drive.create({ name: "山田 太郎（サンプル株式会社）.md", parents: [drive.folderId("Theta Vault/Meishi/People")], mimeType: "text/markdown" }, Buffer.from(md));
  const r = await a.vault.run();
  assert.deepStrictEqual([r.down, r.up], [1, 0], "nothing to write back: the rev verifies");
  const p = a.store.people()[0];
  assert.strictEqual(p.logoSource, "web:example.com");
  assert.strictEqual(V.editedOutside(V.render(p)), false);
});

test("series settings file: read when absent, created once, then updated in place", async () => {
  const drive = new FakeDrive();
  const a = await browser(drive);
  await a.vault.run();
  const io = await a.vault.settingsFile();
  assert.strictEqual(await io.read(), null);
  await io.write('{"spec":1}\n');
  await io.write('{"spec":1,"series":{}}\n');
  const files = [...drive.files.values()].filter((f) => f.name === "settings.json");
  assert.strictEqual(files.length, 1);
  assert.strictEqual(files[0].parents[0], drive.folderId("Theta Vault/.theta"));
  const b = await browser(drive);
  assert.strictEqual(await (await b.vault.settingsFile()).read(), '{"spec":1,"series":{}}\n');
});
