package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class ImageZipTest {
  static File zip(Charset cs, String... names) throws Exception {
    File f = File.createTempFile("cards", ".zip");
    f.deleteOnExit();
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f), cs)) {
      for (String n : names) {
        z.putNextEntry(new ZipEntry(n));
        z.write(new byte[] {1});
        z.closeEntry();
      }
    }
    return f;
  }

  static Map<String, BulkImport.Card> groups(File f) throws Exception {
    try (ZipFile z = BulkImport.open(f)) {
      List<ZipEntry> list = new ArrayList<>();
      for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) list.add(e.nextElement());
      return BulkImport.group(list);
    }
  }

  @Test
  public void eightNamesPairAndPrefill() throws Exception {
    // Windows-made zip: Shift_JIS names without the UTF-8 flag.
    File f =
        zip(
            Charset.forName("windows-31j"),
            "cards/20211217_Aki Kiyoshige_Lexus Tezukayama_20792827764.jpg",
            "cards/20211217_Aki Kiyoshige_Lexus Tezukayama_20792827764_裏.jpg",
            "cards/20181201_名倉重勝_株式会社千勝不動産_320301927.jpg",
            "cards/20260722_須貝美穂_社会福祉法人__済生会大阪北リハビリテーション病院_21341892647.jpg");
    Map<String, BulkImport.Card> g = groups(f);
    assertEquals(3, g.size());
    BulkImport.Card aki = g.get("eight:20792827764");
    assertEquals("Aki Kiyoshige", aki.name);
    assertEquals("Lexus Tezukayama", aki.company);
    assertEquals("2021-12-17", aki.date);
    assertEquals(Arrays.asList(1, 2), new ArrayList<>(aki.sides.keySet()));
    assertTrue(aki.sides.get(2).get(0).getName().endsWith("_裏.jpg"));
    assertEquals("名倉重勝", g.get("eight:320301927").name);
    assertEquals("社会福祉法人 済生会大阪北リハビリテーション病院", g.get("eight:21341892647").company);
  }

  @Test
  public void genericSuffixesPair() throws Exception {
    File f = zip(Charset.forName("UTF-8"), "dl/001_1.jpg", "dl/001_2.jpg", "dl/山田_表.png", "dl/山田_裏.png", "dl/solo.jpg");
    Map<String, BulkImport.Card> g = groups(f);
    assertEquals(3, g.size());
    assertEquals(2, g.get("img:dl/001").sides.size());
    assertEquals(2, g.get("img:dl/山田").sides.size());
    assertEquals(1, g.get("img:dl/solo.jpg").sides.size());
  }

  // Optional check against a real export: REAL_ZIP=/path/to/cards.zip
  @Test
  public void realZipIfProvided() throws Exception {
    String path = System.getenv("REAL_ZIP");
    if (path == null || path.isEmpty()) return;
    Map<String, BulkImport.Card> g = groups(new File(path));
    int pages = 0, backs = 0, unnamed = 0;
    for (BulkImport.Card c : g.values()) {
      for (List<ZipEntry> l : c.sides.values()) pages += l.size();
      if (c.sides.containsKey(2)) backs++;
      if (c.name.isEmpty()) unnamed++;
      assertTrue("front missing: " + c.key, c.sides.containsKey(1));
    }
    System.out.println("REAL_ZIP people=" + g.size() + " pages=" + pages + " withBack=" + backs + " unnamed=" + unnamed);
  }

  @Test
  public void syncFolderRecordsAreRecognised() {
    String re = "(?i)(.*/)?people/[^/]+\\.json";
    assertTrue("people/abc.json".matches(re));
    assertTrue("Theta Meishi Hatch データ/people/abc-1.json".matches(re));
    assertTrue("export/People/x.JSON".matches(re));
    assertFalse("cards.csv".matches(re));
    assertFalse("people/sub/x.json".matches(re));
    assertFalse("media/x.json.jpg".matches(re));
  }
}
