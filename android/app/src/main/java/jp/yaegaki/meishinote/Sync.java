package jp.yaegaki.meishinote;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

// Two-way sync of people and media with a Drive folder. Protocol: docs/SYNC_SPEC.md.
// Change detection uses content hashes against the last synced state (sync.json), so
// clocks never decide who wins unless both sides changed the same person.
public final class Sync {
  // Device-specific fields: contact row ids differ per phone, queues are local work.
  static final String[] LOCAL = {
    "contactRawId", "contactAccount", "contactPhoto", "queue", "queueError",
    "contactDuplicate", "suggestion", "contactUnlinked"
  };
  static final String DELETED = "deleted";
  public static final String ROOT_NAME = "Meishi-Claude データ";

  public static class Result {
    int up, down, merged, deleted, mediaUp, mediaDown;
    final List<String> errors = new ArrayList<>();

    public String text() {
      StringBuilder b = new StringBuilder("同期しました");
      List<String> parts = new ArrayList<>();
      if (down > 0) parts.add("受信 " + down + "人");
      if (up > 0) parts.add("送信 " + up + "人");
      if (merged > 0) parts.add("両方の変更を統合 " + merged + "人");
      if (deleted > 0) parts.add("削除の反映 " + deleted + "人");
      if (mediaUp + mediaDown > 0) parts.add("画像・録音 " + (mediaUp + mediaDown) + "件");
      if (!parts.isEmpty()) b.append("（").append(String.join(" / ", parts)).append("）");
      for (String e : errors) b.append("\n").append(e);
      return b.toString();
    }
  }

  static JSONObject shared(JSONObject p) throws Exception {
    JSONObject s = new JSONObject(p.toString());
    for (String k : LOCAL) s.remove(k);
    return s;
  }

