// Theta Meishi Hatch for Windows / Mac. Lives in the tray, serves the shared UI on 127.0.0.1
// (the app window and DeskHatch drawers both use it), and keeps the Theta Vault folder as its data.
"use strict";
const { app, BrowserWindow, Tray, Menu, dialog, shell, nativeImage, safeStorage, clipboard, ipcMain } = require("electron");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { VaultStore } = require("./lib/vault-store");
const { Backend } = require("./lib/backend");
const server = require("./lib/server");

// THETA_USER_DATA / THETA_VAULT: point a test run at throwaway folders (test/e2e/electron-smoke.sh).
if (process.env.THETA_USER_DATA) app.setPath("userData", process.env.THETA_USER_DATA);
if (!app.requestSingleInstanceLock()) app.quit();

const userData = app.getPath("userData");
const settingsFile = path.join(userData, "settings.json");
let settingsData = {};
try {
  settingsData = JSON.parse(fs.readFileSync(settingsFile, "utf8"));
} catch {}
const settings = {
  get: (k, d) => (k in settingsData ? settingsData[k] : d),
  set: (obj) => {
    Object.assign(settingsData, obj);
    fs.mkdirSync(userData, { recursive: true });
    fs.writeFileSync(settingsFile, JSON.stringify(settingsData, null, 2));
  },
};
// The Gemini key is encrypted with the OS keychain (DPAPI / Keychain) and never leaves this PC.
const secrets = {
  get: () => {
    const enc = settings.get("apiKeyEnc", "");
    if (!enc) return "";
    try {
      return safeStorage.decryptString(Buffer.from(enc, "base64"));
    } catch {
      return "";
    }
  },
  set: (v) => settings.set({ apiKeyEnc: v ? safeStorage.encryptString(v).toString("base64") : "" }),
};

// Google Drive for desktop puts "My Drive" here; the phone syncs "Theta Vault" into it.
function findVault() {
  const names = ["マイドライブ", "My Drive"];
  const roots = [];
  if (process.platform === "win32") for (const d of "GHIJKLMNOPQRSTUVWXYZ") roots.push(d + ":\\");
  else {
    const cloud = path.join(os.homedir(), "Library", "CloudStorage");
    try {
      for (const e of fs.readdirSync(cloud)) if (e.startsWith("GoogleDrive")) roots.push(path.join(cloud, e));
    } catch {}
    roots.push(path.join(os.homedir(), "Google Drive"));
  }
  for (const r of roots) for (const n of names) {
    const v = path.join(r, n, "Theta Vault");
    if (fs.existsSync(v)) return v;
  }
  return "";
}

async function resolveVault() {
  let v = process.env.THETA_VAULT || "";
  if (!v) {
    const saved = settings.get("vaultPath", "");
    if (saved && fs.existsSync(saved)) return saved;
    v = findVault();
  }
  if (!v) {
    const r = await dialog.showMessageBox({
      type: "question",
      buttons: ["フォルダを選ぶ", "書類フォルダに作る"],
      message: "Theta Vault の場所",
      detail: "スマホと同期するには、Googleドライブ（デスクトップ版）の「マイドライブ/Theta Vault」を選んでください。まだ無ければ、書類フォルダに新しく作れます（後から変えられます）。",
    });
    if (r.response === 0) {
      const pick = await dialog.showOpenDialog({ title: "Theta Vault のフォルダ", properties: ["openDirectory", "createDirectory"] });
      v = pick.canceled ? "" : pick.filePaths[0];
    }
    if (!v) v = path.join(app.getPath("documents"), "Theta Vault");
  }
  fs.mkdirSync(v, { recursive: true });
  settings.set({ vaultPath: v });
  return v;
}

// ---- images ---------------------------------------------------------------------------
let pdfWin = null, pdfReady = null;
const pdfJobs = new Map();
ipcMain.on("pdf-done", (_e, id, result) => {
  const job = pdfJobs.get(id);
  if (!job) return;
  pdfJobs.delete(id);
  result.error ? job.ng(new Error(result.error)) : job.ok(result.pages.map((b) => Buffer.from(b, "base64")));
});
function pdfWindow(base) {
  if (pdfReady) return pdfReady;
  pdfReady = new Promise((ok) => {
    pdfWin = new BrowserWindow({ show: false, webPreferences: { preload: path.join(__dirname, "assets", "pdf-preload.js"), contextIsolation: true, sandbox: false } });
    ipcMain.once("pdf-ready", () => ok(pdfWin));
    pdfWin.webContents.on("console-message", (_e, level, message) => level >= 2 && console.error("pdf:", message));
    pdfWin.loadURL(base + "/pdf/render.html?token=" + settings.get("token"));
  });
  return pdfReady;
}
function imaging(base) {
  return {
    toJpeg: async (buf, max) => {
      let img = nativeImage.createFromBuffer(buf);
      if (img.isEmpty()) throw new Error("この画像形式は読み込めません（JPEG・PNGをお使いください）");
      const { width, height } = img.getSize();
      const s = Math.min(1, max / Math.max(width, height));
      if (s < 1) img = img.resize({ width: Math.round(width * s), height: Math.round(height * s), quality: "best" });
      return img.toJPEG(85);
    },
    pdfPages: async (buf, max) => {
      const win = await pdfWindow(base);
      return new Promise((ok, ng) => {
        const id = crypto.randomUUID();
        pdfJobs.set(id, { ok, ng });
        win.webContents.send("pdf-job", { id, data: buf.toString("base64"), max });
      });
    },
  };
}

