package jp.yaegaki.meishinote;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

// Writes "名刺交換" all-day events into a Google calendar on the phone, which Android syncs to
// Google Calendar. calendarEventId is per device; calendarRegistered is shared through sync so
// another device does not add the same event again.
public final class CalendarSync {
  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  public static boolean allowed(Context c) {
    return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
  }

  // Writable Google calendars on this device.
  public static JSONArray calendars(Context c) throws Exception {
    JSONArray out = new JSONArray();
    try (Cursor q =
        c.getContentResolver()
            .query(
                Calendars.CONTENT_URI,
                new String[] {
                  Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.IS_PRIMARY
                },
                Calendars.ACCOUNT_TYPE + "='com.google' AND " + Calendars.CALENDAR_ACCESS_LEVEL + ">=" + Calendars.CAL_ACCESS_CONTRIBUTOR,
                null,
                Calendars.ACCOUNT_NAME + "," + Calendars.IS_PRIMARY + " DESC")) {
      while (q != null && q.moveToNext())
        out.put(
            new JSONObject()
                .put("id", q.getLong(0))
                .put("name", q.getString(1))
                .put("account", q.getString(2))
                .put("primary", q.getInt(3) == 1));
    }
    return out;
  }

  // The chosen calendar, or the primary calendar of the contacts account, or any writable one.
  public static long calendarId(Context c) throws Exception {
    long saved = prefs(c).getLong("calendarId", -1);
    JSONArray all = calendars(c);
    for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getLong("id") == saved) return saved;
    String account = prefs(c).getString("contactAccount", "");
    long pick = -1;
    for (int i = 0; i < all.length(); i++) {
      JSONObject k = all.getJSONObject(i);
      if (k.optBoolean("primary") && k.optString("account").equals(account)) pick = k.getLong("id");
    }
    if (pick < 0)
      for (int i = 0; i < all.length() && pick < 0; i++)
        if (all.getJSONObject(i).optBoolean("primary")) pick = all.getJSONObject(i).getLong("id");
    if (pick < 0 && all.length() > 0) pick = all.getJSONObject(0).getLong("id");
    if (pick < 0) throw new IOException("書き込めるGoogleカレンダーが端末にありません");
    prefs(c).edit().putLong("calendarId", pick).apply();
    return pick;
  }

  public static String calendarName(Context c) {
    try {
      long id = prefs(c).getLong("calendarId", -1);
      JSONArray all = calendars(c);
      for (int i = 0; i < all.length(); i++)
        if (all.getJSONObject(i).getLong("id") == id)
          return all.getJSONObject(i).optString("name") + "（" + all.getJSONObject(i).optString("account") + "）";
    } catch (Exception ignored) {
    }
    return "";
  }

  static long day(String ymd) throws Exception {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.parse(ymd).getTime();
  }

  static ContentValues values(JSONObject p) throws Exception {
    String date = p.optString("metDate");
    if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IOException("名刺交換日がありません");
    long start = day(date);
    String who = p.optString("name").isEmpty() ? "氏名未登録" : p.optString("name");
    String title = "名刺交換：" + who + (p.optString("company").isEmpty() ? "" : "（" + p.optString("company") + "）");
    StringBuilder d = new StringBuilder();
    for (String[] f :
        new String[][] {{"department", "部署"}, {"title", "肩書き"}, {"phone", "電話"}, {"mobile", "携帯"}, {"email", "メール"}})
      if (!p.optString(f[0]).isEmpty()) d.append(f[1]).append("：").append(p.optString(f[0]).replace('\n', ' ')).append("\n");
    d.append("\nTheta Meishi Hatchから登録");
    ContentValues v = new ContentValues();
    v.put(Events.TITLE, title);
    v.put(Events.DESCRIPTION, d.toString());
    v.put(Events.EVENT_LOCATION, p.optString("metPlace"));
    v.put(Events.DTSTART, start);
    v.put(Events.DTEND, start + 24L * 60 * 60 * 1000);
    v.put(Events.ALL_DAY, 1);
    v.put(Events.EVENT_TIMEZONE, "UTC");
    v.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE);
    return v;
  }

  static boolean exists(Context c, long eventId) {
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                new String[] {Events._ID, Events.DELETED},
                null,
                null,
                null)) {
      return q != null && q.moveToFirst() && q.getInt(1) == 0;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean wanted(JSONObject p) {
    return p.optString("metDate").matches("\\d{4}-\\d{2}-\\d{2}")
        && !p.optBoolean("calendarRegistered")
        && p.optLong("calendarEventId", -1) < 0;
  }

  public static void add(Context c, JSONObject p) throws Exception {
    ContentValues v = values(p);
    v.put(Events.CALENDAR_ID, calendarId(c));
    Uri u = c.getContentResolver().insert(Events.CONTENT_URI, v);
    if (u == null) throw new IOException("カレンダーに登録できませんでした");
    Store s = Store.get(c);
    JSONObject cur = s.person(p.getString("id"));
    cur.put("calendarEventId", ContentUris.parseId(u)).put("calendarRegistered", true);
    s.put(cur);
  }

  // Keeps the event in step with edits (date, name, company…).
  public static void update(Context c, JSONObject p) throws Exception {
    long id = p.optLong("calendarEventId", -1);
    if (id < 0 || !allowed(c) || !exists(c, id)) return;
    if (!p.optString("metDate").matches("\\d{4}-\\d{2}-\\d{2}")) {
      remove(c, p);
      return;
    }
    c.getContentResolver().update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values(p), null, null);
  }

  public static void remove(Context c, JSONObject p) {
    long id = p.optLong("calendarEventId", -1);
    if (id < 0 || !allowed(c)) return;
    try {
      c.getContentResolver().delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null);
    } catch (Exception ignored) {
    }
  }

  public static int registerAll(Context c, Pipeline.Progress progress) throws Exception {
    if (!allowed(c)) throw new IOException("カレンダーの権限がありません");
    Store s = Store.get(c);
    JSONArray a = s.snapshot().getJSONArray("people");
    List<JSONObject> todo = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) if (wanted(a.getJSONObject(i))) todo.add(a.getJSONObject(i));
    int n = 0;
    for (JSONObject p : todo) {
      progress.update("Googleカレンダーに登録中 " + (n + 1) + " / " + todo.size());
      add(c, p);
      n++;
    }
    return n;
  }
}
