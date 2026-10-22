package jp.yaegaki.meishinote;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;

// Runs imports from Drive and the recognise → register queue off the UI, so the user can
// keep scanning. Foreground so Android does not kill it mid-batch.
public class ProcessingService extends Service {
  public static volatile boolean running = false;
  public static volatile String status = "";
  static volatile String driveToken = "";
  static final int ONGOING = 8, DONE = 9;

  public IBinder onBind(Intent i) {
    return null;
  }

  public static void start(Context c) {
    c.startForegroundService(new Intent(c, ProcessingService.class));
  }

  static volatile boolean syncRequested = false, extrasRequested = false;

  // Map geocoding and calendar registration: cheap no-ops when there is nothing to do.
  public static void requestExtras(Context c) {
    extrasRequested = true;
    start(c);
  }

  void extras(StringBuilder report) {
    extrasRequested = false;
    android.content.SharedPreferences prefs = getSharedPreferences("prefs", 0);
    try {
      if (prefs.getBoolean("geoEnabled", false) && Geo.missing(Store.get(this)) > 0) {
        Geo.fill(this, this::progress);
        syncRequested = true;
      }
    } catch (Exception e) {
      report.append(report.length() > 0 ? "\n" : "").append(e.getMessage());
    }
    try {
      if ((prefs.getBoolean("autoCalendar", false) || prefs.getBoolean("calendarOnce", false))
          && CalendarSync.allowed(this)) {
        prefs.edit().remove("calendarOnce").apply();
        int n = CalendarSync.registerAll(this, this::progress);
        if (n > 0) {
          report.append(report.length() > 0 ? "\n" : "").append("Googleカレンダーに名刺交換を").append(n).append("件登録しました");
          syncRequested = true;
        }
      }
    } catch (Exception e) {
      report.append(report.length() > 0 ? "\n" : "").append("カレンダー登録：").append(e.getMessage());
    }
    tell("");
  }

  public static void requestSync(Context c) {
    syncRequested = true;
    start(c);
  }

  public static void startDrive(Context c, String token) {
    driveToken = token;
    start(c);
  }

  Notification note(String text, boolean ongoing) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("processing", "名刺の読み取り・登録", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this, 3, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "processing")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(ongoing ? "Theta Meishi Hatch：処理中" : "Theta Meishi Hatch：処理が終わりました")
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .build();
  }

  void tell(String message) {
    sendBroadcast(
        new Intent(getPackageName() + ".RECORDING").setPackage(getPackageName()).putExtra("message", message));
  }

  void progress(String text) {
    status = text;
    getSystemService(NotificationManager.class).notify(ONGOING, note(text, true));
    tell("");
  }

  void sync(StringBuilder report) {
    syncRequested = false;
    android.content.SharedPreferences prefs = getSharedPreferences("prefs", 0);
    try {
      Drive drive = new Drive(DriveAuth.silentToken(this));
      VaultSync.Result r = VaultSync.run(this, drive, this::progress);
      try {
        Series.run(this, new VaultRemote.OnDrive(drive)); // settings shared with the other Theta Hatch apps
      } catch (Exception e) {
        prefs.edit().putString("seriesError", "シリーズの設定を同期できませんでした：" + e.getMessage()).apply();
      }
      prefs.edit().putLong("lastSync", System.currentTimeMillis()).remove("syncError").apply();
      // Quiet when nothing moved; a notification per routine sync would be noise.
      if (r.up + r.down + r.merged + r.deleted > 0 || !r.errors.isEmpty())
        report.append(report.length() > 0 ? "\n" : "").append(r.text());
    } catch (Exception e) {
      prefs.edit().putString("syncError", String.valueOf(e.getMessage())).apply();
      report.append(report.length() > 0 ? "\n" : "").append("同期できませんでした：").append(e.getMessage());
    }
    tell("");
  }

  void drive(String token, StringBuilder report) {
    try {
      JSONObject r = Pipeline.driveImport(this, new Drive(token), this::progress);
      int n = r.getJSONArray("ids").length();
      report.append(report.length() > 0 ? "\n" : "");
      report.append(n == 0 ? "Driveに新しい名刺はありませんでした" : "Driveから" + n + "人を取り込みました");
      if (r.optInt("moved") > 0)
        report.append("（").append(r.optInt("moved")).append("ファイルを「").append(Drive.DONE_FOLDER).append("」へ移動）");
      JSONArray e = r.getJSONArray("errors");
      for (int k = 0; k < e.length(); k++) report.append("\n").append(e.getString(k));
      JSONArray ids = r.getJSONArray("ids");
      java.util.List<String> list = new java.util.ArrayList<>();
      for (int k = 0; k < ids.length(); k++) list.add(ids.getString(k));
      if (getSharedPreferences("prefs", 0).getBoolean("autoRecognize", true)
          && getSharedPreferences("prefs", 0).contains("key"))
        Pipeline.enqueue(Store.get(this), list, "recognize");
      tell("");
    } catch (Exception e) {
      report.append(report.length() > 0 ? "\n" : "").append("Driveからの取り込みに失敗しました：").append(e.getMessage());
    }
  }

  public int onStartCommand(Intent i, int flags, int startId) {
    Notification n = note("準備中", true);
    if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(ONGOING, n);
    if (running) return START_NOT_STICKY; // the running loop picks up newly queued people
    running = true;
    new Thread(this::work, "processing").start();
    return START_NOT_STICKY;
  }

  void work() {
    StringBuilder report = new StringBuilder();
    try {
      // Re-check after each pass: people queued or a Drive import requested mid-run
      // are handled before the service stops.
      for (int round = 0; round < 50; round++) {
        String token = driveToken;
        driveToken = "";
        if (!token.isEmpty()) drive(token, report);
        if (Pipeline.pending(Store.get(this)) == 0) {
          if (driveToken.isEmpty()) break;
          continue;
        }
        Pipeline.Summary s = Pipeline.run(this, this::progress, () -> tell(""));
        if (s.read + s.registered + s.updated + s.failed > 0 || s.halted)
          report.append(report.length() > 0 ? "\n" : "").append(s.text());
        for (String e : s.errors) report.append("\n").append(e);
        if (s.read + s.registered + s.updated > 0) syncRequested = true; // share what was just added
        if (s.halted) break;
      }
      extras(report);
      if (syncRequested && getSharedPreferences("prefs", 0).getBoolean("syncEnabled", false)) sync(report);
    } catch (Exception e) {
      report.append("処理に失敗しました：").append(e.getMessage());
    } finally {
      running = false;
      status = "";
      stopForeground(STOP_FOREGROUND_REMOVE);
      String text = report.toString().trim();
      if (!text.isEmpty()) {
        getSystemService(NotificationManager.class).notify(DONE, note(text, false));
        getSharedPreferences("prefs", 0).edit().putString("lastReport", text).putLong("lastReportAt", System.currentTimeMillis()).apply();
      }
      tell(text.isEmpty() ? "" : text.split("\n")[0]);
      stopSelf();
    }
  }
}
