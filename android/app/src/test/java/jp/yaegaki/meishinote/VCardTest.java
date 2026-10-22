package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;

// The same people as desktop/test/vcard.test.js: Android must write the same bytes
// (desktop/test/fixtures/contacts.vcf).
public class VCardTest {
  static List<JSONObject> people() throws Exception {
    return Arrays.asList(
        new JSONObject()
            .put("name", "山田 太郎").put("kana", "やまだ たろう").put("company", "サンプル商事株式会社").put("department", "営業本部 大阪支店")
            .put("title", "").put("phone", "(06)0000-1111").put("mobile", "090-0000-2222").put("fax", "(06)0000-3333")
            .put("email", "taro-yamada-qn@example.co.jp").put("website", "").put("postal", "541-0000").put("address", "大阪市中央区本町1-2-3")
            .put("tags", "取引先、展示会").put("metDate", "2026-10-01").put("metPlace", "大阪, 本町"),
        new JSONObject()
            .put("kind", "shop").put("name", "炭火焼 さくら").put("phone", "06-0000-0000").put("genre", "焼き鳥")
            .put("hours", "17:00-23:00").put("holidays", "月曜").put("website", "https://example.jp/sakura"));
  }

  @Test
  public void sameBytesAsTheDesktop() throws Exception {
    String expected = new String(Files.readAllBytes(new File("../../desktop/test/fixtures/contacts.vcf").toPath()), StandardCharsets.UTF_8);
    assertEquals(expected, VCard.build(people()));
    assertEquals("山田_太郎.vcf", VCard.fileName(people().subList(0, 1)));
    assertEquals("名刺_2件.vcf", VCard.fileName(people()));
  }

  @Test
  public void longLinesFoldBetweenCharacters() {
    StringBuilder b = new StringBuilder("NOTE:");
    for (int i = 0; i < 60; i++) b.append('あ');
    String f = VCard.fold(b.toString());
    assertEquals(b.toString(), f.replace("\r\n ", ""));
    for (String l : f.split("\r\n")) assertTrue(l.getBytes(StandardCharsets.UTF_8).length <= 75);
  }
}
