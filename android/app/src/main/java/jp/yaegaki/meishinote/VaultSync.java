package jp.yaegaki.meishinote;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

// Two-way sync between the phone and an Obsidian-style vault on Google Drive
// (docs/THETA_VAULT_SPEC.md). Remote change detection uses Drive's md5 of the file content,
// so edits made in Obsidian through the Google Drive desktop app are seen like any other.
// The decision table is Sync.decide (unit-tested); this class maps vault state onto it.
public final class VaultSync {
  public static final String ROOT = "Theta Vault";
  static final String DELETED = Sync.DELETED;

  public static class Result {
    int up, down, merged, deleted, mediaUp, mediaDown;
    final List<String> errors = new ArrayList<>();

    public String text() {
      List<String> parts = new ArrayList<>();
      if (down > 0) parts.add("受信 " + down + "人");
      if (up > 0) parts.add("送信 " + up + "人");
      if (merged > 0) parts.add("両方の変更を統合 " + merged + "人");
      if (deleted > 0) parts.add("削除の反映 " + deleted + "人");
      if (mediaUp + mediaDown > 0) parts.add("画像・録音 " + (mediaUp + mediaDown) + "件");
      StringBuilder b = new StringBuilder("Theta Vaultと同期しました");
      if (!parts.isEmpty()) b.append("（").append(String.join(" / ", parts)).append("）");
      for (String e : errors) b.append("\n").append(e);
      return b.toString();
    }
  }

  // ---- local state (outside the vault) ----------------------------------------------

  static File indexFile(Store s) {
    return new File(s.root, "vault-sync.json");
  }

