package jp.yaegaki.meishinote;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class Backup {
  public static File make(Context c) throws Exception {
    Store s = Store.get(c);
    JSONObject snapshot = s.snapshot();
    JSONArray savedPeople = snapshot.getJSONArray("people");
    for (int i = 0; i < savedPeople.length(); i++) {
      JSONObject p = savedPeople.getJSONObject(i);
      p.remove("contactRawId");
      p.remove("contactAccount");
      p.remove("contactPhoto");
    }
    long total = snapshot.toString().getBytes(StandardCharsets.UTF_8).length;
    for (String name : Store.referenced(snapshot)) total += s.mediaFile(name).length();
    if (total > 2L * 1024 * 1024 * 1024)
      throw new IOException("このバージョンのバックアップ上限は展開後2GBです。不要な録音を整理してください。");
    File f = new File(c.getCacheDir(), "meishinote-backup.zip");
    try (ZipOutputStream z =
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
      z.putNextEntry(new ZipEntry("data.json"));
      z.write(snapshot.toString(2).getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
      for (String name : Store.referenced(snapshot)) {
        File src = s.mediaFile(name);
        if (!src.isFile()) throw new IOException("添付ファイルが見つかりません: " + name);
        z.putNextEntry(new ZipEntry("media/" + name));
        try (InputStream in = new FileInputStream(src)) {
          Store.copy(in, z, 1024L * 1024 * 1024);
        }
        z.closeEntry();
      }
    } catch (Exception e) {
      f.delete();
      throw e;
    }
    return f;
  }

  public static int restore(Context c, InputStream input) throws Exception {
    Store s = Store.get(c);
    File stage = new File(c.getCacheDir(), "restore-" + UUID.randomUUID());
    stage.mkdirs();
    Set<String> entries = new HashSet<>();
    try {
      long total = 0;
      try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(input))) {
        ZipEntry e;
        int count = 0;
        while ((e = z.getNextEntry()) != null) {
          String n = e.getName();
          if (e.isDirectory()) continue;
          if (++count > 20000) throw new IOException("ファイル数が多すぎます");
          if (!n.equals("data.json") && !n.matches("media/[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"))
            throw new IOException("バックアップ内に不正なパスがあります");
          if (!entries.add(n)) throw new IOException("重複したZIP項目があります");
          File dest = new File(stage, n);
          dest.getParentFile().mkdirs();
          long entrySize = 0;
          try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = z.read(buf)) != -1) {
              entrySize += k;
              total += k;
              if (total > 2L * 1024 * 1024 * 1024
                  || entrySize > 1024L * 1024 * 1024
                  || (n.equals("data.json") && entrySize > 32 * 1024 * 1024))
                throw new IOException("バックアップの展開上限を超えています（合計2GB）");
              out.write(buf, 0, k);
            }
          }
        }
      }
      JSONObject data;
      try (InputStream in = new FileInputStream(new File(stage, "data.json"))) {
        data = new JSONObject(new String(Store.read(in, 32 * 1024 * 1024), StandardCharsets.UTF_8));
      }
      if (data.optInt("schema") != 1) throw new IOException("対応していないバックアップ形式です");
      JSONArray people = data.getJSONArray("people");
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < people.length(); i++) {
        JSONObject p = people.getJSONObject(i);
        String id = p.getString("id");
        if (!id.matches("[A-Za-z0-9-]{1,80}") || !ids.add(id)) throw new IOException("名刺IDが不正です");
        if (!Arrays.asList("new", "review", "confirmed").contains(p.optString("status")))
          p.put("status", "review");
        for (String key : Store.FIELDS) if (!(p.opt(key) instanceof String)) p.put(key, "");
        if (p.has("suggestion") && p.optJSONObject("suggestion") == null) p.remove("suggestion");
        if (p.optJSONObject("suggestion") != null) {
          JSONObject suggestion = p.getJSONObject("suggestion");
          for (String key : Store.FIELDS)
            if (suggestion.has(key) && !(suggestion.opt(key) instanceof String))
              suggestion.put(key, "");
        }
        for (String arr : new String[] {"pages", "sources", "audio", "notes"})
          if (p.optJSONArray(arr) == null) throw new IOException("バックアップの項目が不足しています");
      }
      Set<String> files = Store.referenced(data);
      for (String name : files) {
        s.mediaFile(name);
        if (!new File(stage, "media/" + name).isFile())
          throw new IOException("画像または録音が不足しています: " + name);
      }
      Set<String> existingIds = new HashSet<>();
      JSONArray old = s.snapshot().getJSONArray("people");
      for (int i = 0; i < old.length(); i++) existingIds.add(old.getJSONObject(i).getString("id"));
      JSONArray add = new JSONArray();
      for (int i = 0; i < people.length(); i++)
        if (!existingIds.contains(people.getJSONObject(i).getString("id")))
          add.put(people.getJSONObject(i));
      JSONObject additions = new JSONObject().put("people", add);
      Set<String> needed = Store.referenced(additions);
      // Never overwrite existing attachments. Rename every restored file before committing the new
      // records.
      Map<String, String> mapping = new HashMap<>();
      List<File> copied = new ArrayList<>();
      try {
        for (String name : needed) {
          String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
          String replacement = UUID.randomUUID() + ext;
          File dest = s.mediaFile(replacement);
          try (InputStream in = new FileInputStream(new File(stage, "media/" + name));
              OutputStream out = new FileOutputStream(dest)) {
            Store.copy(in, out, 1024L * 1024 * 1024);
          }
          mapping.put(name, replacement);
          copied.add(dest);
        }
        for (int i = 0; i < add.length(); i++) {
          JSONObject p = add.getJSONObject(i);
          for (String key : new String[] {"portrait", "logo"})
            if (mapping.containsKey(p.optString(key))) p.put(key, mapping.get(p.getString(key)));
          for (String key : new String[] {"pages", "sources", "audio"}) {
            JSONArray a = p.getJSONArray(key);
            for (int j = 0; j < a.length(); j++) {
              JSONObject o = a.getJSONObject(j);
              if (mapping.containsKey(o.optString("file")))
                o.put("file", mapping.get(o.getString("file")));
              if (mapping.containsKey(o.optString("source")))
                o.put("source", mapping.get(o.getString("source")));
              if (key.equals("audio") && o.optString("state").equals("recording"))
                o.put("state", "interrupted");
            }
          }
        }
        s.merge(additions);
        return add.length();
      } catch (Exception e) {
        Set<String> live = Store.referenced(s.snapshot());
        for (File f : copied) if (!live.contains(f.getName())) f.delete();
        throw e;
      }
    } finally {
      deleteTree(stage);
    }
  }

  static void deleteTree(File f) {
    if (f.isDirectory()) {
      File[] a = f.listFiles();
      if (a != null) for (File x : a) deleteTree(x);
    }
    f.delete();
  }

  public static File csv(Context c) throws Exception {
    Store s = Store.get(c);
    File f = new File(c.getCacheDir(), "meishinote.csv");
    try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
      w.write('\uFEFF');
      String[] head = {
        "氏名", "ふりがな", "会社・組織", "部署", "肩書き", "メール", "電話", "携帯", "FAX", "郵便番号", "住所", "Web", "タグ",
        "交換日", "交換場所", "ジャンル・業種", "営業時間", "定休日"
      };
      for (String h : head) w.write(cell(h) + ",");
      w.write("\"メモ\",\"画像ファイル\",\"録音ファイル\",\"確認状態\",\"種類\",\"年賀状\"\r\n");
      JSONArray a = s.snapshot().getJSONArray("people");
      for (int i = 0; i < a.length(); i++) {
        JSONObject p = a.getJSONObject(i);
        if (p.optString("kind").equals(Nenga.INBOX)) continue; // not sorted into a person yet
        for (String k : Store.FIELDS) w.write(cell(p.optString(k)) + ",");
        StringBuilder notes = new StringBuilder();
        JSONArray ns = p.getJSONArray("notes");
        for (int j = 0; j < ns.length(); j++) {
          JSONObject n = ns.getJSONObject(j);
          notes
              .append(
                  new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN)
                      .format(new Date(n.getLong("time"))))
              .append(" ")
              .append(n.optString("text"))
              .append("\n");
        }
        w.write(
            cell(notes.toString())
                + ","
                + cell(paths(p.getJSONArray("pages")))
                + ","
                + cell(paths(p.getJSONArray("audio")))
                + ","
                + cell(p.optString("status"))
                + ","
                + cell(p.optString("kind").equals("shop") ? "お店" : "人")
                + ","
                + cell(greetings(p))
                + "\r\n");
      }
    }
    return f;
  }

  static String paths(JSONArray a) throws Exception {
    List<String> r = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) r.add("media/" + a.getJSONObject(i).getString("file"));
    return String.join("\n", r);
  }

  // "2027 届 01/02 / 2027 送 12/20" — a compact history for the spreadsheet.
  static String greetings(JSONObject p) throws Exception {
    JSONArray a = p.optJSONArray("greetings");
    if (a == null) return "";
    List<String> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject g = a.getJSONObject(i);
      out.add(g.optInt("year") + " " + (g.optString("dir").equals("sent") ? "送" : "届") + " " + g.optString("date"));
    }
    Collections.sort(out);
    return String.join(" / ", out);
  }

  static String cell(String s) {
    if (s.matches("(?s)^[\\s]*[=+@-].*")) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }
}
