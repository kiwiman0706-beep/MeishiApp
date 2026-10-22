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

  // Gemini's box corrected with the picture (desktop/lib/model.js refineLogoRect is the same):
  // each side grows while the logo continues past it, then empty margin is trimmed and an even
  // margin added. The background is the commonest colour around the box; gaps stop the growth.
  static Rect refine(int[] px, int w, int h, Rect r) {
    int mx = (int) Math.max(r.width(), w * 0.08), my = (int) Math.max(r.height(), h * 0.08);
    int wx0 = clamp(r.left - mx, 0, w - 1), wy0 = clamp(r.top - my, 0, h - 1);
    int wx1 = clamp(r.right + mx, 1, w), wy1 = clamp(r.bottom + my, 1, h);
    Map<Integer, Integer> counts = new HashMap<>();
    for (int x = wx0; x < wx1; x += 2) {
      count(counts, px[wy0 * w + x]);
      count(counts, px[(wy1 - 1) * w + x]);
    }
    for (int y = wy0; y < wy1; y += 2) {
      count(counts, px[y * w + wx0]);
      count(counts, px[y * w + wx1 - 1]);
    }
    int top = 0, best = -1;
    for (Map.Entry<Integer, Integer> e : counts.entrySet())
      if (e.getValue() > best) {
        best = e.getValue();
        top = e.getKey();
      }
    int[] bg = {((top >> 8) & 15) * 16 + 8, ((top >> 4) & 15) * 16 + 8, (top & 15) * 16 + 8};
    Ink ink = (x, y) -> {
      int c = px[y * w + x];
      return Math.abs(((c >> 16) & 255) - bg[0]) + Math.abs(((c >> 8) & 255) - bg[1]) + Math.abs((c & 255) - bg[2]) > 90;
    };
    int x0 = r.left, y0 = r.top, x1 = r.right, y1 = r.bottom;
    int step = Math.max(2, Math.round(Math.min(w, h) * 0.006f));
    for (int i = 0; i < 400; i++) {
      boolean grew = false;
      if (y0 - step >= wy0 && row(ink, y0 - step, x0, x1) >= 2) { y0 -= step; grew = true; }
      if (y1 + step <= wy1 && row(ink, y1 + step - 1, x0, x1) >= 2) { y1 += step; grew = true; }
      if (x0 - step >= wx0 && col(ink, x0 - step, y0, y1) >= 2) { x0 -= step; grew = true; }
      if (x1 + step <= wx1 && col(ink, x1 + step - 1, y0, y1) >= 2) { x1 += step; grew = true; }
      if (!grew) break;
    }
    while (y0 < y1 - 4 && row(ink, y0, x0, x1) == 0) y0++;
    while (y1 > y0 + 4 && row(ink, y1 - 1, x0, x1) == 0) y1--;
    while (x0 < x1 - 4 && col(ink, x0, y0, y1) == 0) x0++;
    while (x1 > x0 + 4 && col(ink, x1 - 1, y0, y1) == 0) x1--;
    if (x1 - x0 < 8 || y1 - y0 < 8) return r; // nothing recognisable: keep Gemini's box
    int pad = Math.round(Math.max(x1 - x0, y1 - y0) * 0.06f) + 2;
    return new Rect(clamp(x0 - pad, 0, w), clamp(y0 - pad, 0, h), clamp(x1 + pad, 0, w), clamp(y1 + pad, 0, h));
  }

  interface Ink {
    boolean at(int x, int y);
  }

  static int row(Ink ink, int y, int x0, int x1) {
    int n = 0;
    for (int x = x0; x < x1; x += 2) if (ink.at(x, y)) n++;
    return n;
  }

  static int col(Ink ink, int x, int y0, int y1) {
    int n = 0;
    for (int y = y0; y < y1; y += 2) if (ink.at(x, y)) n++;
    return n;
  }

  static void count(Map<Integer, Integer> m, int c) {
    int k = ((c >> 20) & 15) * 256 + ((c >> 12) & 15) * 16 + ((c >> 4) & 15);
    m.merge(k, 1, Integer::sum);
  }

  static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  // Saves the cut-out as a new media file; "" when there is no usable logo.
  public static String crop(Store s, JSONObject p, String boxText) throws Exception {
    JSONArray pages = p.getJSONArray("pages");
    if (pages.length() == 0) return "";
    Bitmap b = Importer.image(s.mediaFile(pages.getJSONObject(0).getString("file")), 1600);
    try {
      Rect r = box(boxText, b.getWidth(), b.getHeight());
      if (r == null || r.width() < 8 || r.height() < 8) return "";
      int[] px = new int[b.getWidth() * b.getHeight()];
      b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
      r = refine(px, b.getWidth(), b.getHeight(), r);
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
              || (origin.startsWith("card") && x.optString("logoSource").startsWith("web:"));
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
        share(s, id, cut, old, CUT);
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

  // Where a logo cut with the corrected method came from; plain "card" = cut before it (0.15.3 and
  // earlier), offered again by the upgrade of old cards.
  public static final String CUT = "card2";

  // One card per company whose logo was cut the old way.
  public static List<String> recut(Store s) throws Exception {
    Map<String, JSONObject> best = new LinkedHashMap<>();
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      String key = Company.key(p);
      if (key.isEmpty() || !p.optString("logoSource").equals("card") || p.getJSONArray("pages").length() == 0 || !p.optString("queue").isEmpty()) continue;
      JSONObject cur = best.get(key);
      if (cur == null || p.optLong("created") > cur.optLong("created")) best.put(key, p);
    }
    List<String> ids = new ArrayList<>();
    for (JSONObject p : best.values()) ids.add(p.getString("id"));
    return ids;
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
