package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.nio.charset.Charset;
import java.util.*;
import org.junit.Test;

public class BulkImportTest {
  @Test
  public void csvHandlesQuotesCommasAndNewlines() {
    List<List<String>> rows =
        BulkImport.csv("氏名,住所,メモ\r\n\"山田, 太郎\",\"東京都\n千代田区\",\"He said \"\"hi\"\"\"\r\n佐藤,,\n");
    assertEquals(3, rows.size());
    assertEquals(Arrays.asList("山田, 太郎", "東京都\n千代田区", "He said \"hi\""), rows.get(1));
    assertEquals(Arrays.asList("佐藤", "", ""), rows.get(2));
  }

  @Test
  public void decodeDetectsUtf8BomAndShiftJis() {
    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xE5, (byte) 0x90, (byte) 0x8D};
    assertEquals("名", BulkImport.decode(bom));
    byte[] sjis = "会社名,氏名".getBytes(Charset.forName("windows-31j"));
    assertEquals("会社名,氏名", BulkImport.decode(sjis));
  }

  @Test
  public void dateNormalisesCommonFormats() {
    assertEquals("2024-03-05", BulkImport.date("2024/3/5"));
    assertEquals("2024-03-05", BulkImport.date("2024年3月5日"));
    assertEquals("2024-12-01", BulkImport.date("２０２４-12-01 10:00"));
    assertEquals("", BulkImport.date("不明"));
  }

  @Test
  public void headerAliasesAreLoose() {
    assertEquals("company", BulkImport.HEADERS.get(BulkImport.norm("会社名")));
    assertEquals("email", BulkImport.HEADERS.get(BulkImport.norm("E-mail")));
    assertEquals("phone", BulkImport.HEADERS.get(BulkImport.norm("TEL 会社")));
    assertEquals("lastKana", BulkImport.HEADERS.get(BulkImport.norm("姓（カナ）")));
    assertEquals("front", BulkImport.HEADERS.get(BulkImport.norm("表面画像")));
    assertEquals("back", BulkImport.HEADERS.get(BulkImport.norm("裏面画像")));
    assertEquals("mobile", BulkImport.HEADERS.get(BulkImport.norm("携帯電話")));
  }
}
