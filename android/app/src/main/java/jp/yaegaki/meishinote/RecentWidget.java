package jp.yaegaki.meishinote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

// Home-screen widget (Theta Hatch standard): the cards registered or opened most recently.
// "Opened" history is device-local (prefs), so opening a card never touches the vault.
public class RecentWidget extends AppWidgetProvider {
  public static final String ACTION_SCAN = "jp.yaegaki.meishinote.SCAN";
  public static final String EXTRA_OPEN = "open";
  static final int SHOW = 8;

  @Override
  public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    for (int id : ids) m.updateAppWidget(id, views(c, id));
  }

  static RemoteViews views(Context c, int widgetId) {
    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_recent);
    Intent svc = new Intent(c, Service.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
    v.setRemoteAdapter(R.id.widget_list, svc);
    v.setEmptyView(R.id.widget_list, R.id.widget_empty);
    // Rows fill in the person id; the template must stay mutable for that.
    v.setPendingIntentTemplate(
        R.id.widget_list,
        PendingIntent.getActivity(
            c, 20, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
    PendingIntent scan =
        PendingIntent.getActivity(
            c,
            21,
            new Intent(c, MainActivity.class).setAction(ACTION_SCAN),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    v.setOnClickPendingIntent(R.id.widget_scan, scan);
    PendingIntent open =
        PendingIntent.getActivity(
            c, 22, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    v.setOnClickPendingIntent(R.id.widget_title, open);
    return v;
  }

  public static void update(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    int[] ids = m.getAppWidgetIds(new ComponentName(c, RecentWidget.class));
    if (ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
  }

  // Remembers an opened card (newest first, short list).
  public static void opened(Context c, String id) {
    SharedPreferences p = c.getSharedPreferences("prefs", 0);
    try {
      JSONArray old = new JSONArray(p.getString("recentOpened", "[]")), now = new JSONArray();
      now.put(new JSONObject().put("id", id).put("t", System.currentTimeMillis()));
      for (int i = 0; i < old.length() && now.length() < 30; i++)
        if (!old.getJSONObject(i).optString("id").equals(id)) now.put(old.getJSONObject(i));
      p.edit().putString("recentOpened", now.toString()).apply();
    } catch (Exception ignored) {
    }
    update(c);
  }

  // Newest of "registered" (created) and "opened", one row per person.
  static List<JSONObject> recent(Context c) throws Exception {
    Map<String, Long> opened = new HashMap<>();
    JSONArray o = new JSONArray(c.getSharedPreferences("prefs", 0).getString("recentOpened", "[]"));
    for (int i = 0; i < o.length(); i++) opened.put(o.getJSONObject(i).optString("id"), o.getJSONObject(i).optLong("t"));
    JSONArray all = Store.get(c).snapshot().getJSONArray("people");
    List<JSONObject> out = new ArrayList<>();
    for (int i = 0; i < all.length(); i++) {
      JSONObject p = all.getJSONObject(i);
      if (p.optString("kind").equals(Nenga.INBOX)) continue;
      long created = p.optLong("created"), seen = opened.containsKey(p.getString("id")) ? opened.get(p.getString("id")) : 0;
      p.put("_when", Math.max(created, seen)).put("_opened", seen > created);
      out.add(p);
    }
    out.sort((a, b) -> Long.compare(b.optLong("_when"), a.optLong("_when")));
    return out.subList(0, Math.min(SHOW, out.size()));
  }

  public static class Service extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
      return new Factory(getApplicationContext());
    }
  }

  static class Factory implements RemoteViewsService.RemoteViewsFactory {
    final Context c;
    List<JSONObject> rows = new ArrayList<>();
    final Map<String, Bitmap> thumbs = new HashMap<>();

    Factory(Context c) {
      this.c = c;
    }

    public void onCreate() {}

    public void onDataSetChanged() {
      try {
        rows = recent(c);
      } catch (Exception e) {
        rows = new ArrayList<>();
      }
      for (Bitmap b : thumbs.values()) if (b != null) b.recycle();
      thumbs.clear();
    }

    public void onDestroy() {
      for (Bitmap b : thumbs.values()) if (b != null) b.recycle();
      thumbs.clear();
    }

    public int getCount() {
      return rows.size();
    }

    public RemoteViews getViewAt(int i) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row);
      if (i >= rows.size()) return v;
      JSONObject p = rows.get(i);
      String name = p.optString("name").isEmpty() ? "氏名未登録" : p.optString("name");
      String org = p.optString("company").isEmpty() ? p.optString("genre") : p.optString("company");
      String when = new SimpleDateFormat("M/d", Locale.JAPAN).format(new Date(p.optLong("_when")));
      v.setTextViewText(R.id.row_name, name);
      v.setTextViewText(R.id.row_sub, (org.isEmpty() ? "" : org + " ・ ") + (p.optBoolean("_opened") ? "開いた " : "登録 ") + when);
      Bitmap b = thumb(p);
      if (b != null) v.setImageViewBitmap(R.id.row_thumb, b);
      else v.setImageViewResource(R.id.row_thumb, R.drawable.ic_app);
      v.setOnClickFillInIntent(R.id.row, new Intent().putExtra(EXTRA_OPEN, p.optString("id")));
      return v;
    }

    Bitmap thumb(JSONObject p) {
      String id = p.optString("id");
      if (thumbs.containsKey(id)) return thumbs.get(id);
      Bitmap b = null;
      try {
        JSONArray pages = p.optJSONArray("pages");
        if (pages != null && pages.length() > 0)
          b = Importer.image(Store.get(c).mediaFile(pages.getJSONObject(0).getString("file")), 200);
      } catch (Exception ignored) {
      }
      thumbs.put(id, b);
      return b;
    }

    public RemoteViews getLoadingView() {
      return null;
    }

    public int getViewTypeCount() {
      return 1;
    }

    public long getItemId(int i) {
      return i < rows.size() ? rows.get(i).optString("id").hashCode() : i;
    }

    public boolean hasStableIds() {
      return true;
    }
  }
}
