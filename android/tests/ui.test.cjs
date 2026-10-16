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
      "/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell",
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
  assert(await page.locator("#count").getByText("2 件").isVisible());
  await page.getByPlaceholder("氏名・会社・メモを検索").fill("資料");
  assert.equal(await page.locator(".person").count(), 1);
  await page.getByPlaceholder("氏名・会社・メモを検索").fill("");
  // The shutter is one tap from anywhere and asks for the auto-recognising scan.
  await page.getByRole("button", { name: "名刺を撮影" }).click();
  let calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "quickScan" });
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
  // Confirmed, unregistered person: the single primary action is contact registration.
  await page.getByRole("button", { name: "Google連絡先に登録" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "contacts", id: "demo-1" });
  await page.getByRole("button", { name: "その他の操作" }).click();
  await page.screenshot({ path: path.join(screenshotDir, "04-detail-menu.png") });
  await page.getByRole("button", { name: /スキャンで画像を追加/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "scan", id: "demo-1", mode: "all" });
  await page.getByRole("button", { name: "編集", exact: true }).first().click();
  await page.locator("input").first().fill("山田 次郎（テスト）");
  await page.getByRole("button", { name: "保存のみ" }).click();
  calls = await page.evaluate(() => window.calls);
  let save = calls.findLast((c) => c.action === "save");
  assert.equal(save.person.name, "山田 次郎（テスト）");
  assert.equal(save.person.status, "confirmed");
  assert.equal(save.thenContact, false);
  await page.evaluate(() => onNative({ type: "saved", data: "demo-1" }));
  // A person with a pending Gemini result opens straight into the pre-filled review form.
  await page.evaluate(() => { layout = "cards"; go("home"); });
  await page.locator(".person", { hasText: "佐藤" }).click();
  assert(await page.getByText("読み取り結果の確認").isVisible());
  assert.equal(await page.locator("label", { hasText: "肩書き" }).locator("input").inputValue(), "医師");
  await page.screenshot({ path: path.join(screenshotDir, "05-review.png"), fullPage: true });
  await page.getByRole("button", { name: "保存して連絡先に登録" }).click();
  calls = await page.evaluate(() => window.calls);
  save = calls.findLast((c) => c.action === "save");
  assert.equal(save.thenContact, true);
  assert.equal(save.person.acceptSuggestion, true);
  assert.equal(save.person.title, "医師");
  await page.evaluate(() => onNative({ type: "saved", data: "demo-2" }));
  // Review event from native lands on the review screen once the state arrives.
  await page.evaluate((d) => { onNative({ type: "review", data: "demo-2" }); onNative({ type: "state", data: d }); }, demo);
  assert.equal(await page.evaluate(() => route), "edit");
  await page.evaluate(() => { draft = null; go("menu"); });
  await page.screenshot({ path: path.join(screenshotDir, "06-menu.png"), fullPage: true });
  // Picking the grouping starts the import directly.
  await page.getByRole("button", { name: /画像・PDFから取り込む/ }).click();
  await page.getByRole("button", { name: /2ページずつ = 1人/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "pick", mode: "pair" });
  await page.getByRole("button", { name: /ScanSnapフォルダから取り込む/ }).click();
  assert(await page.getByText("1ページ = 1人").isVisible());
  await page.getByRole("button", { name: /2ページずつ = 1人（前回）/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "folder", mode: "pair" });
  // Manual entry goes straight to the form once native reports the new id.
  await page.getByRole("button", { name: /手入力で追加/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "new" });
  await page.evaluate(() => onNative({ type: "open", data: "demo-1" }));
  assert.equal(await page.evaluate(() => route), "edit");
  await page.evaluate(() => { draft = null; go("menu"); });
  await page.getByRole("button", { name: /一覧をCSVで書き出す/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.findLast((c) => c.action === "export").kind, "csv");
  await page.locator("input.switch").uncheck();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "settings", autoRecognize: false });
  await page.getByRole("button", { name: /Gemini APIキー・モデル/ }).click();
  await page.screenshot({ path: path.join(screenshotDir, "07-gemini.png"), fullPage: true });
  await page.getByRole("button", { name: "保存", exact: true }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.at(-1).model, "gemini-2.5-flash");
  // 320px to tablet: only explicit tables and carousel may overflow, never the whole viewport.
  for (const width of [320, 360, 393, 768]) {
    await page.setViewportSize({ width, height: 800 });
    for (const r of ["home", "menu", "gemini"]) {
      await page.evaluate((r) => go(r), r);
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        `${r} overflow at ${width}`,
      );
    }
    await page.evaluate(() => { go("menu"); backupSheet(); });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `backup overflow at ${width}`);
    await page.evaluate(() => { closeModal(); openPerson("demo-1"); detailMenu(); });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `detail menu overflow at ${width}`);
    await page.evaluate(() => { closeModal(); openPerson("demo-2"); });
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      `review overflow at ${width}`,
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
    "PASS: list/search/table, shutter, review→save→contact, menu import/export/settings and 320–768px layout; HTML content escaping; no JS errors.",
  );
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
