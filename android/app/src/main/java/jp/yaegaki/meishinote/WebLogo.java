package jp.yaegaki.meishinote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.regex.*;
import org.json.*;

// A company's logo from its own website, for cards without one (desktop/lib/weblogo.js is the
// same). The site is the card's web address, else its e-mail domain (not a free-mail one);
// Gemini's search is asked only when neither exists, and its answer is used only if the page opens.
public final class WebLogo {
  static final Pattern FREE_MAIL =
      Pattern.compile(
          "(^|\\.)(gmail\\.com|googlemail\\.com|yahoo\\.co\\.jp|yahoo\\.com|ymail\\.ne\\.jp|outlook\\.(com|jp)|hotmail\\.(com|co\\.jp)|live\\.(com|jp)|msn\\.com|icloud\\.com|me\\.com|mac\\.com|docomo\\.ne\\.jp|ezweb\\.ne\\.jp|au\\.com|softbank\\.ne\\.jp|i\\.softbank\\.jp|ymobile\\.ne\\.jp|nifty\\.com|biglobe\\.ne\\.jp|so-net\\.ne\\.jp|ocn\\.ne\\.jp|plala\\.or\\.jp|dion\\.ne\\.jp|aol\\.com|proton\\.me|protonmail\\.com|example\\.(com|jp|org))$",
          Pattern.CASE_INSENSITIVE);

  public interface Search {
    String site(JSONObject p) throws Exception;
  }

  static String host(String u) {
    try {
      String h = URI.create(u.matches("(?i)^https?://.*") ? u : "https://" + u).getHost();
      return h == null ? "" : h.toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return "";
    }
  }

