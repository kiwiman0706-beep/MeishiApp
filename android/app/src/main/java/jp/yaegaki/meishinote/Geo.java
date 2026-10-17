package jp.yaegaki.meishinote;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.util.*;
import org.json.*;

// Turns card addresses into map coordinates with the platform Geocoder (Google's service on
// most phones). Results are stored as "geo": {lat, lng, q} where q is the address used, so an
// edited address is looked up again. Runs only after the user has opened the map once.
public final class Geo {
  static String query(JSONObject p) {
    String a = p.optString("address").replace('\n', ' ').trim();
    return a.isEmpty() ? "" : a;
  }

  public static int missing(Store s) throws Exception {
    int n = 0;
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) if (needs(a.getJSONObject(i))) n++;
    return n;
  }

  static boolean needs(JSONObject p) {
    String q = query(p);
    JSONObject g = p.optJSONObject("geo");
    return !q.isEmpty() && (g == null || !q.equals(g.optString("q")));
  }

  @SuppressWarnings("deprecation")
  public static int fill(Context c, Pipeline.Progress progress) throws Exception {
    if (!Geocoder.isPresent()) return 0;
    Store s = Store.get(c);
    Geocoder geocoder = new Geocoder(c, Locale.JAPAN);
    JSONArray a = s.snapshot().getJSONArray("people");
    Map<String, JSONObject> cache = new HashMap<>(); // colleagues share an office address
    int done = 0, total = 0;
    for (int i = 0; i < a.length(); i++) if (needs(a.getJSONObject(i))) total++;
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      if (!needs(p)) continue;
      String q = query(p);
      progress.update("地図用に住所を変換中 " + (done + 1) + " / " + total);
      JSONObject geo = cache.get(q);
      if (geo == null) {
        geo = new JSONObject().put("q", q);
        try {
          List<Address> r = geocoder.getFromLocationName(q, 1);
          if (r != null && !r.isEmpty()) geo.put("lat", r.get(0).getLatitude()).put("lng", r.get(0).getLongitude());
        } catch (Exception e) {
          // Network or quota trouble: stop and retry on a later run rather than mark it unfindable.
          if (done == 0) throw new java.io.IOException("住所を地図に変換できませんでした：" + e.getMessage());
          break;
        }
        cache.put(q, geo);
      }
      try {
        JSONObject cur = s.person(p.getString("id"));
        if (!q.equals(query(cur))) continue; // edited meanwhile
        cur.put("geo", geo);
        s.put(cur);
      } catch (Exception ignored) {
        // deleted meanwhile
      }
      done++;
    }
    return done;
  }
}
