// Google sign-in (Google Identity Services, token model) and the Drive / People API calls.
// The OAuth client is the user's own "Web application" client (docs/WEB_SETUP.md). Tokens live in
// memory only (about an hour); a new one needs a tap, because Safari blocks pop-ups otherwise.
"use strict";

const SCOPES = [
  "https://www.googleapis.com/auth/drive",
  "https://www.googleapis.com/auth/contacts",
  "openid",
  "email",
].join(" ");

class NeedLogin extends Error {
  constructor() {
    super("Googleへの再ログインが必要です（メニュー →「今すぐ同期」などを押してください）");
  }
}

class Google {
  constructor(clientId) {
    this.clientId = clientId;
    this.access = null; // { token, until }
    this.email = "";
    this.gis = null;
  }

  // Loaded early, so that a tap can open the sign-in window at once.
  load() {
    if (this.gis || !this.clientId) return this.gis;
    this.gis = new Promise((ok, ng) => {
      const s = document.createElement("script");
      s.src = "https://accounts.google.com/gsi/client";
      s.onload = () => ok(window.google.accounts.oauth2);
      s.onerror = () => ng(new Error("Googleのログイン画面を読み込めませんでした（通信を確認してください）"));
      document.head.appendChild(s);
    });
    return this.gis;
  }

  valid() {
    return !!(this.access && this.access.until > Date.now() + 60000);
  }

  // interactive: called during a tap (opens Google's window when needed). Otherwise fails with
  // NeedLogin instead of opening a window that Safari would block.
  token(interactive) {
    if (this.valid()) return Promise.resolve(this.access.token);
    if (!this.clientId) return Promise.reject(new Error("先にメニューの「Googleアカウント」で、OAuthクライアントIDを設定してください"));
    if (!interactive) return Promise.reject(new NeedLogin());
    const oauth = window.google?.accounts?.oauth2;
    if (!oauth) return this.load().then(() => Promise.reject(new Error("もう一度押してください（Googleの準備ができました）")));
    // Must start synchronously inside the tap.
    return new Promise((ok, ng) => {
      const client = oauth.initTokenClient({
        client_id: this.clientId,
        scope: SCOPES,
        callback: (r) => {
          if (r.error) return ng(new Error("Googleにログインできませんでした：" + r.error));
          this.access = { token: r.access_token, until: Date.now() + (r.expires_in || 3600) * 1000 };
          ok(r.access_token);
          this.whoami().catch(() => {});
        },
        error_callback: (e) => ng(new Error("Googleのログインを中止しました" + (e && e.type ? `（${e.type}）` : ""))),
      });
      client.requestAccessToken({ prompt: this.email ? "" : "consent", hint: this.email || undefined });
    });
  }

  signOut() {
    if (this.access && window.google?.accounts?.oauth2) window.google.accounts.oauth2.revoke(this.access.token, () => {});
    this.access = null;
    this.email = "";
  }

  async whoami() {
    const r = await this.call("https://www.googleapis.com/oauth2/v3/userinfo");
    this.email = r.email || "";
    return this.email;
  }

  async call(url, { method = "GET", body, headers = {}, raw = false } = {}) {
    if (!this.valid()) throw new NeedLogin();
    const res = await fetch(url, { method, body, headers: { Authorization: "Bearer " + this.access.token, ...headers } });
    if (res.status === 401) {
      this.access = null;
      throw new NeedLogin();
    }
    if (!res.ok) {
      let msg = "";
      try {
        msg = (await res.json()).error.message;
      } catch {}
      throw new Error(`Google HTTP ${res.status} ${msg}`);
    }
    if (raw) return res;
    return res.status === 204 ? {} : res.json();
  }

  // ---- Drive (the api drive-vault.js expects) --------------------------------------------
  drive() {
    const D = "https://www.googleapis.com/drive/v3/files";
    const U = "https://www.googleapis.com/upload/drive/v3/files";
    const multipart = (meta, bytes, mime) => {
      const b = "theta" + Math.random().toString(36).slice(2);
      const head = `--${b}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n--${b}\r\nContent-Type: ${mime || "application/octet-stream"}\r\n\r\n`;
      return { body: new Blob([head, bytes, `\r\n--${b}--`]), type: `multipart/related; boundary=${b}` };
    };
    return {
      list: async (q) => {
        const out = [];
        let page = "";
        do {
          const u = `${D}?q=${encodeURIComponent(q)}&pageSize=1000&spaces=drive&fields=nextPageToken,files(id,name,mimeType,parents,md5Checksum)${page ? "&pageToken=" + encodeURIComponent(page) : ""}`;
          const r = await this.call(u);
          out.push(...(r.files || []));
          page = r.nextPageToken || "";
        } while (page);
        return out;
      },
      download: async (id) => new Uint8Array(await (await this.call(`${D}/${encodeURIComponent(id)}?alt=media`, { raw: true })).arrayBuffer()),
      create: async (meta, bytes, mime) => {
        if (!bytes) return this.call(`${D}?fields=id,md5Checksum`, { method: "POST", body: JSON.stringify(meta), headers: { "Content-Type": "application/json" } });
        const m = multipart(meta, bytes, mime);
        return this.call(`${U}?uploadType=multipart&fields=id,md5Checksum`, { method: "POST", body: m.body, headers: { "Content-Type": m.type } });
      },
      update: async (id, meta, bytes, mime, move) => {
        const q = move ? `&addParents=${encodeURIComponent(move.add)}&removeParents=${encodeURIComponent(move.remove)}` : "";
        if (!bytes)
          return this.call(`${D}/${encodeURIComponent(id)}?fields=id,md5Checksum${q}`, { method: "PATCH", body: JSON.stringify(meta || {}), headers: { "Content-Type": "application/json" } });
        const m = multipart(meta || {}, bytes, mime);
        return this.call(`${U}/${encodeURIComponent(id)}?uploadType=multipart&fields=id,md5Checksum${q}`, { method: "PATCH", body: m.body, headers: { "Content-Type": m.type } });
      },
    };
  }

  // ---- People (Google Contacts) ------------------------------------------------------------
  people() {
    const P = "https://people.googleapis.com/v1/";
    const FIELDS = "names,organizations,phoneNumbers,emailAddresses,addresses,urls,biographies";
    const json = { "Content-Type": "application/json" };
    return {
      create: (body) => this.call(`${P}people:createContact?personFields=names`, { method: "POST", body: JSON.stringify(body), headers: json }),
      update: async (resource, body) => {
        const cur = await this.call(`${P}${resource}?personFields=names`);
        return this.call(`${P}${resource}:updateContact?updatePersonFields=${FIELDS}`, { method: "PATCH", body: JSON.stringify({ ...body, etag: cur.etag }), headers: json });
      },
      photo: (resource, base64) => this.call(`${P}${resource}:updateContactPhoto`, { method: "PATCH", body: JSON.stringify({ photoBytes: base64 }), headers: json }),
      remove: (resource) => this.call(`${P}${resource}:deleteContact`, { method: "DELETE" }),
    };
  }
}

module.exports = { Google, NeedLogin, SCOPES };
