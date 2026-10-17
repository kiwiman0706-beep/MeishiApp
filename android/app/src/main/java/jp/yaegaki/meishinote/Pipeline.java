package jp.yaegaki.meishinote;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.*;
import java.util.*;
import org.json.*;

// Background work queue. Each person carries its own pending step in "queue"
// ("recognize" → "contact"), so a killed process simply resumes on the next start.
public final class Pipeline {
  public interface Progress {
    void update(String text);
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  public static int pending(Store s) throws Exception {
    int n = 0;
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("queue").isEmpty()) n++;
    return n;
  }

  public static void enqueue(Store s, List<String> ids, String step) throws Exception {
    for (String id : ids) {
      JSONObject p = s.person(id);
      p.put("queue", step).remove("queueError");
      s.put(p);
    }
  }

  // Account the user picked once; usable only while it is still on the device.
  public static String contactAccount(Context c) {
    String saved = prefs(c).getString("contactAccount", "");
    if (saved.isEmpty()) return "";
    for (Account a : AccountManager.get(c).getAccountsByType("com.google"))
      if (a.name.equals(saved)) return saved;
    return "";
  }

  public static boolean canWriteContacts(Context c) {
    return c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        && c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean autoContact(Context c) {
    return prefs(c).getBoolean("autoContact", true);
  }

  // Applies a Gemini result directly (no review step). Cards with no name or with
  // reading warnings stay 要確認 so they surface in the list.
  static void apply(JSONObject p, JSONObject r) throws Exception {
    // Gemini only returns card fields, so tags / metDate / metPlace typed by the user survive.
    // Only values Gemini actually read replace what is there (e.g. names from an import's file names).
    for (String k : Store.FIELDS) if (!r.optString(k).trim().isEmpty()) p.put(k, r.optString(k));
    if (r.optString("kind").equals("shop")) p.put("kind", "shop");
    else if (r.has("kind")) p.remove("kind");
    p.put("rawText", r.optString("rawText")).put("warnings", r.optString("warnings"));
    if (p.optString("metDate").isEmpty())
      p.put(
          "metDate",
          new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(new Date(p.optLong("created"))));
    p.remove("suggestion");
    boolean ok = !p.optString("name").trim().isEmpty() && p.optString("warnings").trim().isEmpty();
    p.put("status", ok ? "confirmed" : "review");
  }

  public static class Summary {
    int read, registered, updated, failed, review, nenga;
    boolean halted;
    final List<String> errors = new ArrayList<>();

    public String text() {
      StringBuilder b = new StringBuilder();
      if (read > 0) b.append("読み取り ").append(read).append("件");
      if (nenga > 0) b.append(b.length() > 0 ? " / " : "").append("年賀状 ").append(nenga).append("通");
      if (registered > 0) b.append(b.length() > 0 ? " / " : "").append("連絡先に登録 ").append(registered).append("件");
      if (updated > 0) b.append(b.length() > 0 ? " / " : "").append("連絡先を更新 ").append(updated).append("件");
      if (review > 0) b.append(b.length() > 0 ? " / " : "").append("要確認 ").append(review).append("件");
      if (failed > 0) b.append(b.length() > 0 ? " / " : "").append("失敗 ").append(failed).append("件");
      if (halted) b.append("\n利用上限または認証エラーのため一時停止しました。");
      return b.length() == 0 ? "処理する名刺はありませんでした" : b.toString();
    }
  }

  public static Summary run(Context c, Progress progress, Runnable changed) throws Exception {
    Store s = Store.get(c);
    Summary sum = new Summary();
    Gemini g = null;
    Set<String> tried = new HashSet<>();
    while (true) {
      JSONObject next = null;
      JSONArray a = s.snapshot().getJSONArray("people");
      int left = 0;
      for (int i = 0; i < a.length(); i++) {
        JSONObject p = a.getJSONObject(i);
        if (p.optString("queue").isEmpty() || tried.contains(p.getString("id") + p.optString("queue"))) continue;
        left++;
        if (next == null) next = p;
      }
      if (next == null) break;
      String id = next.getString("id"), step = next.optString("queue");
      tried.add(id + step);
      String label = next.optString("name").isEmpty() ? "名刺" : next.optString("name");
      try {
        if (step.equals("recognize")) {
          progress.update("読み取り中（残り" + left + "件）" + label);
          if (g == null) g = new Gemini(c);
          JSONObject result = g.recognize(next);
          JSONObject p = s.person(id);
          apply(p, result);
          boolean contact =
              autoContact(c)
                  && !p.optString("name").trim().isEmpty()
                  && !p.optBoolean("contactUnlinked")
                  && !p.optBoolean("contactRegistered")
                  && !p.optBoolean("noAutoContact");
          if (contact) p.put("queue", "contact");
          else p.remove("queue");
          if (p.optString("status").equals("review")) sum.review++;
          s.put(p);
          sum.read++;
        } else if (step.equals("nenga")) {
          progress.update("年賀状を読み取り中（残り" + left + "件）");
          if (g == null) g = new Gemini(c);
          Nenga.file(s, id, g.recognizeNenga(next));
          sum.nenga++;
        } else if (step.equals("contact")) {
          progress.update("連絡先に登録中 " + label);
          JSONObject p = s.person(id);
          if (Contacts.registered(c, p)) {
            if (!canWriteContacts(c)) throw new IOException("連絡先の権限がありません");
            Contacts.update(c, p);
            sum.updated++;
          } else {
            String account = contactAccount(c);
            if (account.isEmpty() || !canWriteContacts(c)) {
              p.remove("queue");
              p.put("queueError", "登録先のGoogleアカウントが未設定です");
              s.put(p);
              changed.run();
              continue;
            }
            String dup = Contacts.duplicate(c, p);
            if (!dup.isEmpty()) p.put("contactDuplicate", dup);
            Contacts.add(c, p, account, prefs(c).getString("contactPhoto", "card"));
            sum.registered++;
          }
          p = s.person(id);
          p.remove("queue");
          s.put(p);
        } else {
          JSONObject p = s.person(id);
          p.remove("queue");
          s.put(p);
        }
      } catch (Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("HTTP 429") || m.contains("HTTP 401") || m.contains("HTTP 403") || m.contains("APIキー")) {
          // Leave the queue intact: the next run (after the quota resets or the key is fixed) resumes.
          sum.halted = true;
          sum.errors.add(m);
          break;
        }
        sum.failed++;
        sum.errors.add(label + ": " + m);
        try {
          JSONObject p = s.person(id);
          p.remove("queue");
          p.put("queueError", m);
          s.put(p);
        } catch (Exception ignored) {
          // The person was deleted while queued.
        }
      }
      changed.run();
    }
    return sum;
  }

