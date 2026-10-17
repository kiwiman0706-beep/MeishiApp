package jp.yaegaki.meishinote;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

// Imports a ZIP of one CSV plus card images (format: docs/IMPORT_FORMAT.md), e.g. built
// from an Eight export. Header names are matched loosely so a lightly edited export works.
public final class BulkImport {
  static final int MAX_ROWS = 20000;

  // Normalised header → target. Several columns may feed one field (joined by newlines).
  static final Map<String, String> HEADERS = new HashMap<>();

  static void alias(String target, String... names) {
    for (String n : names) HEADERS.put(norm(n), target);
  }

  static {
    alias("name", "氏名", "名前", "フルネーム", "name", "fullname", "氏名(漢字)");
    alias("lastName", "姓", "苗字", "lastname", "familyname");
    alias("firstName", "名", "firstname", "givenname");
    alias("kana", "ふりがな", "フリガナ", "よみ", "読み", "氏名(カナ)", "氏名カナ", "氏名(ふりがな)", "kana");
    alias("lastKana", "姓(カナ)", "姓カナ", "姓よみ", "セイ", "せい", "姓(ふりがな)");
    alias("firstKana", "名(カナ)", "名カナ", "名よみ", "メイ", "めい", "名(ふりがな)");
    alias("company", "会社名", "会社", "組織", "会社・組織", "組織名", "所属", "company", "organization");
    alias("department", "部署名", "部署", "部門", "department");
    alias("title", "役職", "役職名", "肩書き", "肩書", "title", "jobtitle", "position");
    alias("email", "e-mail", "email", "メール", "メールアドレス", "mail", "eメール");
    alias("phone", "電話番号", "電話", "tel", "tel会社", "tel部門", "tel直通", "会社電話", "代表電話", "直通電話", "phone");
    alias("mobile", "携帯電話", "携帯", "携帯番号", "mobile", "cellphone");
    alias("fax", "fax", "fax番号", "ファックス");
    alias("postal", "郵便番号", "〒", "zip", "postalcode");
    alias("address", "住所", "所在地", "address", "都道府県", "市区町村", "住所1", "住所2", "番地", "建物名");
    alias("website", "url", "webサイト", "ウェブサイト", "ホームページ", "website", "web");
    alias("tags", "タグ", "tags", "分類", "グループ");
    alias("metDate", "名刺交換日", "交換日", "出会った日", "登録日", "metdate");
    alias("metPlace", "交換場所", "出会った場所", "場所", "metplace");
    alias("note", "メモ", "備考", "note", "notes", "コメント");
    alias("front", "表面画像", "表画像", "画像表", "表面", "画像", "画像1", "front", "image", "image1");
    alias("back", "裏面画像", "裏画像", "画像裏", "裏面", "画像2", "back", "image2");
    alias("extra", "追加画像", "画像3", "画像4", "image3", "image4");
    alias("key", "id", "名刺id", "カードid", "eightid");
  }

