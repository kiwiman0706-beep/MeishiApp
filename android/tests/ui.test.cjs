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
    // CHROME, else the sandbox's preinstalled browser, else Playwright's own (as on CI).
    executablePath:
      process.env.CHROME ||
      [
        "/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell",
      ].find((f) => fs.existsSync(f)),
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
    if (!["index.html", "app.js", "style.css"].includes(file) && !/^lib\/[A-Za-z.-]+\.(js|css)$/.test(file))
      return r.fulfill({ status: 404, body: "" });
    r.fulfill({
      path: path.join(assets, file),
      contentType: file.endsWith("html")
        ? "text/html"
        : file.endsWith(".css")
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
  await page.getByRole("tab", { name: "表" }).click();
  assert.equal(await page.locator("tbody tr").count(), 2);
  await page.locator("tbody tr").first().click();
  await page.getByRole("heading", { name: "山田 太郎（サンプル）" }).waitFor({ timeout: 5000 });
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
  await page.locator("input.switch").first().uncheck();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "settings", autoRecognize: false });
  await page.getByRole("button", { name: /Gemini APIキー・モデル/ }).click();
  await page.screenshot({ path: path.join(screenshotDir, "07-gemini.png"), fullPage: true });
  await page.getByRole("button", { name: "保存", exact: true }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.at(-1).model, "gemini-2.5-flash");
  // Drive: browse → choose folder with grouping; home gets a one-tap import button.
  await page.evaluate(() => { closeModal(); go("menu"); });
  await page.getByRole("button", { name: /Google DriveのScanSnapフォルダを設定/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "driveFolders", parent: "root" });
  await page.evaluate(() => onNative({ type: "driveFolders", data: [{ id: "f1", name: "ScanSnap" }, { id: "f2", name: "写真" }] }));
  assert(await page.getByRole("button", { name: /を使う/ }).isDisabled());
  await page.locator("#modal").getByRole("button", { name: "ScanSnap", exact: true }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "driveFolders", parent: "f1" });
  await page.evaluate(() => onNative({ type: "driveFolders", data: [{ id: "f3", name: "登録済み" }] }));
  await page.screenshot({ path: path.join(screenshotDir, "08-drive-folder.png") });
  await page.getByRole("button", { name: "「ScanSnap」を使う" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "driveSelect", folderId: "f1", folderName: "ScanSnap", mode: "file" });
  demo.driveFolderName = "ScanSnap";
  demo.driveFolderId = "f1";
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  await page.getByRole("button", { name: /Google Driveから取り込む/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "driveImport" });
  await page.screenshot({ path: path.join(screenshotDir, "09-home-drive.png") });
  // After a multi-person import + auto read, the list is filtered to 要確認.
  await page.evaluate(() => onNative({ type: "batchDone", data: "2人・4ページを取り込みました。" }));
  assert.equal(await page.evaluate(() => filter), "review");
  await page.evaluate(() => { closeModal(); filter = "all"; render(); });
  // Duplicates: same mobile number → banner → merge keeps the newer card as the base.
  const dup = p("demo-3", "山田 太郎（サンプル）", "サンプル商事");
  dup.mobile = "090-1234-5678";
  dup.created = Date.now() + 1000;
  demo.people[0].mobile = "09012345678";
  demo.people[0].contactRawId = 11;
  demo.people.push(dup);
  demo.people.forEach((x, i) => (x.email = `demo${i}@example.test`));
  demo.people[1].contactDuplicate = "佐藤 花子";
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  assert(await page.getByText("重複の可能性 2件").isVisible());
  await page.getByRole("button", { name: /重複の可能性/ }).click();
  await page.screenshot({ path: path.join(screenshotDir, "10-duplicates.png") });
  await page.getByRole("button", { name: "1人にまとめる" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "merge", id: "demo-3", sourceId: "demo-1", stay: true });
  // After an action the duplicate sheet comes back (no jump to the home screen).
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.getByRole("heading", { name: /重複の可能性/ }).waitFor();
  assert.equal(await page.evaluate(() => route), "home");
  await page.getByRole("button", { name: "別人", exact: true }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.at(-1).action, "notDuplicate");
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.getByRole("heading", { name: /重複の可能性/ }).waitFor();
  // Pick several and merge them at once.
  await page.getByRole("button", { name: "すべて選択" }).click();
  await page.getByRole("button", { name: /選択した 2件を統合/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "mergeBatch", pairs: [["demo-3", "demo-1"]], contacts: ["demo-2"] });
  // A task: the sheet returns when the busy indicator clears; a report stays on screen instead.
  await page.evaluate((d) => { onNative({ type: "busy", data: "まとめて統合中" }); onNative({ type: "state", data: d }); onNative({ type: "busy", data: "" }); }, demo);
  await page.getByRole("heading", { name: /重複の可能性/ }).waitFor();
  await page.screenshot({ path: path.join(screenshotDir, "10a-duplicates-bulk.png") });
  await page.getByRole("button", { name: "問題なし" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "dismissContactDup", id: "demo-2" });
  // Contact duplicates: merge into / delete the contact that was already there, picked from a list.
  await page.getByRole("button", { name: /重複の可能性/ }).click();
  await page.getByRole("button", { name: /既存と統合/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "contactCandidates", id: "demo-2", mode: "merge" });
  const candidates = [
    { rawId: 41, name: "佐藤 花子", account: "me@gmail.com", why: "電話・氏名", detail: "サンプル医療センター / 090-0000-0000", photo: true },
    { rawId: 42, name: "受付", account: "me@gmail.com", why: "電話", detail: "03-0000-0000", photo: false },
  ];
  await page.evaluate((list) => onNative({ type: "contactCandidates", data: { id: "demo-2", mode: "merge", list } }), candidates);
  assert(await page.getByText("一致：電話・氏名 ・ 写真あり").isVisible());
  await page.screenshot({ path: path.join(screenshotDir, "10b-contact-merge.png") });
  await page.getByRole("button", { name: /この連絡先と統合/ }).first().click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "contactMerge", id: "demo-2", rawId: 41 });
  await page.evaluate((list) => onNative({ type: "contactCandidates", data: { id: "demo-2", mode: "delete", list } }), candidates);
  await page.getByRole("button", { name: /この連絡先を削除/ }).nth(1).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "contactDeleteExisting", id: "demo-2", rawId: 42 });
  await page.evaluate(() => onNative({ type: "contactCandidates", data: { id: "demo-2", mode: "delete", list: [] } }));
  assert(await page.getByText("一致する連絡先が見つかりません").isVisible());
  await page.evaluate(() => closeModal());
  // Company view: one tile per company (spelling variants together), logo or initial.
  await page.evaluate((d) => {
    d.people.push({ ...d.people[0], id: "demo-4", name: "鈴木 次郎", company: "(株)サンプルデザイン", mobile: "", email: "", contactRawId: undefined, logo: "logo.jpg", pages: [{ file: "card4.jpg", name: "card" }], created: 1, notes: [], audio: [] });
    d.people.push({ ...d.people[0], id: "demo-5", name: "高橋 三郎", company: "ロゴなし工業株式会社", mobile: "", email: "", contactRawId: undefined, pages: [{ file: "card.jpg", name: "card" }], created: 2, notes: [], audio: [] });
    onNative({ type: "state", data: d });
    go("home");
    setLayout("company");
  }, demo);
  const tile = page.locator(".cotile", { hasText: "サンプルデザイン" });
  assert.equal(await tile.count(), 1);
  assert(await tile.getByText("2人").isVisible());
  assert.equal(await tile.locator("img").count(), 1);
  assert(await page.getByText(/ロゴの無い会社 \d+社/).isVisible());
  for (const w of [320, 768]) {
    await page.setViewportSize({ width: w, height: 800 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "company view fits " + w);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(screenshotDir, "17-companies.png") });
  await tile.click();
  assert.equal(await page.locator(".person").count(), 2);
  assert(await page.getByText("ロゴ：手動で登録").isVisible());
  await page.getByRole("button", { name: /ロゴを探し直す/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls.at(-1).action, "logoOne");
  await page.screenshot({ path: path.join(screenshotDir, "18-company.png") });
  await page.evaluate(() => handleBack());
  assert.equal(await page.locator(".cotile").count() > 1, true);
  await page.evaluate(() => setLayout("cards"));
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  // Deleting a registered person offers removing the Google contact too.
  await page.evaluate(() => { closeModal(); openPerson("demo-1"); deletePerson(); });
  await page.getByRole("button", { name: /名刺と連絡先を削除/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "delete", id: "demo-1", withContact: true });
  // Registered person: saving says it updates the Google contact.
  await page.evaluate(() => beginEdit());
  assert(await page.getByRole("button", { name: /Google連絡先にも反映/ }).isVisible());
  await page.evaluate(() => { draft = null; go("menu"); });
  await page.getByRole("button", { name: /まとめて撮影/ }).click();
  await page.getByRole("button", { name: /表・裏の順に撮る/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "bulkScan", mode: "pair" });
  await page.getByRole("button", { name: /ZIPから一括取り込み/ }).click();
  await page.getByRole("button", { name: /ZIPファイルを選ぶ/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "importZip", toContacts: false });
  // Queued people show progress and a background banner.
  demo.people[2].queue = "recognize";
  demo.processing = "読み取り中（残り1件）";
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); onNative({ type: "queued", data: { ids: ["demo-3"], skipped: 0, errors: [], pages: 2 } }); }, demo);
  assert(await page.locator(".procbar").isVisible());
  await page.screenshot({ path: path.join(screenshotDir, "11-processing.png") });
  delete demo.processing;
  delete demo.people[2].queue;
  demo.people.pop();
  delete demo.people[1].contactDuplicate;
  delete demo.people[0].contactRawId;
  await page.evaluate((d) => { onNative({ type: "state", data: d }); closeModal(); go("home"); }, demo);
  // Sync: start from the menu, then manual sync with last-synced time.
  await page.evaluate(() => { closeModal(); go("menu"); });
  await page.getByRole("button", { name: /Google Driveで同期を始める/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "syncNow" });
  demo.syncEnabled = true;
  demo.lastSync = Date.now();
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("menu"); }, demo);
  assert(await page.getByRole("button", { name: /今すぐ同期 Theta Vault ・ 最終同期/ }).isVisible());
  await page.screenshot({ path: path.join(screenshotDir, "12-sync-menu.png"), fullPage: true });
  // A card registered on another device offers no second registration.
  demo.people[0].contactRegistered = true;
  await page.evaluate((d) => { onNative({ type: "state", data: d }); openPerson("demo-1"); }, demo);
  assert(await page.getByText("別の端末でGoogle連絡先に登録済みです").isVisible());
  assert.equal(await page.getByRole("button", { name: "Google連絡先に登録" }).count(), 0);
  delete demo.people[0].contactRegistered;
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  // Calendar: exchange dates grouped by day; Google Calendar registration for the unregistered.
  demo.people[0].metDate = "2026-10-16";
  demo.people[1].metDate = "2026-10-16";
  demo.people[0].geo = { q: "サンプル住所", lat: 34.69, lng: 135.5 };
  demo.people[1].address = "大阪府大阪市北区1-1";
  demo.people[1].geo = { q: "大阪府大阪市北区1-1", lat: 34.70, lng: 135.49 };
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); setLayout("calendar"); }, demo);
  assert(await page.locator(".calgrid button.has").first().isVisible());
  assert.equal(await page.locator(".calgrid button.has i").first().textContent(), "2");
  await page.locator(".calgrid button.has").first().click();
  assert.equal(await page.locator(".person.compact").count(), 2);
  await page.screenshot({ path: path.join(screenshotDir, "14-calendar.png"), fullPage: true });
  await page.getByRole("button", { name: /Googleカレンダーに登録（未登録 2件）/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "calendarRegister" });
  // Map: opting in asks native to geocode; markers come from stored coordinates.
  await page.evaluate(() => setLayout("map"));
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "mapOpen" });
  await page.waitForFunction(() => window.L && cluster && cluster.getLayers().length === 2, null, { timeout: 10000 });
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo); // a background refresh keeps the same map
  assert.equal(await page.evaluate(() => document.querySelectorAll(".map").length), 1);
  await page.screenshot({ path: path.join(screenshotDir, "15-map.png") });
  await page.evaluate(() => { layout = "cards"; go("home"); });
  // Shop cards: own filter chip, badge and shop-only fields in the editor.
  const shop = p("shop-1", "炭火焼 さくら", "", "confirmed");
  shop.kind = "shop";
  shop.genre = "焼き鳥";
  shop.hours = "17:00-23:00";
  shop.holidays = "月曜";
  shop.email = "shop@example.test";
  demo.people.push(shop);
  // New Year cards: one received 2027 card (with image), one sent last year.
  const y = new Date().getMonth() >= 9 ? new Date().getFullYear() + 1 : new Date().getFullYear();
  demo.people[0].greetings = [{ id: "g1", year: y, dir: "received", date: `${y}-01-02`, kind: "nenga", files: [], senders: "山田 太郎\n山田 花子" }];
  demo.people[1].greetings = [{ id: "g2", year: y - 1, dir: "sent", date: `${y - 1}-12-20`, kind: "nenga", files: [] }];
  demo.people.push({ ...p("inbox-1", "", ""), kind: "nengaInbox", queue: "nenga" });
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  assert.equal(await page.locator(".person", { hasText: "inbox" }).count(), 0); // inbox cards stay out of the list
  await page.getByRole("button", { name: /^お店 1/ }).click();
  assert.equal(await page.locator(".person").count(), 1);
  await page.locator(".person").first().click();
  assert(await page.getByText("お店の情報").isVisible());
  assert(await page.getByText("17:00-23:00").isVisible());
  await page.evaluate(() => beginEdit());
  assert(await page.getByText("店名").isVisible());
  assert(await page.getByText("定休日").isVisible());
  await page.evaluate(() => { draft = null; filter = "all"; go("menu"); });
  await page.getByRole("button", { name: /^年賀状・お便り/ }).click();
  assert.equal(await page.locator(".calhead select").inputValue(), String(y));
  await page.screenshot({ path: path.join(screenshotDir, "17-nenga.png"), fullPage: true });
  assert(await page.getByText("差出人を読み取り中").isVisible());
  // "返礼が必要": received, not sent → mark sent.
  await page.getByRole("button", { name: /返礼が必要 1/ }).click();
  assert.equal(await page.locator(".nrow").count(), 1);
  await page.locator(".nrow .ntog", { hasText: "送った" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "greeting", id: "demo-1", year: y, dir: "sent", on: true });
  await page.getByRole("button", { name: /昨年あり・今年未送 1/ }).click();
  assert(await page.locator(".nrow", { hasText: "佐藤" }).isVisible());
  await page.getByPlaceholder("名前・住所で探して記録").fill("さくら");
  assert.equal(await page.locator(".nrow").count(), 0); // shops are not New Year card recipients
  await page.getByPlaceholder("名前・住所で探して記録").fill("佐藤");
  assert.equal(await page.locator(".nrow").count(), 1);
  await page.getByRole("button", { name: /届いた年賀状を撮影/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "scan", id: "", mode: "all", nenga: true, year: y });
  await page.getByRole("button", { name: /宛名リストをCSV/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "export", kind: "nenga", year: y });
  await page.evaluate(() => openPerson("demo-1"));
  assert(await page.getByText("差出人：山田 太郎・山田 花子").isVisible());
  // Attaching a document to this person goes through import with attachTo.
  await page.getByRole("button", { name: "その他の操作" }).click();
  await page.getByRole("button", { name: /書類・手紙を添付/ }).click();
  await page.locator("#atitle").fill("見積書");
  await page.getByRole("button", { name: /画像・PDFを選んで添付/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "pick", id: "", mode: "all", attachTo: "demo-1", title: "見積書", dir: "received" });
  // A Christmas card is mail, not part of the New Year count.
  demo.people[0].greetings.push({ id: "g3", year: y, dir: "received", date: `${y}-12-24`, kind: "xmas", files: [] });
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("nenga"); }, demo);
  assert(await page.getByText("最近のお便り・書類").isVisible());
  assert(await page.locator(".mailrow", { hasText: "クリスマスカード" }).isVisible());
  assert(await page.locator(".stats strong").first().textContent() === "1");
  demo.people = demo.people.filter((x) => !["shop-1", "inbox-1"].includes(x.id));
  delete demo.people[0].greetings;
  delete demo.people[1].greetings;
  await page.evaluate((d) => { onNative({ type: "state", data: d }); nengaQuery = ""; go("home"); }, demo);
  // 320px to tablet: only explicit tables and carousel may overflow, never the whole viewport.
  for (const width of [320, 360, 393, 768]) {
    await page.setViewportSize({ width, height: 800 });
    for (const r of ["home", "menu", "gemini", "nenga"]) {
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
  // Menu: put the widget on the home screen; fill readings for Google Contacts.
  await page.evaluate((d) => { onNative({ type: "state", data: { ...d, kanaMissing: 3, widgets: 0 } }); go("menu"); }, demo);
  await page.getByRole("button", { name: /ホーム画面にウィジェットを追加/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "pinWidget" });
  // Old cards: one place for everything new versions add (logos, readings).
  await page.evaluate((d) => onNative({ type: "state", data: { ...d, kanaMissing: 3, upgradeLogos: 5, widgets: 0 } }), demo);
  assert(await page.getByText("ロゴの無い会社 5社・読み仮名が空 3件").isVisible());
  await page.getByRole("button", { name: /過去の名刺を最新の状態にする/ }).click();
  assert(await page.getByText("約3円以下", { exact: false }).isVisible());
  await page.screenshot({ path: path.join(screenshotDir, "19-upgrade.png") });
  await page.getByRole("button", { name: /まとめて実行/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "upgradeAll" });
  // Google Cloud setup guide: opened first when Drive was never authorized; copy / open buttons.
  await page.evaluate((d) => { onNative({ type: "state", data: { ...d, driveAuthorized: false, appPackage: "jp.yaegaki.meishiclaude", appSha1: "A1:13:EA" } }); go("menu"); }, demo);
  assert.equal(await page.getByText("Googleアカウント", { exact: true }).count(), 0, "the web-only row stays off Android");
  await page.evaluate(() => syncStart());
  await page.getByRole("heading", { name: /Google連携の初期設定/ }).waitFor();
  assert(await page.getByText("jp.yaegaki.meishiclaude").isVisible());
  await page.getByRole("button", { name: "コピー" }).nth(1).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "copyText", text: "A1:13:EA" });
  await page.getByRole("button", { name: /開く/ }).nth(1).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "openUrl", url: "https://console.cloud.google.com/apis/library/drive.googleapis.com" });
  await page.locator(".stepcheck input").first().check();
  assert(await page.getByText("1/5 完了").isVisible());
  await page.screenshot({ path: path.join(screenshotDir, "20-setup-guide.png"), fullPage: true });
  await page.getByRole("button", { name: /接続を試す/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "syncNow" });
  // The silent cancel after the account picker opens the guide with the reason.
  await page.evaluate(() => onNative({ type: "driveSetup", data: { reason: "Googleの許可が完了しませんでした（Googleのコード 10）" } }));
  assert(await page.getByText("Googleのコード 10").isVisible());
  await page.evaluate(() => closeModal());
  await page.evaluate(() => go("home"));
  // A newer release: banner on the home screen.
  await page.evaluate((d) => onNative({ type: "state", data: { ...d, version: "0.14.0", update: { version: "0.15.0", url: "https://github.com/x.apk", ready: false } } }), demo);
  await page.getByRole("button", { name: /新しい版 0\.15\.0/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "installUpdate" });
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  // Handwritten note: pen strokes with pressure, fingers scroll once a pen is seen, side button erases.
  await page.evaluate((d) => { onNative({ type: "state", data: d }); openPerson("demo-1"); }, demo);
  await page.getByRole("button", { name: /手書き/ }).first().click();
  await page.locator("#inkcanvas").waitFor();
  const strokes = await page.evaluate(() => {
    const c = document.getElementById("inkcanvas");
    const r = c.getBoundingClientRect();
    const fire = (type, x, y, o = {}) =>
      c.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: o.id || 7, pointerType: o.type || "pen", clientX: r.left + x, clientY: r.top + y, pressure: o.p ?? 0.8, buttons: o.buttons ?? 1, button: 0 }));
    const line = (x0, y0, x1, y1, o) => {
      fire("pointerdown", x0, y0, o);
      for (let i = 1; i <= 10; i++) fire("pointermove", x0 + ((x1 - x0) * i) / 10, y0 + ((y1 - y0) * i) / 10, o);
      fire("pointerup", x1, y1, o);
    };
    line(30, 60, 250, 80, { p: 0.9 });
    line(30, 120, 250, 140, { p: 0.2 });
    line(30, 200, 250, 200, { type: "touch", id: 9 }); // a palm: not drawn
    line(30, 120, 250, 140, { buttons: 3 }); // side button held: erases
    return ink.strokes.map((s) => ({ erase: s.erase, pts: s.pts.length, p: s.pts[1][2] }));
  });
  assert.deepEqual(strokes.map((s) => s.erase), [false, false, true], "palm ignored, side button erases: " + JSON.stringify(strokes));
  assert(Math.abs(strokes[0].p - 0.9) < 0.001 && Math.abs(strokes[1].p - 0.2) < 0.001, "pen pressure kept");
  assert(await page.evaluate(() => !document.getElementById("inkfingers").checked), "fingers off after a pen");
  await page.screenshot({ path: path.join(screenshotDir, "22-ink-pad.png") });
  await page.getByRole("button", { name: "元に戻す" }).click();
  assert.equal(await page.evaluate(() => ink.strokes.length), 2);
  await page.getByRole("button", { name: /保存/ }).click();
  calls = await page.evaluate(() => window.calls);
  const inkCall = calls.at(-1);
  assert.equal(inkCall.action, "inkNote");
  assert.equal(inkCall.id, "demo-1");
  assert.equal(inkCall.read, true);
  assert.equal(Buffer.from(inkCall.png, "base64").subarray(1, 4).toString(), "PNG");
  assert.equal(await page.locator("#inkpad").count(), 0);
  // Shown in the timeline with the text Gemini read; empty text offers to read it.
  demo.people[0].notes = [...(demo.people[0].notes || []), { id: "ink-1", time: Date.now(), text: "", ink: "ink-1.png" }];
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.getByRole("button", { name: /文字にする/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "inkRead", id: "demo-1", noteId: "ink-1" });
  demo.people[0].notes = demo.people[0].notes.filter((n) => n.id !== "ink-1");
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  // Series settings: the one-time carry-over offer, item by item.
  const offer = [
    { key: "gemini.model", label: "Geminiのモデル", value: "gemini-2.5-pro", by: "recipe", at: "2026-10-22T01:00:00Z" },
    { key: "calendar", label: "Googleカレンダーの登録先", value: { account: "a@x.jp", name: "仕事" }, by: "recipe", at: "2026-10-22T01:00:00Z" },
  ];
  const series = [
    { key: "gemini.model", label: "Geminiのモデル", value: "gemini-2.5-pro", mine: "gemini-2.5-flash", by: "recipe", follow: true },
    { key: "calendar", label: "Googleカレンダーの登録先", value: { account: "a@x.jp", name: "仕事" }, mine: null, by: "recipe", follow: false },
  ];
  await page.evaluate(([d, o, s]) => { onNative({ type: "state", data: { ...d, seriesOffer: o, series: s } }); go("home"); }, [demo, offer, series]);
  await page.getByRole("button", { name: /シリーズの設定を引き継ぐ/ }).click();
  assert(await page.getByText("Theta Recipe Hatch").first().isVisible());
  assert(await page.getByText("仕事（a@x.jp）").isVisible());
  await page.locator("#sofr1").uncheck({ force: true });
  await page.screenshot({ path: path.join(screenshotDir, "21-series-offer.png"), fullPage: true });
  await page.getByRole("button", { name: "決定" }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "seriesAdopt", keys: ["gemini.model"] });
  await page.evaluate(([d, s]) => { onNative({ type: "state", data: { ...d, series: s } }); go("menu"); }, [demo, series]);
  await page.getByRole("button", { name: /Theta Hatch シリーズの設定/ }).click();
  await page.locator("#modal input.switch").nth(1).check({ force: true });
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "seriesFollow", key: "calendar", on: true });
  await page.evaluate(() => closeModal());
  // No key yet, another app of the series on the phone has one: offered on the Gemini page.
  await page.evaluate((d) => { onNative({ type: "state", data: { ...d, keySet: false, keyLenders: [{ package: "jp.yaegaki.theta.recipe", label: "Theta Recipe Hatch" }] } }); settingsDraft = null; go("gemini"); }, demo);
  await page.getByRole("button", { name: /Theta Recipe Hatch のキーを使う/ }).click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.at(-1), { action: "importKey", package: "jp.yaegaki.theta.recipe" });
  await page.evaluate((d) => { onNative({ type: "state", data: d }); go("home"); }, demo);
  // HTML injection in content must be rendered as text.
  demo.people[0].name = '<img src=x onerror="window.pwned=true">';
  demo.people[0].notes[0].text = "<script>window.pwned=true</script>";
  await page.evaluate((d) => onNative({ type: "state", data: d }), demo);
  await page.evaluate(() => openPerson("demo-1"));
  assert(!(await page.evaluate(() => window.pwned)));
  assert.equal(errors.length, 0, errors.join("\n"));
  console.log(
    "PASS: list/search/table, shutter, review→save→contact, menu import/export/settings, Drive folder/import, calendar, map, shops, New Year cards and 320–768px layout; HTML content escaping; no JS errors.",
  );
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
