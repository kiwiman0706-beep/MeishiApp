package jp.yaegaki.meishinote;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;

// Person record ⇄ Obsidian Markdown (docs/THETA_VAULT_SPEC.md). Pure Java so it is unit-tested
// on the JVM. Frontmatter carries the structured data; "## メモ" carries notes; the image embeds
// and the 年賀状 / 録音 / 全文 sections are generated. Unknown frontmatter keys and any other body
// text are kept in vaultExtra / vaultFree and written back untouched.
public final class VaultCodec {
  public static final String APP_DIR = "Meishi";
  public static final String FILES = APP_DIR + "/_files/";
  static final String H_NOTES = "## メモ", H_MAIL = "## 年賀状・お便り・書類", H_AUDIO = "## 録音", H_TEXT = "## 名刺の全文";
  static final List<String> MANAGED = Arrays.asList(H_NOTES, H_MAIL, H_AUDIO, H_TEXT);
  static final String FREE_MARK = "<!-- ここから下は自由に書けます（アプリは変更しません） -->";

  // Frontmatter keys this app owns, in output order.
  static final String[] ORDER = {
    "theta", "rev", "id", "name", "kana", "company", "department", "title", "email", "phone", "mobile", "fax",
    "postal", "address", "website", "genre", "hours", "holidays", "tags", "met", "met_place", "status",
    "contact_registered", "calendar_registered", "no_auto_contact", "import_key", "not_duplicate", "geo",
    "warnings", "created", "updated", "pages", "originals", "portrait", "logo", "audio", "greetings"
  };
  static final Set<String> OWN = new HashSet<>(Arrays.asList(ORDER));
  static final String[] LISTS = {"email", "phone", "mobile", "fax"};

  // ---- time -----------------------------------------------------------------------

  static String iso(long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    return f.format(new Date(ms));
  }

