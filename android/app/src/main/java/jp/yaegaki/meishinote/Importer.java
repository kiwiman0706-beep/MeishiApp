package jp.yaegaki.meishinote;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

public final class Importer {
  final Context c;
  final Store store;

  public interface Progress {
    void update(String text);
  }

  public Importer(Context c) throws Exception {
    this.c = c;
    store = Store.get(c);
  }

  public String name(Uri u) {
    try (Cursor q =
        c.getContentResolver()
            .query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (q != null && q.moveToFirst()) return q.getString(0);
    } catch (Exception ignored) {
    }
    return u.getLastPathSegment() == null ? "画像" : u.getLastPathSegment();
  }

  public List<Uri> folder(Uri tree) throws Exception {
    List<Uri> r = new ArrayList<>();
    walk(tree, DocumentsContract.getTreeDocumentId(tree), r, 0);
    r.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
    return r;
  }

  private void walk(Uri tree, String parent, List<Uri> out, int depth) throws Exception {
    if (depth > 8) throw new IOException("フォルダが深すぎます。取り込み対象を絞ってください");
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
    try (Cursor q =
        c.getContentResolver()
            .query(
                children,
                new String[] {
                  DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                  DocumentsContract.Document.COLUMN_MIME_TYPE,
                  DocumentsContract.Document.COLUMN_DISPLAY_NAME
                },
                null,
                null,
                null)) {
      if (q == null) throw new IOException("フォルダを開けません");
      while (q.moveToNext()) {
        String id = q.getString(0),
            mime = q.getString(1),
            name = q.getString(2).toLowerCase(Locale.ROOT);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(tree, id, out, depth + 1);
        else if ((mime != null && mime.startsWith("image/"))
            || "application/pdf".equals(mime)
            || name.matches(".*\\.(jpg|jpeg|png|webp|heic|heif|pdf)$")) {
          out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
          if (out.size() > 200) throw new IOException("1回の取り込みは200ファイルまでです。フォルダを分けてください");
        }
      }
    }
  }

  public static Bitmap image(File f, int max) throws Exception {
    Bitmap b;
    if (Build.VERSION.SDK_INT >= 28) {
      b =
          ImageDecoder.decodeBitmap(
              ImageDecoder.createSource(f),
              (d, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float scale = Math.min(1f, (float) max / Math.max(w, h));
                d.setTargetSize(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              });
    } else {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(f.getPath(), o);
      if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("この画像形式を読み取れません");
      o.inSampleSize = 1;
      while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > max * 1.5) o.inSampleSize *= 2;
      o.inJustDecodeBounds = false;
      b = BitmapFactory.decodeFile(f.getPath(), o);
      if (b == null) throw new IOException("画像が読み込めません");
      try {
        int v = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        Matrix m = new Matrix();
        switch (v) {
          case 2:
            m.setScale(-1, 1);
            break;
          case 3:
            m.setRotate(180);
            break;
          case 4:
            m.setRotate(180);
            m.postScale(-1, 1);
            break;
          case 5:
            m.setRotate(90);
            m.postScale(-1, 1);
            break;
          case 6:
            m.setRotate(90);
            break;
          case 7:
            m.setRotate(-90);
            m.postScale(-1, 1);
            break;
          case 8:
            m.setRotate(-90);
            break;
        }
        if (v > 1) {
          Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
          if (rotated != b) b.recycle();
          b = rotated;
        }
      } catch (IOException ignored) {
      }
    }
    return b;
  }