  // Sorted-key JSON so the same content always hashes the same on every platform.
  static String canonical(Object o) throws Exception {
    if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      List<String> keys = new ArrayList<>();
      for (Iterator<String> it = j.keys(); it.hasNext(); ) keys.add(it.next());
      Collections.sort(keys);
      StringBuilder b = new StringBuilder("{");
      for (String k : keys) {
        if (b.length() > 1) b.append(',');
        b.append(JSONObject.quote(k)).append(':').append(canonical(j.get(k)));
      }
      return b.append('}').toString();
    }
    if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < a.length(); i++) {
        if (i > 0) b.append(',');
        b.append(canonical(a.get(i)));
      }
      return b.append(']').toString();
    }
    if (o instanceof String) return JSONObject.quote((String) o);
    if (o == null || o == JSONObject.NULL) return "null";
    return String.valueOf(o);
  }

  // "updated" is excluded: a local-only touch (queue bookkeeping) is not a change to share.
  static String hash(JSONObject p) throws Exception {
    JSONObject s = shared(p);
    s.remove("updated");
    byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(s).getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i]));
    return b.toString();
  }

  // Both sides changed: the newer edit wins field by field where it has a value; lists
  // (notes, recordings, pages, sources) are unioned so nothing captured on either side is lost.
  static JSONObject merge(JSONObject local, JSONObject remote) throws Exception {
    boolean remoteNewer = remote.optLong("updated") > local.optLong("updated");
    JSONObject base = shared(remoteNewer ? remote : local), other = shared(remoteNewer ? local : remote);
    for (String k : Store.FIELDS)
      if (base.optString(k).isEmpty() && !other.optString(k).isEmpty()) base.put(k, other.optString(k));
    for (String k : new String[] {"portrait", "logo", "rawText", "warnings"})
      if (base.optString(k).isEmpty() && !other.optString(k).isEmpty()) base.put(k, other.optString(k));
    union(base, other, "notes", "id");
    union(base, other, "audio", "file");
    union(base, other, "pages", "file");
    union(base, other, "sources", "file");
    JSONArray nd = base.optJSONArray("notDuplicate"), ond = other.optJSONArray("notDuplicate");
    if (ond != null) {
      if (nd == null) base.put("notDuplicate", nd = new JSONArray());
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < nd.length(); i++) seen.add(nd.getString(i));
      for (int i = 0; i < ond.length(); i++) if (seen.add(ond.getString(i))) nd.put(ond.getString(i));
    }
    base.put("updated", Math.max(local.optLong("updated"), remote.optLong("updated")) + 1);
    return base;
  }

  static void union(JSONObject base, JSONObject other, String key, String idKey) throws Exception {
    JSONArray a = base.optJSONArray(key), b = other.optJSONArray(key);
    if (b == null) return;
    if (a == null) base.put(key, a = new JSONArray());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < a.length(); i++) seen.add(a.getJSONObject(i).optString(idKey));
    for (int i = 0; i < b.length(); i++)
      if (seen.add(b.getJSONObject(i).optString(idKey))) a.put(b.getJSONObject(i));
  }

  static Set<String> media(JSONObject p) throws Exception {
    Set<String> files = new HashSet<>();
    for (String k : new String[] {"portrait", "logo"}) if (!p.optString(k).isEmpty()) files.add(p.getString(k));
    for (String k : new String[] {"pages", "sources", "audio"}) {
      JSONArray a = p.optJSONArray(k);
      if (a == null) continue;
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        // A recording still being written is shared once it is finished.
        if (k.equals("audio") && o.optString("state").equals("recording")) continue;
        if (!o.optString("file").isEmpty()) files.add(o.getString("file"));
      }
    }
    return files;
  }

  static String mime(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".jpg")) return "image/jpeg";
    if (n.endsWith(".pdf")) return "application/pdf";
    if (n.endsWith(".m4a")) return "audio/mp4";
    return "application/octet-stream";
  }

  // ---- index of the last synced state ------------------------------------------------

  static File indexFile(Store s) {
    return new File(s.root, "sync.json");
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
    new File(s.root, "sync.json.bak").delete();
  }

  // ---- the run ------------------------------------------------------------------------

  public static Result run(Context c, Drive d, Pipeline.Progress progress) throws Exception {
    Store s = Store.get(c);
    Result res = new Result();
    progress.update("Google Driveと同期中");
    String root = d.markedFolder("root", "meishiRoot", ROOT_NAME);
    String peopleDir = d.markedFolder(root, "meishiPeople", "people");
    String mediaDir = d.markedFolder(root, "meishiMedia", "media");

    Map<String, JSONObject> remote = new HashMap<>();
    JSONArray files = d.listAll(Drive.q(peopleDir) + " in parents and trashed=false", "id,name,appProperties");
    for (int i = 0; i < files.length(); i++) {
      JSONObject f = files.getJSONObject(i);
      JSONObject props = f.optJSONObject("appProperties");
      String pid = props == null ? "" : props.optString("pid");
      if (pid.isEmpty() && f.optString("name").endsWith(".json"))
        pid = f.optString("name").substring(0, f.optString("name").length() - 5);
      if (pid.isEmpty() || remote.containsKey(pid)) continue;
      remote.put(pid, new JSONObject().put("f", f.getString("id")).put("h", props == null ? "" : props.optString("h")));
    }
    Map<String, String> remoteMedia = new HashMap<>();
    JSONArray mf = d.listAll(Drive.q(mediaDir) + " in parents and trashed=false", "id,name");
    for (int i = 0; i < mf.length(); i++) remoteMedia.put(mf.getJSONObject(i).getString("name"), mf.getJSONObject(i).getString("id"));

    JSONObject index = loadIndex(s);
    JSONObject known = index.optJSONObject("people");
    if (known == null) index.put("people", known = new JSONObject());

    Map<String, JSONObject> local = new HashMap<>();
    JSONArray people = s.snapshot().getJSONArray("people");
    for (int i = 0; i < people.length(); i++) local.put(people.getJSONObject(i).getString("id"), people.getJSONObject(i));
    JSONObject tomb = s.tombstoneSnapshot();

    Set<String> ids = new TreeSet<>(local.keySet());
    ids.addAll(remote.keySet());
    for (Iterator<String> it = tomb.keys(); it.hasNext(); ) ids.add(it.next());
    int n = 0;
    for (String id : ids) {
      if (++n % 10 == 0) progress.update("Google Driveと同期中 " + n + " / " + ids.size());
      try {
        step(c, d, s, id, local.get(id), tomb.has(id) ? tomb.getLong(id) : -1, remote.get(id), known, peopleDir, mediaDir, remoteMedia, res);
      } catch (Exception e) {
        res.errors.add(id.substring(0, Math.min(8, id.length())) + ": " + e.getMessage());
        String m = String.valueOf(e.getMessage());
        if (m.contains("HTTP 401") || m.contains("HTTP 403")) break;
      }
      if (n % 20 == 0) saveIndex(s, index);
    }
    saveIndex(s, index);
    return res;
  }

  static void step(
      Context c, Drive d, Store s, String id, JSONObject L, long tombAt, JSONObject R, JSONObject known,
      String peopleDir, String mediaDir, Map<String, String> remoteMedia, Result res) throws Exception {
    if (L != null && tombAt >= 0) {
      // Restored from a backup after deleting: the record on hand wins.
      s.clearTombstone(id);
      tombAt = -1;
    }
    String lh = L != null ? hash(L) : tombAt >= 0 ? DELETED : null;
    String rh = R != null ? R.optString("h") : null;
    String ih = known.has(id) ? known.getJSONObject(id).optString("h") : null;
    String fileId = R != null ? R.getString("f") : "";
    switch (decide(lh, rh, ih)) {
      case SKIP:
        return;
      case SAME:
        known.put(id, new JSONObject().put("h", lh).put("f", fileId));
        if (lh.equals(DELETED)) s.clearTombstone(id);
        return;
      case FORGET_TOMBSTONE:
        s.clearTombstone(id);
        return;
      case RECORD_REMOTE_DELETE:
        known.put(id, new JSONObject().put("h", rh).put("f", fileId));
        return;
      case PUSH:
        push(d, s, id, L, lh, fileId, peopleDir, mediaDir, remoteMedia, known, res);
        return;
      case PULL:
        pull(c, d, s, id, lh.equals(DELETED) ? null : L, R, known, remoteMedia, res);
        return;
      case MERGE:
        {
          JSONObject theirs = new JSONObject(d.text(fileId));
          JSONObject merged = merge(L, theirs);
          fetchMedia(d, s, merged, remoteMedia, res);
          JSONObject current = s.person(id);
          if (!hash(current).equals(lh)) return; // edited during sync; next run picks it up
          for (String k : LOCAL) if (current.has(k)) merged.put(k, current.get(k));
          s.putSynced(merged);
          push(d, s, id, merged, hash(merged), fileId, peopleDir, mediaDir, remoteMedia, known, res);
          res.up--;
          res.merged++;
        }
    }
  }

  enum Action { SKIP, SAME, FORGET_TOMBSTONE, RECORD_REMOTE_DELETE, PUSH, PULL, MERGE }

  // lh/rh: hash of the local / remote record ("deleted" for a tombstone, null if absent);
  // ih: hash both sides had at the last successful sync (null if never synced).
  static Action decide(String lh, String rh, String ih) {
    if (lh == null && rh == null) return Action.SKIP;
    if (lh != null && lh.equals(rh)) return Action.SAME;
    if (rh == null) return lh.equals(DELETED) ? Action.FORGET_TOMBSTONE : Action.PUSH;
    if (lh == null) return rh.equals(DELETED) ? Action.RECORD_REMOTE_DELETE : Action.PULL;
    boolean localChanged = !lh.equals(ih), remoteChanged = !rh.equals(ih);
    if (!remoteChanged) return Action.PUSH;
    if (!localChanged) return Action.PULL;
    if (lh.equals(DELETED)) return Action.PULL; // an edit elsewhere beats a delete here
    if (rh.equals(DELETED)) return Action.PUSH; // and a local edit beats a delete elsewhere
    return Action.MERGE;
  }

  static void push(
      Drive d, Store s, String id, JSONObject L, String lh, String fileId, String peopleDir, String mediaDir,
      Map<String, String> remoteMedia, JSONObject known, Result res) throws Exception {
    String body;
    if (L == null || lh.equals(DELETED)) {
      body = new JSONObject().put("id", id).put("deleted", true).put("updated", System.currentTimeMillis()).toString();
    } else {
      // Media first, so a device that sees the record can always fetch its files.
      for (String name : media(L)) {
        if (remoteMedia.containsKey(name)) continue;
        File f = s.mediaFile(name);
        if (!f.isFile()) continue;
        remoteMedia.put(name, d.upload(mediaDir, name, mime(name), f));
        res.mediaUp++;
      }
      body = shared(L).toString();
    }
    String f = d.putJson(fileId, peopleDir, id + ".json", new JSONObject().put("pid", id).put("h", lh), body);
    known.put(id, new JSONObject().put("h", lh).put("f", f));
    if (lh.equals(DELETED)) {
      s.clearTombstone(id);
      res.deleted++;
    } else res.up++;
  }

  static void pull(
      Context c, Drive d, Store s, String id, JSONObject L, JSONObject R, JSONObject known,
      Map<String, String> remoteMedia, Result res) throws Exception {
    String rh = R.optString("h");
    if (rh.equals(DELETED)) {
      if (L != null) {
        JSONObject current = s.person(id);
        if (!hash(current).equals(hash(L))) return;
        // The Google contact this phone created stays: deleting contacts is never done implicitly.
        s.removeQuietly(id);
        s.clearTombstone(id);
        res.deleted++;
      }
      known.put(id, new JSONObject().put("h", rh).put("f", R.getString("f")));
      return;
    }
    JSONObject theirs = new JSONObject(d.text(R.getString("f")));
    if (!id.equals(theirs.optString("id"))) throw new IOException("同期データのIDが一致しません");
    fetchMedia(d, s, theirs, remoteMedia, res);
    if (L != null) {
      JSONObject current = s.person(id);
      if (!hash(current).equals(hash(L))) return;
      for (String k : LOCAL) if (current.has(k)) theirs.put(k, current.get(k));
    }
    s.putSynced(theirs);
    known.put(id, new JSONObject().put("h", hash(theirs)).put("f", R.getString("f")));
    res.down++;
  }

  static void fetchMedia(Drive d, Store s, JSONObject p, Map<String, String> remoteMedia, Result res) throws Exception {
    for (String name : media(p)) {
      File f = s.mediaFile(name);
      if (f.isFile() || !remoteMedia.containsKey(name)) continue;
      File part = new File(f.getPath() + ".part");
      try {
        d.download(remoteMedia.get(name), part);
        if (!part.renameTo(f)) throw new IOException("画像を保存できません");
        res.mediaDown++;
      } finally {
        part.delete();
      }
    }
  }
}
