// The desktop backend (cards, Gemini, import, logos, New Year cards) plus what the web version does
// through Google: contacts (People API) and the Theta Vault on Drive (drive-vault.js).
"use strict";
const { Backend } = require("../../desktop/lib/backend");
const { Google, NeedLogin } = require("./google");
const { DriveVault } = require("./drive-vault");
const contacts = require("./contacts");

const CAPS = ["pick", "scan", "gemini", "map", "export", "nenga", "attach", "contacts", "sync", "google"];
// Actions that may need a Google sign-in window: it has to open during the tap itself.
const GOOGLE = new Set(["syncNow", "contacts", "unlinkContact", "googleSignIn"]);

class WebBackend extends Backend {
  constructor(opts) {
    super({ ...opts, platform: "web", caps: CAPS, webLogos: false });
    this.db = opts.db;
    this.google = new Google(this.settings.get("googleClientId", ""));
    this.google.load();
    this.vault = new DriveVault(this.store, this.db, this.google.drive());
    this.syncing = null;
    this.timer = null;
    this.store.on(() => this.scheduleSync());
  }

  state() {
    const s = this.settings;
    const pending = this.store.people().some((p) => p.contactPending);
    return {
      ...super.state(),
      syncEnabled: s.get("syncEnabled", false),
      lastSync: s.get("lastSync", 0),
      syncError: s.get("syncError", ""),
      autoContact: s.get("autoContact", true),
      contactPhoto: s.get("contactPhoto", "card"),
      contactAccount: this.google.email,
      googleClientId: s.get("googleClientId", ""),
      googleSignedIn: this.google.valid(),
      // A tap is needed to sign in again: the home screen offers it.
      googleNeeded: !!s.get("googleClientId", "") && !this.google.valid() && (s.get("syncEnabled", false) || pending),
    };
  }

  async handle(action, q = {}) {
    // Start the sign-in synchronously, still inside the tap (Safari blocks later pop-ups).
    let signIn = null;
    if (GOOGLE.has(action) || ((action === "save" || action === "delete") && this.linked(q.id))) signIn = this.google.token(true).catch((e) => e);
    try {
      if (signIn) {
        const t = await signIn;
        if (t instanceof Error) throw t;
      }
      await this.dispatch(action, q);
    } catch (e) {
      this.event("error", e.message || String(e));
    }
  }

  linked(id) {
    try {
      return !!(id && this.store.person(id).contactRawId);
    } catch {
      return false;
    }
  }

