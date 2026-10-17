// The Theta Vault folder *is* the database on desktop: one Markdown note per person, read on start
// and re-read when the folder changes (Google Drive for desktop, Obsidian, the phone's sync).
// Device-only fields (queue, suggestion…) live in an overlay file outside the vault.
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const V = require("./vault-codec");

const SAFE = /^[A-Za-z0-9_.-]+$/;

class VaultStore {
  constructor(root, overlayFile) {
    this.root = root;
    this.app = path.join(root, V.APP_DIR);
    this.media = path.join(this.app, "_files");
    this.overlayFile = overlayFile;
    this.byId = new Map(); // id -> { file, person }
    this.warnings = [];
    this.selfWrites = new Map(); // file -> content we just wrote (ignore our own watch events)
    this.listeners = new Set();
    this.overlay = {};
    try {
      this.overlay = JSON.parse(fs.readFileSync(overlayFile, "utf8"));
    } catch {
      this.overlay = {};
    }
  }

  ensure() {
    for (const d of [this.root, path.join(this.root, ".theta"), path.join(this.root, ".theta", "trash"), this.app, path.join(this.app, "People"), path.join(this.app, "Shops"), this.media])
      fs.mkdirSync(d, { recursive: true });
    const spec = path.join(this.root, ".theta", "vault.json");
    if (!fs.existsSync(spec)) fs.writeFileSync(spec, JSON.stringify({ spec: 1, apps: ["meishi"] }, null, 2));
  }

  // Every .md under Meishi/ except _files (subfolders are fine: people organise in Obsidian).
  notes(dir = this.app, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name.startsWith(".")) continue;
      const f = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (f !== this.media) this.notes(f, out);
      } else if (e.name.endsWith(".md")) out.push(f);
    }
    return out;
  }

  load() {
    this.ensure();
    const next = new Map();
    this.warnings = [];
    for (const file of this.notes()) {
      let md;
      try {
        md = fs.readFileSync(file, "utf8");
      } catch {
        continue;
      }
      let p;
      try {
        p = V.parse(md, true);
      } catch (e) {
        this.warnings.push(`${path.relative(this.root, file)}：読み込めません（${e.message}）`);
        continue;
      }
      if (next.has(p.id)) {
        this.warnings.push(`${path.relative(this.root, file)}：同じidのノートが既にあります（無視しました）`);
        continue;
      }
      next.set(p.id, { file, person: p });
      if (p.vaultNewId) {
        // A note typed by hand in Obsidian: give it an id and the app layout once.
        delete p.vaultNewId;
        this.write(file, V.render(p));
      }
    }
    this.byId = next;
    return this;
  }

  withOverlay(p) {
    const o = this.overlay[p.id];
    return o ? { ...p, ...o } : { ...p };
  }

  people() {
    return [...this.byId.values()].map((x) => this.withOverlay(x.person));
  }

  person(id) {
    const x = this.byId.get(id);
    if (!x) throw new Error("名刺が見つかりません");
    return JSON.parse(JSON.stringify(this.withOverlay(x.person)));
  }

  saveOverlay() {
    for (const [k, v] of Object.entries(this.overlay)) if (!v || !Object.keys(v).length) delete this.overlay[k];
    fs.writeFileSync(this.overlayFile + ".tmp", JSON.stringify(this.overlay));
    fs.renameSync(this.overlayFile + ".tmp", this.overlayFile);
  }

  write(file, md) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = path.join(path.dirname(file), "." + path.basename(file) + "." + process.pid + ".tmp");
    fs.writeFileSync(tmp, md);
    fs.renameSync(tmp, file);
    this.selfWrites.set(file, md);
  }

  uniquePath(dir, name, except) {
    let f = path.join(dir, name);
    for (let i = 2; fs.existsSync(f) && f !== except; i++) f = path.join(dir, name.replace(/\.md$/, ` (${i}).md`));
    return f;
  }

  // Saves a person: device-only fields go to the overlay, the rest to its note (renamed /
  // re-filed between People and Shops as needed; notes filed elsewhere stay where they are).
  put(p, touch = true) {
    p = JSON.parse(JSON.stringify(p));
    if (touch) p.updated = Date.now();
    const overlay = {};
    for (const k of V.LOCAL) if (p[k] != null && p[k] !== "") overlay[k] = p[k];
    this.overlay[p.id] = overlay;
    this.saveOverlay();
    for (const k of V.LOCAL) delete p[k];
    const cur = this.byId.get(p.id);
    const top = path.join(this.app, V.folder(p));
    let file;
    if (!cur) file = this.uniquePath(top, V.fileName(p));
    else {
      const dir = path.dirname(cur.file);
      const ours = dir === path.join(this.app, "People") || dir === path.join(this.app, "Shops");
      const target = ours ? top : dir;
      file = path.basename(cur.file) === V.fileName(p) && target === dir ? cur.file : this.uniquePath(target, V.fileName(p), cur.file);
    }
    this.write(file, V.render(p));
    if (cur && cur.file !== file) {
      try {
        fs.unlinkSync(cur.file);
      } catch {}
    }
    this.byId.set(p.id, { file, person: p });
    this.emit();
  }

  // Deleting moves the note into .theta/trash (the vault's own recycle bin).
  remove(id) {
    const cur = this.byId.get(id);
    if (!cur) return;
    const trash = path.join(this.root, ".theta", "trash");
    fs.mkdirSync(trash, { recursive: true });
    fs.renameSync(cur.file, this.uniquePath(trash, path.basename(cur.file)));
    this.byId.delete(id);
    delete this.overlay[id];
    this.saveOverlay();
    this.emit();
  }

  mediaFile(name) {
    if (!SAFE.test(name) || name.includes("..")) throw new Error("不正なファイル名");
    fs.mkdirSync(this.media, { recursive: true });
    return path.join(this.media, name);
  }

  hashes() {
    const s = new Set();
    for (const { person } of this.byId.values()) for (const src of person.sources || []) if (src.hash) s.add(src.hash);
    return s;
  }

  on(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit() {
    for (const fn of this.listeners) fn();
  }

  // Reload when anything under Meishi/ changes from outside (debounced; our own writes ignored).
  watch() {
    let timer = null;
    const reload = () => {
      const before = new Map([...this.byId].map(([id, x]) => [id, V.render(x.person)]));
      this.load();
      const changed =
        before.size !== this.byId.size || [...this.byId].some(([id, x]) => before.get(id) !== V.render(x.person));
      if (changed) this.emit();
    };
    try {
      this.watcher = fs.watch(this.app, { recursive: true }, (ev, name) => {
        const f = name && path.join(this.app, name.toString());
        if (f && this.selfWrites.has(f)) {
          let now = null;
          try {
            now = fs.readFileSync(f, "utf8");
          } catch {}
          if (now === this.selfWrites.get(f)) return;
          this.selfWrites.delete(f);
        }
        clearTimeout(timer);
        timer = setTimeout(reload, 700);
      });
    } catch {
      // No recursive watch on this platform: poll instead.
      this.poll = setInterval(reload, 15000);
    }
  }

  close() {
    if (this.watcher) this.watcher.close();
    if (this.poll) clearInterval(this.poll);
  }
}

const newId = () => crypto.randomUUID();
module.exports = { VaultStore, newId };