  public static void jpeg(Bitmap b, File to) throws Exception {
    Bitmap flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(flat);
    canvas.drawColor(Color.WHITE);
    canvas.drawBitmap(b, 0, 0, null);
    try (OutputStream out = new FileOutputStream(to)) {
      if (!flat.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new IOException("画像保存に失敗しました");
    } finally {
      flat.recycle();
    }
  }

  static String hash(File f) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(f)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) md.update(b, 0, n);
    }
    StringBuilder s = new StringBuilder();
    for (byte x : md.digest()) s.append(String.format("%02x", x));
    return s.toString();
  }

  public JSONObject run(List<Uri> uris, String mode, String target, Progress progress)
      throws Exception {
    return run(uris, mode, target, progress, false);
  }

  public JSONObject run(List<Uri> uris, String mode, String target, Progress progress,
      boolean preserveOrder) throws Exception {
    uris = new ArrayList<>(uris);
    // Scanner results use the user's final page order; file/folder imports use filename order.
    if (!preserveOrder) uris.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
    if (uris.size() > 200) throw new IOException("一度に200ファイルまで選択してください");
    Set<String> seen = store.hashes();
    List<List<JSONObject>> docs = new ArrayList<>();
    List<JSONObject> sources = new ArrayList<>();
    JSONArray errors = new JSONArray();
    int skipped = 0;
    List<File> uncommitted = new ArrayList<>();
    try {
      for (int i = 0; i < uris.size(); i++) {
        Uri u = uris.get(i);
        String original = name(u);
        progress.update("画像を保存中 " + (i + 1) + " / " + uris.size() + "\n" + original);
        File temp = File.createTempFile("scan-", ".tmp", c.getCacheDir());
        List<File> current = new ArrayList<>();
        try {
          try (InputStream in = c.getContentResolver().openInputStream(u);
              OutputStream out = new FileOutputStream(temp)) {
            Store.copy(in, out, 100L * 1024 * 1024);
          }
          String hash = hash(temp);
          if (seen.contains(hash)) {
            skipped++;
            if (mode.equals("pair") && target.isEmpty()) {
              int count = 1;
              String type = c.getContentResolver().getType(u);
              if ("application/pdf".equals(type)
                  || original.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                try (ParcelFileDescriptor fd =
                        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer = new PdfRenderer(fd)) {
                  count = renderer.getPageCount();
                  if (count > 100) throw new IOException("PDFは100ページまでです");
                }
              }
              List<JSONObject> placeholders = new ArrayList<>();
              for (int k = 0; k < count; k++) placeholders.add(new JSONObject().put("skip", true));
              docs.add(placeholders);
            }
            continue;
          }
          String mime = c.getContentResolver().getType(u);
          boolean pdf =
              "application/pdf".equals(mime) || original.toLowerCase(Locale.ROOT).endsWith(".pdf");
          String base = UUID.randomUUID().toString();
          File source = store.mediaFile(base + (pdf ? ".pdf" : ".original"));
          try (InputStream in = new FileInputStream(temp);
              OutputStream out = new FileOutputStream(source)) {
            Store.copy(in, out, 100L * 1024 * 1024);
          }
          current.add(source);
          List<JSONObject> pages = new ArrayList<>();
          JSONObject src =
              new JSONObject()
                  .put("file", source.getName())
                  .put("name", original)
                  .put("hash", hash)
                  .put("mime", pdf ? "application/pdf" : (mime == null ? "image/jpeg" : mime));
          if (pdf) {
            try (ParcelFileDescriptor fd =
                    ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer = new PdfRenderer(fd)) {
              if (renderer.getPageCount() > 100) throw new IOException("PDFは100ページまでです");
              if (renderer.getPageCount() == 0) throw new IOException("空のPDFです");
              for (int p = 0; p < renderer.getPageCount(); p++) {
                try (PdfRenderer.Page page = renderer.openPage(p)) {
                  float scale = 2400f / Math.max(page.getWidth(), page.getHeight());
                  Bitmap b =
                      Bitmap.createBitmap(
                          Math.max(1, (int) (page.getWidth() * scale)),
                          Math.max(1, (int) (page.getHeight() * scale)),
                          Bitmap.Config.ARGB_8888);
                  b.eraseColor(Color.WHITE);
                  page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                  File f = store.mediaFile(base + "-" + p + ".jpg");
                  current.add(f);
                  try {
                    jpeg(b, f);
                  } finally {
                    b.recycle();
                  }
                  pages.add(
                      new JSONObject()
                          .put("file", f.getName())
                          .put("name", original + " / " + (p + 1))
                          .put("source", source.getName()));
                }
              }
            }
          } else {
            Bitmap b = image(temp, 2400);
            File f = store.mediaFile(base + ".jpg");
            current.add(f);
            try {
              jpeg(b, f);
            } finally {
              b.recycle();
            }
            pages.add(
                new JSONObject()
                    .put("file", f.getName())
                    .put("name", original)
                    .put("source", source.getName()));
          }
          docs.add(pages);
          sources.add(src);
          seen.add(hash);
          uncommitted.addAll(current);
        } catch (Exception e) {
          for (File f : current) f.delete();
          errors.put(original + ": " + e.getMessage());
        } finally {
          temp.delete();
        }
      }
      if (mode.equals("pair") && errors.length() > 0)
        throw new IOException(
            "表裏の組み合わせがずれないよう、この回の取り込みを中止しました。読み込めないファイルを取り除いてください。\n" + errors.toString());
      List<List<JSONObject>> groups = new ArrayList<>();
      List<JSONObject> flat = new ArrayList<>();
      for (List<JSONObject> d : docs) flat.addAll(d);
      if (!target.isEmpty() || mode.equals("all")) {
        if (!flat.isEmpty()) groups.add(flat);
      } else if (mode.equals("file")) {
        groups.addAll(docs);
      } else {
        int size = mode.equals("pair") ? 2 : 1;
        for (int i = 0; i < flat.size(); i += size)
          groups.add(new ArrayList<>(flat.subList(i, Math.min(i + size, flat.size()))));
      }
      JSONArray ids = new JSONArray();
      int importedPages = 0;
      for (List<JSONObject> group : groups) {
        boolean skipGroup = false, hasNew = false;
        for (JSONObject page : group) {
          if (page.optBoolean("skip")) skipGroup = true;
          else hasNew = true;
        }
        if (skipGroup) {
          if (hasNew) errors.put("表裏の片側だけが登録済みの組をスキップしました。該当する人の詳細から画像を追加してください。");
          continue;
        }
        importedPages += group.size();
        JSONObject p = target.isEmpty() ? Store.empty() : store.person(target);
        Set<String> srcNames = new HashSet<>();
        for (JSONObject page : group) {
          p.getJSONArray("pages").put(page);
          srcNames.add(page.getString("source"));
        }
        Set<String> oldSrc = new HashSet<>();
        for (int j = 0; j < p.getJSONArray("sources").length(); j++)
          oldSrc.add(p.getJSONArray("sources").getJSONObject(j).getString("file"));
        for (JSONObject src : sources)
          if (srcNames.contains(src.getString("file")) && !oldSrc.contains(src.getString("file")))
            p.getJSONArray("sources").put(src);
        p.put("status", "new");
        store.put(p);
        ids.put(p.getString("id"));
      }
      return new JSONObject()
          .put("ids", ids)
          .put("skipped", skipped)
          .put("errors", errors)
          .put("pages", importedPages);
    } finally {
      Set<String> refs = Store.referenced(store.snapshot());
      for (File f : uncommitted) if (!refs.contains(f.getName())) f.delete();
    }
  }

  public String asset(Uri uri) throws Exception {
    File temp = File.createTempFile("asset-", ".tmp", c.getCacheDir());
    try {
      try (InputStream in = c.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(temp)) {
        Store.copy(in, out, 30L * 1024 * 1024);
      }
      Bitmap b = image(temp, 1400);
      File f = store.mediaFile(UUID.randomUUID() + ".jpg");
      try {
        jpeg(b, f);
      } finally {
        b.recycle();
      }
      return f.getName();
    } finally {
      temp.delete();
    }
  }
}
