package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class VaultCodecTest {
  static JSONObject sample() throws Exception {
    JSONObject p = new JSONObject().put("id", "2b6f3c1e-aaaa-bbbb-cccc-000000000001");
    for (String k : Store.FIELDS) p.put(k, "");
    p.put("name", "山田 太郎").put("kana", "やまだ たろう").put("company", "サンプル株式会社").put("title", "部長")
        .put("email", "taro@example.com\ntaro2@example.com").put("mobile", "090-1234-5678")
        .put("address", "東京都千代田区丸の内1-1-1").put("tags", "取引先、展示会").put("metDate", "2024-03-05")
        .put("metPlace", "展示会: 東京ビッグサイト").put("status", "confirmed").put("contactRegistered", true)
        .put("created", 1709600400123L).put("updated", 1760000000456L)
        .put("geo", new JSONObject().put("lat", 35.681).put("lng", 139.767).put("q", "東京都千代田区丸の内1-1-1"))
        .put("rawText", "山田 太郎\nサンプル株式会社\n部長")
        .put("contactRawId", 42).put("queue", "contact"); // device-only, must not be written
    p.put("pages", new JSONArray().put(new JSONObject().put("file", "a1-0.jpg")).put(new JSONObject().put("file", "a1-1.jpg")));
    p.put("sources", new JSONArray().put(new JSONObject().put("file", "a1.pdf").put("name", "scan.pdf").put("hash", "abc")));
    p.put("notes", new JSONArray()
        .put(new JSONObject().put("id", "note-1").put("time", 1709600400000L).put("text", "展示会でご挨拶"))
        .put(new JSONObject().put("id", "note-2").put("time", 1709700000000L).put("text", "見積もり送付\n来週フォロー")));
    p.put("audio", new JSONArray().put(new JSONObject().put("file", "r1.m4a").put("time", 1709700000000L).put("seconds", 85).put("state", "saved")));
    p.put("greetings", new JSONArray().put(new JSONObject().put("id", "g1").put("year", 2027).put("dir", "received")
        .put("date", "2027-01-02").put("kind", "nenga").put("senders", "山田 太郎\n山田 花子")
        .put("files", new JSONArray().put(new JSONObject().put("file", "n1.jpg")))));
    return p;
  }

  @Test
  public void roundTripKeepsData() throws Exception {
    String md = VaultCodec.render(sample());
    assertFalse("device fields leak", md.contains("contactRawId") || md.contains("queue"));
    assertTrue(md.contains("company: '[[サンプル株式会社]]'") || md.contains("company: \"[[サンプル株式会社]]\""));
    assertTrue(md.contains("![[Meishi/_files/a1-0.jpg|360]]"));
    JSONObject q = VaultCodec.parse(md);
    assertEquals("山田 太郎", q.getString("name"));
    assertEquals("サンプル株式会社", q.getString("company"));
    assertEquals("taro@example.com\ntaro2@example.com", q.getString("email"));
    assertEquals("取引先、展示会", q.getString("tags"));
    assertEquals("展示会: 東京ビッグサイト", q.getString("metPlace"));
    assertEquals("2024-03-05", q.getString("metDate"));
    assertEquals(1709600400123L, q.getLong("created"));
    assertEquals(1760000000456L, q.getLong("updated"));
    assertTrue(q.getBoolean("contactRegistered"));
    assertEquals(35.681, q.getJSONObject("geo").getDouble("lat"), 1e-9);
    assertEquals(2, q.getJSONArray("pages").length());
    assertEquals("a1.pdf", q.getJSONArray("sources").getJSONObject(0).getString("file"));
    assertEquals(2, q.getJSONArray("notes").length());
    assertEquals("note-2", q.getJSONArray("notes").getJSONObject(1).getString("id"));
    assertEquals("見積もり送付\n来週フォロー", q.getJSONArray("notes").getJSONObject(1).getString("text"));
    assertEquals(85, q.getJSONArray("audio").getJSONObject(0).getInt("seconds"));
    JSONObject g = q.getJSONArray("greetings").getJSONObject(0);
    assertEquals(2027, g.getInt("year"));
    assertEquals("山田 太郎\n山田 花子", g.getString("senders"));
    assertEquals("n1.jpg", g.getJSONArray("files").getJSONObject(0).getString("file"));
    assertEquals("山田 太郎\nサンプル株式会社\n部長", q.getString("rawText"));
    assertFalse(q.has("vaultFree"));
  }

  @Test
  public void renderIsStableAfterARoundTrip() throws Exception {
    String once = VaultCodec.render(sample());
    String twice = VaultCodec.render(VaultCodec.parse(once));
    assertEquals(once, twice);
    // and the content hash used for change detection settles too
    JSONObject a = VaultCodec.parse(once), b = VaultCodec.parse(twice);
    assertEquals(Sync.hash(a), Sync.hash(b));
  }

  @Test
  public void obsidianEditsAreTakenAndForeignContentKept() throws Exception {
    String md = VaultCodec.render(sample());
    md = md.replace("name: 山田 太郎", "name: 山田 太一\naliases: [やまちゃん]\ncssclasses:\n  - card")
        .replace("- 2024-03-06", "- 2024-03-08 電話で確認\n- 2024-03-06")
        + "\n## 自分用\n- [[サンプル株式会社]] の担当\n";
    JSONObject q = VaultCodec.parse(md);
    assertTrue(VaultCodec.editedOutside(md));
    assertFalse(VaultCodec.editedOutside(VaultCodec.render(sample())));
    assertEquals("山田 太一", q.getString("name"));
    assertEquals(3, q.getJSONArray("notes").length());
    assertTrue(q.getJSONObject("vaultExtra").has("aliases"));
    assertTrue(q.getString("vaultFree").contains("## 自分用"));
    String again = VaultCodec.render(q);
    assertTrue(again.contains("aliases:"));
    assertTrue(again.contains("cssclasses:"));
    assertTrue(again.contains("## 自分用"));
    assertTrue(again.contains("電話で確認"));
    // the new note got a stable id written as a block reference
    assertEquals(VaultCodec.parse(again).getJSONArray("notes").toString(), VaultCodec.parse(VaultCodec.render(VaultCodec.parse(again))).getJSONArray("notes").toString());
  }

  @Test
  public void handWrittenNoteParses() throws Exception {
    String md =
        "---\ntheta: meishi/shop@1\nid: shop-1\nname: 炭火焼 さくら\nphone: 06-0000-0000\ngenre: 焼き鳥\ntags: [居酒屋, 梅田]\n---\n\n## メモ\n* 2026-10-01 つくねが美味しい\n- 予約は電話のみ\n\nなんでも書ける\n";
    JSONObject q = VaultCodec.parse(md);
    assertEquals("shop", q.getString("kind"));
    assertEquals("06-0000-0000", q.getString("phone"));
    assertEquals("居酒屋、梅田", q.getString("tags"));
    assertEquals(2, q.getJSONArray("notes").length());
    assertEquals("confirmed", q.getString("status"));
    assertTrue(q.getString("vaultFree").contains("なんでも書ける"));
  }

  @Test
  public void fileNamesAreSafe() throws Exception {
    assertEquals("山田 太郎（A／B社）.md", VaultCodec.fileName(new JSONObject().put("name", "山田 太郎").put("company", "A／B社")));
    assertEquals("a b（x y）.md", VaultCodec.fileName(new JSONObject().put("name", "a/b").put("company", "x:y")));
    assertEquals("名称未登録.md", VaultCodec.fileName(new JSONObject()));
    assertEquals("さくら.md", VaultCodec.fileName(new JSONObject().put("name", "さくら").put("company", "X").put("kind", "shop")));
    assertEquals("x.jpg", VaultCodec.local("Meishi/_files/../x.jpg")); // only the bare name is ever used
    assertNull(VaultCodec.local("Meishi/_files/名刺.jpg"));
    assertNull(VaultCodec.local("Meishi/_files/.."));
    assertEquals("x.jpg", VaultCodec.local("[[Meishi/_files/x.jpg|360]]"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsNotesWithoutAnId() throws Exception {
    VaultCodec.parse("---\nname: x\n---\n");
  }
}
