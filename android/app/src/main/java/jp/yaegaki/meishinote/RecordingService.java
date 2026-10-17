package jp.yaegaki.meishinote;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class RecordingService extends Service {
  public static volatile String activeId = "";
  public static volatile long startTime = 0;
  MediaRecorder recorder;
  File file;
  String entryId;

  public IBinder onBind(Intent i) {
    return null;
  }

  public int onStartCommand(Intent i, int flags, int start) {
    if (i == null) return START_NOT_STICKY;
    if ("stop".equals(i.getAction())) {
      finish();
      return START_NOT_STICKY;
    }
    if (recorder != null) return START_NOT_STICKY;
    try {
      String person = i.getStringExtra("id");
      Store s = Store.get(this);
      s.person(person);
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(
          new NotificationChannel("recording", "やり取りの録音", NotificationManager.IMPORTANCE_LOW));
      PendingIntent stop =
          PendingIntent.getService(
              this,
              1,
              new Intent(this, RecordingService.class).setAction("stop"),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      PendingIntent open =
          PendingIntent.getActivity(
              this,
              2,
              new Intent(this, MainActivity.class),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      Notification n =
          new Notification.Builder(this, "recording")
              .setSmallIcon(android.R.drawable.ic_btn_speak_now)
              .setContentTitle("Theta Meishi Hatch：録音中")
              .setContentText("タップでアプリを開く / 停止すると保存します")
              .setContentIntent(open)
              .setOngoing(true)
              .addAction(new Notification.Action.Builder(null, "停止して保存", stop).build())
              .build();
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      else startForeground(7, n);
      file = s.mediaFile(UUID.randomUUID() + ".m4a");
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioSamplingRate(44100);
      recorder.setAudioEncodingBitRate(96000);
      recorder.setOutputFile(file.getPath());
      recorder.setMaxFileSize(512L * 1024 * 1024);
      recorder.setOnInfoListener(
          (mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finish();
          });
      recorder.setOnErrorListener((mr, what, extra) -> finish());
      recorder.prepare();
      recorder.start();
      activeId = person;
      startTime = System.currentTimeMillis();
      entryId = UUID.randomUUID().toString();
      JSONObject p = s.person(person);
      p.getJSONArray("audio")
          .put(
              new JSONObject()
                  .put("id", entryId)
                  .put("file", file.getName())
                  .put("time", startTime)
                  .put("seconds", 0)
                  .put("state", "recording"));
      s.put(p);
      notifyUi("");
    } catch (Exception e) {
      if (recorder != null) {
        try {
          recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
      }
      activeId = "";
      if (file != null) file.delete();
      notifyUi("録音を開始できませんでした: " + e.getMessage());
      stopForeground(true);
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  synchronized void finish() {
    if (recorder == null) {
      stopSelf();
      return;
    }
    boolean ok = true;
    try {
      recorder.stop();
    } catch (Exception e) {
      ok = false;
    }
    recorder.release();
    recorder = null;
    String person = activeId;
    activeId = "";
    boolean saved = true;
    try {
      Store s = Store.get(this);
      JSONObject p = s.person(person);
      JSONArray a = p.getJSONArray("audio");
      for (int j = 0; j < a.length(); j++)
        if (entryId.equals(a.getJSONObject(j).optString("id"))) {
          if (ok)
            a.getJSONObject(j)
                .put("seconds", (System.currentTimeMillis() - startTime) / 1000)
                .put("state", "saved");
          else {
            a.remove(j);
            if (file != null) file.delete();
          }
          break;
        }
      s.put(p);
    } catch (Exception e) {
      saved = false;
      notifyUi("録音の情報保存に失敗しました: " + e.getMessage());
    }
    if (saved) notifyUi(ok ? "録音を保存しました" : "録音が短すぎたため保存できませんでした");
    stopForeground(true);
    stopSelf();
  }

  void notifyUi(String message) {
    sendBroadcast(
        new Intent(getPackageName() + ".RECORDING")
            .setPackage(getPackageName())
            .putExtra("message", message));
  }

  public void onDestroy() {
    if (recorder != null) finish();
    super.onDestroy();
  }
}
