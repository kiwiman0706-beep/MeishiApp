// Desktop counterpart of MainActivity: answers the shared UI's Native.call actions against the
// vault folder. Platform services (dialogs, images, secrets, browser) are injected so this runs
// under plain Node in tests.
"use strict";
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const model = require("./model");
const importer = require("./importer");
const gemini = require("./gemini");
const V = require("./vault-codec");
const weblogo = require("./weblogo");
const series = require("./series");
const qr = require("./qr");
const vcard = require("./vcard");

// Features the desktop offers; the UI hides the rest (see THETA_HATCH_SPEC §4.1).
const CAPS = ["pick", "folder", "gemini", "vault", "map", "export", "nenga", "attach"];
const NOT_HERE = "この操作はスマホ版（Android）で行えます";

class Backend {
  // platform / caps: what the UI may offer (the web version passes its own); webLogos: whether
  // company websites can be fetched (not from a browser page: other sites refuse it).
  constructor({ store, settings, secrets, services, fetchImpl, info, platform = "desktop", caps = CAPS, webLogos = true }) {
    this.info = info || (() => ({}));
    this.platform = platform;
    this.caps = caps;
    this.webLogos = webLogos;
    this.store = store;
    this.settings = settings; // { get(k, d), set(obj) }
    this.secrets = secrets; // { get(), set(v) }
    this.svc = services; // { imaging, openFiles, openFolder, saveFile, openExternal, openPath, showInFolder }
    this.fetch = fetchImpl || fetch;
    this.listeners = new Set();
    this.processing = "";
    this.running = false;
    this.geoCache = new Map();
    store.on(() => this.refresh());
  }

