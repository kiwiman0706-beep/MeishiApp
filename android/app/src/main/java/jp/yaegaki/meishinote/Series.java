package jp.yaegaki.meishinote;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

// Meishi's side of the series settings (SeriesSettings has the rules): which of its settings are
// series items, reading / applying them, and the trip to .theta/settings.json during a sync.
public final class Series {
  // The items this app can take from the series (offered / shown); the rest it only publishes.
  static final Set<String> USES = new HashSet<>(Arrays.asList("gemini.model", "gemini.format", "google.account", "calendar"));

  static JSONArray usable(JSONArray items) throws JSONException {
    JSONArray out = new JSONArray();
    for (int i = 0; i < items.length(); i++) if (USES.contains(items.getJSONObject(i).getString("key"))) out.put(items.getJSONObject(i));
    return out;
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  static String now() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  public static JSONObject local(Context c) {
    try {
      String s = prefs(c).getString("series", "");
      return s.isEmpty() ? SeriesSettings.initial() : new JSONObject(s);
    } catch (Exception e) {
      try {
        return SeriesSettings.initial();
      } catch (JSONException x) {
        throw new IllegalStateException(x);
      }
    }
  }

  static void save(Context c, JSONObject local) {
    prefs(c).edit().putString("series", local.toString()).apply();
  }

  // This app's values of the series items (unset ones left out).
  public static JSONObject current(Context c) throws JSONException {
    SharedPreferences p = prefs(c);
    JSONObject o = new JSONObject();
    if (p.contains("model")) o.put("gemini.model", p.getString("model", ""));
    if (p.contains("format")) o.put("gemini.format", p.getString("format", ""));
    if (!p.getString("contactAccount", "").isEmpty()) o.put("google.account", p.getString("contactAccount", ""));
    try {
      long id = p.getLong("calendarId", -1);
      if (id >= 0 && CalendarSync.allowed(c)) {
        JSONArray all = CalendarSync.calendars(c);
        for (int i = 0; i < all.length(); i++)
          if (all.getJSONObject(i).getLong("id") == id)
            o.put("calendar", new JSONObject().put("account", all.getJSONObject(i).optString("account")).put("name", all.getJSONObject(i).optString("name")));
      }
    } catch (Exception ignored) {
      // calendar permission not granted: the item is simply not published
    }
    if (p.getBoolean("driveAuthorized", false)) o.put("google.cloudReady", true);
    return o;
  }

  // Values from the series, for the items this app follows.
  static void apply(Context c, JSONObject values) throws JSONException {
    SharedPreferences.Editor e = prefs(c).edit();
    for (Iterator<String> i = values.keys(); i.hasNext(); ) {
      String k = i.next();
      Object v = values.get(k);
      switch (k) {
        case "gemini.model":
          if (v instanceof String && ((String) v).matches("[A-Za-z0-9._-]+")) e.putString("model", (String) v);
          break;
        case "gemini.format":
          if ("legacy".equals(v) || "modern".equals(v)) e.putString("format", (String) v);
          break;
        case "google.account":
          // Only an account that is on this phone.
          for (Account a : AccountManager.get(c).getAccountsByType("com.google"))
            if (a.name.equals(v)) e.putString("contactAccount", a.name);
          break;
        case "calendar":
          try {
            JSONObject want = (JSONObject) v;
            JSONArray all = CalendarSync.calendars(c);
            for (int j = 0; j < all.length(); j++) {
              JSONObject k2 = all.getJSONObject(j);
              if (k2.optString("account").equals(want.optString("account")) && k2.optString("name").equals(want.optString("name")))
                e.putLong("calendarId", k2.getLong("id"));
            }
          } catch (Exception ignored) {
            // no calendar permission yet
          }
          break;
        default:
          break; // google.cloudReady, google.webClientId: information for other apps / the web version
      }
    }
    e.apply();
  }

  // A series item changed here: the series gets it at the next sync (when this app follows it).
  public static void changed(Context c, String key) {
    try {
      Object v = current(c).opt(key);
      if (v != null) save(c, SeriesSettings.changed(local(c), key, v, SeriesSettings.ME, now()));
    } catch (Exception ignored) {
    }
  }

  // During a vault sync: read, merge, write back, apply; offers wait for the user.
  public static void run(Context c, VaultRemote d) throws Exception {
    String root = d.folder("root", VaultSync.ROOT);
    String theta = d.folder(root, ".theta");
    String fileId = "", text = null;
    JSONArray files = d.files(theta);
    for (int i = 0; i < files.length(); i++)
      if (files.getJSONObject(i).optString("name").equals("settings.json")) {
        fileId = files.getJSONObject(i).getString("id");
        text = d.read(fileId);
      }
    SeriesSettings.Result r = SeriesSettings.sync(local(c), text, current(c), SeriesSettings.ME, now());
    if (r.broken) {
      prefs(c).edit().putString("seriesError", "Theta Vault の .theta/settings.json が読めません（手で直した場合はJSONの形を確認してください）").apply();
      return;
    }
    if (r.write != null) d.put(fileId, theta, "settings.json", r.write.getBytes(StandardCharsets.UTF_8), "", "");
    save(c, r.local);
    apply(c, r.apply);
    SharedPreferences.Editor e = prefs(c).edit().remove("seriesError");
    JSONArray offers = usable(r.offers);
    if (offers.length() > 0) e.putString("seriesOffer", offers.toString());
    else e.remove("seriesOffer");
    e.apply();
  }

  // The user's choice on the carry-over screen.
  public static void adopt(Context c, Set<String> keys) throws Exception {
    JSONArray offers = new JSONArray(prefs(c).getString("seriesOffer", "[]"));
    JSONObject local = SeriesSettings.adopt(local(c), offers, keys);
    JSONObject apply = new JSONObject();
    for (int i = 0; i < offers.length(); i++) {
      JSONObject o = offers.getJSONObject(i);
      if (keys.contains(o.getString("key"))) apply.put(o.getString("key"), o.get("value"));
    }
    save(c, local);
    apply(c, apply);
    prefs(c).edit().remove("seriesOffer").apply();
  }

  public static void follow(Context c, String key, boolean on) throws Exception {
    JSONObject local = SeriesSettings.setFollow(local(c), key, on);
    save(c, local);
    if (on) {
      // Following again: take the series value now.
      JSONObject e = local.getJSONObject("doc").getJSONObject("series").optJSONObject(key);
      if (e != null) apply(c, new JSONObject().put(key, e.get("value")));
    }
  }

  // For the screen: items, the pending carry-over, any problem with the file.
  public static void state(Context c, JSONObject state) throws JSONException {
    state.put("series", usable(SeriesSettings.view(local(c), current(c))));
    String offer = prefs(c).getString("seriesOffer", "");
    if (!offer.isEmpty()) state.put("seriesOffer", new JSONArray(offer));
    state.put("seriesError", prefs(c).getString("seriesError", ""));
  }
}
