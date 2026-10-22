package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;

// The same cases as desktop/test/qr.test.js. The vCard code drawn here is also written to
// desktop/test/fixtures/qr-vcard.txt for the desktop decoder.
public class QrTest {
  static final String VCARD =
      "BEGIN:VCARD\nVERSION:3.0\nN:山田;太郎;;;\nFN:山田 太郎\nORG:サンプル株式会社;営業部\nTITLE:部長\nTEL;TYPE=WORK:03-1234-5678\nTEL;TYPE=CELL:090-1111-2222\nTEL;TYPE=FAX:03-1234-5679\nEMAIL:taro-yamada-qn@example.co.jp\nURL:https://example.co.jp/\nADR;TYPE=WORK:;;丸の内1-1-1;千代田区;東京都;100-0005;Japan\nEND:VCARD";

  @Test
  public void decodesAPrintedCodeAmongOtherInk() throws Exception {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
    BitMatrix m = new QRCodeWriter().encode(VCARD, BarcodeFormat.QR_CODE, 0, 0, hints);
    StringBuilder rows = new StringBuilder();
    for (int y = 0; y < m.getHeight(); y++) {
      for (int x = 0; x < m.getWidth(); x++) rows.append(m.get(x, y) ? '1' : '0');
      rows.append('\n');
    }
    File d = new File("../../desktop/test/fixtures");
    if (d.isDirectory()) Files.write(new File(d, "qr-vcard.txt").toPath(), rows.toString().getBytes(StandardCharsets.UTF_8));
    // A "card" 900x600 with the code small in a corner (4px a module) and a dark band of text.
    int w = 900, h = 600, s = 4;
    int[] px = new int[w * h];
    Arrays.fill(px, 0xfff6f6f2);
    for (int y = 40; y < 60; y++) for (int x = 40; x < 600; x++) px[y * w + x] = 0xff202020;
    int ox = w - m.getWidth() * s - 30, oy = h - m.getHeight() * s - 30;
    for (int y = 0; y < m.getHeight() * s; y++)
      for (int x = 0; x < m.getWidth() * s; x++) if (m.get(x / s, y / s)) px[(oy + y) * w + ox + x] = 0xff111111;
    assertEquals(VCARD, Qr.decode(px, w, h));
    int[] blank = new int[w * h];
    Arrays.fill(blank, 0xffffffff);
    assertEquals("", Qr.decode(blank, w, h));
  }

  @Test
  public void vcardParsed() throws Exception {
    JSONObject q = Qr.parse(VCARD);
    assertEquals("山田 太郎", q.getString("name"));
    assertEquals("サンプル株式会社", q.getString("company"));
    assertEquals("営業部", q.getString("department"));
    assertEquals("100-0005", q.getString("postal"));
    assertEquals("東京都千代田区丸の内1-1-1", q.getString("address"));
    assertEquals("mobile", q.getJSONArray("phones").getJSONObject(1).getString("kind"));
    assertEquals("fax", q.getJSONArray("phones").getJSONObject(2).getString("kind"));
    assertEquals("https://example.co.jp/", q.getJSONArray("urls").getString(0));
    JSONObject me = Qr.parse("MECARD:N:山田,太郎;TEL:0312345678;EMAIL:t@example.jp;;");
    assertEquals("山田 太郎", me.getString("name"));
    assertEquals("t@example.jp", me.getJSONArray("emails").getString(0));
    assertEquals("t@example.jp", Qr.parse("mailto:t@example.jp?subject=hi").getJSONArray("emails").getString(0));
  }

  @Test
  public void exactCharactersWinGapsFilled() throws Exception {
    // Gemini misread one letter of the address; the phone it read in another form is kept.
    JSONObject r = new JSONObject().put("name", "山田 花子").put("email", "hanako-yamada-gn@example.co.jp").put("phone", "(06)0000-4444").put("mobile", "").put("fax", "").put("website", "").put("rawText", "サンプル薬品株式会社");
    Qr.apply(r, Collections.singletonList("hanako-yamada-qn@example.co.jp"));
    assertEquals("hanako-yamada-qn@example.co.jp", r.getString("email"));
    assertTrue(r.getString("rawText").endsWith("[QRコード] hanako-yamada-qn@example.co.jp"));
    JSONObject v = new JSONObject().put("name", "山田 太郎").put("email", "other@x.jp").put("phone", "03 1234 5678").put("mobile", "").put("fax", "").put("website", "").put("title", "");
    Qr.apply(v, Collections.singletonList(VCARD));
    assertEquals("other@x.jp\ntaro-yamada-qn@example.co.jp", v.getString("email"));
    assertEquals("03 1234 5678", v.getString("phone"));
    assertEquals("090-1111-2222", v.getString("mobile"));
    assertEquals("03-1234-5679", v.getString("fax"));
    assertEquals("https://example.co.jp/", v.getString("website"));
    assertEquals("部長", v.getString("title"));
    assertEquals("山田 太郎", v.getString("name"));
    assertEquals("", Qr.hint(new ArrayList<>()));
    assertTrue(Qr.hint(Collections.singletonList("x")).contains("QR1: x"));
  }
}
