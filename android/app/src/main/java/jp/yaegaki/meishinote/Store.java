package jp.yaegaki.meishinote;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class Store {
  public static final String[] FIELDS = {
    "name",
    "kana",
    "company",
    "department",
    "title",
    "email",
    "phone",
    "mobile",
    "fax",
    "postal",
    "address",
    "website",
    "tags",
    "metDate",
    "metPlace",
    "genre",
    "hours",
    "holidays"
  };
  private static Store instance;
  public final File root, media;
  private JSONObject db;
  // Last local write, so the app knows whether there is anything to sync on leaving.
  public volatile long changedAt = 0;
  private String lastGood;

  public static synchronized Store get(Context c) throws Exception {
    if (instance == null) instance = new Store(c);
    return instance;
  }

  private Store(Context c) throws Exception {
    this(new File(c.getFilesDir(), "vault"));
  }

  // Separate instances over plain folders, for tests that play two devices.
  static Store openForTest(File dir) throws Exception {
    return new Store(dir);
  }

  private Store(File dir) throws Exception {
    root = dir;
    media = new File(root, "media");
    media.mkdirs();
    AtomicFile f = new AtomicFile(new File(root, "data.json"));
    if (f.getBaseFile().exists() || new File(root, "data.json.bak").exists()) {
      try (InputStream in = f.openRead()) {
        db = new JSONObject(new String(read(in, 32 * 1024 * 1024), StandardCharsets.UTF_8));
      }
      if (db.optInt("schema") != 1 || db.optJSONArray("people") == null)
        throw new IOException("データ形式が不正です。元データを保護するため起動を停止しました。");
      lastGood = db.toString();
    } else {
      db = new JSONObject().put("schema", 1).put("people", new JSONArray());
      save();
    }
  }

  public synchronized JSONObject snapshot() throws Exception {
    return new JSONObject(db.toString());
  }

  public synchronized JSONObject person(String id) throws Exception {
    JSONArray a = db.getJSONArray("people");
    for (int i = 0; i < a.length(); i++)
      if (id.equals(a.getJSONObject(i).optString("id")))
        return new JSONObject(a.getJSONObject(i).toString());
    throw new IOException("名刺が見つかりません");
  }

  public static JSONObject empty() throws Exception {
    JSONObject p =
        new JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("created", System.currentTimeMillis())
            .put("updated", System.currentTimeMillis())
            .put("status", "new")
            .put("pages", new JSONArray())
            .put("sources", new JSONArray())
            .put("notes", new JSONArray())
            .put("audio", new JSONArray());
    for (String k : FIELDS) p.put(k, "");
    return p;
  }

  public synchronized void put(JSONObject p) throws Exception {
    p = new JSONObject(p.toString());
    p.put("updated", System.currentTimeMillis());
    putKeepingTime(p);
  }

  // For records arriving from sync: keeps the sender's "updated" so devices agree.
  public synchronized void putSynced(JSONObject p) throws Exception {
    putKeepingTime(new JSONObject(p.toString()));
    tombstones().remove(p.getString("id"));
    save();
  }

  private void putKeepingTime(JSONObject p) throws Exception {
    Set<String> before = referenced(db);
    JSONArray a = db.getJSONArray("people");
    for (int i = 0; i < a.length(); i++)
      if (p.getString("id").equals(a.getJSONObject(i).getString("id"))) {
        a.put(i, p);
        save();
        removeUnused(before);
        return;
      }
    a.put(p);
    save();
  }

  public synchronized void edit(String id, JSONObject input) throws Exception {
    JSONObject p = person(id);
    for (String k : FIELDS) if (input.has(k)) p.put(k, input.optString(k, ""));
    if (input.has("status")) p.put("status", input.optString("status"));
    if (input.has("kind")) {
      if (input.optString("kind").equals("shop")) p.put("kind", "shop");
      else if (p.optString("kind").equals("shop")) p.remove("kind");
    }
    // Saving from the review screen settles a pending Gemini result into the record.
    if (input.optBoolean("acceptSuggestion") && p.has("suggestion")) {
      JSONObject s = p.getJSONObject("suggestion");
      p.put("rawText", s.optString("rawText")).put("warnings", s.optString("warnings"));
      p.remove("suggestion");
    }
    put(p);
  }

  // Deleted ids are remembered so sync can tell other devices (see docs/SYNC_SPEC.md).
  JSONObject tombstones() throws Exception {
    JSONObject t = db.optJSONObject("tombstones");
    if (t == null) db.put("tombstones", t = new JSONObject());
    return t;
  }

  public synchronized JSONObject tombstoneSnapshot() throws Exception {
    return new JSONObject(tombstones().toString());
  }

  public synchronized void clearTombstone(String id) throws Exception {
    tombstones().remove(id);
    save();
  }

  public synchronized void remove(String id) throws Exception {
    tombstones().put(id, System.currentTimeMillis());
    removeQuietly(id);
  }

  // Removal requested by sync itself: no new tombstone.
  public synchronized void removeQuietly(String id) throws Exception {
    Set<String> before = referenced(db);
    JSONArray a = db.getJSONArray("people");
    for (int i = 0; i < a.length(); i++)
      if (id.equals(a.getJSONObject(i).optString("id"))) {
        a.remove(i);
        save();
        removeUnused(before);
        return;
      }
  }

  public synchronized void note(String id, String text) throws Exception {
    JSONObject p = person(id);
    p.getJSONArray("notes")
        .put(
            new JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("time", System.currentTimeMillis())
                .put("text", text));
    put(p);
  }

  public synchronized Set<String> hashes() throws Exception {
    Set<String> s = new HashSet<>();
    JSONArray a = db.getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONArray sources = a.getJSONObject(i).optJSONArray("sources");
      if (sources != null)
        for (int j = 0; j < sources.length(); j++)
          s.add(sources.getJSONObject(j).optString("hash"));
    }
    return s;
  }

  public synchronized void merge(JSONObject incoming) throws Exception {
    JSONArray a = incoming.getJSONArray("people");
    Set<String> ids = new HashSet<>();
    JSONArray old = db.getJSONArray("people");
    for (int i = 0; i < old.length(); i++) ids.add(old.getJSONObject(i).getString("id"));
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      if (!ids.contains(p.getString("id"))) {
        p.remove("contactRawId");
        p.remove("contactAccount");
        p.remove("contactPhoto");
        old.put(p);
        ids.add(p.getString("id"));
      }
    }
    save();
  }

  private void removeUnused(Set<String> before) throws Exception {
    before.removeAll(referenced(db));
    for (String name : before) mediaFile(name).delete();
  }

  public synchronized void mergePeople(String targetId, String sourceId) throws Exception {
    if (targetId.equals(sourceId)) return;
    JSONObject target = person(targetId), source = person(sourceId);
    for (String k : FIELDS) if (target.optString(k).isEmpty()) target.put(k, source.optString(k));
    for (String k : new String[] {"pages", "sources", "notes", "audio", "greetings"}) {
      JSONArray a = source.optJSONArray(k);
      if (a == null) continue;
      if (target.optJSONArray(k) == null) target.put(k, new JSONArray());
      for (int i = 0; i < a.length(); i++) target.getJSONArray(k).put(a.get(i));
    }
    for (String k : new String[] {"portrait", "logo"})
      if (target.optString(k).isEmpty() && !source.optString(k).isEmpty())
        target.put(k, source.getString(k));
    // Keep the target's review state: its details were already checked (or are queued).
    if (target.optString("status").isEmpty()) target.put("status", "new");
    target.put("updated", System.currentTimeMillis());
    target.remove("contactDuplicate");
    target.remove("suggestion");
    JSONArray all = db.getJSONArray("people");
    for (int i = 0; i < all.length(); i++)
      if (targetId.equals(all.getJSONObject(i).getString("id"))) all.put(i, target);
    for (int i = 0; i < all.length(); i++)
      if (sourceId.equals(all.getJSONObject(i).getString("id"))) {
        all.remove(i);
        break;
      }
    tombstones().put(sourceId, System.currentTimeMillis());
    save();
  }

  private void save() throws Exception {
    changedAt = System.currentTimeMillis();
    AtomicFile af = new AtomicFile(new File(root, "data.json"));
    FileOutputStream out = null;
    try {
      out = af.startWrite();
      out.write(db.toString().getBytes(StandardCharsets.UTF_8));
      af.finishWrite(out);
      lastGood = db.toString();
    } catch (Exception e) {
      if (out != null) af.failWrite(out);
      if (lastGood != null) db = new JSONObject(lastGood);
      throw e;
    }
  }

  public File mediaFile(String name) throws IOException {
    if (!name.matches("[A-Za-z0-9_.-]+") || name.contains("..")) throw new IOException("不正なファイル名");
    return new File(media, name);
  }

  public static byte[] read(InputStream in, int max) throws IOException {
    if (in == null) throw new IOException("ファイルを開けません");
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[32768];
    int n;
    while ((n = in.read(buf)) != -1) {
      if (b.size() + n > max) throw new IOException("ファイルが大きすぎます");
      b.write(buf, 0, n);
    }
    return b.toByteArray();
  }

  public static void copy(InputStream in, OutputStream out, long max) throws IOException {
    if (in == null) throw new IOException("ファイルを開けません");
    byte[] b = new byte[65536];
    long total = 0;
    int n;
    while ((n = in.read(b)) != -1) {
      total += n;
      if (total > max) throw new IOException("上限サイズを超えています");
      out.write(b, 0, n);
    }
  }

  public static Set<String> referenced(JSONObject data) throws Exception {
    Set<String> files = new HashSet<>();
    JSONArray a = data.getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      for (String key : new String[] {"portrait", "logo"})
        if (!p.optString(key).isEmpty()) files.add(p.getString(key));
      for (String key : new String[] {"pages", "sources", "audio"}) {
        JSONArray arr = p.optJSONArray(key);
        if (arr != null)
          for (int j = 0; j < arr.length(); j++) {
            JSONObject o = arr.getJSONObject(j);
            if (!o.optString("file").isEmpty()) files.add(o.getString("file"));
          }
      }
      // Images of New Year cards live inside each greeting entry.
      JSONArray greetings = p.optJSONArray("greetings");
      if (greetings != null)
        for (int j = 0; j < greetings.length(); j++) {
          JSONArray fs = greetings.getJSONObject(j).optJSONArray("files");
          if (fs != null)
            for (int k = 0; k < fs.length(); k++)
              if (!fs.getJSONObject(k).optString("file").isEmpty()) files.add(fs.getJSONObject(k).getString("file"));
        }
      // Handwritten notes.
      JSONArray notesWithInk = p.optJSONArray("notes");
      for (int j = 0; notesWithInk != null && j < notesWithInk.length(); j++)
        if (!notesWithInk.getJSONObject(j).optString("ink").isEmpty()) files.add(notesWithInk.getJSONObject(j).getString("ink"));
    }
    return files;
  }
}
