// Syncs the web version's store with the Theta Vault on Google Drive (THETA_VAULT_SPEC.md), the
// same folder the phone (VaultSync.java) and the PC (through Google Drive for desktop) use.
//
// The index (kept in kv "vaultIndex") remembers, per card, its note's Drive file, the file's md5
// when last seen and a hash of what this browser last wrote or read. Remote changed = md5 differs;
// local changed = hash differs; both = merged (newer field wins, lists unioned).
// Safety as on Android: deletions move notes into .theta/trash, an edit wins over a deletion,
// and when many known notes are missing at once nothing is deleted.
"use strict";
const crypto = require("crypto");
const V = require("../../desktop/lib/vault-codec");
const model = require("../../desktop/lib/model");

const FOLDER = "application/vnd.google-apps.folder";
const hash = (p) => crypto.createHash("sha256").update(V.render(p)).digest("hex");
const decode = (b) => new TextDecoder().decode(b instanceof Uint8Array ? b : new Uint8Array(b));

class DriveVault {
  // api: { list(q), download(id), create(meta, bytes, mime), update(id, meta, bytes?, mime?, parents?), meta(id) }
  constructor(store, db, api) {
    this.store = store;
    this.db = db;
    this.api = api;
  }

  async child(parent, name, create = true) {
    const q = `name='${name.replace(/'/g, "\\'")}' and '${parent}' in parents and mimeType='${FOLDER}' and trashed=false`;
    const found = await this.api.list(q);
    if (found.length) return found[0].id;
    if (!create) return null;
    return (await this.api.create({ name, parents: [parent], mimeType: FOLDER })).id;
  }

  async folders() {
    const root = await this.child("root", "Theta Vault");
    const theta = await this.child(root, ".theta");
    const app = await this.child(root, V.APP_DIR);
    return {
      root,
      trash: await this.child(theta, "trash"),
      app,
      people: await this.child(app, "People"),
      shops: await this.child(app, "Shops"),
      files: await this.child(app, "_files"),
    };
  }

  // Every note under Meishi/ (subfolders too: people organise in Obsidian), except _files.
  async notes(f) {
    const out = [];
    const walk = async (id, depth) => {
      for (const x of await this.api.list(`'${id}' in parents and trashed=false`)) {
        if (x.mimeType === FOLDER) {
          if (x.id !== f.files && !x.name.startsWith(".") && depth < 6) await walk(x.id, depth + 1);
        } else if (x.name.endsWith(".md")) out.push(x);
      }
    };
    await walk(f.app, 0);
    return out;
  }

  async mediaIndex(f) {
    if (!this.remoteFiles) this.remoteFiles = new Map((await this.api.list(`'${f.files}' in parents and trashed=false`)).map((x) => [x.name, x.id]));
    return this.remoteFiles;
  }

  // Lets the store fetch a picture that another device uploaded.
  attach(f) {
    this.store.remoteMedia = async (name) => {
      const id = (await this.mediaIndex(f)).get(name);
      return id ? new Uint8Array(await this.api.download(id)) : null;
    };
  }

