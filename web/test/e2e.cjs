// The built web app (dist/) in Chromium: import a photo, Gemini (mocked) reads it and finds the
// logo, the company view shows it, and everything survives a reload (IndexedDB + service worker).
// Run: npm run build && NODE_PATH=$(npm root -g) node test/e2e.cjs
"use strict";
const { chromium } = require("playwright");
const assert = require("assert");
const fs = require("fs"), path = require("path"), http = require("http"), os = require("os");

const dist = path.join(__dirname, "../dist");
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".png": "image/png", ".webmanifest": "application/manifest+json" };

(async () => {
  // Served under a sub-path, as on GitHub Pages (/MeishiApp/).
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, "http://x");
    if (!u.pathname.startsWith("/MeishiApp/")) return res.writeHead(404).end();
    let f = path.join(dist, decodeURIComponent(u.pathname.slice("/MeishiApp/".length)) || "index.html");
    if (f.endsWith(path.sep) || f === dist) f = path.join(dist, "index.html");
    if (!f.startsWith(dist) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) return res.writeHead(404).end("not found");
    res.writeHead(200, { "Content-Type": TYPES[path.extname(f)] || "application/octet-stream" });
    fs.createReadStream(f).pipe(res);
  });
  await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
  const base = `http://localhost:${server.address().port}/MeishiApp/`;

  const exe = ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome"].find((f) => fs.existsSync(f));
  const browser = await chromium.launch(exe ? { executablePath: exe } : {});
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => m.type() === "error" && !/404|Failed to load resource/.test(m.text()) && errors.push(m.text()));

  // Gemini, mocked: a card with a logo in its top-left corner.
  let geminiCalls = 0;
  await context.route("https://generativelanguage.googleapis.com/**", async (route) => {
    geminiCalls++;
    const keys = JSON.parse(route.request().postData()).generationConfig.responseSchema.required;
    const r = Object.fromEntries(keys.map((k) => [k, ""]));
    Object.assign(r, { name: "鈴木 一郎", company: "株式会社テスト", email: "ichiro@test.example", logoBox: "50,50,300,300" });
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ candidates: [{ finishReason: "STOP", content: { parts: [{ text: JSON.stringify(r) }] } }] }) });
  });

  await page.goto(base);
  await page.getByText("はじめての1枚を、ここへ。").waitFor();
  await page.evaluate(() => Native.call(JSON.stringify({ action: "settings", key: "test-key" })));
  await page.waitForFunction(() => state.keySet);

  // A photo (made by the page's own canvas) through the photo picker.
  const photo = path.join(os.tmpdir(), "card-" + Date.now() + ".png");
  const png = await page.evaluate(() => {
    const c = document.createElement("canvas");
    c.width = 900;
    c.height = 540;
    const g = c.getContext("2d");
    g.fillStyle = "#fff";
    g.fillRect(0, 0, 900, 540);
    g.fillStyle = "#c33";
    g.fillRect(60, 40, 160, 110); // the "logo"
    g.fillStyle = "#000";
    g.font = "40px sans-serif";
    g.fillText("Suzuki Ichiro", 300, 300);
    return c.toDataURL("image/png").split(",")[1];
  });
  fs.writeFileSync(photo, Buffer.from(png, "base64"));
  const chooser = page.waitForEvent("filechooser");
  await page.evaluate(() => Native.call(JSON.stringify({ action: "pick", mode: "file" })));
  await (await chooser).setFiles(photo);

  await page.locator(".person", { hasText: "鈴木 一郎" }).waitFor({ timeout: 20000 });
  assert.strictEqual(geminiCalls, 1);
  await page.waitForFunction(() => state.people[0]?.logo, null, { timeout: 10000 });
  const p = await page.evaluate(() => state.people[0]);
  assert.strictEqual(p.company, "株式会社テスト");
  assert.ok(p.logo && p.logoSource === "card2", "logo cut at recognition");
  assert.strictEqual(await page.evaluate(() => state.platform), "web");

  // Company view with the logo picture.
  await page.evaluate(() => setLayout("company"));
  const logoImg = page.locator(".cotile img");
  await logoImg.waitFor();
  await page.waitForFunction(() => document.querySelector(".cotile img").naturalWidth > 0);
  await page.screenshot({ path: path.join(os.tmpdir(), "web-companies.png") });

  // Reload: the card, its picture and the key are still there.
  await page.reload();
  await page.locator(".person", { hasText: "鈴木 一郎" }).waitFor();
  await page.waitForFunction(() => [...document.querySelectorAll(".person img")].every((i) => i.complete && i.naturalWidth > 0));
  assert.ok(await page.evaluate(() => state.keySet));
  assert.ok(await page.evaluate(async () => !!(await navigator.serviceWorker.getRegistration())), "service worker registered");

  // Menu: Google account row (web), no Android-only rows; no horizontal overflow.
  await page.evaluate(() => go("menu"));
  await page.getByRole("button", { name: /Googleアカウント/ }).click();
  await page.locator("#gclient").fill("not-an-id");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.getByText("OAuthクライアントIDの形式が違います").waitFor();
  await page.evaluate(() => closeModal());
  assert.ok(!(await page.getByText("ホーム画面にウィジェットを追加").count()));
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({ path: path.join(os.tmpdir(), "web-menu.png"), fullPage: true });

  assert.deepStrictEqual(errors, []);
  console.log("PASS web e2e");
  await browser.close();
  server.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
