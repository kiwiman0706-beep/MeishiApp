// The shared UI over the desktop HTTP bridge, in Chromium: real VaultStore + Backend, fake imaging.
// Run: NODE_PATH=$(npm root -g) node test/e2e/ui.e2e.cjs   (needs Playwright; not part of npm test)
"use strict";
const { chromium } = require("playwright");
const assert = require("assert");
const fs = require("fs"), os = require("os"), path = require("path");
const { VaultStore } = require("../../lib/vault-store");
const { Backend } = require("../../lib/backend");
const server = require("../../lib/server");

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "theta-e2e-"));
  const store = new VaultStore(path.join(dir, "Theta Vault"), path.join(dir, "overlay.json")).load();
  const values = {};
  const backend = new Backend({
    store,
    settings: { get: (k, d) => (k in values ? values[k] : d), set: (o) => Object.assign(values, o) },
    secrets: { get: () => "", set: () => {} },
    services: { imaging: { toJpeg: async (b) => b, pdfPages: async (b) => [b] }, openFiles: async () => [] },
  });
  const port = 47000 + Math.floor(Math.random() * 900), token = "e2e";
  const srv = await new Promise((ok) => {
    const s = server.start({ backend, store, uiDir: path.join(__dirname, "../../ui"), port, token, onReady: () => ok(s) });
  });
  const shots = process.env.SCREENSHOTS || path.join(dir, "shots");
  fs.mkdirSync(shots, { recursive: true });
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  try {
    for (const width of [360, 1100]) {
      const page = await browser.newPage({ viewport: { width, height: 800 } });
      const errors = [];
      page.on("pageerror", (e) => errors.push(e.message));
      await page.goto(`http://127.0.0.1:${port}/?token=${token}`);
      await page.getByText("取り込む").first().waitFor();
      await page.screenshot({ path: path.join(shots, `home-${width}.png`) });
      // New card via the bridge, edit and save.
      await page.evaluate(() => Native.call(JSON.stringify({ action: "new" })));
      await page.getByLabel("編集").click();
      await page.locator("input").first().waitFor();
      await page.locator("input").first().fill("E2E 太郎");
      await page.getByRole("button", { name: /保存/ }).first().click();
      for (let i = 0; i < 50 && !store.people().some((p) => p.name === "E2E 太郎"); i++) await page.waitForTimeout(50);
      assert.ok(store.people().some((p) => p.name === "E2E 太郎"), "saved to the vault");
      const before = store.people().length;
      assert.ok(before >= 1);
      const html = await page.content();
      assert.ok(!/Google連絡先に登録して保存/.test(html), "contacts button must be hidden on desktop");
      const scroll = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
      assert.ok(scroll <= 0, "no horizontal scroll at " + width);
      await page.screenshot({ path: path.join(shots, `edit-${width}.png`) });
      await page.screenshot({ path: path.join(shots, `detail-${width}.png`) });
      // Menu shows the vault row, not the Drive sync rows.
      await page.goto(`http://127.0.0.1:${port}/`);
      await page.getByText("取り込む").first().waitFor();
      await page.getByText("メニュー").click();
      await page.getByRole("button", { name: /Theta Vault フォルダ/ }).waitFor();
      assert.ok(!(await page.getByText("Googleドライブと同期").count()), "Drive sync hidden on desktop");
      await page.screenshot({ path: path.join(shots, `menu-${width}.png`), fullPage: true });
      assert.deepStrictEqual(errors, []);
      await page.close();
    }
    console.log("PASS", shots);
  } finally {
    await browser.close();
    srv.close();
    store.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
