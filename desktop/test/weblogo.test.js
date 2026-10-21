// Logos from company websites (weblogo.js) and the logo flow at recognition (backend.js).
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const W = require("../lib/weblogo");
const { VaultStore } = require("../lib/vault-store");
const { Backend } = require("../lib/backend");
const PROMPTS = require("../lib/prompts.json");

test("sites: web address first, company e-mail domain next, free mail never", () => {
  assert.deepStrictEqual(W.sites({ website: "www.sample.co.jp/about", email: "taro@sample.co.jp\nx@gmail.com" }), [
    "https://www.sample.co.jp/",
    "https://sample.co.jp/",
  ]);
  assert.deepStrictEqual(W.sites({ email: "a@docomo.ne.jp\nb@yahoo.co.jp" }), []);
});

test("candidates: structured logo, touch icon, biggest icon, then the usual places", () => {
  const html = `<html><head>
    <link rel="icon" href="/fav-16.png" sizes="16x16"><link rel="icon" href="/fav-192.png" sizes="192x192">
    <link rel="icon" href="/logo.svg" type="image/svg+xml">
    <link rel='apple-touch-icon' href='img/touch.png'>
    <script type="application/ld+json">{"@type":"Organization","logo":{"url":"https://cdn.sample.jp/logo.png"}}</script>
  </head></html>`;
  const urls = W.candidates(html, "https://www.sample.jp/top/").map((c) => c.url);
  assert.deepStrictEqual(urls.slice(0, 4), [
    "https://cdn.sample.jp/logo.png",
    "https://www.sample.jp/top/img/touch.png",
    "https://www.sample.jp/fav-192.png",
    "https://www.sample.jp/fav-16.png",
  ]);
  assert.ok(!urls.some((u) => u.endsWith(".svg")));
  assert.ok(urls.includes("https://www.sample.jp/favicon.ico"));
});

// A tiny web: pages and images by URL. Images are "PNG…" strings; anything else is not an image.
function web(routes) {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    const body = routes[url];
    if (body == null) return { ok: false, status: 404, headers: new Map(), arrayBuffer: async () => new ArrayBuffer(0) };
    const buf = new TextEncoder().encode(body);
    return { ok: true, status: 200, url, headers: new Map([["content-type", url.endsWith("/") ? "text/html" : "image/png"]]), arrayBuffer: async () => buf.buffer };
  };
  return { fetchImpl, calls };
}
const size = async (b) => (new TextDecoder().decode(b).startsWith("PNG") ? { width: 180, height: 180 } : null);

test("find: skips what is not an image, falls back to search only without an address", async () => {
  const w = web({
    "https://sample.jp/": `<link rel="apple-touch-icon" href="/broken.png"><link rel="icon" sizes="64x64" href="/ok.png">`,
    "https://sample.jp/broken.png": "<html>not found</html>",
    "https://sample.jp/ok.png": "PNG ok",
  });
  const r = await W.find({ fetchImpl: w.fetchImpl, size, searchSite: null }, { website: "sample.jp" });
  assert.strictEqual(r.domain, "sample.jp");
  assert.strictEqual(r.url, "https://sample.jp/ok.png");
  let asked = 0;
  const s = await W.find({ fetchImpl: w.fetchImpl, size, searchSite: async () => (asked++, "https://sample.jp/company") }, { company: "サンプル" });
  assert.strictEqual(asked, 1);
  assert.strictEqual(s.domain, "sample.jp");
  assert.strictEqual(await W.find({ fetchImpl: w.fetchImpl, size, searchSite: null }, { company: "サンプル" }), null);
});

test("recognition keeps the card's logo; without one the website's; a card logo replaces a web one", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "theta-logo-"));
  const store = new VaultStore(path.join(dir, "Theta Vault"), path.join(dir, "overlay.json")).load();
  const card = Object.fromEntries(PROMPTS.card.keys.map((k) => [k, ""]));
  let answer = {};
  const site = web({ "https://www.sample.co.jp/": `<link rel="apple-touch-icon" href="/t.png">`, "https://www.sample.co.jp/t.png": "PNG touch" });
  const fetchImpl = async (url, opt) => {
    if (!url.startsWith("https://generativelanguage")) return site.fetchImpl(url, opt);
    const body = { candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify({ ...card, ...answer }) }] } }] };
    return { ok: true, status: 200, text: async () => JSON.stringify(body) };
  };
  const values = {};
  const backend = new Backend({
    store,
    settings: { get: (k, d) => (k in values ? values[k] : d), set: (o) => Object.assign(values, o) },
    secrets: { get: () => "k", set: () => {} },
    services: {
      imaging: {
        toJpeg: async (b) => b,
        crop: async (b, rectOf) => (rectOf(1000, 600) ? Buffer.from("cut-" + b) : null),
        size,
        toPng: async (b) => Buffer.from(b),
      },
    },
    fetchImpl,
  });
  const settle = async () => {
    for (let i = 0; i < 100 && (backend.running || store.people().some((p) => p.queue)); i++) await new Promise((r) => setTimeout(r, 10));
  };
  const file = (n, c) => (fs.writeFileSync(path.join(dir, n), c), path.join(dir, n));

  // 1. No logo on the card, company web address on it → the website's touch icon.
  answer = { name: "A", company: "株式会社サンプル", website: "www.sample.co.jp", logoBox: "" };
  await backend.importFiles([file("a.jpg", "cardA")], {});
  await settle();
  const a = store.people().find((p) => p.name === "A");
  assert.strictEqual(a.logoSource, "web:www.sample.co.jp");
  assert.strictEqual(String(await store.readMedia(a.logo)), "PNG touch");

  // 2. A colleague's card has the logo → cut from it, and it replaces the web one for A too.
  answer = { name: "B", company: "(株)サンプル", logoBox: "100,100,300,500" };
  await backend.importFiles([file("b.jpg", "cardB")], {});
  await settle();
  const b = store.people().find((p) => p.name === "B");
  assert.strictEqual(b.logoSource, "card");
  assert.strictEqual(String(await store.readMedia(b.logo)), "cut-cardB");
  assert.strictEqual(store.person(a.id).logo, b.logo);

  // 3. A hand-picked logo is never replaced; the source survives the vault round trip.
  const c = store.person(a.id);
  c.logo = "hand.jpg";
  delete c.logoSource;
  store.put(c);
  answer = { name: "C", company: "サンプル株式会社", logoBox: "100,100,300,500" };
  await backend.importFiles([file("c.jpg", "cardC")], {});
  await settle();
  assert.strictEqual(store.person(a.id).logo, "hand.jpg");
  const again = new VaultStore(store.root, path.join(dir, "overlay.json")).load();
  assert.strictEqual(again.person(b.id).logoSource, "card");
  assert.strictEqual(again.person(a.id).logoSource, undefined);
});
