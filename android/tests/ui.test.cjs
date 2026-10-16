// Local UI integration tests. No real contacts, recordings, API keys or Google accounts.
const { chromium } = require("playwright");
const fs = require("fs"),
  path = require("path"),
  assert = require("assert");
const assets = path.resolve(__dirname, "../app/src/main/assets");
const screenshotDir = process.env.SCREENSHOTS || "/tmp/meishinote-qa";
fs.mkdirSync(screenshotDir, { recursive: true });
const p = (id, name, company, status = "confirmed") => ({
  id,
  name,
  company,
  status,
  kana: "",
  department: "",
  title: "代表",
  email: "demo@example.test",
  phone: "00-0000-0000",
  mobile: "",
  fax: "",
  postal: "",
  address: "サンプル住所",
  website: "",
  tags: "サンプル",
  metDate: "2026-10-16",
  metPlace: "展示会",
  created: Date.now(),
  updated: Date.now(),
  pages: [],
  sources: [],
  notes: [],
  audio: [],
});
(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.CHROME ||
      "/tmp/meishi-tools/chrome-headless-shell-linux64/chrome-headless-shell",
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const page = await browser.newPage({
    viewport: { width: 393, height: 820 },
    deviceScaleFactor: 1,
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("dialog", (d) => d.accept());
  await page.route("https://app.meishinote.local/**", (r) => {
    let file = new URL(r.request().url()).pathname.slice(1) || "index.html";
    if (!["index.html", "app.js", "style.css"].includes(file))
      return r.fulfill({ status: 404, body: "" });
    r.fulfill({
      path: path.join(assets, file),
      contentType: file.endsWith("html")
        ? "text/html"
        : file.endsWith("css")
          ? "text/css"
          : "application/javascript",
    });
  });
  await page.addInitScript(() => {
    window.calls = [];
    window.Native = { call: (s) => window.calls.push(JSON.parse(s)) };
  });
  await page.goto("https://app.meishinote.local/index.html");
  await page.screenshot({ path: path.join(screenshotDir, "01-empty.png") });
  assert(await page.getByText("はじめての1枚を、ここへ。").isVisible());
  const demo = {
    people: [
      p("demo-1", "山田 太郎（サンプル）", "サンプルデザイン株式会社"),
      p("demo-2", "佐藤 花子（サンプル）", "サンプル医療センター", "review"),
    ],
    keySet: true,
    model: "gemini-2.5-flash",
    format: "legacy",
    folder: "content://test",
    recording: "",
  };
  demo.people[1].suggestion = {
    name: "佐藤 花子（サンプル）",
    company: "サンプル医療センター",
    title: "医師",
    warnings: "裏面の文字を確認してください",
    rawText: "サンプル名刺",
  };
  demo.people[0].notes = [
    {
      id: "note-1",
      time: Date.now(),
      text: "展示会でご挨拶。次回、資料を共有する予定。",
    },
  ];
  demo.people[0].audio = [
    {
      id: "audio-1",
      file: "sample.m4a",
      time: Date.now(),
      seconds: 85,
      state: "saved",
    },
  ];
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.screenshot({ path: path.join(screenshotDir, "02-list.png") });
  assert(await page.getByText("2 件の名刺").isVisible());
  await page.getByPlaceholder("氏名・会社・メモを検索").fill("資料");
  assert.equal(await page.locator(".person").count(), 1);
  await page.getByPlaceholder("氏名・会社・メモを検索").fill("");
  await page
    .getByRole("button", { name: "カード表示と表表示を切り替え" })
    .click();
  assert.equal(await page.locator("tbody tr").count(), 2);
  await page.locator("tbody tr").first().click();
  assert(
    await page
      .getByRole("heading", { name: "山田 太郎（サンプル）" })
      .isVisible(),
  );
  await page.screenshot({
    path: path.join(screenshotDir, "03-detail.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "編集", exact: true }).first().click();
  await page.locator("input").first().fill("山田 次郎（テスト）");
  await page.getByRole("button", { name: "確認済みで保存" }).click();
  let calls = await page.evaluate(() => window.calls);
  let save = calls.findLast((c) => c.action === "save");
  assert.equal(save.person.name, "山田 次郎（テスト）");
  assert.equal(save.person.status, "confirmed");
  await page.evaluate(() => {
    onNative({ type: "saved", data: "demo-1" });
    go("import");
  });
  await page.screenshot({
    path: path.join(screenshotDir, "04-import.png"),
    fullPage: true,
  });
  // Scan grouping is independent from file-import grouping and preserves target person.
  await page.getByRole("button", { name: /スキャン撮影/ }).click();
  assert.equal(await page.locator("#scanGrouping").inputValue(), "all");
  await page.screenshot({ path: path.join(screenshotDir, "06-scan-options.png"), fullPage: true });
  await page.locator("#startScan").click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "scan", id: "", mode: "all" });
  for (const mode of ["pair", "page"]) {
    await page.getByRole("button", { name: /スキャン撮影/ }).click();
    await page.locator("#scanGrouping").selectOption(mode);
    await page.locator("#startScan").click();
    calls = await page.evaluate(() => window.calls);
    assert.deepEqual(calls.at(-1), { action: "scan", id: "", mode });
  }
  await page.evaluate(() => openPerson("demo-1"));
  await page.getByRole("button", { name: "スキャンで追加" }).click();
  assert.equal(await page.locator("#scanGrouping").count(), 0);
  await page.locator("#startScan").click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "scan", id: "demo-1", mode: "all" });
  await page.getByRole("button", { name: "スキャンで追加" }).click();
  await page.locator("#normalCamera").click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "camera", id: "demo-1" });
  await page.evaluate(() => go("import"));
  await page.locator("#grouping").selectOption("pair");
  await page.getByRole("button", { name: /画像・PDFを選択/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.findLast((c) => c.action === "pick").mode, "pair");
  await page.evaluate(() => go("settings"));
  await page.screenshot({
    path: path.join(screenshotDir, "05-settings.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "一覧をCSVで保存" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.findLast((c) => c.action === "export").kind, "csv");
  // 320px to tablet: only explicit tables and carousel may overflow, never the whole viewport.
  for (const width of [320, 360, 393, 768]) {
    await page.setViewportSize({ width, height: 800 });
    for (const r of ["home", "import", "settings"]) {
      await page.evaluate((r) => go(r), r);
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        `${r} overflow at ${width}`,
      );
    }
    await page.evaluate(() => { go("import"); scanSheet(); });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `scan overflow at ${width}`);
    await page.evaluate(() => { closeModal(); openPerson("demo-2"); });
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      `detail overflow at ${width}`,
    );
    await page.evaluate(() => beginEdit());
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      `edit overflow at ${width}`,
    );
    await page.evaluate(() => {
      draft = null;
      route = "home";
      render();
    });
  }
  // HTML injection in content must be rendered as text.
  demo.people[0].name = '<img src=x onerror="window.pwned=true">';
  demo.people[0].notes[0].text = "<script>window.pwned=true</script>";
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.evaluate(() => openPerson("demo-1"));
  assert(!(await page.evaluate(() => window.pwned)));
  assert.equal(errors.length, 0, errors.join("\n"));
  console.log(
    "PASS: list/search/table/edit/import/export, scan grouping/target/fallback and 320–768px layout; HTML content escaping; no JS errors.",
  );
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