  async dispatch(action, q) {
    const st = this.store, id = q.id || "";
    switch (action) {
      case "googleSignIn":
        await this.afterSignIn();
        this.refresh();
        return this.toast("Googleにログインしました" + (this.google.email ? "：" + this.google.email : ""));
      case "googleSignOut":
        this.google.signOut();
        this.refresh();
        return this.toast("Googleからログアウトしました");
      case "settings": {
        const mine = {};
        if ("googleClientId" in q) {
          const v = String(q.googleClientId || "").trim();
          if (v && !/^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/.test(v)) throw new Error("OAuthクライアントIDの形式が違います（…apps.googleusercontent.com）");
          mine.googleClientId = v;
          this.google.signOut();
          this.google = new Google(v);
          this.google.load();
          this.vault = new DriveVault(this.store, this.db, this.google.drive());
        }
        if ("autoContact" in q) mine.autoContact = !!q.autoContact;
        if (["card", "portrait", "logo", "none"].includes(q.contactPhoto)) mine.contactPhoto = q.contactPhoto;
        if (q.clearAccount) this.google.signOut();
        if (Object.keys(mine).length) this.settings.set(mine);
        const rest = Object.keys(q).filter((k) => !["googleClientId", "autoContact", "contactPhoto", "clearAccount"].includes(k));
        if (rest.length) return super.dispatch(action, q);
        this.refresh();
        return this.toast("設定を保存しました");
      }
      case "syncNow":
        this.settings.set({ syncEnabled: true });
        await this.afterSignIn();
        return this.sync(true);
      case "syncStop":
        this.settings.set({ syncEnabled: false, syncError: "" });
        this.refresh();
        return this.toast("同期をやめました。Googleドライブのデータはそのまま残ります");
      case "contacts": {
        const p = st.person(id);
        await this.register(p, q.photo || this.settings.get("contactPhoto", "card"));
        this.refresh();
        return this.toast("Google連絡先に登録しました");
      }
      case "save": {
        await super.dispatch(action, q);
        if (this.linked(id)) {
          await this.updateContact(st.person(id));
          this.toast("Google連絡先にも反映しました");
        }
        return;
      }
      case "delete":
        if (q.withContact && this.linked(id)) await this.google.people().remove(st.person(id).contactRawId);
        return super.dispatch(action, q);
      case "unlinkContact": {
        const p = st.person(id);
        if (p.contactRawId) await this.google.people().remove(p.contactRawId);
        delete p.contactRawId;
        delete p.contactRegistered;
        p.contactUnlinked = true;
        st.put(p);
        return this.toast("Google連絡先から削除しました");
      }
      case "viewContact": {
        const p = st.person(id);
        return this.svc.openExternal("https://contacts.google.com/" + String(p.contactRawId || "").replace(/^people\//, "person/"));
      }
      default:
        return super.dispatch(action, q);
    }
  }

  // Series settings: .theta/settings.json on Drive, read and written during a sync. The web
  // version also publishes its OAuth client ID and account (other installs show them in the guide).
  seriesCurrent() {
    const o = super.seriesCurrent();
    const id = this.settings.get("googleClientId", "");
    if (id) o["google.webClientId"] = id;
    if (this.google.email) o["google.account"] = this.google.email;
    return o;
  }
  async seriesSync(during) {
    // Only within a sync (signed in, sync turned on): never a sign-in window of its own.
    if (during) return super.seriesSync();
  }
  seriesFile() {
    const vault = this.vault;
    let io;
    const get = async () => (io = io || (await vault.settingsFile()));
    return { read: async () => (await get()).read(), write: async (t) => (await get()).write(t) };
  }

  // Signed in during a tap: cards waiting for their contact get it now.
  async afterSignIn() {
    if (!this.google.email) await this.google.whoami().catch(() => {});
    for (const p of this.store.people().filter((x) => x.contactPending)) {
      try {
        await this.register(this.store.person(p.id), this.settings.get("contactPhoto", "card"));
      } catch (e) {
        this.event("report", `${p.name}: ${e.message}`);
      }
    }
  }

  // Cards read in the background are registered at once when signed in, else when next signed in.
  async afterRecognized(p) {
    const s = this.settings;
    if (!s.get("autoContact", true) || !s.get("googleClientId", "") || !String(p.name || "").trim()) return;
    if (p.contactRegistered || p.contactUnlinked || p.noAutoContact || p.contactRawId) return;
    if (!this.google.valid()) {
      p.contactPending = true;
      return this.store.put(p, false);
    }
    await this.register(p, s.get("contactPhoto", "card"));
  }

  async register(p, photo) {
    if (p.contactRawId) return this.updateContact(p);
    const r = await this.google.people().create(contacts.body(p));
    p.contactRawId = r.resourceName;
    p.contactRegistered = true;
    p.contactAccount = this.google.email;
    delete p.contactPending;
    delete p.contactUnlinked;
    this.store.put(p);
    const image = await this.contactImage(p, photo).catch(() => null);
    if (image) await this.google.people().photo(r.resourceName, image);
  }

  async updateContact(p) {
    await this.google.people().update(p.contactRawId, contacts.body(p));
  }

  // The card (or portrait / logo) centred on a white square, as on Android.
  async contactImage(p, photo) {
    const file = photo === "card" ? p.pages?.[0]?.file : photo === "portrait" || photo === "logo" ? p[photo] : "";
    if (!file) return null;
    const b = await createImageBitmap(new Blob([await this.store.readMedia(file)]));
    const c = document.createElement("canvas");
    c.width = c.height = 900;
    const g = c.getContext("2d");
    g.fillStyle = "#fff";
    g.fillRect(0, 0, 900, 900);
    const s = Math.min(820 / b.width, 820 / b.height);
    g.drawImage(b, (900 - b.width * s) / 2, (900 - b.height * s) / 2, b.width * s, b.height * s);
    b.close();
    return c.toDataURL("image/jpeg", 0.92).split(",")[1];
  }

  // ---- Theta Vault on Google Drive -------------------------------------------------------
  scheduleSync() {
    if (!this.settings.get("syncEnabled", false) || !this.google.valid() || this.syncing) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.sync(false), 8000);
  }

  async sync(shown) {
    if (this.syncing) return this.syncing;
    this.syncing = (async () => {
      try {
        const r = await this.vault.run((t) => shown && this.event("busy", t));
        this.settings.set({ lastSync: Date.now(), syncError: "" });
        await this.seriesSync(true);
        if (shown) this.toast(`同期しました（受信 ${r.down}・送信 ${r.up}${r.merged ? `・統合 ${r.merged}` : ""}${r.deleted ? `・削除 ${r.deleted}` : ""}）`);
      } catch (e) {
        this.settings.set({ syncError: e instanceof NeedLogin ? "Googleへの再ログインが必要です" : e.message });
        if (shown) this.event("report", e.message);
      } finally {
        if (shown) this.event("busy", "");
        this.syncing = null;
        this.refresh();
      }
    })();
    return this.syncing;
  }
}

module.exports = { WebBackend, CAPS };
