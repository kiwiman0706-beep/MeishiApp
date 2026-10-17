package jp.yaegaki.meishinote;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

// New Year cards (and mourning / seasonal postcards). A captured card arrives as a temporary
// person (kind "nengaInbox"); after Gemini reads the sender it is filed as a greeting on the
// matching person, or becomes a new person. Each person keeps
// greetings: [{id, year, dir: "received"|"sent", date, kind, files, senders, message}].
public final class Nenga {
  public static final String INBOX = "nengaInbox";

  static String name(String s) {
    return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC)
        .replaceAll("[\\s・･]", "")
        .toLowerCase(Locale.ROOT);
  }

  static String address(String s) {
    return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC)
        .replaceAll("〒?\\d{3}-?\\d{4}", "")
        .replaceAll("\\s", "")
        .replaceAll("[‐‑‒–—―−ー－]", "-")
        .replaceAll("(丁目|番地|番|の)", "-")
        .replaceAll("号", "")
        .replaceAll("-+", "-")
        .replaceAll("-$", "");
  }

  // The person a card is from: a unique name match (any sender in the 連名), narrowed by
  // address if several share the name; else a unique address match. null = new person.
  public static String match(JSONArray people, JSONObject r) throws Exception {
    Set<String> names = new HashSet<>();
    for (String line : r.optString("senders").split("\n")) if (name(line).length() >= 2) names.add(name(line));
    String addr = address(r.optString("address"));
    List<JSONObject> byName = new ArrayList<>(), byAddr = new ArrayList<>();
    for (int i = 0; i < people.length(); i++) {
      JSONObject p = people.getJSONObject(i);
      if (p.optString("kind").equals(INBOX) || p.optString("kind").equals("shop")) continue;
      if (names.contains(name(p.optString("name")))) byName.add(p);
      if (addr.length() >= 8 && addr.equals(address(p.optString("address")))) byAddr.add(p);
    }
    if (byName.size() == 1) return byName.get(0).getString("id");
    if (byName.size() > 1) {
      List<JSONObject> both = new ArrayList<>();
      for (JSONObject p : byName) if (byAddr.contains(p)) both.add(p);
      return both.size() == 1 ? both.get(0).getString("id") : null;
    }
    return byAddr.size() == 1 ? byAddr.get(0).getString("id") : null;
  }

  // The card's season: cards arriving from October on belong to next year's New Year.
  public static int season(long when) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(when);
    return c.get(Calendar.MONTH) >= Calendar.OCTOBER ? c.get(Calendar.YEAR) + 1 : c.get(Calendar.YEAR);
  }

  static String today(long when) {
    return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(when));
  }

  public static JSONObject greeting(JSONObject temp, JSONObject r) throws Exception {
    int year = temp.optInt("nengaYear", season(temp.optLong("created")));
    String y = r.optString("year");
    if (y.matches("\\d{4}")) {
      int v = Integer.parseInt(y);
      if (v >= 1990 && v <= 2100) year = v;
    }
    JSONArray files = new JSONArray();
    JSONArray pages = temp.getJSONArray("pages");
    for (int i = 0; i < pages.length(); i++)
      files.put(new JSONObject().put("file", pages.getJSONObject(i).getString("file")).put("name", pages.getJSONObject(i).optString("name")));
    String kind = r.optString("kind");
    if (!Arrays.asList("nenga", "mochu", "kanchu", "shochu", "other").contains(kind)) kind = "nenga";
    return new JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("year", year)
        .put("dir", "received")
        .put("date", today(temp.optLong("created", System.currentTimeMillis())))
        .put("kind", kind)
        .put("files", files)
        .put("senders", r.optString("senders"))
        .put("message", r.optString("message"));
  }

  static boolean has(JSONObject p, int year, String dir) {
    JSONArray a = p.optJSONArray("greetings");
    if (a != null)
      for (int i = 0; i < a.length(); i++) {
        JSONObject g = a.optJSONObject(i);
        if (g != null && g.optInt("year") == year && g.optString("dir").equals(dir)) return true;
      }
    return false;
  }

  // Address list for printing: everyone sent to, or heard from, this year or last year.
  public static java.io.File csv(android.content.Context c, int year) throws Exception {
    Store s = Store.get(c);
    java.io.File f = new java.io.File(c.getCacheDir(), "nenga-" + year + ".csv");
    JSONArray a = s.snapshot().getJSONArray("people");
    try (java.io.Writer w =
        new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
      w.write("\uFEFF氏名,ふりがな,会社・組織,郵便番号,住所," + year + "年 送った," + year + "年 届いた," + (year - 1) + "年 送った," + (year - 1) + "年 届いた,喪中\r\n");
      for (int i = 0; i < a.length(); i++) {
        JSONObject p = a.getJSONObject(i);
        if (p.optString("kind").equals(INBOX) || p.optString("kind").equals("shop")) continue;
        boolean any = false, mochu = false;
        for (int y : new int[] {year, year - 1})
          for (String d : new String[] {"sent", "received"}) any |= has(p, y, d);
        JSONArray gs = p.optJSONArray("greetings");
        if (gs != null)
          for (int k = 0; k < gs.length(); k++)
            if (gs.getJSONObject(k).optString("kind").equals("mochu") && gs.getJSONObject(k).optInt("year") >= year - 1) mochu = true;
        if (!any) continue;
        w.write(
            Backup.cell(p.optString("name")) + "," + Backup.cell(p.optString("kana")) + "," + Backup.cell(p.optString("company")) + ","
                + Backup.cell(p.optString("postal")) + "," + Backup.cell(p.optString("address").replace('\n', ' ')) + ","
                + (has(p, year, "sent") ? "○" : "") + "," + (has(p, year, "received") ? "○" : "") + ","
                + (has(p, year - 1, "sent") ? "○" : "") + "," + (has(p, year - 1, "received") ? "○" : "") + ","
                + (mochu ? "喪中" : "") + "\r\n");
      }
    }
    return f;
  }

  // Files the card under its person. Returns the id of the person it ended up on.
  public static String file(Store s, String tempId, JSONObject r) throws Exception {
    JSONObject temp = s.person(tempId);
    JSONObject g = greeting(temp, r);
    String target = match(s.snapshot().getJSONArray("people"), r);
    if (target != null && !target.equals(tempId)) {
      JSONObject p = s.person(target);
      JSONArray list = p.optJSONArray("greetings");
      if (list == null) p.put("greetings", list = new JSONArray());
      list.put(g);
      // Fill gaps only; never overwrite what the person's business card says.
      for (String[] k : new String[][] {{"postal", "postal"}, {"address", "address"}, {"phone", "phone"}, {"email", "email"}, {"kana", "kana"}})
        if (p.optString(k[0]).isEmpty() && !r.optString(k[1]).isEmpty()) p.put(k[0], r.optString(k[1]));
      s.put(p); // first, so the card images stay referenced when the temp record goes
      s.removeQuietly(tempId);
      return target;
    }
    String[] senders = r.optString("senders").trim().split("\n");
    temp.put("name", senders[0].trim())
        .put("kana", r.optString("kana"))
        .put("postal", r.optString("postal"))
        .put("address", r.optString("address"))
        .put("phone", r.optString("phone"))
        .put("email", r.optString("email"))
        .put("tags", temp.optString("tags").isEmpty() ? "年賀状" : temp.optString("tags"))
        .put("kind", "person")
        .put("pages", new JSONArray())
        .put("greetings", new JSONArray().put(g))
        .put("noAutoContact", true)
        .put("rawText", r.optString("rawText"))
        .put("warnings", r.optString("warnings"));
    temp.remove("nengaYear");
    temp.remove("queue");
    temp.put("status", senders[0].trim().isEmpty() || !r.optString("warnings").trim().isEmpty() ? "review" : "confirmed");
    s.put(temp);
    return tempId;
  }
}
