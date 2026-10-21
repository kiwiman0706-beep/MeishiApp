package jp.yaegaki.meishinote;

import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;

// Company logos cut out of the card's front page (Gemini says where), shared by everyone at the
// same company who has no logo yet. A logo picked by hand is never replaced.
public final class Logo {
  // "ymin,xmin,ymax,xmax" on a 0–1000 grid → pixel rectangle with a little margin, or null.
  static Rect box(String s, int w, int h) {
    String[] v = s == null ? new String[0] : s.replaceAll("[\\[\\]\\s]", "").split(",");
    if (v.length != 4) return null;
    int[] n = new int[4];
    try {
      for (int i = 0; i < 4; i++) n[i] = Math.max(0, Math.min(1000, Integer.parseInt(v[i])));
    } catch (NumberFormatException e) {
      return null;
    }
    int bh = n[2] - n[0], bw = n[3] - n[1];
    // Nothing, a speck, or most of the card is not a logo.
    if (bh < 15 || bw < 15 || bh * bw > 600_000) return null;
    int pad = Math.max(bh, bw) / 12;
    int top = Math.max(0, n[0] - pad), left = Math.max(0, n[1] - pad);
    int bottom = Math.min(1000, n[2] + pad), right = Math.min(1000, n[3] + pad);
    return new Rect(left * w / 1000, top * h / 1000, right * w / 1000, bottom * h / 1000);
  }

  // Saves the cut-out as a new media file; "" when there is no usable logo.
  public static String crop(Store s, JSONObject p, String boxText) throws Exception {
    JSONArray pages = p.getJSONArray("pages");
    if (pages.length() == 0) return "";
    Bitmap b = Importer.image(s.mediaFile(pages.getJSONObject(0).getString("file")), 1600);
    try {
      Rect r = box(boxText, b.getWidth(), b.getHeight());
      if (r == null || r.width() < 8 || r.height() < 8) return "";
      float scale = Math.min(1f, 512f / Math.max(r.width(), r.height()));
      int w = Math.max(1, Math.round(r.width() * scale)), h = Math.max(1, Math.round(r.height() * scale));
      Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(out);
      canvas.drawColor(Color.WHITE);
      canvas.drawBitmap(b, r, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
      String name = UUID.randomUUID() + ".jpg";
      try (OutputStream o = new FileOutputStream(s.mediaFile(name))) {
        if (!out.compress(Bitmap.CompressFormat.JPEG, 92, o)) throw new IOException("ロゴを保存できませんでした");
      } finally {
        out.recycle();
      }
      return name;
    } finally {
      b.recycle();
    }
  }

  // Gives the logo to the source and to everyone at the same company without one (or with the
  // previous cut-out "old", when cutting again).
  public static int share(Store s, String sourceId, String logo, String old, String origin) throws Exception {
    JSONObject source = s.person(sourceId);
    String key = Company.key(source);
    int n = 0;
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONObject x = a.getJSONObject(i);
      boolean same = x.getString("id").equals(sourceId) || (!key.isEmpty() && key.equals(Company.key(x)));
      String cur = x.optString("logo");
      // A logo cut from a card also replaces one found on the web.
      boolean free =
          cur.isEmpty()
              || (!old.isEmpty() && cur.equals(old))
              || (origin.equals("card") && x.optString("logoSource").startsWith("web:"));
      if (!same || !free || x.optString("kind").equals(Nenga.INBOX)) continue;
      JSONObject p = s.person(x.getString("id"));
      p.put("logo", logo).put("logoSource", origin);
      s.put(p);
      n++;
    }
    return n;
  }

  // The card's logo (cut where Gemini says), else the company's website; given to everyone at the
  // company without one. Search (Gemini + Google Search) only when the card has no address.
  public static boolean auto(Store s, String id, String box, String old, WebLogo.Search search) throws Exception {
    JSONObject p = s.person(id);
    if (box != null && !box.isEmpty()) {
      String cut = crop(s, p, box);
      if (!cut.isEmpty()) {
        share(s, id, cut, old, "card");
        return true;
      }
    }
    String key = Company.key(p);
    if (key.isEmpty()) return false;
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONObject x = a.getJSONObject(i);
      String l = x.optString("logo");
      if (!l.isEmpty() && !l.equals(old) && !x.getString("id").equals(id) && key.equals(Company.key(x))) return false;
    }
    WebLogo.Found f = WebLogo.find(p, search);
    if (f == null) return false;
    share(s, id, WebLogo.save(s, f), old, "web:" + f.domain);
    return true;
  }

  // One card per company that has no logo yet (the newest with an image, not tried before).
  public static List<String> pick(Store s) throws Exception {
    Map<String, JSONObject> best = new LinkedHashMap<>();
    Set<String> has = new HashSet<>();
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      if (p.optString("kind").equals(Nenga.INBOX)) continue;
      String key = Company.key(p);
      if (key.isEmpty()) continue;
      if (!p.optString("logo").isEmpty()) has.add(key);
      if (p.optBoolean("logoTried") || !p.optString("queue").isEmpty()) continue;
      // Prefer a card with an image (its logo can be cut), then the newest.
      JSONObject cur = best.get(key);
      int pages = p.getJSONArray("pages").length() > 0 ? 1 : 0, curPages = cur == null ? -1 : cur.getJSONArray("pages").length() > 0 ? 1 : 0;
      if (cur == null || pages > curPages || (pages == curPages && p.optLong("created") > cur.optLong("created"))) best.put(key, p);
    }
    List<String> ids = new ArrayList<>();
    for (Map.Entry<String, JSONObject> e : best.entrySet()) if (!has.contains(e.getKey())) ids.add(e.getValue().getString("id"));
    return ids;
  }
}
