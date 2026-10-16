package jp.yaegaki.meishinote.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import jp.yaegaki.meishinote.*;
import org.json.*;

public class Smoke extends Instrumentation {
  StringBuilder log = new StringBuilder();
  int checks = 0;

  void check(boolean v, String message) throws Exception {
    if (!v) throw new Exception("FAIL: " + message);
    checks++;
    log.append("PASS ").append(message).append('\n');
  }

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      tests();
      result.putString("stream", log.toString() + "PASS " + checks + " checks\n");
      finish(-1, result);
    } catch (Throwable e) {
      result.putString(
          "stream", log + "FAIL " + e.toString() + "\n" + android.util.Log.getStackTraceString(e));
      finish(0, result);
    }
  }

  void tests() throws Exception {
    Context c = getTargetContext();
    Store s = Store.get(c);
    check(s.snapshot().getJSONArray("people").length() == 0, "empty storage");
    JSONObject person = Store.empty();
    String id = person.getString("id");
    person.put("name", "テスト 太郎").put("company", "サンプル株式会社").put("status", "confirmed");
    s.put(person);
    person.put("name", "mutated copy");
    check(s.person(id).getString("name").equals("テスト 太郎"), "store isolation");
    s.note(id, "面談メモ\n次回の予定");
    check(s.person(id).getJSONArray("notes").length() == 1, "note persisted");
    List<Uri> images = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      File f = new File(c.getCacheDir(), "scan-" + i + ".jpg");
      Bitmap b = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(b);
      canvas.drawColor(Color.rgb(230, 240 - i * 12, 230));
      Paint paint = new Paint();
      paint.setTextSize(30);
      paint.setColor(Color.BLACK);
      canvas.drawText("TEST CARD " + i, 20, 70, paint);
      canvas.drawText(i % 2 == 0 ? "FRONT" : "BACK", 20, 130, paint);
      try (OutputStream o = new FileOutputStream(f)) {
        b.compress(Bitmap.CompressFormat.JPEG, 95, o);
      }
      b.recycle();
      images.add(Uri.fromFile(f));
    }
    Importer importer = new Importer(c);
    Importer.Progress progress =
        new Importer.Progress() {
          public void update(String x) {}
        };
    JSONObject imported = importer.run(images.subList(0, 2), "pair", "", progress);
    check(imported.getJSONArray("ids").length() == 1, "pair groups two pages");
    String pairId = imported.getJSONArray("ids").getString(0);
    check(s.person(pairId).getJSONArray("pages").length() == 2, "both sides stored");
    JSONObject rerun = importer.run(images, "pair", "", progress);
    check(
        rerun.getInt("skipped") == 2 && rerun.getJSONArray("ids").length() == 1,
        "duplicate pair does not shift subsequent pair");
    String newPairId = rerun.getJSONArray("ids").getString(0);
    check(
        s.person(newPairId)
            .getJSONArray("pages")
            .getJSONObject(0)
            .getString("name")
            .contains("scan-2"),
        "pair order preserved");
    // Scanner filenames need not match its page order. Reverse four generated pages,
    // remove prior imports so dedupe does not hide ordering errors, then pair them.
    s.remove(pairId);
    s.remove(newPairId);
    List<Uri> scannerPages = new ArrayList<>(images);
    Collections.reverse(scannerPages);
    JSONObject scans = importer.run(scannerPages, "pair", "", progress, true);
    check(scans.getJSONArray("ids").length() == 2, "scanner batch creates two people");
    pairId = scans.getJSONArray("ids").getString(0);
    newPairId = scans.getJSONArray("ids").getString(1);
    check(s.person(pairId).getJSONArray("pages").getJSONObject(0).getString("name").contains("scan-3")
        && s.person(pairId).getJSONArray("pages").getJSONObject(1).getString("name").contains("scan-2"),
        "scanner preserves reviewed order rather than filenames");
    check(s.person(newPairId).getJSONArray("pages").getJSONObject(0).getString("name").contains("scan-1"),
        "scanner pair boundary is preserved");
    check(scannerPages.get(0).equals(images.get(3)), "caller page sequence is unchanged");
    File pdf = new File(c.getCacheDir(), "multi.pdf");
    PdfDocument doc = new PdfDocument();
    for (int i = 0; i < 3; i++) {
      PdfDocument.Page page =
          doc.startPage(new PdfDocument.PageInfo.Builder(300, 200, i + 1).create());
      page.getCanvas().drawColor(Color.WHITE);
      Paint paint = new Paint();
      paint.setTextSize(25);
      page.getCanvas().drawText("PDF page " + i, 20, 50, paint);
      doc.finishPage(page);
    }
    try (OutputStream out = new FileOutputStream(pdf)) {
      doc.writeTo(out);
    }
    doc.close();
    JSONObject pdfImport =
        importer.run(Collections.singletonList(Uri.fromFile(pdf)), "file", "", progress);
    check(
        pdfImport.getJSONArray("ids").length() == 1 && pdfImport.getInt("pages") == 3,
        "multi-page PDF one person");
    int before = s.snapshot().getJSONArray("people").length();
    File broken = new File(c.getCacheDir(), "broken.jpg");
    try (OutputStream o = new FileOutputStream(broken)) {
      o.write(new byte[] {1, 2, 3});
    }
    boolean rejected = false;
    try {
      importer.run(Collections.singletonList(Uri.fromFile(broken)), "pair", "", progress);
    } catch (Exception e) {
      rejected = true;
    }
    check(
        rejected && s.snapshot().getJSONArray("people").length() == before,
        "bad pair import is non-destructive");
    File csv = Backup.csv(c);
    String csvText;
    try (InputStream in = new FileInputStream(csv)) {
      csvText = new String(Store.read(in, 100000), "UTF-8");
    }
    check(csvText.charAt(0) == '\uFEFF' && csvText.contains("面談メモ"), "CSV BOM and Japanese notes");
    JSONObject edit = new JSONObject().put("company", "=1+1");
    s.edit(id, edit);
    csv = Backup.csv(c);
    try (InputStream in = new FileInputStream(csv)) {
      csvText = new String(Store.read(in, 100000), "UTF-8");
    }
    check(csvText.contains("'=1+1"), "CSV spreadsheet formula escaped");
    File backup = Backup.make(c);
    check(backup.length() > 0, "backup generated");
    Set<String> files = Store.referenced(s.snapshot());
    Map<String, Long> sizes = new HashMap<>();
    for (String name : files) sizes.put(name, s.mediaFile(name).length());
    JSONArray all = s.snapshot().getJSONArray("people");
    for (int i = 0; i < all.length(); i++) s.remove(all.getJSONObject(i).getString("id"));
    check(s.snapshot().getJSONArray("people").length() == 0, "deletion persisted");
    for (String name : files) check(!s.mediaFile(name).exists(), "unreferenced attachment removed");
    int count = Backup.restore(c, new FileInputStream(backup));
    check(count == before, "backup restores all people");
    check(s.person(pairId).getJSONArray("pages").length() == 2, "backup restores both sides");
    check(
        s.person(id).getJSONArray("notes").getJSONObject(0).getString("text").contains("面談"),
        "backup restores notes");
    for (String name : Store.referenced(s.snapshot()))
      check(s.mediaFile(name).length() > 0, "restored attachment bytes");
    JSONArray restored = s.snapshot().getJSONArray("people");
    for (int i = 0; i < restored.length(); i++) {
      JSONArray sources = restored.getJSONObject(i).getJSONArray("sources");
      for (int j = 0; j < sources.length(); j++) {
        JSONObject source = sources.getJSONObject(j);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(s.mediaFile(source.getString("file")))) {
          byte[] buf = new byte[65536];
          int n;
          while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        check(hex.toString().equals(source.getString("hash")), "original SHA-256 preserved");
      }
    }
    s.edit(id, new JSONObject().put("name", "現在の編集を保持"));
    check(Backup.restore(c, new FileInputStream(backup)) == 0, "repeat restore skips known IDs");
    check(
        s.person(id).getString("name").equals("現在の編集を保持"),
        "restore does not overwrite current edits");
    ByteArrayOutputStream malicious = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(malicious)) {
      z.putNextEntry(new ZipEntry("../../escape.txt"));
      z.write(1);
      z.closeEntry();
    }
    rejected = false;
    try {
      Backup.restore(c, new ByteArrayInputStream(malicious.toByteArray()));
    } catch (Exception e) {
      rejected = true;
    }
    check(rejected, "ZIP traversal rejected");
    ByteArrayOutputStream missing = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(missing)) {
      z.putNextEntry(new ZipEntry("data.json"));
      z.write(s.snapshot().toString().getBytes("UTF-8"));
      z.closeEntry();
    }
    rejected = false;
    try {
      Backup.restore(c, new ByteArrayInputStream(missing.toByteArray()));
    } catch (Exception e) {
      rejected = true;
    }
    check(
        rejected && s.snapshot().getJSONArray("people").length() == before,
        "incomplete restore leaves data intact");
    s.mergePeople(pairId, newPairId);
    check(s.person(pairId).getJSONArray("pages").length() == 4, "merge preserves all pages");
    check(
        s.snapshot().getJSONArray("people").length() == before - 1,
        "merge removes source person atomically");
  }
}
