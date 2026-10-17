// Backend + VaultStore + HTTP bridge against a temporary vault, with fake imaging and Gemini.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { VaultStore } = require("../lib/vault-store");
const { Backend } = require("../lib/backend");
const server = require("../lib/server");
const V = require("../lib/vault-codec");

function setup(geminiResult) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "theta-test-"));
  const root = path.join(dir, "Theta Vault");
  const store = new VaultStore(root, path.join(dir, "overlay.json")).load();
  const values = {};
  let key = "";
  const calls = [];
  const fetchImpl = async (url, opt) => {
    calls.push(url);
    const body = { candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(geminiResult) }] } }] };
    return { ok: true, status: 200, text: async () => JSON.stringify(body), json: async () => body };
  };
  const events = [];
  const imaging = { toJpeg: async (b) => b, pdfPages: async (b) => [b, Buffer.concat([b, Buffer.from("2")])] };
  const backend = new Backend({
    store,
    settings: { get: (k, d) => (k in values ? values[k] : d), set: (o) => Object.assign(values, o) },
    secrets: { get: () => key, set: (v) => (key = v) },
    services: { imaging, openFiles: async () => [], saveFile: async () => "x" },
    fetchImpl,
  });
  backend.on((e) => events.push(e));
  const file = (name, content) => {
    const f = path.join(dir, name);
    fs.writeFileSync(f, content);
    return f;
  };
  const settle = async () => {
    for (let i = 0; i < 50 && (backend.running || store.people().some((p) => p.queue)); i++) await new Promise((r) => setTimeout(r, 10));
  };
  return { dir, root, store, backend, events, calls, file, settle, setKey: (k) => (key = k) };
}

const CARD = Object.fromEntries(require("../lib/prompts.json").card.keys.map((k) => [k, ""]));

test("import → recognize → note in the vault, device fields kept out", async () => {
  const t = setup({ ...CARD, name: "山田 太郎", company: "テスト株式会社", email: "t@example.com" });
  t.setKey("k");
  await t.backend.importFiles([t.file("a.jpg", "jpegA"), t.file("b.pdf", "pdfB")], {});
  await t.settle();
  const people = t.store.people();
  assert.strictEqual(people.length, 2);
  assert.ok(people.every((p) => p.status === "confirmed" && p.name === "山田 太郎"));
  const pdf = people.find((p) => p.pages.length === 2);
  assert.ok(pdf.sources[0].file.endsWith(".pdf"));
  const notes = t.store.notes();
  assert.strictEqual(notes.length, 2);
  const md = fs.readFileSync(notes[0], "utf8");
  assert.match(path.basename(notes[0]), /山田 太郎（テスト株式会社）/);
  assert.doesNotMatch(md, /queue/);
  assert.strictEqual(t.calls.length, 2);
  // Same files again: skipped by hash.
  await t.backend.importFiles([t.file("a2.jpg", "jpegA")], {});
  assert.strictEqual(t.store.people().length, 2);
});

test("save, note, merge, delete to trash; reload reads the same", async () => {
  const t = setup(CARD);
  await t.backend.dispatch("new", {});
  await t.backend.dispatch("new", {});
  const [a, b] = t.store.people().map((p) => p.id);
  await t.backend.dispatch("save", { id: a, person: { name: "佐藤 花子", kind: "shop", genre: "居酒屋" } });
  await t.backend.dispatch("save", { id: b, person: { name: "佐藤花子", phone: "03-0000-0000" } });
  await t.backend.dispatch("note", { id: a, text: "初回訪問" });
  assert.ok(fs.existsSync(path.join(t.root, "Meishi", "Shops", "佐藤 花子.md")));
  await t.backend.dispatch("merge", { id: a, sourceId: b });
  const p = t.store.person(a);
  assert.strictEqual(p.phone, "03-0000-0000");
  assert.strictEqual(p.notes[0].text, "初回訪問");
  assert.strictEqual(t.store.people().length, 1);
  assert.strictEqual(fs.readdirSync(path.join(t.root, ".theta", "trash")).length, 1);
  const again = new VaultStore(t.root, path.join(t.dir, "overlay.json")).load();
  assert.deepStrictEqual(V.render(again.person(a)), V.render(t.store.person(a)));
  await t.backend.dispatch("delete", { id: a });
  assert.strictEqual(t.store.people().length, 0);
});