  on(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  event(type, data) {
    for (const fn of this.listeners) fn({ type, data });
  }
  toast(t) {
    this.event("toast", t);
  }

  state() {
    const s = this.settings;
    return {
      platform: this.platform,
      caps: this.caps,
      people: this.store.people(),
      keySet: !!this.secrets.get(),
      model: s.get("model", "gemini-2.5-flash"),
      format: s.get("format", "legacy"),
      autoRecognize: s.get("autoRecognize", true),
      folder: s.get("folder", ""),
      vaultPath: this.store.root,
      vaultWarnings: this.store.warnings,
      processing: this.processing,
      lastReport: s.get("lastReport", ""),
      upgradeLogos: model.logoPicks(this.store.people()).length,
      upgradeRecut: model.recutPicks(this.store.people()).length,
      ...this.seriesState(),
      ...this.info(), // app version and a pending update (main.js)
    };
  }
  refresh() {
    this.event("state", this.state());
  }

  async handle(action, q = {}) {
    try {
      await this.dispatch(action, q);
    } catch (e) {
      this.event("error", e.message || String(e));
    }
  }

  // ---- actions --------------------------------------------------------------------------
  async dispatch(action, q) {
    const id = q.id || "";
    const st = this.store;
    switch (action) {
      case "boot":
      case "opened":
        if (action === "boot") {
          this.refresh();
          await this.seriesSync();
        }
        return;
      case "new": {
        const p = model.empty();
        st.put(p);
        return this.event("open", p.id);
      }
      case "save": {
        const p = model.edit(st.person(id), q.person || {});
        delete p.queueError;
        st.put(p);
        return this.event("saved", id);
      }
      case "delete":
        st.remove(id);
        return this.event("home", "");
      case "note": {
        const p = st.person(id);
        p.notes.push({ id: crypto.randomUUID(), time: Date.now(), text: String(q.text) });
        return st.put(p);
      }
      case "inkNote": {
        // A handwritten note from the pen pad (PNG, base64): a new note, or a new picture for one.
        const png = Buffer.from(String(q.png || ""), "base64");
        if (png.length < 8 || png[0] !== 0x89 || png[1] !== 0x50) throw new Error("手書きの画像が不正です");
        const name = crypto.randomUUID() + ".png";
        await st.writeMedia(name, png);
        const p = st.person(id);
        let n = p.notes.find((x) => x.id === q.noteId);
        if (!n) p.notes.push((n = { id: crypto.randomUUID(), time: Date.now(), text: "" }));
        n.ink = name;
        await st.put(p);
        if (q.read && this.secrets.get()) this.inkRead(id, n.id);
        return;
      }
      case "inkRead":
        this.needKey();
        return this.inkRead(id, q.noteId);
      case "editNote": {
        const p = st.person(id);
        for (const n of p.notes) if (n.id === q.noteId) n.text = String(q.text);
        return st.put(p);
      }
      case "removeItem": {
        if (!["notes", "audio", "pages"].includes(q.key)) throw new Error("不正な項目");
        const p = st.person(id);
        if (q.index >= 0 && q.index < p[q.key].length) p[q.key].splice(q.index, 1);
        if (q.key === "pages") {
          p.status = "new";
          delete p.suggestion;
        }
        return st.put(p);
      }
      case "reorder": {
        const p = st.person(id);
        const a = p.pages;
        if (q.from >= 0 && q.from < a.length && q.to >= 0 && q.to < a.length) [a[q.from], a[q.to]] = [a[q.to], a[q.from]];
        return st.put(p);
      }
      case "merge": {
        const t = model.merge(st.person(id), st.person(q.sourceId));
        st.put(t);
        st.remove(q.sourceId);
        return q.stay ? undefined : this.event("open", id);
      }
      case "mergeBatch": {
        // Card pairs from the duplicate sheet (contacts live on the phone).
        const gone = new Map();
        let done = 0;
        for (let [t, src] of q.pairs || []) {
          while (gone.has(t)) t = gone.get(t);
          while (gone.has(src)) src = gone.get(src);
          if (t === src) continue;
          st.put(model.merge(st.person(t), st.person(src)));
          st.remove(src);
          gone.set(src, t);
          done++;
        }
        if ((q.contacts || []).length) this.event("report", "Google連絡先の重複はスマホ版で統合してください。");
        return this.toast(done + "件を統合しました");
      }
      case "upgradeAll": // old cards brought up to date: on the desktop, the company logos
      case "logoAll":
      case "logoOne": {
        this.needKey();
        const ids = action === "logoOne" ? [id] : model.logoPicks(st.people());
        // The upgrade also cuts again the logos cut before the corrected method.
        const again = action === "upgradeAll" ? model.recutPicks(st.people()).filter((x) => !ids.includes(x)) : [];
        if (!ids.length && !again.length) return this.toast("ロゴを探す会社はありません（ロゴのある会社・試した会社は除きます）");
        this.enqueue(ids, action === "logoOne" ? "relogo" : "logo");
        if (again.length) this.enqueue(again, "relogo");
        return this.toast(ids.length + again.length + "社のロゴを名刺から探します（バックグラウンド）");
      }
      case "notDuplicate": {
        for (const [a, b] of [[id, q.other], [q.other, id]]) {
          const p = st.person(a);
          p.notDuplicate = [...(p.notDuplicate || []), b];
          st.put(p);
        }
        return;
      }
      case "dismissContactDup":
        return;
      case "greeting": {
        if (!["sent", "received"].includes(q.dir)) throw new Error("不正な種類");
        const p = st.person(id);
        p.greetings = p.greetings || [];
        if (q.on) {
          if (!model.hasGreeting(p, q.year, q.dir))
            p.greetings.push({ id: crypto.randomUUID(), year: q.year, dir: q.dir, date: q.date || model.today(Date.now()), kind: "nenga", files: [] });
        } else p.greetings = p.greetings.filter((g) => !(g.year === q.year && g.dir === q.dir && !(g.files || []).length));
        return st.put(p);
      }
      case "greetingEdit": {
        const p = st.person(id);
        const i = (p.greetings || []).findIndex((g) => g.id === q.gid);
        if (i >= 0) {
          if (q.remove) p.greetings.splice(i, 1);
          else {
            if (/^\d{4}-\d{2}-\d{2}$/.test(q.date || "")) p.greetings[i].date = q.date;
            if ("year" in q) p.greetings[i].year = q.year;
            if ("title" in q) p.greetings[i].title = q.title;
          }
        }
        return st.put(p);
      }
      case "recognize":
        this.needKey();
        this.enqueue([id], "recognize");
        return this.toast("読み取り中（バックグラウンド）。終わると自動で反映します");
      case "recognizeAll": {
        this.needKey();
        const ids = st.people().filter((p) => p.status === "new" && p.pages.length && !p.queue && p.kind !== model.INBOX).map((p) => p.id);
        this.enqueue(ids, "recognize");
        return this.toast(ids.length + "件をバックグラウンドで読み取ります");
      }
      case "nengaRetry":
        this.needKey();
        return this.enqueue([id], "nenga");
      case "reread": {
        this.needKey();
        this.event("busy", "Geminiで名刺を読み取り中");
        try {
          const r = await this.read(st.person(id), "card");
          const p = st.person(id);
          p.suggestion = r;
          p.status = "review";
          st.put(p);
          this.refresh();
          this.event("review", id);
        } finally {
          this.event("busy", "");
        }
        return;
      }
      case "discardRecognition": {
        const p = st.person(id);
        delete p.suggestion;
        return st.put(p);
      }
      case "settings": {
        if (q.clearKey) this.secrets.set("");
        else if (String(q.key || "").trim()) this.secrets.set(String(q.key).trim());
        const set = {};
        if ("model" in q) {
          const m = String(q.model || "").trim().replace("models/", "");
          if (!/^[A-Za-z0-9._-]+$/.test(m)) throw new Error("モデル名を確認してください");
          set.model = m;
          set.format = q.format || "legacy";
        }
        if ("autoRecognize" in q) set.autoRecognize = !!q.autoRecognize;
        this.settings.set(set);
        if (set.model) {
          this.seriesChanged("gemini.model", set.model);
          this.seriesChanged("gemini.format", set.format);
          await this.seriesSync();
        }
        this.refresh();
        return this.toast("設定を保存しました");
      }
      case "models":
        this.needKey();
        return this.event("models", await gemini.models(this.secrets.get(), this.fetch));
      case "copyText":
        // The page writes the clipboard itself; this is the fallback when it cannot.
        if (!this.svc.copyText) return this.toast("コピーできませんでした");
        this.svc.copyText(String(q.text || ""));
        return this.toast("コピーしました");
      case "vcard": {
        // One or many cards as a vCard file (attach it in Gmail, send it in LINE...).
        const people = (q.ids || []).map((x) => st.person(String(x)));
        if (!people.length) throw new Error("名刺を選んでください");
        const file = await this.svc.saveFile(vcard.fileName(people), vcard.build(people), "text/vcard");
        if (file) {
          this.toast("vCardを書き出しました。Gmail・LINEなどで添付して送れます");
          if (this.svc.showInFolder && this.platform === "desktop") this.svc.showInFolder(file);
        }
        return;
      }
      case "export": {
        const year = q.year | 0;
        const csv = q.kind === "nenga" ? model.nengaCsv(st.people(), year) : model.csv(st.people());
        const stamp = model.today(Date.now()).replace(/-/g, "");
        const file = await this.svc.saveFile(q.kind === "nenga" ? `nenga-${year}-${stamp}.csv` : `meishi-${stamp}.csv`, csv);
        if (file) this.toast("書き出しました");
        return;
      }
      case "quickScan":
      case "scan":
      case "bulkScan":
      case "pickFiles":
      case "pick": {
        // The action tells a phone browser whether to open the camera or the photo library.
        const files = await this.svc.openFiles(false, action);
        if (files && files.length) await this.importFiles(files, q);
        return;
      }
      case "folder":
      case "rescan": {
        let dir = action === "rescan" ? this.settings.get("folder", "") : await this.svc.openFolder("取り込むフォルダ（ScanSnapの保存先など）");
        if (!dir) return;
        this.settings.set({ folder: dir });
        const files = [];
        const walk = (d, depth) => {
          if (depth > 8) return;
          for (const e of fs.readdirSync(d, { withFileTypes: true })) {
            const f = path.join(d, e.name);
            if (e.isDirectory()) walk(f, depth + 1);
            else if (/\.pdf$/i.test(e.name) || importer.IMAGE.test(e.name)) files.push(f);
          }
        };
        walk(dir, 0);
        if (!files.length) throw new Error("画像・PDFが見つかりませんでした");
        return this.importFiles(files.slice(0, 200), q);
      }
      case "asset": {
        if (!["portrait", "logo"].includes(q.kind)) throw new Error("不正な種類");
        const files = await this.svc.openFiles(true);
        if (!files || !files.length) return;
        const name = crypto.randomUUID() + ".jpg";
        const src = typeof files[0] === "string" ? fs.readFileSync(files[0]) : await files[0].read();
        await st.writeMedia(name, await this.svc.imaging.toJpeg(src, 1400));
        const p = st.person(id);
        p[q.kind] = name;
        if (q.kind === "logo") delete p.logoSource; // picked by hand: automatic logos never replace it
        return st.put(p);
      }
      case "clearAsset": {
        const p = st.person(id);
        if (q.kind === "logo" || q.kind === "portrait") delete p[q.kind];
        if (q.kind === "logo") delete p.logoSource;
        return st.put(p);
      }
      case "search": {
        const p = st.person(id);
        const term = q.kind === "logo" ? `${p.company} ロゴ` : `${p.name} ${p.company} ${p.title}`;
        return this.svc.openExternal("https://www.google.com/search?tbm=isch&q=" + encodeURIComponent(term));
      }
      case "openUrl": {
        const u = String(q.url || "");
        if (!/^(https?|tel|mailto):/i.test(u)) throw new Error("このリンクは開けません");
        return this.svc.openExternal(u);
      }
      case "openMedia":
        return this.svc.openPath(st.mediaFile(q.file));
      case "shareImage":
        return this.svc.showInFolder(st.mediaFile(st.person(id).pages[q.index].file));
      case "mapOpen":
        this.settings.set({ geoEnabled: true });
        return this.geocode();
      case "installUpdate":
        return this.svc.installUpdate && this.svc.installUpdate();
      case "seriesAdopt": {
        // The carry-over choice: checked items follow the series, the rest stay this app's.
        const keys = Array.isArray(q.keys) ? q.keys.map(String) : [];
        const offers = this.settings.get("seriesOffer", []);
        this.settings.set({ series: series.adopt(this.seriesLocal(), offers, keys), seriesOffer: [] });
        this.seriesApply(Object.fromEntries(offers.filter((o) => keys.includes(o.key)).map((o) => [o.key, o.value])));
        await this.seriesSync();
        this.refresh();
        return this.toast(keys.length ? keys.length + "項目を引き継ぎました" : "このアプリの設定のまま使います");
      }
      case "seriesFollow": {
        const local = series.setFollow(this.seriesLocal(), String(q.key), !!q.on);
        this.settings.set({ series: local });
        const e = local.doc.series[q.key];
        if (q.on && e) this.seriesApply({ [q.key]: e.value }); // following again: take the series value
        await this.seriesSync();
        return this.refresh();
      }
      case "chooseVault": {
        const dir = await this.svc.openFolder("Theta Vault のフォルダ（Googleドライブの「Theta Vault」など）");
        if (dir) this.event("chooseVault", dir);
        return;
      }
      default:
        return this.toast(NOT_HERE);
    }
  }

  // ---- Theta Hatch series settings (series.js, docs/THETA_SHARED_SETTINGS.md) ------------
  // The items this app takes from the series; the others it only publishes or keeps.
  seriesUses() {
    return ["gemini.model", "gemini.format"];
  }
  seriesLocal() {
    return this.settings.get("series", null) || series.initial();
  }
  // This app's values of the series items (unset ones left out).
  seriesCurrent() {
    const s = this.settings, o = {};
    if (s.get("model", "")) o["gemini.model"] = s.get("model", "");
    if (s.get("format", "")) o["gemini.format"] = s.get("format", "");
    return o;
  }
  seriesApply(values) {
    const set = {};
    const m = values["gemini.model"];
    if (typeof m === "string" && /^[A-Za-z0-9._-]+$/.test(m)) set.model = m;
    if (["legacy", "modern"].includes(values["gemini.format"])) set.format = values["gemini.format"];
    this.settings.set(set);
  }
  seriesChanged(key, value) {
    this.settings.set({ series: series.changed(this.seriesLocal(), key, value, series.ME) });
  }
  // Where .theta/settings.json lives: { read() → text | null, write(text) }; on the desktop, the
  // vault folder itself (Google Drive for desktop carries it to the other devices).
  seriesFile() {
    if (!this.store.root) return null;
    const file = path.join(this.store.root, ".theta", "settings.json");
    return {
      read: () => fs.promises.readFile(file, "utf8").catch((e) => (e.code === "ENOENT" ? null : Promise.reject(e))),
      write: async (text) => {
        await fs.promises.mkdir(path.dirname(file), { recursive: true });
        await fs.promises.writeFile(file + ".tmp", text);
        await fs.promises.rename(file + ".tmp", file);
      },
    };
  }
  async seriesSync() {
    const io = this.seriesFile();
    if (!io) return;
    const s = this.settings;
    try {
      const r = series.sync(this.seriesLocal(), await io.read(), this.seriesCurrent(), series.ME);
      if (r.broken) return s.set({ seriesError: "Theta Vault の .theta/settings.json が読めません（手で直した場合はJSONの形を確認してください）" });
      if (r.write) await io.write(r.write);
      this.seriesApply(r.apply);
      const uses = this.seriesUses();
      s.set({ series: r.local, seriesOffer: r.offers.filter((o) => uses.includes(o.key)), seriesError: "" });
    } catch (e) {
      s.set({ seriesError: "シリーズの設定：" + (e.message || e) });
    }
  }
  seriesState() {
    const uses = this.seriesUses();
    const offer = this.settings.get("seriesOffer", []);
    return {
      series: series.view(this.seriesLocal(), this.seriesCurrent()).filter((i) => uses.includes(i.key)),
      ...(offer.length ? { seriesOffer: offer } : {}),
      seriesError: this.settings.get("seriesError", ""),
    };
  }

  // Gemini writes out a handwritten note in the background; the picture stays the original.
  async inkRead(id, noteId) {
    this.toast("手書きを文字にしています");
    try {
      const n = this.store.person(id).notes.find((x) => x.id === noteId);
      if (!n || !n.ink) return;
      const s = this.settings;
      const png = Buffer.from(await this.store.readMedia(n.ink));
      const out = await gemini.generate({ key: this.secrets.get(), model: s.get("model", "gemini-2.5-flash"), format: s.get("format", "legacy") }, "ink", [png], this.fetch, "image/png");
      const p = this.store.person(id); // fresh: the user may have edited meanwhile
      const m = p.notes.find((x) => x.id === noteId);
      if (!m) return;
      m.text = String(out.text || "").trim();
      await this.store.put(p);
      this.toast(m.text ? "手書きを文字にしました（検索できます）" : "文字は見つかりませんでした");
    } catch (e) {
      this.event("error", e.message || String(e));
    }
  }

  needKey() {
    if (!this.secrets.get()) throw new Error("先にメニューの「Gemini APIキー・モデル」でAPIキーを設定してください");
  }

  // ---- importing ----------------------------------------------------------------------
  async importFiles(files, q = {}) {
    this.event("busy", "名刺を取り込み中");
    let r;
    try {
      r = await importer.run(this.store, this.svc.imaging, files, q.mode || "file", q.attachTo ? "" : q.id || "", (t) => this.event("busy", t));
    } finally {
      this.event("busy", "");
    }
    return this.afterImport(r, q);
  }

  afterImport(r, q) {
    const st = this.store;
    if (q.attachTo && !q.id) {
      for (const tid of r.ids) this.attach(tid, q.attachTo, q.dir, q.title);
      this.event("open", q.attachTo);
      return this.toast("書類を添付しました");
    }
    if (q.nenga && !q.id) {
      for (const tid of r.ids) {
        const p = st.person(tid);
        p.kind = model.INBOX;
        p.nengaYear = q.year || model.season(Date.now());
        st.put(p);
      }
      if (this.secrets.get()) this.enqueue(r.ids, "nenga");
      return this.toast(`${r.ids.length}通を保存しました。差出人の読み取りはバックグラウンドで進めます`);
    }
    if (!q.id && r.ids.length && this.settings.get("autoRecognize", true) && this.secrets.get()) {
      this.enqueue(r.ids, "recognize");
      this.event("queued", r);
    } else this.event("imported", r);
  }

  attach(tempId, targetId, dir, title) {
    const st = this.store;
    const temp = st.person(tempId), p = st.person(targetId);
    const files = temp.pages.map((x) => ({ file: x.file, name: x.name || x.file }));
    for (const s of temp.sources || []) if (s.file.endsWith(".pdf")) files.push({ file: s.file, name: s.name || s.file, original: true });
    p.greetings = p.greetings || [];
    p.greetings.push({ id: crypto.randomUUID(), year: new Date().getFullYear(), dir: dir === "sent" ? "sent" : "received", date: model.today(Date.now()), kind: "doc", title: title || "", files });
    st.put(p);
    st.remove(tempId);
  }

  // ---- background queue (Pipeline.java) -------------------------------------------------
  enqueue(ids, step) {
    for (const id of ids) {
      const p = this.store.person(id);
      p.queue = step;
      delete p.queueError;
      this.store.put(p, false);
    }
    this.work();
  }

  async read(p, kind) {
    const images = [], codes = [];
    for (const pg of p.pages) {
      const bytes = await this.store.readMedia(pg.file);
      images.push(await this.svc.imaging.toJpeg(bytes, 1600));
      if (kind === "card" && codes.length < 4) {
        const t = await this.qrOf(bytes);
        if (t && !codes.includes(t)) codes.push(t);
      }
    }
    const s = this.settings;
    const r = await gemini.generate({ key: this.secrets.get(), model: s.get("model", "gemini-2.5-flash"), format: s.get("format", "legacy") }, kind, images, this.fetch, "image/jpeg", qr.hint(codes));
    return kind === "card" ? qr.apply(r, codes) : r;
  }

  // A QR code on the card: tried at a few sizes (a small code in a big photo, or a blurry one).
  async qrOf(bytes) {
    if (!this.svc.imaging.rgba) return "";
    for (const max of [1600, 1000, 2400]) {
      try {
        const t = qr.decode(await this.svc.imaging.rgba(bytes, max));
        if (t) return t;
      } catch {
        return "";
      }
    }
    return "";
  }

  async work() {
    if (this.running) return;
    this.running = true;
    let done = 0, failed = 0;
    const logos = { none: 0 };
    const errors = [];
    try {
      for (;;) {
        const next = this.store.people().find((p) => p.queue);
        if (!next) break;
        const left = this.store.people().filter((p) => p.queue).length;
        const what = { nenga: "年賀状を読み取り中", logo: "会社ロゴを切り出し中", relogo: "会社ロゴを切り出し中" }[next.queue] || "名刺を読み取り中";
        this.processing = `${what}（残り${left}件）`;
        this.refresh();
        try {
          if (next.queue === "nenga") this.fileNenga(next.id, await this.read(next, "nenga"));
          else if (next.queue === "logo" || next.queue === "relogo") {
            if (!(await this.cutLogo(next))) logos.none++;
          }
          else {
            const r = await this.read(next, "card");
            const p = model.applyResult(this.store.person(next.id), r);
            delete p.queue;
            this.store.put(p);
            // The logo comes with the same answer; without one, the company's website (no search).
            if (!p.logo) await this.autoLogo(p, r.logoBox, "", false).catch(() => {});
            await this.afterRecognized(this.store.person(next.id)).catch((e) => errors.push(`${p.name || "名刺"}: ${e.message}`));
          }
          done++;
        } catch (e) {
          const m = String(e.message);
          if (/HTTP (429|401|403)|APIキー/.test(m)) {
            errors.push(m + "\n残りは後で「未認識をまとめて読み取る」から再開できます");
            break;
          }
          failed++;
          errors.push(`${next.name || "名刺"}: ${m}`);
          try {
            const p = this.store.person(next.id);
            delete p.queue;
            p.queueError = m;
            this.store.put(p, false);
          } catch {}
        }
      }
    } finally {
      this.running = false;
      this.processing = "";
      if (done + failed) {
        const text = `処理 ${done}件${failed ? ` / 失敗 ${failed}件` : ""}${logos.none ? ` / ロゴが見つからない ${logos.none}社` : ""}` + (errors.length ? "\n" + errors.join("\n") : "");
        this.settings.set({ lastReport: text });
        this.toast(text.split("\n")[0]);
      } else if (errors.length) this.event("report", errors.join("\n"));
      this.refresh();
    }
  }

  // Logo.java / WebLogo.java: the logo cut from the card's front page (Gemini says where), else the
  // company's website. It goes to everyone at the company without a logo, also replacing a web logo
  // when cut from a card, and the previous cut-out when asked again ("relogo"). Hand-picked stay.
  async cutLogo(next) {
    const st = this.store;
    const p = st.person(next.id);
    const old = next.queue === "relogo" ? p.logo || "" : "";
    delete p.queue;
    p.logoTried = true;
    st.put(p, false);
    if (!(next.pages || []).length) return this.autoLogo(st.person(next.id), "", old, true);
    const front = await this.svc.imaging.toJpeg(await st.readMedia(next.pages[0].file), 1600);
    const r = await gemini.generate(this.cfg(), "logo", [front], this.fetch);
    return this.autoLogo(st.person(next.id), r.box, old, true, front);
  }

  // After a card is read in the background (the web version registers the Google contact here).
  async afterRecognized() {}

  cfg() {
    const s = this.settings;
    return { key: this.secrets.get(), model: s.get("model", "gemini-2.5-flash"), format: s.get("format", "legacy") };
  }

  async autoLogo(p, box, old, search, front) {
    const st = this.store, im = this.svc.imaging;
    if (box && (p.pages || []).length) {
      front = front || (await im.toJpeg(await st.readMedia(p.pages[0].file), 1600));
      const jpeg = await im.crop(front, (w, h) => model.logoRect(box, w, h), 512, model.refineLogoRect);
      if (jpeg) return this.shareLogo(p, jpeg, ".jpg", old, model.CUT);
    }
    const key = model.companyKey(p);
    if (!this.webLogos) return false;
    if (!key || st.people().some((x) => x.logo && x.logo !== old && model.companyKey(x) === key && x.id !== p.id)) return false;
    const found = await weblogo.find(
      {
        fetchImpl: this.fetch,
        size: (b) => im.size(b),
        searchSite: search && this.secrets.get() ? (x) => gemini.officialSite(this.cfg(), x, this.fetch) : null,
      },
      p,
    );
    if (!found) return false;
    return this.shareLogo(p, await im.toPng(found.bytes, 512), ".png", old, "web:" + found.domain);
  }

  async shareLogo(p, bytes, ext, old, origin) {
    const st = this.store;
    const name = crypto.randomUUID() + ext;
    await st.writeMedia(name, bytes);
    const key = model.companyKey(p);
    for (const x of st.people()) {
      const same = x.id === p.id || (key && model.companyKey(x) === key);
      const free = !x.logo || (old && x.logo === old) || (origin.startsWith("card") && String(x.logoSource || "").startsWith("web:"));
      if (!same || !free || x.kind === model.INBOX) continue;
      const y = st.person(x.id);
      y.logo = name;
      y.logoSource = origin;
      st.put(y);
    }
    return true;
  }

  // Nenga.file: attach the card to the matching person, or turn it into a new person.
  fileNenga(tempId, r) {
    const st = this.store;
    const temp = st.person(tempId);
    const g = model.greeting(temp, r);
    const target = model.matchSender(st.people(), r);
    if (target && target !== tempId) {
      const p = st.person(target);
      p.greetings = [...(p.greetings || []), g];
      for (const k of ["postal", "address", "phone", "email", "kana"]) if (!p[k] && r[k]) p[k] = r[k];
      st.put(p);
      st.remove(tempId);
      return;
    }
    const first = String(r.senders || "").trim().split("\n")[0].trim();
    Object.assign(temp, { name: first, kana: r.kana || "", postal: r.postal || "", address: r.address || "", phone: r.phone || "", email: r.email || "" });
    temp.tags = temp.tags || "年賀状";
    delete temp.kind;
    delete temp.nengaYear;
    delete temp.queue;
    temp.pages = [];
    temp.greetings = [g];
    temp.noAutoContact = true;
    temp.rawText = r.rawText || "";
    temp.warnings = r.warnings || "";
    temp.status = first && !String(r.warnings || "").trim() ? "confirmed" : "review";
    st.put(temp);
  }

  // ---- map: OpenStreetMap Nominatim (1 request/second, as its usage policy asks) ----------
  async geocode() {
    if (this.geocoding) return;
    const todo = this.store.people().filter((p) => {
      const q = String(p.address || "").replace(/\n/g, " ").trim();
      return q && (!p.geo || p.geo.q !== q);
    });
    if (!todo.length) return;
    this.geocoding = true;
    this.toast("住所を地図用に変換しています");
    try {
      for (const p0 of todo) {
        const q = String(p0.address).replace(/\n/g, " ").trim();
        let geo = this.geoCache.get(q);
        if (!geo) {
          const res = await this.fetch(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=jp&q=" + encodeURIComponent(q),
            { headers: { "User-Agent": "ThetaMeishiHatch/0.1 (personal use)" }, signal: AbortSignal.timeout(20000) },
          );
          if (!res.ok) break;
          const r = await res.json();
          geo = { q };
          if (r[0]) Object.assign(geo, { lat: +r[0].lat, lng: +r[0].lon });
          this.geoCache.set(q, geo);
          await new Promise((ok) => setTimeout(ok, 1100));
        }
        try {
          const p = this.store.person(p0.id);
          if (String(p.address || "").replace(/\n/g, " ").trim() === q) {
            p.geo = geo;
            this.store.put(p);
          }
        } catch {}
      }
    } catch (e) {
      this.toast("住所の変換を中断しました：" + e.message);
    } finally {
      this.geocoding = false;
    }
  }
}

module.exports = { Backend, CAPS };
