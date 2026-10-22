package jp.yaegaki.meishinote;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

// Cards as vCard 3.0 (desktop/lib/vcard.js writes the same bytes), the form LINE, Gmail and the
// phone's contacts app all take: one file holding one or many contacts.
public final class VCard {
  static String esc(String s) {
    return s.replace("\\", "\\\\").replaceAll("\\r?\\n", "\\\\n").replace(",", "\\,").replace(";", "\\;");
  }

  static List<String> lines(String s) {
    List<String> out = new ArrayList<>();
    for (String x : s.split("\n")) if (!x.trim().isEmpty()) out.add(x.trim());
    return out;
  }

  static List<String> words(String s) {
    List<String> out = new ArrayList<>();
    for (String x : s.trim().split("[\\s\\u3000]+")) if (!x.isEmpty()) out.add(x);
    return out;
  }

  // Lines longer than 75 bytes continue on the next line after a space (never inside a character).
  static String fold(String line) {
    StringBuilder out = new StringBuilder(), cur = new StringBuilder();
    int bytes = 0;
    boolean first = true;
    for (int i = 0; i < line.length(); ) {
      int cp = line.codePointAt(i);
      String ch = new String(Character.toChars(cp));
      int n = ch.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + n > (first ? 75 : 74)) {
        out.append(cur).append("\r\n ");
        cur.setLength(0);
        bytes = 0;
        first = false;
      }
      cur.append(ch);
      bytes += n;
      i += Character.charCount(cp);
    }
    return out.append(cur).toString();
  }

  static String one(JSONObject p) {
    boolean shop = p.optString("kind").equals("shop");
    String name = p.optString("name").trim();
    List<String> parts = words(name), kana = words(p.optString("kana"));
    List<String> v = new ArrayList<>(Arrays.asList("BEGIN:VCARD", "VERSION:3.0"));
    if (shop || parts.size() < 2) v.add("N:" + esc(name) + ";;;;");
    else v.add("N:" + esc(parts.get(0)) + ";" + esc(String.join(" ", parts.subList(1, parts.size()))) + ";;;");
    v.add("FN:" + esc(!name.isEmpty() ? name : !p.optString("company").isEmpty() ? p.optString("company") : "名称未登録"));
    if (!shop && !kana.isEmpty()) {
      v.add("X-PHONETIC-LAST-NAME:" + esc(kana.get(0)));
      if (kana.size() > 1) v.add("X-PHONETIC-FIRST-NAME:" + esc(String.join(" ", kana.subList(1, kana.size()))));
    }
    String org = shop ? name : p.optString("company").trim(), dept = p.optString("department");
    if (!org.isEmpty() || !dept.isEmpty()) v.add("ORG:" + esc(org) + (dept.isEmpty() ? "" : ";" + esc(dept)));
    if (shop) v.add("X-ABShowAs:COMPANY");
    if (!p.optString("title").isEmpty()) v.add("TITLE:" + esc(p.optString("title")));
    for (String t : lines(p.optString("phone"))) v.add("TEL;TYPE=WORK,VOICE:" + esc(t));
    for (String t : lines(p.optString("mobile"))) v.add("TEL;TYPE=CELL:" + esc(t));
    for (String t : lines(p.optString("fax"))) v.add("TEL;TYPE=WORK,FAX:" + esc(t));
    for (String e : lines(p.optString("email"))) v.add("EMAIL;TYPE=INTERNET,WORK:" + esc(e));
    for (String u : lines(p.optString("website"))) v.add("URL:" + esc(u));
    if (!p.optString("address").isEmpty() || !p.optString("postal").isEmpty())
      v.add("ADR;TYPE=WORK:;;" + esc(p.optString("address")) + ";;;" + esc(p.optString("postal")) + ";");
    List<String> note = new ArrayList<>();
    String met = (p.optString("metDate") + " " + p.optString("metPlace")).trim();
    if (!met.isEmpty()) note.add("名刺交換：" + met);
    if (shop && !p.optString("genre").isEmpty()) note.add("ジャンル：" + p.optString("genre"));
    if (shop && !p.optString("hours").isEmpty()) note.add("営業時間：" + p.optString("hours"));
    if (shop && !p.optString("holidays").isEmpty()) note.add("定休日：" + p.optString("holidays"));
    if (!note.isEmpty()) v.add("NOTE:" + esc(String.join("\n", note)));
    List<String> tags = new ArrayList<>();
    for (String t : p.optString("tags").split("[、,，\\s]+")) if (!t.isEmpty()) tags.add(esc(t));
    if (!tags.isEmpty()) v.add("CATEGORIES:" + String.join(",", tags));
    v.add("END:VCARD");
    StringBuilder b = new StringBuilder();
    for (String l : v) b.append(fold(l)).append("\r\n");
    return b.toString();
  }

  // people → the file's text.
  public static String build(List<JSONObject> people) {
    StringBuilder b = new StringBuilder();
    for (JSONObject p : people) b.append(one(p));
    return b.toString();
  }

  // The file's name as the receiver sees it.
  public static String fileName(List<JSONObject> people) {
    if (people.size() != 1) return "名刺_" + people.size() + "件.vcf";
    JSONObject p = people.get(0);
    String n = !p.optString("name").trim().isEmpty() ? p.optString("name") : !p.optString("company").trim().isEmpty() ? p.optString("company") : "名刺";
    n = n.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    return (n.isEmpty() ? "名刺" : n) + ".vcf";
  }
}