test("hand-written Obsidian note gets an id once; nenga matched to sender", async () => {
  const t = setup({ ...Object.fromEntries(require("../lib/prompts.json").nenga.keys.map((k) => [k, ""])), senders: "鈴木 一郎", kind: "nenga", year: "2027" });
  fs.mkdirSync(path.join(t.root, "Meishi", "People"), { recursive: true });
  const f = path.join(t.root, "Meishi", "People", "鈴木.md");
  fs.writeFileSync(f, "---\nname: 鈴木 一郎\ncompany: 例\n---\n\nObsidianで書いたメモ\n");
  t.store.load();
  const id = t.store.people()[0].id;
  assert.match(fs.readFileSync(f, "utf8"), new RegExp("id: " + id));
  t.store.load();
  assert.strictEqual(t.store.people()[0].id, id);
  t.setKey("k");
  await t.backend.importFiles([t.file("n.jpg", "nenga")], { nenga: true, year: 2027 });
  await t.settle();
  const people = t.store.people();
  assert.strictEqual(people.length, 1);
  assert.strictEqual(people[0].greetings[0].year, 2027);
  assert.match(fs.readFileSync(t.store.byId.get(id).file, "utf8"), /Obsidianで書いたメモ/);
});

test("phone-only actions answer with a toast, caps say desktop", async () => {
  const t = setup(CARD);
  await t.backend.dispatch("syncNow", {});
  assert.deepStrictEqual(t.events.pop(), { type: "toast", data: "この操作はスマホ版（Android）で行えます" });
  const s = t.backend.state();
  assert.strictEqual(s.platform, "desktop");
  assert.ok(!s.caps.includes("contacts") && s.caps.includes("vault"));
});

function get(port, p, headers = {}, method = "GET", body) {
  return new Promise((ok, ng) => {
    const req = http.request({ host: "127.0.0.1", port, path: p, method, headers }, (res) => {
      let d = "";
      res.on("data", (c) => (d += c));
      res.on("end", () => ok({ status: res.statusCode, headers: res.headers, body: d }));
    });
    req.on("error", ng);
    if (body) req.write(body);
    req.end();
  });
}

test("HTTP bridge: token, cookie, Host guard, UI, calls", async () => {
  const t = setup(CARD);
  const ui = path.join(__dirname, "..", "ui");
  const token = "abc123";
  const port = await new Promise((ok) => {
    const s = server.start({ backend: t.backend, store: t.store, uiDir: ui, port: 0, token, onReady: ok });
    t.srv = s;
  });
  // port 0 → the Host check needs the real port; restart on it.
  t.srv.close();
  const srv = await new Promise((ok) => {
    const s = server.start({ backend: t.backend, store: t.store, uiDir: ui, port, token, onReady: () => ok(s) });
  });
  try {
    const H = { Host: `127.0.0.1:${port}` };
    assert.strictEqual((await get(port, "/", H)).status, 401);
    assert.strictEqual((await get(port, "/?token=" + token, { Host: "evil.example:" + port })).status, 403);
    const r = await get(port, "/?token=" + token, H);
    assert.strictEqual(r.status, 302);
    assert.strictEqual(r.headers.location, "/");
    const cookie = r.headers["set-cookie"][0].split(";")[0];
    const page = await get(port, "/", { ...H, Cookie: cookie });
    assert.strictEqual(page.status, 200);
    assert.match(page.body, /bridge\.js/);
    assert.match(page.body, /connect-src 'self'/);
    assert.strictEqual((await get(port, "/app.js", { ...H, Cookie: cookie })).status, 200);
    assert.strictEqual((await get(port, "/../package.json", { ...H, Cookie: cookie })).status, 404);
    assert.strictEqual((await get(port, "/media/..%2Fx", { ...H, Cookie: cookie })).status, 500);
    const call = await get(port, "/api/call", { ...H, Cookie: cookie, "Content-Type": "application/json" }, "POST", JSON.stringify({ action: "new" }));
    assert.strictEqual(call.status, 204);
    await new Promise((r) => setTimeout(r, 50));
    assert.strictEqual(t.store.people().length, 1);
  } finally {
    srv.close();
  }
});