  // Home pages to try, best first.
  static List<String> sites(JSONObject p) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String w : p.optString("website").split("[\\n,;\\s]+")) {
      String h = host(w.trim());
      if (!h.isEmpty() && h.contains(".")) out.add("https://" + h + "/");
    }
    for (String e : p.optString("email").split("[\\n,;\\s]+")) {
      int at = e.indexOf('@');
      if (at < 0) continue;
      String d = e.substring(at + 1).trim().toLowerCase(Locale.ROOT);
      if (d.contains(".") && !FREE_MAIL.matcher(d).find()) out.add("https://" + d.replaceFirst("^(mail|mx|smtp)\\.", "") + "/");
    }
    return new ArrayList<>(out);
  }

  static String attr(String tag, String name) {
    Matcher m = Pattern.compile("\\s" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE).matcher(tag);
    if (!m.find()) return "";
    String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
    return v.replace("&amp;", "&").trim();
  }

  // Logo images a page declares, best first: its structured-data logo, its touch icon, the
  // largest icon, then the conventional locations.
  static List<String> candidates(String html, String base) {
    List<Object[]> found = new ArrayList<>(); // { url, score }
    URI b = URI.create(base);
    java.util.function.BiConsumer<String, Double> add =
        (u, score) -> {
          if (u == null || u.isEmpty() || u.startsWith("data:")) return;
          try {
            String abs = b.resolve(u.trim().replace(" ", "%20")).toString();
            if (abs.matches("(?i)^https?:.*")) found.add(new Object[] {abs, score});
          } catch (Exception ignored) {
          }
        };
    Matcher ld = Pattern.compile("<script[^>]+application/ld\\+json[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE).matcher(html);
    while (ld.find()) {
      try {
        Object j = new JSONTokener(ld.group(1).trim()).nextValue();
        walk(j, u -> add.accept(u, 100.0));
      } catch (Exception ignored) {
      }
    }
    Matcher tags = Pattern.compile("<(link|meta)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
    while (tags.find()) {
      String tag = tags.group(0);
      if (tags.group(1).equalsIgnoreCase("meta")) {
        String prop = attr(tag, "property");
        if (prop.isEmpty()) prop = attr(tag, "itemprop");
        if (prop.isEmpty()) prop = attr(tag, "name");
        prop = prop.toLowerCase(Locale.ROOT);
        if (prop.equals("og:logo") || prop.equals("logo")) add.accept(attr(tag, "content"), 90.0);
        continue;
      }
      String rel = attr(tag, "rel").toLowerCase(Locale.ROOT), href = attr(tag, "href");
      if (rel.matches("(.*\\s)?apple-touch-icon(-precomposed)?(\\s.*)?")) add.accept(href, 80.0);
      else if (rel.matches("(.*\\s)?icon(\\s.*)?") && !href.toLowerCase(Locale.ROOT).matches(".*\\.svg(\\?.*)?$")) {
        int size = 0;
        for (String sz : attr(tag, "sizes").split("\\s+")) {
          Matcher n = Pattern.compile("^(\\d+)").matcher(sz);
          if (n.find()) size = Math.max(size, Integer.parseInt(n.group(1)));
        }
        add.accept(href, 40 + Math.min(size, 256) / 8.0);
      }
    }
    add.accept("/apple-touch-icon.png", 30.0);
    add.accept("/favicon.ico", 10.0);
    // Stable: equal scores keep page order.
    Collections.sort(found, (x, y) -> Double.compare((Double) y[1], (Double) x[1]));
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (Object[] f : found) out.add((String) f[0]);
    return new ArrayList<>(out);
  }

  interface Url {
    void accept(String u);
  }

  static void walk(Object x, Url found) throws JSONException {
    if (x instanceof JSONArray) {
      JSONArray a = (JSONArray) x;
      for (int i = 0; i < a.length(); i++) walk(a.get(i), found);
    } else if (x instanceof JSONObject) {
      JSONObject o = (JSONObject) x;
      Object l = o.opt("logo");
      if (l instanceof String) found.accept((String) l);
      else if (l instanceof JSONObject) {
        String u = ((JSONObject) l).optString("url", ((JSONObject) l).optString("contentUrl"));
        if (!u.isEmpty()) found.accept(u);
      }
      for (Iterator<String> k = o.keys(); k.hasNext(); ) {
        Object v = o.get(k.next());
        if (v instanceof JSONObject || v instanceof JSONArray) walk(v, found);
      }
    }
  }

  static final class Got {
    byte[] body;
    String type = "", url;
  }

  static Got get(String url, int max, String accept) throws IOException {
    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
    con.setConnectTimeout(10000);
    con.setReadTimeout(12000);
    con.setInstanceFollowRedirects(true);
    con.setRequestProperty("Accept", accept);
    con.setRequestProperty("User-Agent", "Mozilla/5.0 ThetaMeishiHatch");
    int code = con.getResponseCode();
    // http → https (and back) redirects are not followed automatically.
    for (int i = 0; i < 4 && code >= 300 && code < 400 && con.getHeaderField("Location") != null; i++) {
      url = URI.create(url).resolve(con.getHeaderField("Location")).toString();
      con = (HttpURLConnection) new URL(url).openConnection();
      con.setConnectTimeout(10000);
      con.setReadTimeout(12000);
      con.setRequestProperty("Accept", accept);
      con.setRequestProperty("User-Agent", "Mozilla/5.0 ThetaMeishiHatch");
      code = con.getResponseCode();
    }
    if (code != 200) throw new IOException("HTTP " + code);
    if (con.getContentLength() > max) throw new IOException("too large");
    Got g = new Got();
    try (InputStream in = con.getInputStream()) {
      g.body = Store.read(in, max);
    }
    g.type = con.getContentType() == null ? "" : con.getContentType();
    g.url = con.getURL().toString();
    return g;
  }

  public static final class Found {
    public Bitmap image;
    public String domain, url;
  }

  // The best logo found, or null.
  public static Found find(JSONObject p, Search search) {
    List<String> pages = sites(p);
    if (pages.isEmpty() && search != null) {
      try {
        String u = search.site(p);
        if (!host(u).isEmpty()) pages = Collections.singletonList("https://" + host(u) + "/");
      } catch (Exception ignored) {
      }
    }
    for (String page : pages.subList(0, Math.min(3, pages.size()))) {
      String html, base;
      try {
        Got g = get(page, 3 * 1024 * 1024, "text/html");
        html = new String(g.body, "UTF-8");
        base = g.url;
      } catch (Exception e) {
        continue; // the site does not open: try the next one
      }
      List<String> c = candidates(html, base);
      for (String u : c.subList(0, Math.min(6, c.size()))) {
        try {
          Got g = get(u, 2 * 1024 * 1024, "image/*");
          if (g.type.contains("svg")) continue;
          Bitmap b = BitmapFactory.decodeByteArray(g.body, 0, g.body.length);
          if (b == null) continue;
          if (Math.min(b.getWidth(), b.getHeight()) < 32) {
            b.recycle();
            continue;
          }
          Found f = new Found();
          f.image = b;
          f.domain = host(base);
          f.url = u;
          return f;
        } catch (Exception ignored) {
        }
      }
    }
    return null;
  }

  // Saved as PNG (logos keep their transparency), at most 512 px.
  public static String save(Store s, Found f) throws Exception {
    Bitmap b = f.image;
    float scale = Math.min(1f, 512f / Math.max(b.getWidth(), b.getHeight()));
    Bitmap out = scale < 1 ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true) : b;
    String name = UUID.randomUUID() + ".png";
    try (OutputStream o = new FileOutputStream(s.mediaFile(name))) {
      if (!out.compress(Bitmap.CompressFormat.PNG, 100, o)) throw new IOException("ロゴを保存できませんでした");
    } finally {
      if (out != b) out.recycle();
      b.recycle();
    }
    return name;
  }
}
