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
      DRIVE_AUTH = 18;
  // Full Drive scope: the ScanSnap folder and its files were not created by this app,
  // and moving them into 登録済み needs write access to them.
  static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
  DriveWork pendingDrive;
  String pendingDriveTitle = "";
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
              if (!"app.meishinote.local".equals(u.getHost()) || !"https".equals(u.getScheme()))
                return blocked();
              String p = u.getPath();
              if (p.equals("/") || p.equals("/index.html"))
                return response("text/html", getAssets().open("index.html"));
              if (p.equals("/style.css"))
                return response("text/css", getAssets().open("style.css"));
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

  void refresh() {
    try {
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

  // New people from any import go straight on to Gemini when auto-recognition is on,
  // so the user lands on the review screen (one person) or the 要確認 list (several).
  void afterImport(JSONObject imported, JSONObject q) throws Exception {
    JSONArray ids = imported.getJSONArray("ids");
    boolean auto =
        q.optString("id").isEmpty()
            && ids.length() > 0
            && prefs().getBoolean("autoRecognize", true);
    if (!auto || !prefs().contains("key")) {
      event("imported", imported);
      if (auto && q.optBoolean("auto")) event("toast", "APIキーを設定すると、取り込み後に自動で読み取ります");
      return;
    }
    if (ids.length() == 1) {
      String id = ids.getString(0);
      event("busy", "Geminiで名刺を読み取り中");
      try {
        recognizeOne(id, new Gemini(this));
        event("review", id);
        if (imported.optInt("skipped") > 0 || imported.getJSONArray("errors").length() > 0)
          event("report", importSummary(imported));
      } catch (Exception e) {
        event("open", id);
        event("report", "画像は保存しました。読み取りに失敗したため、詳細画面の「Geminiで読み取る」から再試行してください。\n\n" + e.getMessage());
      }
      return;
    }
    List<String> list = new ArrayList<>();
    for (int i = 0; i < ids.length(); i++) list.add(ids.getString(i));
    event("batchDone", importSummary(imported) + "\n\n" + recognizeIds(list));
  }

  static String importSummary(JSONObject r) throws Exception {
    StringBuilder b =
        new StringBuilder(r.getJSONArray("ids").length() + "人・" + r.optInt("pages") + "ページを取り込みました。");
    if (r.optInt("skipped") > 0) b.append("\n取り込み済みのためスキップ：").append(r.optInt("skipped")).append("ファイル");
    if (r.optInt("moved") > 0) b.append("\n「" + Drive.DONE_FOLDER + "」へ移動：").append(r.optInt("moved")).append("ファイル");
    JSONArray errors = r.getJSONArray("errors");
    if (errors.length() > 0) {
      b.append("\n\n取り込めなかったファイル：");
      for (int i = 0; i < errors.length(); i++) b.append("\n").append(errors.getString(i));
    }
    return b.toString();
  }

  void recognizeOne(String id, Gemini g) throws Exception {
    JSONObject result = g.recognize(store.person(id));
    JSONObject p = store.person(id);
    p.put("suggestion", result).put("status", "review");
    store.put(p);
  }

  // Stops early on quota/auth errors so a bad key does not burn through every card.
  String recognizeIds(List<String> ids) throws Exception {
    Gemini g = new Gemini(this);
    int done = 0, failed = 0;
    StringBuilder errors = new StringBuilder();
    for (String id : ids) {
      String name = store.person(id).optString("name");
      event("busy", "Geminiで読み取り中 " + (done + failed + 1) + " / " + ids.size() + "\n" + name);
      try {
        recognizeOne(id, g);
        done++;
      } catch (Exception e) {
        failed++;
        errors.append("\n").append(name.isEmpty() ? "未登録" : name).append(": ").append(e.getMessage());
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("429") || m.contains("403") || m.contains("401")) {
          errors.append("\n利用上限または認証エラーのため中断しました。残りはメニューの「未認識をまとめて読み取る」から再開できます。");
          break;
        }
      }
    }
    return "読み取り完了 " + done + "件 / 失敗 " + failed + "件\n「要確認」から1人ずつ確認して保存してください。" + errors;
  }

  // Authorization is re-requested before each Drive job: it is silent once granted and
  // hands back a fresh short-lived token, so nothing needs to be stored.
  void withDrive(String title, DriveWork work) {
    if (busy || scannerLoading != null) {
      event("toast", "処理が終わるまでお待ちください");
      return;
    }
    AuthorizationRequest req =
        AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_SCOPE)))
            .build();
    Identity.getAuthorizationClient(this)
        .authorize(req)
        .addOnSuccessListener(
            r -> {
              if (r.hasResolution()) {
                pendingDrive = work;
                pendingDriveTitle = title;
                try {
                  startIntentSenderForResult(
                      r.getPendingIntent().getIntentSender(), DRIVE_AUTH, null, 0, 0, 0);
                } catch (Exception e) {
                  pendingDrive = null;
                  error(e);
                }
              } else runDrive(title, work, r.getAccessToken());
            })
        .addOnFailureListener(e -> event("error", driveSetupHelp(e)));
  }

  void runDrive(String title, DriveWork work, String token) {
    if (token == null || token.isEmpty()) {
      event("error", "Google Driveの許可を取得できませんでした。もう一度お試しください。");
      return;
    }
    task(title, () -> work.run(new Drive(token)));
  }

  static String driveSetupHelp(Exception e) {
    return "Google Driveに接続できませんでした。\n\n"
        + "初回はGoogle Cloudで、このアプリ用のOAuthクライアント（Android）登録が必要です。"
        + "手順はリポジトリの docs/DRIVE_SETUP.md を参照してください。\n\n詳細: "
        + e.getMessage();
  }

  void driveImport(Drive d) throws Exception {
    String folder = prefs().getString("driveFolderId", "");
    if (folder.isEmpty()) throw new IOException("先にメニューからGoogle Driveのフォルダを設定してください");
    String mode = prefs().getString("driveMode", "file");
    event("busy", "Google Driveを確認中");
    JSONArray files = d.cards(folder);
    if (files.length() == 0) {
      event("toast", "新しい名刺はありません");
      return;
    }
    if (files.length() > 200) throw new IOException("1回の取り込みは200ファイルまでです。フォルダ内を減らしてください");
    String done = d.subfolder(folder, Drive.DONE_FOLDER);
    Importer importer = new Importer(this);
    JSONArray ids = new JSONArray(), errors = new JSONArray();
    int pages = 0, skipped = 0, moved = 0;
    // Pairing across files needs the whole batch at once; every other mode goes file by file
    // so each file is moved only after its own import succeeded.
    boolean perFile = !mode.equals("pair") && !mode.equals("all");
    List<File> staged = new ArrayList<>();
    List<Uri> uris = new ArrayList<>();
    List<JSONObject> metas = new ArrayList<>();
    try {
      for (int i = 0; i < files.length(); i++) {
        JSONObject f = files.getJSONObject(i);
        String name = f.optString("name");
        event("busy", "Google Driveから取得中 " + (i + 1) + " / " + files.length() + "\n" + name);
        String ext = name.toLowerCase(Locale.ROOT).replaceAll("^.*(\\.[a-z0-9]{1,5})$", "$1");
        if (!ext.matches("\\.[a-z0-9]{1,5}"))
          ext = f.optString("mimeType").equals("application/pdf") ? ".pdf" : ".jpg";
        File local = new File(getCacheDir(), "drive-" + UUID.randomUUID() + ext);
        staged.add(local);
        try {
          d.download(f.getString("id"), local);
        } catch (Exception e) {
          errors.put(name + ": " + e.getMessage());
          continue;
        }
        Uri u = FilesProvider.uri(this, local);
        importer.names.put(u.toString(), name);
        if (!perFile) {
          uris.add(u);
          metas.add(f);
          continue;
        }
        JSONObject r = importer.run(Collections.singletonList(u), "file", "", t -> {}, true);
        JSONArray e = r.getJSONArray("errors");
        for (int k = 0; k < e.length(); k++) errors.put(e.get(k));
        for (int k = 0; k < r.getJSONArray("ids").length(); k++) ids.put(r.getJSONArray("ids").get(k));
        pages += r.optInt("pages");
        skipped += r.optInt("skipped");
        if (e.length() == 0) moved += moveDone(d, f, folder, done, errors);
      }
      if (!perFile && errors.length() > 0)
        throw new IOException(
            "組み合わせがずれないよう、この回の取り込みを中止しました（Driveのファイルは移動していません）。\n" + errors.toString());
      if (!perFile && !uris.isEmpty()) {
        event("busy", "名刺を保存中");
        JSONObject r = importer.run(uris, mode, "", t -> event("busy", t), true);
        ids = r.getJSONArray("ids");
        pages = r.optInt("pages");
        skipped = r.optInt("skipped");
        JSONArray e = r.getJSONArray("errors");
        for (int k = 0; k < e.length(); k++) errors.put(e.get(k));
        // Importer aborts the whole pair batch on any bad file, so reaching here means all saved.
        for (JSONObject f : metas) moved += moveDone(d, f, folder, done, errors);
      }
    } finally {
      for (File f : staged) f.delete();
    }
    afterImport(
        new JSONObject()
            .put("ids", ids)
            .put("pages", pages)
            .put("skipped", skipped)
            .put("moved", moved)
            .put("errors", errors),
        new JSONObject());
  }

  int moveDone(Drive d, JSONObject f, String from, String to, JSONArray errors) {
    try {
      d.move(f.getString("id"), from, to);
      return 1;
    } catch (Exception e) {
      errors.put(f.optString("name") + "：取り込みましたが「" + Drive.DONE_FOLDER + "」へ移動できませんでした（" + e.getMessage() + "）");
      return 0;
    }
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
      else event("error", "必要な権限が許可されませんでした。設定 → アプリ → 名刺ノート → 権限から変更できます。");
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
                "driveImport")
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
        store.edit(id, q.getJSONObject("person"));
        refresh();
        event("saved", id);
        if (q.optBoolean("thenContact")) handle("contacts", q);
        break;
      case "delete":
        if (id.equals(RecordingService.activeId)) throw new IOException("録音を停止してから削除してください");
        store.remove(id);
        refresh();
        event("home", "");
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
          store.mergePeople(id, q.getString("sourceId"));
          refresh();
          event("open", id);
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
      case "driveImport":
        withDrive("Google Driveから取り込み中", this::driveImport);
        break;
      case "quickScan":
        startScanner(q.put("mode", "all").put("id", "").put("auto", true));
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
          e.apply();
          refresh();
          event("toast", "設定を保存しました");
          break;
        }
      case "models":
        task("利用可能なモデルを取得中", () -> event("models", new Gemini(this).models()));
        break;
      case "recognize":
        task(
            "Geminiで名刺を読み取り中",
            () -> {
              JSONObject p = store.person(id);
              JSONObject result = new Gemini(this).recognize(p);
              p = store.person(id);
              p.put("suggestion", result).put("status", "review");
              store.put(p);
              event("review", id);
            });
        break;
      case "recognizeAll":
        task(
            "未認識の名刺を読み取り中",
            () -> {
              JSONArray a = store.snapshot().getJSONArray("people");
              List<String> ids = new ArrayList<>();
              for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                if (p.optString("status").equals("new") && p.getJSONArray("pages").length() > 0)
                  ids.add(p.getString("id"));
              }
              event("batchDone", recognizeIds(ids));
            });
        break;
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
              File f = kind.equals("csv") ? Backup.csv(this) : Backup.make(this);
              String mime = kind.equals("csv") ? "text/csv" : "application/zip";
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
                                    "meishinote-" + stamp + (kind.equals("csv") ? ".csv" : ".zip")),
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
      DriveWork work = pendingDrive;
      pendingDrive = null;
      if (result != RESULT_OK || work == null) {
        if (work == null && result == RESULT_OK) event("toast", "許可しました。もう一度操作してください");
        return;
      }
      try {
        AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
        runDrive(pendingDriveTitle, work, r.getAccessToken());
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
      if (request == ACCOUNT && data != null) {
        String account = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME),
            type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        if (account == null || !"com.google".equals(type))
          throw new IOException("Googleアカウントを選択してください");
        prefs().edit().putString("contactAccount", account).apply();
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