  static String norm(String s) {
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s　_]", "")
        .replace("（", "(")
        .replace("）", ")");
  }

  // RFC 4180 CSV: quoted fields, doubled quotes, CR/LF inside quotes.
  static List<List<String>> csv(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else quoted = false;
        } else cell.append(ch);
      } else if (ch == '"') quoted = true;
      else if (ch == ',') {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(cell.toString());
        cell.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
        if (rows.size() > MAX_ROWS + 1) throw new IllegalStateException("行数が多すぎます（" + MAX_ROWS + "件まで）");
      } else cell.append(ch);
    }
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString());
      rows.add(row);
    }
    return rows;
  }

  // Eight and Excel exports may be UTF-8 (with or without BOM) or Shift_JIS.
  static String decode(byte[] b) {
    int off = b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF ? 3 : 0;
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(b, off, b.length - off))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(b, Charset.forName("windows-31j"));
    }
  }

  static String date(String s) {
    s = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFKC);
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(\\d{4})[/年.-](\\d{1,2})[/月.-](\\d{1,2})").matcher(s);
    if (m.find())
      return String.format(
          Locale.ROOT, "%s-%02d-%02d", m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    return "";
  }

  static String sha(String s) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 12; i++) b.append(String.format("%02x", d[i]));
    return b.toString();
  }

  public static JSONObject run(Context c, Uri zipUri, boolean toContacts, Pipeline.Progress progress)
      throws Exception {
    Store store = Store.get(c);
    File zip = File.createTempFile("import-", ".zip", c.getCacheDir());
    List<File> staged = new ArrayList<>();
    JSONArray errors = new JSONArray();
    List<String> recognize = new ArrayList<>(), contact = new ArrayList<>();
    int added = 0, skipped = 0, images = 0;
    try {
      progress.update("インポートファイルを読み込み中");
      try (InputStream in = c.getContentResolver().openInputStream(zipUri);
          OutputStream out = new FileOutputStream(zip)) {
        Store.copy(in, out, 4L * 1024 * 1024 * 1024);
      }
      try (ZipFile z = new ZipFile(zip)) {
        // Entries by normalised path, so a CSV saying "images\\a.jpg" or "Images/A.JPG" still resolves.
        Map<String, ZipEntry> entries = new HashMap<>();
        ZipEntry table = null;
        int count = 0;
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
          ZipEntry en = e.nextElement();
          if (++count > 50000) throw new IOException("ZIP内のファイルが多すぎます");
          if (en.isDirectory()) continue;
          String path = en.getName().replace('\\', '/');
          if (path.startsWith("__MACOSX/")) continue;
          entries.put(path.toLowerCase(Locale.ROOT), en);
          if (path.toLowerCase(Locale.ROOT).endsWith(".csv")
              && (table == null || path.toLowerCase(Locale.ROOT).endsWith("cards.csv"))) table = en;
        }
        if (table == null) throw new IOException("ZIPの中にCSVファイルが見つかりません");
        String base = table.getName().replace('\\', '/');
        base = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "";
        String text;
        try (InputStream in = z.getInputStream(table)) {
          text = decode(Store.read(in, 64 * 1024 * 1024));
        }
        List<List<String>> rows = csv(text);
        if (rows.size() < 2) throw new IOException("CSVにデータ行がありません");
        List<String> header = rows.get(0);
        String[] map = new String[header.size()];
        boolean any = false;
        for (int i = 0; i < header.size(); i++) {
          map[i] = HEADERS.get(norm(header.get(i)));
          if (map[i] != null) any = true;
        }
        if (!any) throw new IOException("CSVの見出しを認識できませんでした。docs/IMPORT_FORMAT.mdの列名を使ってください");
        Set<String> keys = new HashSet<>();
        JSONArray people = store.snapshot().getJSONArray("people");
        for (int i = 0; i < people.length(); i++) keys.add(people.getJSONObject(i).optString("importKey"));
        Importer importer = new Importer(c);
        for (int r = 1; r < rows.size(); r++) {
          List<String> row = rows.get(r);
          if (row.size() == 1 && row.get(0).trim().isEmpty()) continue;
          progress.update("インポート中 " + r + " / " + (rows.size() - 1));
          Map<String, List<String>> v = new HashMap<>();
          for (int i = 0; i < map.length && i < row.size(); i++) {
            String val = row.get(i).trim();
            if (map[i] == null || val.isEmpty()) continue;
            v.computeIfAbsent(map[i], k -> new ArrayList<>()).add(val);
          }
          if (v.isEmpty()) continue;
          JSONObject p = Store.empty();
          for (String k : Store.FIELDS) if (v.containsKey(k)) p.put(k, String.join(k.equals("address") ? " " : "\n", v.get(k)));
          if (p.optString("name").isEmpty() && (v.containsKey("lastName") || v.containsKey("firstName")))
            p.put("name", (first(v, "lastName") + " " + first(v, "firstName")).trim());
          if (p.optString("kana").isEmpty() && (v.containsKey("lastKana") || v.containsKey("firstKana")))
            p.put("kana", (first(v, "lastKana") + " " + first(v, "firstKana")).trim());
          if (!p.optString("metDate").isEmpty()) p.put("metDate", date(p.optString("metDate")));
          String key =
              v.containsKey("key")
                  ? "id:" + first(v, "key")
                  : "row:" + sha(p.optString("name") + "|" + p.optString("company") + "|" + p.optString("email") + "|" + first(v, "front"));
          if (keys.contains(key)) {
            skipped++;
            continue;
          }
          keys.add(key);
          p.put("importKey", key);
          if (v.containsKey("note"))
            for (String n : v.get("note"))
              p.getJSONArray("notes")
                  .put(new JSONObject().put("id", UUID.randomUUID().toString()).put("time", System.currentTimeMillis()).put("text", n));
          List<Uri> pics = new ArrayList<>();
          List<String> refs = new ArrayList<>();
          for (String col : new String[] {"front", "back", "extra"}) if (v.containsKey(col)) refs.addAll(v.get(col));
          for (String ref : refs) {
            String path = (ref.startsWith("/") ? ref.substring(1) : ref).replace('\\', '/');
            ZipEntry en = entries.get((base + path).toLowerCase(Locale.ROOT));
            if (en == null) en = entries.get(path.toLowerCase(Locale.ROOT));
            if (en == null) {
              errors.put(r + "行目：画像が見つかりません（" + ref + "）");
              continue;
            }
            if (en.getSize() > 100L * 1024 * 1024) {
              errors.put(r + "行目：画像が大きすぎます（" + ref + "）");
              continue;
            }
            String ext = path.toLowerCase(Locale.ROOT).replaceAll("^.*(\\.[a-z0-9]{1,5})$", "$1");
            if (!ext.matches("\\.[a-z0-9]{1,5}")) ext = ".jpg";
            File f = new File(c.getCacheDir(), "import-" + UUID.randomUUID() + ext);
            staged.add(f);
            try (InputStream in = z.getInputStream(en);
                OutputStream out = new FileOutputStream(f)) {
              Store.copy(in, out, 100L * 1024 * 1024);
            }
            Uri u = FilesProvider.uri(c, f);
            importer.names.put(u.toString(), path.substring(path.lastIndexOf('/') + 1));
            pics.add(u);
          }
          boolean named = !p.optString("name").trim().isEmpty();
          p.put("status", named ? "confirmed" : "new");
          store.put(p);
          if (!pics.isEmpty()) {
            JSONObject res = importer.run(pics, "all", p.getString("id"), t -> {}, true);
            images += res.optInt("pages");
            JSONArray e = res.getJSONArray("errors");
            for (int k = 0; k < e.length(); k++) errors.put(r + "行目：" + e.getString(k));
            // Importer marks the target "new"; keep the CSV's own data authoritative.
            JSONObject q = store.person(p.getString("id"));
            q.put("status", named ? "confirmed" : "new");
            store.put(q);
          }
          for (File f : staged) f.delete();
          staged.clear();
          added++;
          if (!named && !pics.isEmpty()) recognize.add(p.getString("id"));
          else if (named && toContacts) contact.add(p.getString("id"));
        }
      }
      Pipeline.enqueue(store, recognize, "recognize");
      Pipeline.enqueue(store, contact, "contact");
      return new JSONObject()
          .put("added", added)
          .put("skipped", skipped)
          .put("images", images)
          .put("queued", recognize.size() + contact.size())
          .put("errors", errors);
    } finally {
      zip.delete();
      for (File f : staged) f.delete();
    }
  }

  static String first(Map<String, List<String>> v, String k) {
    return v.containsKey(k) ? v.get(k).get(0) : "";
  }
}