  // ---- Google Drive folder import ----------------------------------------------

  public static JSONObject driveImport(Context c, Drive d, Progress progress) throws Exception {
    SharedPreferences prefs = prefs(c);
    String folder = prefs.getString("driveFolderId", "");
    if (folder.isEmpty()) throw new IOException("先にメニューからGoogle Driveのフォルダを設定してください");
    String mode = prefs.getString("driveMode", "file");
    progress.update("Google Driveを確認中");
    JSONArray files = d.cards(folder);
    JSONArray ids = new JSONArray(), errors = new JSONArray();
    int pages = 0, skipped = 0, moved = 0;
    JSONObject out = new JSONObject();
    if (files.length() == 0)
      return out.put("ids", ids).put("pages", 0).put("skipped", 0).put("moved", 0).put("errors", errors);
    if (files.length() > 200) throw new IOException("1回の取り込みは200ファイルまでです。フォルダ内を減らしてください");
    String done = d.subfolder(folder, Drive.DONE_FOLDER);
    Importer importer = new Importer(c);
    // Pairing across files needs the whole batch at once; every other mode goes file by file
    // so each file is moved only after its own import succeeded.
    boolean perFile = !mode.equals("pair") && !mode.equals("all");
    List<File> staged = new ArrayList<>();
    List<Uri> uris = new ArrayList<>();
    List<JSONObject> metas = new ArrayList<>();
    try {
      for (int i = 0; i < files.length(); i++) {
        JSONObject f = files.getJSONObject(i);
        String name = f.optString("name");
        progress.update("Google Driveから取得中 " + (i + 1) + " / " + files.length() + "\n" + name);
        String ext = name.toLowerCase(Locale.ROOT).replaceAll("^.*(\\.[a-z0-9]{1,5})$", "$1");
        if (!ext.matches("\\.[a-z0-9]{1,5}"))
          ext = f.optString("mimeType").equals("application/pdf") ? ".pdf" : ".jpg";
        File local = new File(c.getCacheDir(), "drive-" + UUID.randomUUID() + ext);
        staged.add(local);
        try {
          d.download(f.getString("id"), local);
        } catch (Exception e) {
          errors.put(name + ": " + e.getMessage());
          continue;
        }
        Uri u = FilesProvider.uri(c, local);
        importer.names.put(u.toString(), name);
        if (!perFile) {
          uris.add(u);
          metas.add(f);
          continue;
        }
        JSONObject r = importer.run(Collections.singletonList(u), "file", "", t -> {}, true);
        JSONArray e = r.getJSONArray("errors");
        for (int k = 0; k < e.length(); k++) errors.put(e.get(k));
        for (int k = 0; k < r.getJSONArray("ids").length(); k++) ids.put(r.getJSONArray("ids").get(k));
        pages += r.optInt("pages");
        skipped += r.optInt("skipped");
        if (e.length() == 0) moved += moveDone(d, f, folder, done, errors);
      }
      if (!perFile && errors.length() > 0)
        throw new IOException(
            "組み合わせがずれないよう、この回の取り込みを中止しました（Driveのファイルは移動していません）。\n" + errors);
      if (!perFile && !uris.isEmpty()) {
        JSONObject r = importer.run(uris, mode, "", progress::update, true);
        ids = r.getJSONArray("ids");
        pages = r.optInt("pages");
        skipped = r.optInt("skipped");
        JSONArray e = r.getJSONArray("errors");
        for (int k = 0; k < e.length(); k++) errors.put(e.get(k));
        // Importer aborts the whole pair batch on any bad file, so reaching here means all saved.
        for (JSONObject f : metas) moved += moveDone(d, f, folder, done, errors);
      }
    } finally {
      for (File f : staged) f.delete();
    }
    return out.put("ids", ids).put("pages", pages).put("skipped", skipped).put("moved", moved).put("errors", errors);
  }

  static int moveDone(Drive d, JSONObject f, String from, String to, JSONArray errors) {
    try {
      d.move(f.getString("id"), from, to);
      return 1;
    } catch (Exception e) {
      errors.put(f.optString("name") + "：取り込みましたが「" + Drive.DONE_FOLDER + "」へ移動できませんでした（" + e.getMessage() + "）");
      return 0;
    }
  }
}
