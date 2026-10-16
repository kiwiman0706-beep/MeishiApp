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
    "metPlace"
  };
  private static Store instance;
  public final File root, media;
  private JSONObject db;
  private String lastGood;

  public static synchronized Store get(Context c) throws Exception {
    if (instance == null) instance = new Store(c);
    return instance;
  }

  private Store(Context c) throws Exception {
    root = new File(c.getFilesDir(), "vault");
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
    Set<String> before = referenced(db);
    JSONArray a = db.getJSONArray("people");
    p.put("updated", System.currentTimeMillis());
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
    // Saving from the review screen settles a pending Gemini result into the record.
    if (input.optBoolean("acceptSuggestion") && p.has("suggestion")) {
      JSONObject s = p.getJSONObject("suggestion");
      p.put("rawText", s.optString("rawText")).put("warnings", s.optString("warnings"));
      p.remove("suggestion");
    }
    put(p);
  }

  public synchronized void remove(String id) throws Exception {
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
    for (String k : new String[] {"pages", "sources", "notes", "audio"}) {
      JSONArray a = source.getJSONArray(k);
      for (int i = 0; i < a.length(); i++) target.getJSONArray(k).put(a.get(i));
    }
    for (String k : new String[] {"portrait", "logo"})
      if (target.optString(k).isEmpty() && !source.optString(k).isEmpty())
        target.put(k, source.getString(k));
    target.put("status", "new").put("updated", System.currentTimeMillis());
    target.remove("suggestion");
    JSONArray all = db.getJSONArray("people");
    for (int i = 0; i < all.length(); i++)
      if (targetId.equals(all.getJSONObject(i).getString("id"))) all.put(i, target);
    for (int i = 0; i < all.length(); i++)
      if (sourceId.equals(all.getJSONObject(i).getString("id"))) {
        all.remove(i);
        break;
      }
    save();
  }

  private void save() throws Exception {
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
    }
    return files;
  }
}