  static JSONObject loadIndex(Store s) {
    try (InputStream in = new AtomicFile(indexFile(s)).openRead()) {
      return new JSONObject(new String(Store.read(in, 32 * 1024 * 1024), StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  static void saveIndex(Store s, JSONObject index) throws Exception {
    AtomicFile af = new AtomicFile(indexFile(s));
    FileOutputStream out = af.startWrite();
    try {
      out.write(index.toString().getBytes(StandardCharsets.UTF_8));
      af.finishWrite(out);
    } catch (Exception e) {
      af.failWrite(out);
      throw e;
    }
  }

  public static void forget(Store s) {
    indexFile(s).delete();
    new File(s.root, "vault-sync.json.bak").delete();
  }

  // ---- one run ------------------------------------------------------------------------

  static final class Remote {
    String fileId, name, folder, md5;
    long modified;
    String text; // filled when we had to read it to learn its id
    JSONObject parsed;
  }

  static final class Ctx {
    VaultRemote d;
    Store s;
    JSONObject known; // id -> {f, md5, lh, name}
    String people, shops, files, trash;
    Map<String, String> media = new HashMap<>(); // local name -> drive id
    Map<String, Set<String>> names = new HashMap<>(); // folder id -> file names in use
    Result res = new Result();
    boolean allowRemoteDeletes = true;
  }

  public static Result run(Context c, Drive d, Pipeline.Progress progress) throws Exception {
    return run(Store.get(c), new VaultRemote.OnDrive(d), progress);
  }

  public static Result run(Store store, VaultRemote d, Pipeline.Progress progress) throws Exception {
    Ctx x = new Ctx();
    x.d = d;
    x.s = store;
    progress.update("Theta Vaultと同期中");
    String root = d.folder("root", ROOT);
    String theta = d.folder(root, ".theta");
    boolean hasSpec = false;
    JSONArray tf = d.files(theta);
    for (int i = 0; i < tf.length(); i++) if (tf.getJSONObject(i).optString("name").equals("vault.json")) hasSpec = true;
    if (!hasSpec)
      d.put("", theta, "vault.json",
          new JSONObject().put("spec", 1).put("apps", new JSONArray().put("meishi")).toString(2).getBytes("UTF-8"), "", "");
    x.trash = d.folder(theta, "trash");
    String app = d.folder(root, VaultCodec.APP_DIR);
    x.people = d.folder(app, "People");
    x.shops = d.folder(app, "Shops");
    x.files = d.folder(app, "_files");

    JSONObject index = loadIndex(x.s);
    x.known = index.optJSONObject("people");
    if (x.known == null) index.put("people", x.known = new JSONObject());
    Map<String, String> byFile = new HashMap<>(); // drive file id -> person id
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) {
      String id = it.next();
      byFile.put(x.known.getJSONObject(id).optString("f"), id);
    }

    // Remote notes. Unknown files are read once to learn which person they are.
    Map<String, Remote> remote = new HashMap<>();
    for (String folder : new String[] {x.people, x.shops}) {
      Set<String> used = new HashSet<>();
      x.names.put(folder, used);
      JSONArray list = d.files(folder);
      for (int i = 0; i < list.length(); i++) {
        JSONObject f = list.getJSONObject(i);
        used.add(f.getString("name"));
        if (!f.getString("name").endsWith(".md")) continue;
        Remote r = new Remote();
        r.fileId = f.getString("id");
        r.name = f.getString("name");
        r.folder = folder;
        r.md5 = f.optString("md5Checksum");
        r.modified = VaultCodec.ms(f.optString("modifiedTime").replace("Z", "+00:00"), 0);
        String id = byFile.get(r.fileId);
        if (id == null) {
          try {
            r.text = d.read(r.fileId);
            r.parsed = VaultCodec.parse(r.text, true);
            id = r.parsed.getString("id");
          } catch (Exception e) {
            x.res.errors.add(r.name + "：読み込めないため対象外（" + e.getMessage() + "）");
            continue;
          }
        }
        if (remote.containsKey(id)) {
          x.res.errors.add(r.name + "：同じidのノートが複数あります。片方は無視しました");
          continue;
        }
        remote.put(id, r);
      }
    }
    // A known note missing from People/Shops may just have been moved into a subfolder in
    // Obsidian: look it up by id before treating it as deleted.
    int missing = 0;
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) {
      String id = it.next();
      if (remote.containsKey(id)) continue;
      JSONObject m = d.meta(x.known.getJSONObject(id).optString("f"));
      if (m == null
          || m.optBoolean("trashed")
          || m.optJSONArray("parents") == null
          || m.getJSONArray("parents").optString(0).equals(x.trash)) {
        missing++;
        continue;
      }
      Remote r = new Remote();
      r.fileId = m.getString("id");
      r.name = m.optString("name");
      r.folder = m.getJSONArray("parents").optString(0);
      r.md5 = m.optString("md5Checksum");
      r.modified = VaultCodec.ms(m.optString("modifiedTime").replace("Z", "+00:00"), 0);
      remote.put(id, r);
    }
    // Many notes vanishing at once looks like a wrong folder or account, not a decision to delete.
    x.allowRemoteDeletes = missing <= Math.max(5, x.known.length() * 3 / 10);
    if (!x.allowRemoteDeletes)
      x.res.errors.add("Vaultから" + missing + "件のノートが見つかりません。誤操作の可能性があるため、端末側の削除は行いませんでした");
    JSONArray mf = d.files(x.files);
    for (int i = 0; i < mf.length(); i++) x.media.put(mf.getJSONObject(i).getString("name"), mf.getJSONObject(i).getString("id"));

    Map<String, JSONObject> local = new HashMap<>();
    JSONArray people = x.s.snapshot().getJSONArray("people");
    for (int i = 0; i < people.length(); i++) {
      JSONObject p = people.getJSONObject(i);
      if (!p.optString("kind").equals(Nenga.INBOX)) local.put(p.getString("id"), p);
    }
    JSONObject tomb = x.s.tombstoneSnapshot();
    Set<String> ids = new TreeSet<>(local.keySet());
    ids.addAll(remote.keySet());
    for (Iterator<String> it = tomb.keys(); it.hasNext(); ) ids.add(it.next());
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) ids.add(it.next());

    int n = 0;
    for (String id : ids) {
      if (++n % 10 == 0) progress.update("Theta Vaultと同期中 " + n + " / " + ids.size());
      try {
        step(x, id, local.get(id), tomb.has(id), remote.get(id));
      } catch (Exception e) {
        x.res.errors.add(label(local.get(id), id) + "：" + e.getMessage());
        String m = String.valueOf(e.getMessage());
        if (m.contains("HTTP 401") || m.contains("HTTP 403")) break;
      }
      if (n % 20 == 0) saveIndex(x.s, index);
    }
    saveIndex(x.s, index);
    return x.res;
  }

  static String label(JSONObject p, String id) {
    return p != null && !p.optString("name").isEmpty() ? p.optString("name") : id.substring(0, Math.min(8, id.length()));
  }

  static void step(Ctx x, String id, JSONObject L, boolean tomb, Remote R) throws Exception {
    if (L != null && tomb) {
      x.s.clearTombstone(id); // restored from a backup after deleting: the record on hand wins
      tomb = false;
    }
    JSONObject I = x.known.optJSONObject(id);
    String lh = L != null ? Sync.hash(L) : tomb ? DELETED : null;
    String ih = I != null ? I.optString("lh") : null;
    // Remote state in the same terms: unchanged file → the hash we last synced; changed → new token;
    // a file we knew that is gone (trashed in Obsidian / Drive) → deleted.
    String rh;
    if (R != null) rh = I != null && R.md5.equals(I.optString("md5")) ? ih : "md5:" + R.md5;
    else rh = I != null ? DELETED : null;
    switch (Sync.decide(lh, rh, ih)) {
      case SKIP:
        return;
      case SAME:
        if (lh.equals(DELETED)) {
          x.s.clearTombstone(id);
          x.known.remove(id);
        }
        return;
      case FORGET_TOMBSTONE:
        x.s.clearTombstone(id);
        x.known.remove(id);
        return;
      case RECORD_REMOTE_DELETE:
        x.known.remove(id);
        return;
      case PUSH:
        if (lh.equals(DELETED)) {
          if (R != null) x.d.trash(R.fileId, R.folder, x.trash);
          x.s.clearTombstone(id);
          x.known.remove(id);
          x.res.deleted++;
        } else push(x, L, R);
        return;
      case PULL:
        if (rh.equals(DELETED)) {
          if (!x.allowRemoteDeletes) return; // keep both the record and the index entry
          // Removed from the vault: drop it here unless edited meanwhile. The Google contact stays.
          if (L != null && Sync.hash(x.s.person(id)).equals(lh)) {
            x.s.removeQuietly(id);
            x.res.deleted++;
          }
          x.known.remove(id);
          return;
        }
        pull(x, id, lh == null || lh.equals(DELETED) ? null : L, R);
        return;
      case MERGE:
        {
          JSONObject theirs = read(x, R);
          if (Sync.hash(theirs).equals(lh)) {
            record(x, id, R.fileId, R.md5, lh, R.name);
            return;
          }
          JSONObject merged = Sync.merge(L, theirs);
          for (String k : new String[] {"vaultExtra", "vaultFree"})
            if (!merged.has(k) && theirs.has(k)) merged.put(k, theirs.get(k));
          fetchMedia(x, merged);
          JSONObject current = x.s.person(id);
          if (!Sync.hash(current).equals(lh)) return; // edited during sync; next run
          for (String k : Sync.LOCAL) if (current.has(k)) merged.put(k, current.get(k));
          x.s.putSynced(merged);
          push(x, merged, R);
          x.res.up--;
          x.res.merged++;
        }
    }
  }

  static JSONObject read(Ctx x, Remote R) throws Exception {
    // Reuse the first reading: a hand-written note gets its new id once, not on every parse.
    String md = R.text != null ? R.text : x.d.read(R.fileId);
    JSONObject p = R.parsed != null ? new JSONObject(R.parsed.toString()) : VaultCodec.parse(md, true);
    // An edit made in Obsidian does not touch "updated"; the file's own time tells when it changed.
    // (A file the app wrote keeps its "updated": upload time is not edit time.)
    if (VaultCodec.editedOutside(md) && R.modified > p.optLong("updated")) p.put("updated", R.modified);
    return p;
  }

  static void record(Ctx x, String id, String fileId, String md5, String lh, String name) throws Exception {
    x.known.put(id, new JSONObject().put("f", fileId).put("md5", md5).put("lh", lh).put("name", name));
  }

  static void push(Ctx x, JSONObject L, Remote R) throws Exception {
    // Attachments first, so anyone who sees the note can open its files.
    for (String name : Sync.media(L)) {
      if (x.media.containsKey(name)) continue;
      File f = x.s.mediaFile(name);
      if (!f.isFile()) continue;
      x.media.put(name, x.d.upload(x.files, name, Sync.mime(name), f));
      x.res.mediaUp++;
    }
    String folder = VaultCodec.folder(L).equals("Shops") ? x.shops : x.people;
    String wanted = VaultCodec.fileName(L);
    String name = R != null ? R.name : "";
    if (!wanted.equals(name)) {
      Set<String> used = x.names.get(folder);
      String base = wanted.substring(0, wanted.length() - 3);
      name = wanted;
      for (int i = 2; used.contains(name) && !(R != null && name.equals(R.name)); i++) name = base + " (" + i + ").md";
      used.add(name);
    }
    byte[] body = VaultCodec.render(L).getBytes(StandardCharsets.UTF_8);
    // Re-file between People and Shops when the kind changes; leave notes the user filed elsewhere.
    boolean move = R != null && !R.folder.equals(folder) && (R.folder.equals(x.people) || R.folder.equals(x.shops));
    JSONObject r =
        x.d.put(R != null ? R.fileId : "", folder, name, body, move ? folder : "", move ? R.folder : "");
    record(x, L.getString("id"), r.getString("id"), r.optString("md5Checksum"), Sync.hash(L), r.optString("name", name));
    x.res.up++;
  }

  static void pull(Ctx x, String id, JSONObject L, Remote R) throws Exception {
    JSONObject theirs = read(x, R);
    fetchMedia(x, theirs);
    boolean fresh = !theirs.getString("id").equals(id); // cannot happen, guarded anyway
    if (fresh) throw new IOException("ノートのidが一致しません");
    if (L != null) {
      JSONObject current = x.s.person(id);
      if (!Sync.hash(current).equals(Sync.hash(L))) return; // edited during sync
      for (String k : Sync.LOCAL) if (current.has(k)) theirs.put(k, current.get(k));
    }
    boolean needsId = theirs.optBoolean("vaultNewId");
    theirs.remove("vaultNewId");
    x.s.putSynced(theirs);
    if (needsId) {
      // A note written by hand in Obsidian: write it back once so it carries its new id.
      push(x, theirs, R);
      x.res.up--;
    } else record(x, id, R.fileId, R.md5, Sync.hash(theirs), R.name);
    x.res.down++;
  }

  static void fetchMedia(Ctx x, JSONObject p) throws Exception {
    for (String name : Sync.media(p)) {
      File f = x.s.mediaFile(name);
      if (f.isFile() || !x.media.containsKey(name)) continue;
      File part = new File(f.getPath() + ".part");
      try {
        x.d.download(x.media.get(name), part);
        if (!part.renameTo(f)) throw new IOException("ファイルを保存できません");
        x.res.mediaDown++;
      } finally {
        part.delete();
      }
    }
  }
}