// ---- app ------------------------------------------------------------------------------
let win = null, tray = null, url = "";
function show() {
  if (!win) {
    win = new BrowserWindow({ width: 1100, height: 820, title: "Theta Meishi Hatch", autoHideMenuBar: true, icon: path.join(__dirname, "assets", "icon.png"), webPreferences: { contextIsolation: true, sandbox: true } });
    win.loadURL(url);
    win.on("close", (e) => {
      if (!app.isQuitting) {
        e.preventDefault();
        win.hide(); // keep serving DeskHatch drawers from the tray
      }
    });
    // Links leave the app: the system browser, mail or phone handler.
    win.webContents.setWindowOpenHandler(({ url: u }) => {
      if (/^(https?|mailto|tel):/i.test(u)) shell.openExternal(u);
      return { action: "deny" };
    });
  }
  win.show();
  win.focus();
}

app.on("second-instance", show);
app.on("activate", () => url && show()); // macOS: clicking the Dock icon
app.on("window-all-closed", (e) => e.preventDefault && e.preventDefault());

app.whenReady().then(async () => {
  if (!settings.get("token")) settings.set({ token: crypto.randomBytes(24).toString("hex") });
  const vault = await resolveVault();
  const store = new VaultStore(vault, path.join(userData, "overlay.json")).load();
  store.watch();
  const port0 = settings.get("port", 47860);
  const services = {
    imaging: null,
    openFiles: async (single) => {
      const r = await dialog.showOpenDialog(win || undefined, {
        properties: single ? ["openFile"] : ["openFile", "multiSelections"],
        filters: [{ name: "画像・PDF", extensions: ["jpg", "jpeg", "png", "pdf", "webp", "gif", "bmp"] }],
      });
      return r.canceled ? [] : r.filePaths;
    },
    openFolder: async (title) => {
      const r = await dialog.showOpenDialog(win || undefined, { title, properties: ["openDirectory"] });
      return r.canceled ? "" : r.filePaths[0];
    },
    saveFile: async (name, text) => {
      const r = await dialog.showSaveDialog(win || undefined, { defaultPath: path.join(app.getPath("downloads"), name) });
      if (r.canceled || !r.filePath) return "";
      fs.writeFileSync(r.filePath, text);
      return r.filePath;
    },
    openExternal: (u) => shell.openExternal(u),
    openPath: (f) => shell.openPath(f),
    showInFolder: (f) => shell.showItemInFolder(f),
  };
  const backend = new Backend({ store, settings, secrets, services });
  backend.on((msg) => {
    if (msg.type === "error" || msg.type === "report") console.error(msg.data);
    if (msg.type === "imported" && msg.data.errors.length) console.error(msg.data.errors.join("\n"));
    if (msg.type === "chooseVault") {
      settings.set({ vaultPath: msg.data });
      app.relaunch();
      app.isQuitting = true;
      app.quit();
    }
  });
  const pdfDir = path.join(__dirname, "node_modules", "pdfjs-dist");
  const listen = (port) =>
    new Promise((ok, ng) => {
      const s = server.start({ backend, store, uiDir: path.join(__dirname, "ui"), pdfDir, port, token: settings.get("token"), onReady: (p) => ok(p) });
      s.on("error", ng);
    });
  let port;
  try {
    port = await listen(port0);
  } catch {
    port = await listen(0); // the usual port is taken: any free one (DeskHatch URL changes)
  }
  if (port !== port0) console.warn(`port ${port0} is in use; serving on ${port} for this run`);
  const base = `http://127.0.0.1:${port}`;
  services.imaging = imaging(base);
  url = `${base}/?token=${settings.get("token")}`;

  const icon = nativeImage.createFromPath(path.join(__dirname, "assets", "tray.png"));
  tray = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon);
  tray.setToolTip("Theta Meishi Hatch");
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: "開く", click: show },
      { label: "DeskHatch用のURLをコピー", click: () => clipboard.writeText(url) },
      { label: "Theta Vault フォルダを開く", click: () => shell.openPath(vault) },
      { type: "separator" },
      { label: "ログイン時に起動", type: "checkbox", checked: app.getLoginItemSettings().openAtLogin, click: (m) => app.setLoginItemSettings({ openAtLogin: m.checked, args: ["--hidden"] }) },
      { label: "終了", click: () => ((app.isQuitting = true), app.quit()) },
    ]),
  );
  tray.on("click", show);
  console.log("serving " + base);
  if (!process.argv.includes("--hidden")) show();
});

app.on("before-quit", () => (app.isQuitting = true));