  async run(progress = () => {}) {
    const st = this.store;
    const ix = (await this.db.get("kv", "vaultIndex")) || { notes: {} };
    progress("Googleドライブを確認しています");
    const f = await this.folders();
    this.remoteFiles = null;
    this.attach(f);
    const remote = await this.notes(f);
    const byFile = new Map(Object.entries(ix.notes).map(([id, n]) => [n.fileId, id]));
    const seen = new Set();
    const r = { down: 0, up: 0, merged: 0, deleted: 0, warnings: [] };

    // 1. Drive → here.
    for (const [i, file] of remote.entries()) {
      const knownId = byFile.get(file.id);
      if (knownId && ix.notes[knownId].md5 === file.md5Checksum) {
        seen.add(knownId);
        if (!st.byId.has(knownId) && st.tombs.has(knownId)) await this.trash(f, ix, knownId, file, r);
        continue;
      }
      progress(`受信 ${i + 1} / ${remote.length}`);
      let rp;
      try {
        rp = V.parse(decode(await this.api.download(file.id)), true);
      } catch (e) {
        r.warnings.push(`${file.name}：読み込めません（${e.message}）`);
        continue;
      }
      if (seen.has(rp.id)) {
        r.warnings.push(`${file.name}：同じidのノートが既にあります（無視しました）`);
        continue;
      }
      seen.add(rp.id);
      const handWritten = !!rp.vaultNewId;
      delete rp.vaultNewId;
      const entry = { fileId: file.id, md5: file.md5Checksum, name: file.name, parent: (file.parents || [])[0] };
      const lp = st.byId.get(rp.id);
      if (!lp) {
        // Deleted here: the note goes to the trash, unless it was edited elsewhere since (edit wins).
        // (An unchanged note was handled above; reaching here with a known entry means it changed.)
        if (st.tombs.has(rp.id) && !ix.notes[rp.id]) {
          ix.notes[rp.id] = { ...entry, hash: "" };
          await this.trash(f, ix, rp.id, file, r);
          continue;
        }
        if (st.tombs.has(rp.id)) st.forgetTomb(rp.id);
        st.putSynced(rp);
        // A note typed by hand in Obsidian gets its id written back.
        ix.notes[rp.id] = { ...entry, hash: handWritten ? "" : hash(rp) };
        r.down++;
        continue;
      }
      const old = ix.notes[rp.id];
      const localChanged = !old || hash(lp) !== old.hash;
      if (localChanged) {
        st.putSynced(model.syncMerge(lp, rp));
        ix.notes[rp.id] = { ...entry, hash: "" }; // the merge goes back up below
        r.merged++;
      } else {
        const keep = {};
        for (const k of V.LOCAL) if (lp[k] !== undefined) keep[k] = lp[k];
        const next = { ...rp, ...keep };
        st.putSynced(next);
        ix.notes[rp.id] = { ...entry, hash: handWritten ? "" : hash(next) };
        r.down++;
      }
    }

    // 2. Known notes no longer under Meishi/: deleted elsewhere (unless edited here since).
    const missing = Object.keys(ix.notes).filter((id) => !seen.has(id));
    const known = Object.keys(ix.notes).length;
    if (missing.length > Math.max(5, Math.floor((known * 3) / 10)))
      throw new Error(`Googleドライブで多くのノート（${missing.length}件）が見つかりません。消えたのでなければフォルダの移動を確認してください。安全のため同期を止めました`);
    for (const id of missing) {
      const lp = st.byId.get(id);
      if (lp && hash(lp) !== ix.notes[id].hash) {
        delete ix.notes[id]; // edited here: it goes up again as a new note
        continue;
      }
      if (lp) {
        st.removeQuietly(id);
        r.deleted++;
      }
      if (st.tombs.has(id)) st.forgetTomb(id);
      delete ix.notes[id];
    }

    // 3. Here → Drive.
    const changed = st.people().filter((p) => p.kind !== model.INBOX && (!ix.notes[p.id] || hash(p) !== ix.notes[p.id].hash));
    for (const [i, p] of changed.entries()) {
      progress(`送信 ${i + 1} / ${changed.length}`);
      await this.uploadMedia(f, p);
      const md = new TextEncoder().encode(V.render(p));
      const name = V.fileName(p);
      const folder = V.folder(p) === "Shops" ? f.shops : f.people;
      const e = ix.notes[p.id];
      let file;
      if (!e) file = await this.api.create({ name, parents: [folder], mimeType: "text/markdown" }, md, "text/markdown");
      else {
        // Re-filed between People and Shops only when it is in one of them (not in a user's subfolder).
        const move = (e.parent === f.people || e.parent === f.shops) && e.parent !== folder ? { add: folder, remove: e.parent } : null;
        file = await this.api.update(e.fileId, { name }, md, "text/markdown", move);
      }
      const ours = !e || e.parent === f.people || e.parent === f.shops;
      ix.notes[p.id] = { fileId: file.id, md5: file.md5Checksum, name, parent: ours ? folder : e.parent, hash: hash(p) };
      r.up++;
      await this.db.put("kv", ix, "vaultIndex"); // progress survives an interrupted run
    }

    // 4. Cards deleted here whose notes were never synced: nothing to tell Drive.
    for (const id of [...st.tombs.keys()]) if (!ix.notes[id]) st.forgetTomb(id);

    await this.db.put("kv", ix, "vaultIndex");
    await st.flush();
    st.warnings = r.warnings;
    st.emit();
    return r;
  }

  async trash(f, ix, id, file, r) {
    await this.api.update(file.id, {}, null, null, { add: f.trash, remove: (file.parents || []).join(",") });
    delete ix.notes[id];
    this.store.forgetTomb(id);
    r.deleted++;
  }

  async uploadMedia(f, p) {
    const remote = await this.mediaIndex(f);
    for (const name of model.media(p)) {
      if (remote.has(name) || !(await this.store.hasMedia(name))) continue;
      const bytes = await this.store.db.get("media", name);
      const mime = /\.png$/i.test(name) ? "image/png" : /\.pdf$/i.test(name) ? "application/pdf" : /\.m4a$/i.test(name) ? "audio/mp4" : /\.jpe?g$/i.test(name) ? "image/jpeg" : "application/octet-stream";
      const file = await this.api.create({ name, parents: [f.files] }, new Uint8Array(bytes), mime);
      remote.set(name, file.id);
    }
  }
}

module.exports = { DriveVault, hash };