  static long ms(Object v, long fallback) {
    if (v instanceof Number) return ((Number) v).longValue();
    if (v == null) return fallback;
    String s = v.toString().trim();
    for (String pat : new String[] {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd"}) {
      try {
        SimpleDateFormat f = new SimpleDateFormat(pat, Locale.ROOT);
        f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        return f.parse(s).getTime();
      } catch (Exception ignored) {
      }
    }
    return fallback;
  }

  static String noteTime(long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    return f.format(new Date(ms));
  }

  // ---- media paths ----------------------------------------------------------------

  public static String path(String localName) {
    return FILES + localName;
  }

  // Vault path → local media name; null if it is not one of ours (or unsafe).
  public static String local(Object path) {
    if (path == null) return null;
    String p = path.toString().trim().replace('\\', '/');
    if (p.startsWith("[[") && p.endsWith("]]")) p = p.substring(2, p.length() - 2);
    if (p.contains("|")) p = p.substring(0, p.indexOf('|'));
    String name = p.substring(p.lastIndexOf('/') + 1);
    return name.matches("[A-Za-z0-9_.-]+") && !name.contains("..") ? name : null;
  }

  // ---- render ---------------------------------------------------------------------

  static List<String> split(String s, String re) {
    List<String> out = new ArrayList<>();
    for (String x : s.split(re)) if (!x.trim().isEmpty()) out.add(x.trim());
    return out;
  }

  static String plain(String s) {
    return s == null ? "" : s;
  }

  public static String render(JSONObject p) throws Exception {
    LinkedHashMap<String, Object> fm = new LinkedHashMap<>();
    boolean shop = p.optString("kind").equals("shop");
    fm.put("theta", shop ? "meishi/shop@1" : "meishi/person@1");
    fm.put("id", p.getString("id"));
    put(fm, "name", p.optString("name"));
    put(fm, "kana", p.optString("kana"));
    if (!p.optString("company").isEmpty()) fm.put("company", "[[" + p.optString("company") + "]]");
    for (String k : new String[] {"department", "title"}) put(fm, k, p.optString(k));
    for (String k : LISTS) {
      List<String> v = split(p.optString(k), "\n");
      if (!v.isEmpty()) fm.put(k, v);
    }
    for (String k : new String[] {"postal", "address", "website", "genre", "hours", "holidays"}) put(fm, k, p.optString(k));
    List<String> tags = split(p.optString("tags"), "[、,，\n]");
    if (!tags.isEmpty()) fm.put("tags", tags);
    put(fm, "met", p.optString("metDate"));
    put(fm, "met_place", p.optString("metPlace"));
    put(fm, "status", p.optString("status"));
    if (p.optBoolean("contactRegistered")) fm.put("contact_registered", true);
    if (p.optBoolean("calendarRegistered")) fm.put("calendar_registered", true);
    if (p.optBoolean("noAutoContact")) fm.put("no_auto_contact", true);
    put(fm, "import_key", p.optString("importKey"));
    JSONArray nd = p.optJSONArray("notDuplicate");
    if (nd != null && nd.length() > 0) fm.put("not_duplicate", toList(nd));
    JSONObject geo = p.optJSONObject("geo");
    if (geo != null) {
      LinkedHashMap<String, Object> g = new LinkedHashMap<>();
      if (geo.has("lat")) g.put("lat", geo.optDouble("lat"));
      if (geo.has("lng")) g.put("lng", geo.optDouble("lng"));
      g.put("address", geo.optString("q"));
      fm.put("geo", g);
    }
    put(fm, "warnings", p.optString("warnings"));
    fm.put("created", iso(p.optLong("created", System.currentTimeMillis())));
    fm.put("updated", iso(p.optLong("updated", System.currentTimeMillis())));
    List<String> pages = new ArrayList<>();
    JSONArray pa = p.optJSONArray("pages");
    for (int i = 0; pa != null && i < pa.length(); i++) pages.add(path(pa.getJSONObject(i).getString("file")));
    if (!pages.isEmpty()) fm.put("pages", pages);
    List<Object> originals = new ArrayList<>();
    JSONArray so = p.optJSONArray("sources");
    for (int i = 0; so != null && i < so.length(); i++) {
      JSONObject s = so.getJSONObject(i);
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      m.put("file", path(s.getString("file")));
      if (!s.optString("name").isEmpty()) m.put("name", s.optString("name"));
      if (!s.optString("hash").isEmpty()) m.put("sha256", s.optString("hash"));
      originals.add(m);
    }
    if (!originals.isEmpty()) fm.put("originals", originals);
    for (String k : new String[] {"portrait", "logo"}) if (!p.optString(k).isEmpty()) fm.put(k, path(p.getString(k)));
    JSONArray au = p.optJSONArray("audio");
    List<Object> audio = new ArrayList<>();
    for (int i = 0; au != null && i < au.length(); i++) {
      JSONObject a = au.getJSONObject(i);
      if (a.optString("state").equals("recording")) continue;
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      m.put("file", path(a.getString("file")));
      m.put("time", iso(a.optLong("time")));
      m.put("seconds", a.optInt("seconds"));
      audio.add(m);
    }
    if (!audio.isEmpty()) fm.put("audio", audio);
    JSONArray gr = p.optJSONArray("greetings");
    List<Object> greetings = new ArrayList<>();
    for (int i = 0; gr != null && i < gr.length(); i++) {
      JSONObject g = gr.getJSONObject(i);
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      m.put("id", g.optString("id"));
      m.put("year", g.optInt("year"));
      m.put("dir", g.optString("dir"));
      put(m, "date", g.optString("date"));
      m.put("kind", g.optString("kind", "nenga"));
      put(m, "title", g.optString("title"));
      put(m, "senders", g.optString("senders"));
      put(m, "message", g.optString("message"));
      JSONArray fs = g.optJSONArray("files");
      List<String> files = new ArrayList<>(), orig = new ArrayList<>();
      for (int k = 0; fs != null && k < fs.length(); k++)
        (fs.getJSONObject(k).optBoolean("original") ? orig : files).add(path(fs.getJSONObject(k).getString("file")));
      if (!files.isEmpty()) m.put("files", files);
      if (!orig.isEmpty()) m.put("originals", orig);
      greetings.add(m);
    }
    if (!greetings.isEmpty()) fm.put("greetings", greetings);
    // Keys other tools added survive.
    JSONObject extra = p.optJSONObject("vaultExtra");
    if (extra != null)
      for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
        String k = it.next();
        if (!OWN.contains(k)) fm.put(k, fromJson(extra.get(k)));
      }

    StringBuilder b = new StringBuilder("---\n").append(dump(fm)).append("---\n\n");
    if (!pages.isEmpty()) {
      for (String pg : pages) b.append("![[").append(pg).append("|360]] ");
      b.setLength(b.length() - 1);
      b.append("\n\n");
    }
    JSONArray notes = p.optJSONArray("notes");
    b.append(H_NOTES).append("\n");
    List<JSONObject> ns = new ArrayList<>();
    for (int i = 0; notes != null && i < notes.length(); i++) ns.add(notes.getJSONObject(i));
    ns.sort(Comparator.comparingLong(n -> n.optLong("time")));
    for (JSONObject n : ns) {
      String[] lines = n.optString("text").split("\n", -1);
      b.append("- ").append(noteTime(n.optLong("time"))).append(" ").append(lines[0]);
      for (int i = 1; i < lines.length; i++) b.append("\n  ").append(lines[i]);
      b.append(" ^").append(blockId(n.optString("id"))).append("\n");
    }
    b.append("\n");
    if (!greetings.isEmpty()) {
      b.append(H_MAIL).append("\n| 年 | 日付 | 届いた/送った | 種類 | 件名・差出人 |\n|---|---|---|---|---|\n");
      List<JSONObject> gs = new ArrayList<>();
      for (int i = 0; i < gr.length(); i++) gs.add(gr.getJSONObject(i));
      gs.sort((x, y) -> y.optString("date").compareTo(x.optString("date")));
      for (JSONObject g : gs)
        b.append("| ").append(g.optInt("year")).append(" | ").append(g.optString("date")).append(" | ")
            .append(g.optString("dir").equals("sent") ? "送った" : "届いた").append(" | ")
            .append(KIND_LABEL.getOrDefault(g.optString("kind", "nenga"), "お便り")).append(" | ")
            .append(cell(g.optString("title").isEmpty() ? g.optString("senders").replace('\n', '・') : g.optString("title")))
            .append(" |\n");
      b.append("\n");
    }
    if (!audio.isEmpty()) {
      b.append(H_AUDIO).append("\n");
      for (Object o : audio) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        int sec = ((Number) m.get("seconds")).intValue();
        b.append("- ![[").append(m.get("file")).append("]] ").append(m.get("time").toString().substring(0, 16).replace('T', ' '))
            .append("（").append(sec / 60).append("分").append(sec % 60).append("秒）\n");
      }
      b.append("\n");
    }
    if (!p.optString("rawText").trim().isEmpty())
      b.append(H_TEXT).append("\n```text\n").append(p.optString("rawText").replace("```", "ˋˋˋ").trim()).append("\n```\n\n");
    b.append(FREE_MARK).append("\n");
    String free = p.optString("vaultFree");
    if (!free.trim().isEmpty()) b.append("\n").append(free.trim()).append("\n");
    // "rev" fingerprints the text exactly as the app wrote it; if the file no longer matches,
    // it was edited elsewhere (Obsidian), and the file's own time says when.
    String text = b.toString();
    return text.replaceFirst("\n", "\nrev: " + rev(text) + "\n");
  }

  static String rev(String textWithoutRev) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(textWithoutRev.getBytes("UTF-8"));
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < 6; i++) s.append(String.format("%02x", d[i]));
      return s.toString();
    } catch (Exception e) {
      return "";
    }
  }

  // True when the note is no longer exactly what the app last wrote.
  public static boolean editedOutside(String md) {
    md = md.replace("\r\n", "\n");
    Matcher m = Pattern.compile("\nrev: ([0-9a-f]+)\n").matcher(md);
    if (!m.find()) return true;
    return !m.group(1).equals(rev(md.substring(0, m.start()) + "\n" + md.substring(m.end())));
  }

  static final Map<String, String> KIND_LABEL = new HashMap<>();

  static {
    String[][] k = {
      {"nenga", "年賀状"}, {"mochu", "喪中"}, {"kanchu", "寒中見舞い"}, {"shochu", "暑中見舞い"},
      {"xmas", "クリスマスカード"}, {"letter", "手紙"}, {"other", "はがき"}, {"doc", "書類"}
    };
    for (String[] x : k) KIND_LABEL.put(x[0], x[1]);
  }

  static String cell(String s) {
    return s.replace("|", "｜").replace("\n", " ");
  }

  // Obsidian block ids allow letters, digits and hyphens.
  static String blockId(String id) {
    String b = id.replaceAll("[^A-Za-z0-9-]", "");
    return b.isEmpty() ? "n" : b;
  }

  static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isEmpty()) m.put(k, v);
  }

  static String dump(Map<String, Object> fm) {
    DumpSettings settings =
        DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setDefaultScalarStyle(ScalarStyle.PLAIN)
            .setUseUnicodeEncoding(true)
            .setWidth(100000)
            .setSplitLines(false)
            .build();
    return new Dump(settings).dumpToString(fm);
  }

  static List<Object> toList(JSONArray a) throws Exception {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) out.add(fromJson(a.get(i)));
    return out;
  }

  static Object fromJson(Object v) throws Exception {
    if (v instanceof JSONObject) {
      LinkedHashMap<String, Object> m = new LinkedHashMap<>();
      JSONObject j = (JSONObject) v;
      for (Iterator<String> it = j.keys(); it.hasNext(); ) {
        String k = it.next();
        m.put(k, fromJson(j.get(k)));
      }
      return m;
    }
    if (v instanceof JSONArray) return toList((JSONArray) v);
    if (v == JSONObject.NULL) return null;
    return v;
  }

  static Object toJson(Object v) throws Exception {
    if (v instanceof Map) {
      JSONObject j = new JSONObject();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) j.put(String.valueOf(e.getKey()), toJson(e.getValue()));
      return j;
    }
    if (v instanceof List) {
      JSONArray a = new JSONArray();
      for (Object o : (List<?>) v) a.put(toJson(o));
      return a;
    }
    if (v == null) return JSONObject.NULL;
    if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
    return v.toString();
  }

  // ---- parse ----------------------------------------------------------------------

  static String str(Object v) {
    if (v == null) return "";
    if (v instanceof List) {
      List<String> out = new ArrayList<>();
      for (Object o : (List<?>) v) if (o != null && !o.toString().trim().isEmpty()) out.add(o.toString().trim());
      return String.join("\n", out);
    }
    return v.toString().trim();
  }

  static String unlink(String s) {
    s = s.trim();
    if (s.startsWith("[[") && s.endsWith("]]")) s = s.substring(2, s.length() - 2);
    if (s.contains("|")) s = s.substring(s.indexOf('|') + 1);
    return s;
  }

  static final Pattern NOTE = Pattern.compile("^[-*]\\s+(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{1,2}:\\d{2}))?\\s*(.*)$");
  static final Pattern BLOCK = Pattern.compile("\\s\\^([A-Za-z0-9-]+)\\s*$");

  public static JSONObject parse(String md) throws Exception {
    return parse(md, false);
  }

  // allowNewId: a note someone wrote by hand in Obsidian (no id yet) becomes a new person.
  public static JSONObject parse(String md, boolean allowNewId) throws Exception {
    md = md.replace("\r\n", "\n");
    if (md.startsWith("﻿")) md = md.substring(1);
    if (!md.startsWith("---\n")) throw new IllegalArgumentException("frontmatterがありません");
    int end = md.indexOf("\n---", 4);
    if (end < 0) throw new IllegalArgumentException("frontmatterが閉じていません");
    String yaml = md.substring(4, end + 1);
    int bodyStart = md.indexOf('\n', end + 4);
    String body = bodyStart < 0 ? "" : md.substring(bodyStart + 1);
    Object loaded = new Load(LoadSettings.builder().build()).loadFromString(yaml);
    if (!(loaded instanceof Map)) throw new IllegalArgumentException("frontmatterの形式が不正です");
    Map<?, ?> fm = (Map<?, ?>) loaded;

    JSONObject p = new JSONObject();
    String id = str(fm.get("id"));
    boolean newId = id.isEmpty() && allowNewId;
    if (newId) id = UUID.randomUUID().toString();
    if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("idが不正です");
    p.put("id", id);
    if (newId) p.put("vaultNewId", true); // tells sync to write the id back into the note
    if (String.valueOf(fm.get("theta")).startsWith("meishi/shop")) p.put("kind", "shop");
    for (String k : Store.FIELDS) p.put(k, "");
    p.put("name", str(fm.get("name")));
    p.put("kana", str(fm.get("kana")));
    p.put("company", unlink(str(fm.get("company"))));
    for (String k : new String[] {"department", "title", "postal", "address", "website", "genre", "hours", "holidays"})
      p.put(k, str(fm.get(k)));
    for (String k : LISTS) p.put(k, str(fm.get(k)));
    p.put("tags", str(fm.get("tags")).replace("\n", "、"));
    String met = str(fm.get("met"));
    p.put("metDate", met.matches("\\d{4}-\\d{2}-\\d{2}.*") ? met.substring(0, 10) : "");
    p.put("metPlace", str(fm.get("met_place")));
    String status = str(fm.get("status"));
    p.put("status", Arrays.asList("new", "review", "confirmed").contains(status) ? status : p.optString("name").isEmpty() ? "new" : "confirmed");
    if (Boolean.TRUE.equals(fm.get("contact_registered"))) p.put("contactRegistered", true);
    if (Boolean.TRUE.equals(fm.get("calendar_registered"))) p.put("calendarRegistered", true);
    if (Boolean.TRUE.equals(fm.get("no_auto_contact"))) p.put("noAutoContact", true);
    if (!str(fm.get("import_key")).isEmpty()) p.put("importKey", str(fm.get("import_key")));
    if (fm.get("not_duplicate") instanceof List) {
      JSONArray a = new JSONArray();
      for (Object o : (List<?>) fm.get("not_duplicate")) if (o != null) a.put(o.toString());
      p.put("notDuplicate", a);
    }
    if (fm.get("geo") instanceof Map) {
      Map<?, ?> g = (Map<?, ?>) fm.get("geo");
      JSONObject geo = new JSONObject().put("q", str(g.get("address")));
      if (g.get("lat") instanceof Number && g.get("lng") instanceof Number)
        geo.put("lat", ((Number) g.get("lat")).doubleValue()).put("lng", ((Number) g.get("lng")).doubleValue());
      p.put("geo", geo);
    }
    if (!str(fm.get("warnings")).isEmpty()) p.put("warnings", str(fm.get("warnings")));
    long now = System.currentTimeMillis();
    p.put("created", ms(fm.get("created"), now));
    p.put("updated", ms(fm.get("updated"), p.getLong("created")));
    JSONArray pages = new JSONArray();
    if (fm.get("pages") instanceof List)
      for (Object o : (List<?>) fm.get("pages")) {
        String l = local(o);
        if (l != null) pages.put(new JSONObject().put("file", l).put("name", l));
      }
    p.put("pages", pages);
    JSONArray sources = new JSONArray();
    if (fm.get("originals") instanceof List)
      for (Object o : (List<?>) fm.get("originals")) {
        Map<?, ?> m = o instanceof Map ? (Map<?, ?>) o : Collections.singletonMap("file", o);
        String l = local(m.get("file"));
        if (l == null) continue;
        JSONObject s = new JSONObject().put("file", l).put("name", str(m.get("name")).isEmpty() ? l : str(m.get("name")));
        if (!str(m.get("sha256")).isEmpty()) s.put("hash", str(m.get("sha256")));
        s.put("mime", l.endsWith(".pdf") ? "application/pdf" : "image/jpeg");
        sources.put(s);
      }
    p.put("sources", sources);
    for (String k : new String[] {"portrait", "logo"}) {
      String l = local(fm.get(k));
      if (l != null) p.put(k, l);
    }
    JSONArray audio = new JSONArray();
    if (fm.get("audio") instanceof List)
      for (Object o : (List<?>) fm.get("audio")) {
        if (!(o instanceof Map)) continue;
        Map<?, ?> m = (Map<?, ?>) o;
        String l = local(m.get("file"));
        if (l == null) continue;
        audio.put(
            new JSONObject()
                .put("id", l)
                .put("file", l)
                .put("time", ms(m.get("time"), now))
                .put("seconds", m.get("seconds") instanceof Number ? ((Number) m.get("seconds")).intValue() : 0)
                .put("state", "saved"));
      }
    p.put("audio", audio);
    JSONArray greetings = new JSONArray();
    if (fm.get("greetings") instanceof List)
      for (Object o : (List<?>) fm.get("greetings")) {
        if (!(o instanceof Map)) continue;
        Map<?, ?> m = (Map<?, ?>) o;
        JSONObject g = new JSONObject();
        g.put("id", str(m.get("id")).isEmpty() ? UUID.nameUUIDFromBytes(m.toString().getBytes("UTF-8")).toString() : str(m.get("id")));
        g.put("year", m.get("year") instanceof Number ? ((Number) m.get("year")).intValue() : 0);
        g.put("dir", str(m.get("dir")).equals("sent") ? "sent" : "received");
        String date = str(m.get("date"));
        g.put("date", date.length() >= 10 ? date.substring(0, 10) : date);
        g.put("kind", str(m.get("kind")).isEmpty() ? "nenga" : str(m.get("kind")));
        for (String k : new String[] {"title", "senders", "message"}) if (!str(m.get(k)).isEmpty()) g.put(k, str(m.get(k)));
        JSONArray files = new JSONArray();
        for (String key : new String[] {"files", "originals"})
          if (m.get(key) instanceof List)
            for (Object f : (List<?>) m.get(key)) {
              String l = local(f);
              if (l == null) continue;
              JSONObject fo = new JSONObject().put("file", l).put("name", l);
              if (key.equals("originals")) fo.put("original", true);
              files.put(fo);
            }
        g.put("files", files);
        greetings.put(g);
      }
    if (greetings.length() > 0) p.put("greetings", greetings);
    JSONObject extra = new JSONObject();
    for (Map.Entry<?, ?> e : fm.entrySet()) {
      String k = String.valueOf(e.getKey());
      if (!OWN.contains(k)) extra.put(k, toJson(e.getValue()));
    }
    if (extra.length() > 0) p.put("vaultExtra", extra);

    // Body: managed sections are read (notes, full text) or regenerated; everything else is free text.
    JSONArray notes = new JSONArray();
    StringBuilder free = new StringBuilder();
    String section = null;
    StringBuilder text = null;
    boolean afterMark = false;
    JSONObject lastNote = null;
    for (String line : body.split("\n", -1)) {
      if (afterMark) {
        free.append(line).append("\n");
        continue;
      }
      if (line.trim().equals(FREE_MARK)) {
        afterMark = true;
        continue;
      }
      if (line.startsWith("## ")) {
        section = MANAGED.contains(line.trim()) ? line.trim() : null;
        lastNote = null;
        if (section == null) free.append(line).append("\n");
        continue;
      }
      if (section == null) {
        // The generated image row before the first heading is not user text.
        if (!(line.trim().startsWith("![[") && line.contains(FILES)) && !(line.trim().isEmpty() && free.length() == 0))
          free.append(line).append("\n");
        continue;
      }
      if (section.equals(H_NOTES)) {
        Matcher m = NOTE.matcher(line.trim());
        if (line.startsWith("- ") || line.startsWith("* ")) {
          if (!m.matches()) {
            // A bullet without a date: keep it as a note stamped now.
            String t = line.substring(2).trim();
            if (t.isEmpty()) continue;
            m = NOTE.matcher("- " + noteTime(now) + " " + t);
            m.matches();
          }
          String t = m.group(3);
          String bid = null;
          Matcher bm = BLOCK.matcher(t);
          if (bm.find()) {
            bid = bm.group(1);
            t = t.substring(0, bm.start());
          }
          long time = ms(m.group(1) + "T" + (m.group(2) == null ? "00:00" : (m.group(2).length() == 4 ? "0" + m.group(2) : m.group(2))), now);
          lastNote =
              new JSONObject()
                  .put("id", bid != null ? bid : UUID.nameUUIDFromBytes((time + t).getBytes("UTF-8")).toString())
                  .put("time", time)
                  .put("text", t.trim());
          notes.put(lastNote);
        } else if (lastNote != null && line.startsWith("  ")) {
          String t = line.substring(2);
          Matcher bm = BLOCK.matcher(t);
          if (bm.find()) {
            lastNote.put("id", bm.group(1));
            t = t.substring(0, bm.start());
          }
          lastNote.put("text", lastNote.optString("text") + "\n" + t);
        } else if (!line.trim().isEmpty()) {
          // Prose typed under the notes heading is the user's, not a note: keep it as free text.
          free.append(line).append("\n");
          lastNote = null;
        }
      } else if (section.equals(H_TEXT)) {
        if (line.startsWith("```")) {
          text = text == null ? new StringBuilder() : text;
          continue;
        }
        if (text != null) text.append(line).append("\n");
      }
    }
    p.put("notes", notes);
    if (text != null && !text.toString().trim().isEmpty()) p.put("rawText", text.toString().trim());
    String f = free.toString().trim();
    if (!f.isEmpty()) p.put("vaultFree", f);
    return p;
  }

  // "山田 太郎（サンプル株式会社）.md" — readable and safe on every OS and in Obsidian links.
  public static String fileName(JSONObject p) {
    String n = p.optString("name").trim();
    if (n.isEmpty()) n = "名称未登録";
    String c = p.optString("company").trim();
    if (!c.isEmpty() && !p.optString("kind").equals("shop")) n += "（" + c + "）";
    n = n.replaceAll("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]", " ").replaceAll("\\s+", " ").trim();
    if (n.startsWith(".")) n = "_" + n;
    if (n.length() > 80) n = n.substring(0, 80).trim();
    return n + ".md";
  }

  public static String folder(JSONObject p) {
    return p.optString("kind").equals("shop") ? "Shops" : "People";
  }
}
