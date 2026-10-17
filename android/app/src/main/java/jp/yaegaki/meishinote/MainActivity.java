package jp.yaegaki.meishinote;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.google.mlkit.vision.documentscanner.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;

public class MainActivity extends Activity {
  WebView web;
  Store store;
  ExecutorService io = Executors.newSingleThreadExecutor();
  JSONObject pending = new JSONObject();
  Runnable afterPermission;
  boolean ready = false;
  volatile boolean busy = false;
  static final int PICK = 10,
      TREE = 11,
      CAMERA = 12,
      ASSET = 13,
      SAVE = 14,
      RESTORE = 15,
      ACCOUNT = 16,
      SCAN = 17,
      DRIVE_AUTH = 18,
      IMPORT_ZIP = 19;
  // Full Drive scope: the ScanSnap folder and its files were not created by this app,
  // and moving them into 登録済み needs write access to them.
  static final String DRIVE_SCOPE = DriveAuth.SCOPE;
  AlertDialog scannerLoading;
  int scannerGeneration = 0;

  interface Work {
    void run() throws Exception;
  }

  interface DriveWork {
    void run(Drive d) throws Exception;
  }

  BroadcastReceiver receiver =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          String message = i.getStringExtra("message");
          if (message != null && !message.isEmpty()) event("toast", message);
          refresh();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      store = Store.get(this);
      pending = new JSONObject(getPreferences(0).getString("pending", "{}"));
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("データを開けません")
          .setMessage(e.getMessage())
          .setPositiveButton("閉じる", (d, w) -> finish())
          .setCancelable(false)
          .show();
      return;
    }
    web = new WebView(this);
    web.setBackgroundColor(Color.rgb(246, 247, 243));
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebViewClient(
        new WebViewClient() {
          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            try {
              // Map tiles are the only outside content the page may load (see index.html CSP).
              if ("tile.openstreetmap.org".equals(u.getHost())
                  && "https".equals(u.getScheme())
                  && "GET".equals(r.getMethod())) return null;
              if (!"app.meishinote.local".equals(u.getHost()) || !"https".equals(u.getScheme()))
                return blocked();
              String p = u.getPath();
              if (p.equals("/") || p.equals("/index.html"))
                return response("text/html", getAssets().open("index.html"));
              if (p.equals("/style.css"))
                return response("text/css", getAssets().open("style.css"));
              if (p.matches("/lib/[A-Za-z.-]+\\.(js|css)"))
                return response(
                    p.endsWith(".js") ? "application/javascript" : "text/css",
                    getAssets().open(p.substring(1)));
              if (p.equals("/app.js"))
                return response("application/javascript", getAssets().open("app.js"));
              if (p.startsWith("/media/")) {
                String name = p.substring(7);
                File f = store.mediaFile(name);
                return response(
                    name.endsWith(".m4a")
                        ? "audio/mp4"
                        : name.endsWith(".pdf") ? "application/pdf" : "image/jpeg",
                    new FileInputStream(f));
              }
            } catch (Exception ignored) {
            }
            return blocked();
          }

          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (r.getUrl().toString().equals("https://app.meishinote.local/index.html"))
              return false;
            return true;
          }
        });
    web.setWebChromeClient(
        new WebChromeClient() {
          public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> result.confirm())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton("続ける", (d, w) -> result.confirm())
                .setNegativeButton("キャンセル", (d, w) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }
        });
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.rgb(246, 247, 243));
    root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> onBackPressed());
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bar =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bar.left, bar.top, bar.right, Math.max(bar.bottom, ime.bottom));
            return insets;
          });
    }
    web.loadUrl("https://app.meishinote.local/index.html");
    fromWidget(getIntent());
    IntentFilter f = new IntentFilter(getPackageName() + ".RECORDING");
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(receiver, f);
  }

  WebResourceResponse response(String type, InputStream in) {
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    h.put("X-Content-Type-Options", "nosniff");
    return new WebResourceResponse(type, "UTF-8", 200, "OK", h, in);
  }

  WebResourceResponse blocked() {
    return new WebResourceResponse(
        "text/plain", "UTF-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
  }

  void event(String type, Object data) {
    try {
      String json = new JSONObject().put("type", type).put("data", data).toString();
      runOnUiThread(
          () -> {
            if (web != null && ready) web.evaluateJavascript("window.onNative(" + json + ")", null);
          });
    } catch (Exception ignored) {
    }
  }

  // Widget taps: open a card, or go straight to the scanner. Held until the page has booted.
  String launch = "";
  long widgetSeen = -1;

  void fromWidget(Intent i) {
    if (i == null) return;
    if (RecentWidget.ACTION_SCAN.equals(i.getAction())) launch = "scan";
    else if (i.getStringExtra(RecentWidget.EXTRA_OPEN) != null) launch = "open:" + i.getStringExtra(RecentWidget.EXTRA_OPEN);
    else return;
    if (ready) runLaunch();
  }

  void runLaunch() {
    String l = launch;
    launch = "";
    try {
      if (l.equals("scan")) handle("quickScan", new JSONObject());
      else if (l.startsWith("open:")) event("open", l.substring(5));
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    fromWidget(intent);
  }

  void refresh() {
    try {
      if (store.changedAt != widgetSeen) {
        widgetSeen = store.changedAt;
        RecentWidget.update(this);
      }
      JSONObject state = store.snapshot();
      state
          .put("keySet", getSharedPreferences("prefs", 0).contains("key"))
          .put("model", getSharedPreferences("prefs", 0).getString("model", "gemini-2.5-flash"))
          .put("format", getSharedPreferences("prefs", 0).getString("format", "legacy"))
          .put("folder", getSharedPreferences("prefs", 0).getString("folder", ""))
          .put("autoRecognize", prefs().getBoolean("autoRecognize", true))
          .put("contactAccount", prefs().getString("contactAccount", ""))
          .put("contactPhoto", prefs().getString("contactPhoto", "card"))
          .put("driveFolderName", prefs().getString("driveFolderName", ""))
          .put("autoContact", prefs().getBoolean("autoContact", true))
          .put("syncEnabled", prefs().getBoolean("syncEnabled", false))
          .put("autoCalendar", prefs().getBoolean("autoCalendar", false))
          .put("calendarName", CalendarSync.allowed(this) ? CalendarSync.calendarName(this) : "")
          .put("lastSync", prefs().getLong("lastSync", 0))
          .put("syncError", prefs().getString("syncError", ""))
          .put("processing", ProcessingService.running ? ProcessingService.status : "")
          .put("lastReport", prefs().getString("lastReport", ""))
          .put("driveFolderId", prefs().getString("driveFolderId", ""))
          .put("driveMode", prefs().getString("driveMode", "file"))
          .put("recording", RecordingService.activeId)
          .put("recordingStart", RecordingService.startTime);
      event("state", state);
    } catch (Exception e) {
      error(e);
    }
  }

  void error(Exception e) {
    event("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
  }

  SharedPreferences prefs() {
    return getSharedPreferences("prefs", 0);
  }

  // New people from any import are queued for background reading (and contact
  // registration), so the user is free to scan the next card straight away.
  void afterImport(JSONObject imported, JSONObject q) throws Exception {
    JSONArray ids = imported.getJSONArray("ids");
    if (!q.optString("attachTo").isEmpty() && q.optString("id").isEmpty()) {
      // A document for a known person: no reading, just file it.
      for (int i = 0; i < ids.length(); i++)
        Nenga.attach(store, ids.getString(i), q.getString("attachTo"), q.optString("dir"), q.optString("title"));
      event("open", q.getString("attachTo"));
      event("toast", "書類を添付しました");
      return;
    }
    if (q.optBoolean("nenga") && q.optString("id").isEmpty()) {
      List<String> list = new ArrayList<>();
      for (int i = 0; i < ids.length(); i++) {
        JSONObject p = store.person(ids.getString(i));
        p.put("kind", Nenga.INBOX).put("nengaYear", q.optInt("year", Nenga.season(System.currentTimeMillis())));
        store.put(p);
        list.add(p.getString("id"));
      }
      if (prefs().contains("key")) {
        Pipeline.enqueue(store, list, "nenga");
        startProcessing();
        event("toast", list.size() + "通の年賀状を保存しました。差出人の読み取りはバックグラウンドで進めます");
      } else event("toast", "年賀状を保存しました。Gemini APIキーを設定すると差出人を読み取ります");
      return;
    }
    boolean auto =
        q.optString("id").isEmpty()
            && ids.length() > 0
            && prefs().getBoolean("autoRecognize", true)
            && prefs().contains("key");
    if (!auto) {
      event("imported", imported);
      if (q.optBoolean("auto") && !prefs().contains("key"))
        event("toast", "APIキーを設定すると、取り込み後に自動で読み取ります");
      return;
    }
    List<String> list = new ArrayList<>();
    for (int i = 0; i < ids.length(); i++) list.add(ids.getString(i));
    Pipeline.enqueue(store, list, "recognize");
    startProcessing();
    event("queued", imported);
  }

  void startProcessing() {
    runOnUiThread(
        () -> {
          ProcessingService.start(this);
          // The finished-batch summary arrives as a notification; ask once on Android 13+.
          if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
              && !prefs().getBoolean("askedNotify", false)) {
            prefs().edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 46);
          }
        });
  }

  void recognizeOne(String id, Gemini g) throws Exception {
    JSONObject result = g.recognize(store.person(id));
    JSONObject p = store.person(id);
    p.put("suggestion", result).put("status", "review");
    store.put(p);
  }

  // First use of auto-registration: grant contacts access and pick the account once,
  // up front, so the background queue never has to ask.
  Runnable afterAccount;

  void ensureContactSetup(Runnable then) {
    if (!Pipeline.autoContact(this)
        || !prefs().getBoolean("autoRecognize", true)
        || !prefs().contains("key")) {
      then.run();
      return;
    }
    contactSetup(then);
  }

  void contactSetup(Runnable then) {
    permission(
        new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
        () -> {
          if (!Pipeline.contactAccount(this).isEmpty()) {
            then.run();
            return;
          }
          afterAccount = then;
          try {
            setPending(new JSONObject().put("setupOnly", true));
          } catch (Exception ignored) {
          }
          startActivityForResult(
              AccountManager.newChooseAccountIntent(
                  null, null, new String[] {"com.google"}, "自動登録先のGoogleアカウントを選択", null, null, null),
              ACCOUNT);
        });
  }

  // People whose registration was skipped for lack of an account get another go.
  void requeueContactless() throws Exception {
    JSONArray a = store.snapshot().getJSONArray("people");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject p = a.getJSONObject(i);
      if (p.optString("queueError").contains("アカウント")) ids.add(p.getString("id"));
    }
    if (!ids.isEmpty()) {
      Pipeline.enqueue(store, ids, "contact");
      startProcessing();
    }
  }

  // Authorization is re-requested before each Drive job: it is silent once granted and
  // hands back a fresh short-lived token, so nothing needs to be stored.
  interface TokenWork {
    void run(String token) throws Exception;
  }

  TokenWork pendingToken;

  void withDriveToken(TokenWork work) {
    AuthorizationRequest req =
        AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_SCOPE)))
            .build();
    Identity.getAuthorizationClient(this)
        .authorize(req)
        .addOnSuccessListener(
            r -> {
              if (r.hasResolution()) {
                pendingToken = work;
                try {
                  startIntentSenderForResult(
                      r.getPendingIntent().getIntentSender(), DRIVE_AUTH, null, 0, 0, 0);
                } catch (Exception e) {
                  pendingToken = null;
                  error(e);
                }
              } else runToken(work, r.getAccessToken());
            })
        .addOnFailureListener(e -> event("error", driveSetupHelp(e)));
  }

  void runToken(TokenWork work, String token) {
    if (token == null || token.isEmpty()) {
      event("error", "Google Driveの許可を取得できませんでした。もう一度お試しください。");
      return;
    }
    try {
      work.run(token);
    } catch (Exception e) {
      error(e);
    }
  }

  void withDrive(String title, DriveWork work) {
    if (busy || scannerLoading != null) {
      event("toast", "処理が終わるまでお待ちください");
      return;
    }
    withDriveToken(token -> task(title, () -> work.run(new Drive(token))));
  }

  static String driveSetupHelp(Exception e) {
    return "Google Driveに接続できませんでした。\n\n"
        + "初回はGoogle Cloudで、このアプリ用のOAuthクライアント（Android）登録が必要です。"
        + "手順はリポジトリの docs/DRIVE_SETUP.md を参照してください。\n\n詳細: "
        + e.getMessage();
  }

  void task(String title, Work work) {
    if (busy || scannerLoading != null) {
      event("toast", "処理が終わるまでお待ちください");
      return;
    }
    busy = true;
    event("busy", title);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    io.execute(
        () -> {
          try {
            work.run();
          } catch (Exception e) {
            error(e);
          } finally {
            busy = false;
            refresh();
            event("busy", "");
            runOnUiThread(
                () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
          }
        });
  }

  void setPending(JSONObject p) {
    pending = p;
    getPreferences(0).edit().putString("pending", p.toString()).commit();
  }

  void permission(String[] perms, Runnable next) {
    List<String> missing = new ArrayList<>();
    for (String p : perms)
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
    if (missing.isEmpty()) next.run();
    else {
      afterPermission = next;
      requestPermissions(missing.toArray(new String[0]), 44);
    }
  }

  public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code, p, r);
    if (code == 44) {
      boolean granted = r.length > 0;
      for (int value : r) if (value != PackageManager.PERMISSION_GRANTED) granted = false;
      Runnable next = afterPermission;
      afterPermission = null;
      if (granted && next != null) next.run();
      else event("error", "必要な権限が許可されませんでした。設定 → アプリ → Theta Meishi Hatch → 権限から変更できます。");
    }
  }

  class Bridge {
    @JavascriptInterface
    public void call(String json) {
      runOnUiThread(
          () -> {
            try {
              JSONObject q = new JSONObject(json);
              handle(q.optString("action"), q);
            } catch (Exception e) {
              error(e);
            }
          });
    }
  }

  void handle(String action, JSONObject q) throws Exception {
    if (action.equals("boot")) {
      ready = true;
      refresh();
      if (!launch.isEmpty()) runLaunch();
      return;
    }
    if (action.equals("opened")) {
      RecentWidget.opened(this, q.optString("id"));
      return;
    }
    if (action.equals("stopRecord")) {
      startService(new Intent(this, RecordingService.class).setAction("stop"));
      return;
    }
    if (busy || scannerLoading != null) {
      event("toast", "処理が終わるまでお待ちください");
      return;
    }
    if (!RecordingService.activeId.isEmpty()
        && Arrays.asList(
                "pick",
                "folder",
                "rescan",
                "camera",
                "scan",
                "asset",
                "rotate",
                "recognize",
                "recognizeAll",
                "merge",
                "restore",
                "export",
                "contacts",
                "driveImport",
                "importZip",
                "bulkScan",
                "quickScan")
            .contains(action)) throw new IOException("この操作の前に録音を停止してください。メモの追加・編集は録音中もできます。");
    String id = q.optString("id");
    switch (action) {
      case "new":
        {
          JSONObject p = Store.empty();
          store.put(p);
          refresh();
          event("open", p.getString("id"));
          break;
        }
      case "save":
        {
          store.edit(id, q.getJSONObject("person"));
          JSONObject p = store.person(id);
          p.remove("queueError");
          store.put(p);
          try {
            CalendarSync.update(this, store.person(id));
          } catch (Exception ignored) {
            // calendar is secondary; the card itself is saved
          }
          refresh();
          event("saved", id);
          // Edits reach the Google contact this app created; unregistered people can opt in.
          if (Contacts.registered(this, p))
            permission(
                new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
                () ->
                    task(
                        "連絡先を更新中",
                        () -> {
                          Contacts.update(this, store.person(id));
                          event("toast", "Google連絡先にも反映しました");
                        }));
          else if (q.optBoolean("thenContact")) handle("contacts", q);
          break;
        }
      case "delete":
        {
          if (id.equals(RecordingService.activeId)) throw new IOException("録音を停止してから削除してください");
          JSONObject p = store.person(id);
          CalendarSync.remove(this, p);
          if (q.optBoolean("withContact") && p.has("contactRawId")) {
            permission(
                new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
                () ->
                    task(
                        "削除中",
                        () -> {
                          Contacts.remove(this, store.person(id));
                          store.remove(id);
                          event("home", "");
                          event("toast", "名刺と連絡先を削除しました");
                        }));
            break;
          }
          store.remove(id);
          refresh();
          event("home", "");
          break;
        }
      case "notDuplicate":
        {
          // Remember "different people" on both sides so the pair is not flagged again.
          String other = q.getString("other");
          for (String[] pair : new String[][] {{id, other}, {other, id}}) {
            JSONObject p = store.person(pair[0]);
            JSONArray a = p.optJSONArray("notDuplicate");
            if (a == null) p.put("notDuplicate", a = new JSONArray());
            a.put(pair[1]);
            store.put(p);
          }
          refresh();
          break;
        }
      case "dismissContactDup":
        {
          JSONObject p = store.person(id);
          p.remove("contactDuplicate");
          store.put(p);
          refresh();
          break;
        }
      case "unlinkContact":
        // Keeps the card, removes the contact this app added (the pre-existing one stays).
        permission(
            new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
            () ->
                task(
                    "連絡先を取り消し中",
                    () -> {
                      JSONObject p = store.person(id);
                      Contacts.remove(this, p);
                      p.remove("contactRawId");
                      p.remove("contactAccount");
                      p.remove("contactPhoto");
                      p.remove("contactDuplicate");
                      p.put("contactUnlinked", true);
                      p.remove("contactRegistered");
                      store.put(p);
                      event("toast", "このアプリで追加した連絡先を削除しました");
                    }));
        break;
      case "note":
        store.note(id, q.getString("text"));
        refresh();
        break;
      case "editNote":
        {
          JSONObject p = store.person(id);
          JSONArray a = p.getJSONArray("notes");
          for (int i = 0; i < a.length(); i++)
            if (a.getJSONObject(i).getString("id").equals(q.getString("noteId")))
              a.getJSONObject(i).put("text", q.getString("text"));
          store.put(p);
          refresh();
          break;
        }
      case "removeItem":
        {
          JSONObject p = store.person(id);
          String key = q.getString("key");
          if (!Arrays.asList("notes", "audio", "pages").contains(key))
            throw new IOException("不正な項目");
          if (id.equals(RecordingService.activeId) && key.equals("audio"))
            throw new IOException("録音中は削除できません");
          JSONArray a = p.getJSONArray(key);
          int n = q.getInt("index");
          if (n >= 0 && n < a.length()) a.remove(n);
          if (key.equals("pages")) {
            p.put("status", "new");
            p.remove("suggestion");
          }
          store.put(p);
          refresh();
          break;
        }
      case "reorder":
        {
          JSONObject p = store.person(id);
          JSONArray a = p.getJSONArray("pages");
          int from = q.getInt("from"), to = q.getInt("to");
          if (from >= 0 && from < a.length() && to >= 0 && to < a.length()) {
            Object v = a.get(from);
            a.put(from, a.get(to));
            a.put(to, v);
            store.put(p);
          }
          refresh();
          break;
        }
      case "rotate":
        task(
            "画像を回転中",
            () -> {
              JSONObject p = store.person(id);
              JSONObject pg = p.getJSONArray("pages").getJSONObject(q.getInt("index"));
              Bitmap b = Importer.image(store.mediaFile(pg.getString("file")), 2400);
              Matrix m = new Matrix();
              m.postRotate(90);
              Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
              File target = store.mediaFile(UUID.randomUUID() + ".jpg");
              try {
                Importer.jpeg(r, target);
              } finally {
                if (r != b) r.recycle();
                b.recycle();
              }
              pg.put("file", target.getName());
              store.put(p);
            });
        break;
      case "merge":
        {
          if (!RecordingService.activeId.isEmpty()) throw new IOException("録音を停止してください");
          String sourceId = q.getString("sourceId");
          JSONObject target = store.person(id), source = store.person(sourceId);
          CalendarSync.remove(this, source); // the merged person gets (or keeps) one event
          boolean targetLinked = Contacts.registered(this, target),
              sourceLinked = Contacts.registered(this, source);
          store.mergePeople(id, sourceId);
          if (!targetLinked && !sourceLinked) {
            refresh();
            event("open", id);
            break;
          }
          // One Google contact survives: the target's, or the source's taken over.
          permission(
              new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
              () ->
                  task(
                      "連絡先を統合中",
                      () -> {
                        JSONObject t = store.person(id);
                        if (targetLinked && sourceLinked) Contacts.remove(this, source);
                        else if (sourceLinked)
                          t.put("contactRawId", source.getLong("contactRawId"))
                              .put("contactAccount", source.optString("contactAccount"));
                        t.remove("contactDuplicate");
                        store.put(t);
                        Contacts.update(this, store.person(id));
                        event("open", id);
                        event("toast", "名刺と連絡先を統合しました");
                      }));
          break;
        }
      case "pick":
        setPending(q);
        Intent pick =
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, PICK);
        break;
      case "folder":
        setPending(q);
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            TREE);
        break;
      case "rescan":
        {
          String uri = getSharedPreferences("prefs", 0).getString("folder", "");
          if (uri.isEmpty()) throw new IOException("先に取込フォルダを選択してください");
          setPending(q);
          scanFolder(Uri.parse(uri), q);
          break;
        }
      case "scan":
        startScanner(q);
        break;
      case "driveFolders":
        {
          String parent = q.optString("parent", "root");
          withDrive("Google Driveのフォルダを取得中", d -> event("driveFolders", d.folders(parent)));
          break;
        }
      case "driveSelect":
        {
          String folderId = q.getString("folderId");
          String mode = q.optString("mode", "file");
          if (!Arrays.asList("file", "pair", "page", "all").contains(mode)) throw new IOException("不正なまとめ方");
          prefs()
              .edit()
              .putString("driveFolderId", folderId)
              .putString("driveFolderName", q.optString("folderName"))
              .putString("driveMode", mode)
              .apply();
          refresh();
          event("toast", "Google Driveのフォルダを設定しました");
          break;
        }
      case "driveClear":
        prefs().edit().remove("driveFolderId").remove("driveFolderName").apply();
        refresh();
        break;
      case "greeting":
        {
          // Toggle a sent/received mark for a year (entries carrying card images are kept).
          JSONObject p = store.person(id);
          int year = q.getInt("year");
          String dir = q.getString("dir");
          if (!dir.equals("sent") && !dir.equals("received")) throw new IOException("不正な種類");
          JSONArray a = p.optJSONArray("greetings");
          if (a == null) p.put("greetings", a = new JSONArray());
          if (q.optBoolean("on")) {
            if (!Nenga.has(p, year, dir))
              a.put(
                  new JSONObject()
                      .put("id", UUID.randomUUID().toString())
                      .put("year", year)
                      .put("dir", dir)
                      .put("date", q.optString("date", Nenga.today(System.currentTimeMillis())))
                      .put("kind", "nenga")
                      .put("files", new JSONArray()));
          } else
            for (int i = a.length() - 1; i >= 0; i--) {
              JSONObject g = a.getJSONObject(i);
              JSONArray fs = g.optJSONArray("files");
              if (g.optInt("year") == year && g.optString("dir").equals(dir) && (fs == null || fs.length() == 0)) a.remove(i);
            }
          store.put(p);
          refresh();
          break;
        }
      case "openMedia":
        {
          String name = q.getString("file");
          File f = store.mediaFile(name);
          if (!f.isFile()) throw new IOException("ファイルが見つかりません");
          openFile(f, name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".m4a") ? "audio/mp4" : "image/jpeg", false);
          break;
        }
      case "greetingEdit":
        {
          JSONObject p = store.person(id);
          JSONArray a = p.optJSONArray("greetings");
          for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject g = a.getJSONObject(i);
            if (!g.optString("id").equals(q.getString("gid"))) continue;
            if (q.optBoolean("remove")) a.remove(i);
            else {
              if (q.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")) g.put("date", q.getString("date"));
              if (q.has("year")) g.put("year", q.getInt("year"));
              if (q.has("title")) g.put("title", q.optString("title"));
            }
            break;
          }
          store.put(p);
          refresh();
          break;
        }
      case "nengaRetry":
        {
          if (!prefs().contains("key")) throw new IOException("先にGemini APIキーを設定してください");
          Pipeline.enqueue(store, Collections.singletonList(id), "nenga");
          refresh();
          startProcessing();
          break;
        }
      case "mapOpen":
        // First map view opts in to sending addresses to the platform geocoder.
        prefs().edit().putBoolean("geoEnabled", true).apply();
        if (Geo.missing(store) > 0) {
          ProcessingService.requestExtras(this);
          event("toast", "住所を地図用に変換しています（バックグラウンド）");
        }
        break;
      case "calendarRegister":
        permission(
            new String[] {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR},
            () -> {
              prefs().edit().putBoolean("calendarOnce", true).apply();
              ProcessingService.requestExtras(this);
              event("toast", "Googleカレンダーに登録します（バックグラウンド）");
            });
        break;
      case "calendarList":
        permission(
            new String[] {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR},
            () -> {
              try {
                long current = -1;
                try {
                  current = CalendarSync.calendarId(this);
                } catch (Exception ignored) {
                }
                event("calendars", new JSONObject().put("list", CalendarSync.calendars(this)).put("selected", current));
              } catch (Exception e) {
                error(e);
              }
            });
        break;
      case "calendarSelect":
        prefs().edit().putLong("calendarId", q.getLong("calendarId")).apply();
        refresh();
        event("toast", "登録先のカレンダーを変更しました");
        break;
      case "syncNow":
        // Through the activity so Google can ask the user if access has lapsed; the
        // service then gets tokens silently.
        withDriveToken(
            token -> {
              prefs().edit().putBoolean("syncEnabled", true).remove("syncError").apply();
              ProcessingService.requestSync(this);
              refresh();
              event("toast", "Google Driveと同期します（バックグラウンド）");
            });
        break;
      case "syncStop":
        prefs().edit().putBoolean("syncEnabled", false).apply();
        VaultSync.forget(store);
        refresh();
        event("toast", "同期を停止しました。Drive上のデータはそのまま残ります");
        break;
      case "driveImport":
        withDriveToken(
            token -> {
              ProcessingService.startDrive(this, token);
              event("toast", "Google Driveから取り込みます（バックグラウンド）");
            });
        break;
      case "quickScan":
        q.put("mode", "all").put("id", "").put("auto", true);
        ensureContactSetup(() -> startScanner(q));
        break;
      case "bulkScan":
        {
          String mode = q.optString("mode", "pair");
          if (!mode.equals("pair") && !mode.equals("page")) throw new IOException("不正なまとめ方");
          q.put("id", "").put("auto", true);
          ensureContactSetup(() -> startScanner(q));
          break;
        }
      case "importZip":
        setPending(q);
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"}),
            IMPORT_ZIP);
        break;
      case "camera":
        {
          File f = new File(getCacheDir(), "camera-" + UUID.randomUUID() + ".jpg");
          q.put("cameraFile", f.getName());
          setPending(q);
          Uri u = FilesProvider.uri(this, f);
          Intent camera =
              new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                  .putExtra(MediaStore.EXTRA_OUTPUT, u)
                  .addFlags(
                      Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                          | Intent.FLAG_GRANT_READ_URI_PERMISSION);
          camera.setClipData(ClipData.newRawUri("camera", u));
          startActivityForResult(camera, CAMERA);
          break;
        }
      case "asset":
        setPending(q);
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
            ASSET);
        break;
      case "settings":
        {
          if (q.optBoolean("clearKey")) VaultKey.save(this, "");
          else if (!q.optString("key").trim().isEmpty())
            VaultKey.save(this, q.getString("key").trim());
          SharedPreferences.Editor e = prefs().edit();
          if (q.has("model")) {
            String model = q.optString("model").trim().replace("models/", "");
            if (!model.matches("[A-Za-z0-9._-]+")) throw new IOException("モデル名を確認してください");
            e.putString("model", model).putString("format", q.optString("format", "legacy"));
          }
          if (q.has("autoRecognize")) e.putBoolean("autoRecognize", q.getBoolean("autoRecognize"));
          if (q.has("contactPhoto")) {
            String photo = q.getString("contactPhoto");
            if (!Arrays.asList("card", "portrait", "logo", "none").contains(photo))
              throw new IOException("不正な写真の種類");
            e.putString("contactPhoto", photo);
          }
          if (q.optBoolean("clearAccount")) e.remove("contactAccount");
          if (q.has("autoContact")) e.putBoolean("autoContact", q.getBoolean("autoContact"));
          if (q.has("autoCalendar")) e.putBoolean("autoCalendar", q.getBoolean("autoCalendar"));
          e.apply();
          if (q.optBoolean("autoCalendar"))
            permission(
                new String[] {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR},
                () -> ProcessingService.requestExtras(this));
          if (q.optBoolean("autoContact"))
            contactSetup(
                () -> {
                  try {
                    requeueContactless();
                    refresh();
                  } catch (Exception ex) {
                    error(ex);
                  }
                });
          refresh();
          event("toast", "設定を保存しました");
          break;
        }
      case "models":
        task("利用可能なモデルを取得中", () -> event("models", new Gemini(this).models()));
        break;
      case "recognize":
        {
          if (!prefs().contains("key")) throw new IOException("先にGemini APIキーを設定してください");
          Pipeline.enqueue(store, Collections.singletonList(id), "recognize");
          refresh();
          startProcessing();
          event("toast", "読み取り中（バックグラウンド）。終わると自動で反映します");
          break;
        }
      case "reread":
        // Re-reading a card that already has data goes through the review form, never auto-overwrites.
        task(
            "Geminiで名刺を読み取り中",
            () -> {
              recognizeOne(id, new Gemini(this));
              event("review", id);
            });
        break;
      case "recognizeAll":
        {
          if (!prefs().contains("key")) throw new IOException("先にGemini APIキーを設定してください");
          JSONArray a = store.snapshot().getJSONArray("people");
          List<String> ids = new ArrayList<>();
          for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            if (p.optString("status").equals("new")
                && p.getJSONArray("pages").length() > 0
                && p.optString("queue").isEmpty()) ids.add(p.getString("id"));
          }
          Pipeline.enqueue(store, ids, "recognize");
          refresh();
          startProcessing();
          event("toast", ids.size() + "件をバックグラウンドで読み取ります");
          break;
        }
      case "applyRecognition":
        {
          JSONObject p = store.person(id), result = p.getJSONObject("suggestion");
          for (String k : Store.FIELDS) if (result.has(k)) p.put(k, result.optString(k));
          p.put("rawText", result.optString("rawText"))
              .put("warnings", result.optString("warnings"))
              .put("status", "review");
          p.remove("suggestion");
          store.put(p);
          refresh();
          event("applied", id);
          break;
        }
      case "discardRecognition":
        {
          JSONObject p = store.person(id);
          p.remove("suggestion");
          store.put(p);
          refresh();
          break;
        }
      case "record":
        if (!RecordingService.activeId.isEmpty()) throw new IOException("別の録音を停止してから開始してください");
        permission(
            new String[] {Manifest.permission.RECORD_AUDIO},
            () -> {
              startForegroundService(
                  new Intent(this, RecordingService.class).setAction("start").putExtra("id", id));
              if (Build.VERSION.SDK_INT >= 33
                  && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                      != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 45);
            });
        break;
      case "play":
        {
          JSONObject p = store.person(id);
          JSONObject a = p.getJSONArray("audio").getJSONObject(q.getInt("index"));
          if (a.optString("state").equals("recording") && id.equals(RecordingService.activeId))
            throw new IOException("録音停止後に再生してください");
          playAudio(store.mediaFile(a.getString("file")));
          break;
        }
      case "export":
        if (!RecordingService.activeId.isEmpty()) throw new IOException("録音を停止してからバックアップしてください");
        task(
            "出力ファイルを作成中",
            () -> {
              String kind = q.optString("kind");
              File f =
                  kind.equals("csv")
                      ? Backup.csv(this)
                      : kind.equals("nenga") ? Nenga.csv(this, q.optInt("year")) : Backup.make(this);
              String mime = kind.equals("zip") ? "application/zip" : "text/csv";
              runOnUiThread(
                  () -> {
                    try {
                      if (q.optBoolean("share")) openFile(f, mime, true);
                      else {
                        JSONObject p = new JSONObject().put("export", f.getName());
                        setPending(p);
                        String stamp =
                            new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN)
                                .format(new Date());
                        startActivityForResult(
                            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .setType(mime)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(
                                    Intent.EXTRA_TITLE,
                                    (kind.equals("nenga") ? "nenga-" + q.optInt("year") + "-" : "meishinote-")
                                        + stamp
                                        + (kind.equals("zip") ? ".zip" : ".csv")),
                            SAVE);
                      }
                    } catch (Exception e) {
                      error(e);
                    }
                  });
            });
        break;
      case "restore":
        if (!RecordingService.activeId.isEmpty()) throw new IOException("録音を停止してください");
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
            RESTORE);
        break;
      case "contacts":
        permission(
            new String[] {Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS},
            () -> {
              try {
                contactsStart(q);
              } catch (Exception e) {
                error(e);
              }
            });
        break;
      case "viewContact":
        permission(
            new String[] {Manifest.permission.READ_CONTACTS},
            () -> {
              try {
                Contacts.view(this, store.person(id).getLong("contactRawId"));
              } catch (Exception e) {
                error(e);
              }
            });
        break;
      case "search":
        {
          JSONObject p = store.person(id);
          String term =
              q.optString("kind").equals("logo")
                  ? p.optString("company") + " ロゴ"
                  : p.optString("name") + " " + p.optString("company") + " " + p.optString("title");
          external("https://www.google.com/search?tbm=isch&q=" + Uri.encode(term));
          break;
        }
      case "openUrl":
        external(q.getString("url"));
        break;
      case "shareImage":
        {
          JSONObject p = store.person(id);
          openFile(
              store.mediaFile(
                  p.getJSONArray("pages").getJSONObject(q.getInt("index")).getString("file")),
              "image/jpeg",
              true);
          break;
        }
      case "clearAsset":
        {
          JSONObject p = store.person(id);
          String key = q.optString("kind");
          if (key.equals("logo") || key.equals("portrait")) {
            p.remove(key);
            store.put(p);
            refresh();
          }
          break;
        }
      default:
        throw new IOException("不明な操作です");
    }
  }

  void playAudio(File f) throws Exception {
    if (!RecordingService.activeId.isEmpty()) throw new IOException("録音を停止してから再生してください");
    MediaPlayer mp = new MediaPlayer();
    try {
      mp.setDataSource(f.getPath());
      mp.prepare();
    } catch (Exception e) {
      mp.release();
      throw new IOException("録音を再生できません。中断された録音ファイルの可能性があります。");
    }
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    view.setPadding(30, 24, 30, 18);
    TextView time = new TextView(this);
    SeekBar seek = new SeekBar(this);
    seek.setMax(mp.getDuration());
    Button toggle = new Button(this);
    toggle.setText("一時停止");
    view.addView(time);
    view.addView(seek);
    view.addView(toggle);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("録音の再生")
            .setView(view)
            .setPositiveButton("閉じる", null)
            .create();
    Handler handler = new Handler(Looper.getMainLooper());
    Runnable tick =
        new Runnable() {
          public void run() {
            if (dialog.isShowing()) {
              int pos = mp.getCurrentPosition();
              seek.setProgress(pos);
              time.setText(
                  String.format(
                      Locale.JAPAN,
                      "%d:%02d / %d:%02d",
                      pos / 60000,
                      (pos / 1000) % 60,
                      mp.getDuration() / 60000,
                      (mp.getDuration() / 1000) % 60));
              handler.postDelayed(this, 300);
            }
          }
        };
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int v, boolean user) {
            if (user) mp.seekTo(v);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    toggle.setOnClickListener(
        v -> {
          if (mp.isPlaying()) {
            mp.pause();
            toggle.setText("再生");
          } else {
            mp.start();
            toggle.setText("一時停止");
          }
        });
    mp.setOnCompletionListener(player -> toggle.setText("もう一度再生"));
    dialog.setOnDismissListener(
        d -> {
          handler.removeCallbacks(tick);
          mp.release();
        });
    dialog.show();
    mp.start();
    handler.post(tick);
  }

  void external(String url) throws Exception {
    Uri u = Uri.parse(url);
    if (!Arrays.asList("https", "http", "tel", "mailto").contains(u.getScheme()))
      throw new IOException("このリンクは開けません");
    startActivity(
        new Intent(u.getScheme().equals("tel") ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, u));
  }

  void openFile(File f, String mime, boolean share) throws Exception {
    Uri u = FilesProvider.uri(this, f);
    Intent i =
        new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (share) i.setType(mime).putExtra(Intent.EXTRA_STREAM, u);
    else i.setDataAndType(u, mime);
    i.setClipData(ClipData.newRawUri("file", u));
    startActivity(Intent.createChooser(i, share ? "保存・共有先を選択" : "開くアプリを選択"));
  }

  void contactsStart(JSONObject q) throws Exception {
    JSONObject p = store.person(q.getString("id"));
    if (!p.optString("status").equals("confirmed"))
      throw new IOException("先に内容を確認して「確認済みで保存」を押してください");
    if (Contacts.registered(this, p)) {
      Contacts.view(this, p.getLong("contactRawId"));
      event("toast", "登録済みの連絡先を開きます。変更は連絡先アプリで行ってください。");
      return;
    }
    String duplicate = Contacts.duplicate(this, p);
    String saved = prefs().getString("contactAccount", "");
    // The chooser grants visibility to the picked account; if it is gone, ask again.
    boolean present = false;
    for (android.accounts.Account acc : AccountManager.get(this).getAccountsByType("com.google"))
      if (acc.name.equals(saved)) present = true;
    String remembered = present ? saved : "";
    Runnable choose =
        () -> {
          if (!remembered.isEmpty()) {
            registerContact(q, remembered);
            return;
          }
          setPending(q);
          startActivityForResult(
              AccountManager.newChooseAccountIntent(
                  null, null, new String[] {"com.google"}, "登録先のGoogleアカウントを選択", null, null, null),
              ACCOUNT);
        };
    if (!duplicate.isEmpty())
      new AlertDialog.Builder(this)
          .setTitle("同じ電話番号・メールの候補")
          .setMessage(duplicate + "\n既存の連絡先は変更しません。別の連絡先として追加しますか？")
          .setNegativeButton("キャンセル", null)
          .setPositiveButton("新しく追加", (d, w) -> choose.run())
          .show();
    else choose.run();
  }

  void registerContact(JSONObject q, String account) {
    String photo = q.optString("photo", prefs().getString("contactPhoto", "card"));
    task(
        "連絡先を登録中",
        () -> {
          Contacts.add(this, store.person(q.getString("id")), account, photo);
          event("toast", account + " の連絡先に登録しました");
        });
  }

  void scanFolder(Uri uri, JSONObject q) {
    task(
        "フォルダを確認中",
        () -> {
          Importer importer = new Importer(this);
          List<Uri> files = importer.folder(uri);
          if (files.isEmpty()) throw new IOException("画像・PDFが見つかりませんでした");
          JSONObject r =
              importer.run(
                  files, q.optString("mode", "file"), q.optString("id"), t -> event("busy", t));
          afterImport(r, q);
        });
  }

  // Play services owns the camera UI and returns corrected JPEGs in the review order.
  void startScanner(JSONObject q) {
    final int generation = ++scannerGeneration;
    scannerLoading = new AlertDialog.Builder(this)
        .setTitle("スキャンを準備中")
        .setMessage("初回はGoogle Play開発者サービスがスキャン機能をダウンロードします。インターネットに接続してお待ちください。")
        .setNegativeButton("キャンセル", (d, w) -> cancelScannerPreparation())
        .setOnCancelListener(d -> cancelScannerPreparation())
        .create();
    scannerLoading.show();
    try {
      GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
          .setGalleryImportAllowed(true)
          .setPageLimit(20)
          .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
          .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
          .build();
      GmsDocumentScanning.getClient(options).getStartScanIntent(this)
          .addOnSuccessListener(sender -> {
            if (generation != scannerGeneration || isFinishing() || isDestroyed()) return;
            dismissScannerLoading();
            try {
              setPending(q);
              startIntentSenderForResult(sender, SCAN, null, 0, 0, 0);
            } catch (Exception e) {
              setPending(new JSONObject());
              scannerUnavailable(q);
            }
          })
          .addOnFailureListener(e -> {
            if (generation != scannerGeneration || isFinishing() || isDestroyed()) return;
            dismissScannerLoading();
            scannerUnavailable(q);
          });
    } catch (Exception e) {
      dismissScannerLoading();
      scannerUnavailable(q);
    }
  }

  void dismissScannerLoading() {
    if (scannerLoading != null) {
      scannerLoading.dismiss();
      scannerLoading = null;
    }
  }

  void cancelScannerPreparation() {
    scannerGeneration++;
    dismissScannerLoading();
  }

  void scannerUnavailable(JSONObject q) {
    new AlertDialog.Builder(this)
        .setTitle("スキャンを開始できません")
        .setMessage("通信状態とGoogle Play開発者サービスの更新を確認してください。対応していない端末では通常のカメラをご利用ください。")
        .setNegativeButton("閉じる", null)
        .setNeutralButton("通常のカメラ", (d, w) -> {
          try { handle("camera", q); } catch (Exception e) { error(e); }
        })
        .setPositiveButton("再試行", (d, w) -> startScanner(q))
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == DRIVE_AUTH) {
      TokenWork work = pendingToken;
      pendingToken = null;
      if (result != RESULT_OK) return;
      if (work == null) {
        event("toast", "許可しました。もう一度操作してください");
        return;
      }
      try {
        AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
        runToken(work, r.getAccessToken());
      } catch (Exception e) {
        event("error", driveSetupHelp(e));
      }
      return;
    }
    JSONObject q = pending;
    setPending(new JSONObject());
    if (result != RESULT_OK) {
      if (request == CAMERA && !q.optString("cameraFile").isEmpty())
        new File(getCacheDir(), q.optString("cameraFile")).delete();
      return;
    }
    try {
      if (request == SCAN) {
        GmsDocumentScanningResult scan = GmsDocumentScanningResult.fromActivityResultIntent(data);
        if (scan == null || scan.getPages() == null || scan.getPages().isEmpty())
          throw new IOException("保存するスキャン画像がありませんでした");
        List<Uri> uris = new ArrayList<>();
        for (GmsDocumentScanningResult.Page page : scan.getPages()) uris.add(page.getImageUri());
        task("スキャン画像を保存中", () -> {
          JSONObject r = new Importer(this).run(uris, q.optString("mode", "all"),
              q.optString("id"), t -> event("busy", t), true);
          afterImport(r, q);
        });
      }
      if (request == PICK) {
        List<Uri> uris = new ArrayList<>();
        if (data != null && data.getClipData() != null) {
          for (int i = 0; i < data.getClipData().getItemCount(); i++)
            uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data != null && data.getData() != null) uris.add(data.getData());
        task(
            "名刺を取り込み中",
            () -> {
              JSONObject r =
                  new Importer(this)
                      .run(
                          uris,
                          q.optString("mode", "file"),
                          q.optString("id"),
                          t -> event("busy", t));
              afterImport(r, q);
            });
      }
      if (request == CAMERA) {
        File f = new File(getCacheDir(), q.getString("cameraFile"));
        if (!f.isFile() || f.length() == 0) throw new IOException("撮影画像を保存できませんでした");
        task(
            "撮影画像を保存中",
            () -> {
              try {
                JSONObject r =
                    new Importer(this)
                        .run(
                            Collections.singletonList(FilesProvider.uri(this, f)),
                            "all",
                            q.optString("id"),
                            t -> event("busy", t));
                afterImport(r, q);
              } finally {
                f.delete();
              }
            });
      }
      if (request == TREE && data != null && data.getData() != null) {
        Uri u = data.getData();
        getContentResolver()
            .takePersistableUriPermission(
                u, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getSharedPreferences("prefs", 0).edit().putString("folder", u.toString()).apply();
        scanFolder(u, q);
      }
      if (request == ASSET && data != null && data.getData() != null) {
        Uri u = data.getData();
        task(
            "画像を保存中",
            () -> {
              String kind = q.getString("kind");
              if (!kind.equals("portrait") && !kind.equals("logo")) throw new IOException("不正な種類");
              String name = new Importer(this).asset(u);
              JSONObject p = store.person(q.getString("id"));
              p.put(kind, name);
              store.put(p);
            });
      }
      if (request == SAVE && data != null && data.getData() != null) {
        Uri u = data.getData();
        task(
            "ファイルを保存中",
            () -> {
              File f = new File(getCacheDir(), q.getString("export"));
              try (InputStream in = new FileInputStream(f);
                  OutputStream out = getContentResolver().openOutputStream(u, "wt")) {
                if (out == null) throw new IOException("保存先を開けません");
                Store.copy(in, out, 3L * 1024 * 1024 * 1024);
              }
              event("report", "保存先への書き込みが完了しました。Google Driveへのアップロード状況はDriveアプリで確認してください。");
            });
      }
      if (request == RESTORE && data != null && data.getData() != null) {
        Uri u = data.getData();
        task(
            "バックアップを復元中",
            () -> {
              int count = Backup.restore(this, getContentResolver().openInputStream(u));
              event("report", count + "件を復元しました。同じIDの名刺は現在の内容を優先してスキップしました。");
            });
      }
      if (request == IMPORT_ZIP && data != null && data.getData() != null) {
        Uri u = data.getData();
        boolean toContacts = q.optBoolean("toContacts");
        Runnable go =
            () ->
                task(
                    "ZIPを取り込み中",
                    () -> {
                      JSONObject r = BulkImport.run(this, u, toContacts, t -> event("busy", t));
                      if (r.optInt("queued") > 0) startProcessing();
                      StringBuilder b = new StringBuilder(r.optInt("added") + "人を追加しました（画像 " + r.optInt("images") + "枚）");
                      if (r.optInt("skipped") > 0) b.append("\n取り込み済みのためスキップ：").append(r.optInt("skipped")).append("人");
                      if (r.optInt("queued") > 0) b.append("\nバックグラウンドで読み取り・連絡先登録：").append(r.optInt("queued")).append("人");
                      JSONArray e = r.getJSONArray("errors");
                      for (int k = 0; k < Math.min(e.length(), 30); k++) b.append("\n").append(e.getString(k));
                      if (e.length() > 30) b.append("\n…ほか").append(e.length() - 30).append("件");
                      event("home", "");
                      event("report", b.toString());
                    });
        if (toContacts) contactSetup(go);
        else go.run();
      }
      if (request == ACCOUNT && data != null) {
        String account = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME),
            type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        if (account == null || !"com.google".equals(type))
          throw new IOException("Googleアカウントを選択してください");
        prefs().edit().putString("contactAccount", account).apply();
        if (q.optBoolean("setupOnly")) {
          Runnable next = afterAccount;
          afterAccount = null;
          requeueContactless();
          refresh();
          if (next != null) next.run();
          return;
        }
        registerContact(q, account);
      }
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (store != null && ready) refresh();
    try {
      if (store != null && !ProcessingService.running && Pipeline.pending(store) > 0 && prefs().contains("key"))
        ProcessingService.start(this);
      // Pick up other devices' changes when the app comes back after a while.
      if (store != null
          && prefs().getBoolean("syncEnabled", false)
          && System.currentTimeMillis() - prefs().getLong("lastSync", 0) > 10 * 60 * 1000)
        ProcessingService.requestSync(this);
    } catch (Exception ignored) {
    }
  }

  // Leaving the app with unsynced edits: push them while we are still allowed to start work.
  @Override
  protected void onPause() {
    super.onPause();
    try {
      if (store != null
          && prefs().getBoolean("syncEnabled", false)
          && store.changedAt > prefs().getLong("lastSync", 0))
        ProcessingService.requestSync(this);
    } catch (Exception ignored) {
    }
  }

  @Override
  public void onBackPressed() {
    if (web != null && ready) web.evaluateJavascript("window.handleBack()", null);
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    cancelScannerPreparation();
    try {
      unregisterReceiver(receiver);
    } catch (Exception ignored) {
    }
    io.shutdown();
    if (web != null) {
      web.removeJavascriptInterface("Native");
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }
}
