package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.util.Calendar;
import org.json.*;
import org.junit.Test;

public class NengaTest {
  static JSONObject p(String id, String name, String address) throws Exception {
    return new JSONObject().put("id", id).put("name", name).put("address", address);
  }

  static JSONObject card(String senders, String address) throws Exception {
    return new JSONObject().put("senders", senders).put("address", address);
  }

  @Test
  public void matchesByAnySenderNameIgnoringSpacing() throws Exception {
    JSONArray people = new JSONArray().put(p("a", "山田 太郎", "")).put(p("b", "佐藤花子", ""));
    assertEquals("a", Nenga.match(people, card("山田太郎\n山田 花子", "")));
    assertEquals("b", Nenga.match(people, card("鈴木 一郎\n佐藤　花子", ""))); // full-width space
    assertNull(Nenga.match(people, card("田中 次郎", "")));
  }

  @Test
  public void sameNameIsResolvedByAddressOrLeftForANewPerson() throws Exception {
    JSONArray people =
        new JSONArray()
            .put(p("a", "山田 太郎", "東京都千代田区丸の内1丁目1番1号"))
            .put(p("b", "山田 太郎", "大阪府大阪市北区梅田2-2-2"));
    assertEquals("b", Nenga.match(people, card("山田太郎", "〒530-0001 大阪府大阪市北区梅田２－２－２")));
    assertNull(Nenga.match(people, card("山田太郎", ""))); // ambiguous: don't guess
  }

  @Test
  public void addressAloneMatchesWhenNamesDiffer() throws Exception {
    // Card signed with a family name only, but the home address is on file.
    JSONArray people = new JSONArray().put(p("a", "山田 太郎", "東京都千代田区丸の内1丁目1番1号"));
    assertEquals("a", Nenga.match(people, card("山田家", "東京都千代田区丸の内1-1-1")));
  }

  @Test
  public void shopsAndInboxCardsAreNeverTargets() throws Exception {
    JSONArray people =
        new JSONArray()
            .put(p("s", "山田 太郎", "").put("kind", "shop"))
            .put(p("i", "山田 太郎", "").put("kind", Nenga.INBOX));
    assertNull(Nenga.match(people, card("山田 太郎", "")));
  }

  @Test
  public void seasonRollsOverInOctober() {
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.SEPTEMBER, 30);
    assertEquals(2026, Nenga.season(c.getTimeInMillis()));
    c.set(2026, Calendar.DECEMBER, 20);
    assertEquals(2027, Nenga.season(c.getTimeInMillis()));
    c.set(2027, Calendar.JANUARY, 3);
    assertEquals(2027, Nenga.season(c.getTimeInMillis()));
  }

  @Test
  public void greetingTakesYearFromCardAndKeepsImages() throws Exception {
    JSONObject temp =
        new JSONObject()
            .put("created", System.currentTimeMillis())
            .put("nengaYear", 2027)
            .put("pages", new JSONArray().put(new JSONObject().put("file", "a.jpg")).put(new JSONObject().put("file", "b.jpg")));
    JSONObject g = Nenga.greeting(temp, new JSONObject().put("year", "2026").put("kind", "mochu").put("senders", "山田 太郎"));
    assertEquals(2026, g.getInt("year"));
    assertEquals("mochu", g.getString("kind"));
    assertEquals("received", g.getString("dir"));
    assertEquals(2, g.getJSONArray("files").length());
    JSONObject odd = Nenga.greeting(temp, new JSONObject().put("year", "令和9").put("kind", "?"));
    assertEquals(2027, odd.getInt("year"));
    assertEquals("nenga", odd.getString("kind"));
  }

  @Test
  public void greetingImagesCountAsReferencedMedia() throws Exception {
    JSONObject person =
        new JSONObject()
            .put("id", "x")
            .put("greetings", new JSONArray().put(new JSONObject().put("id", "g").put("files", new JSONArray().put(new JSONObject().put("file", "n.jpg")))));
    assertTrue(Store.referenced(new JSONObject().put("people", new JSONArray().put(person))).contains("n.jpg"));
    assertTrue(Sync.media(person).contains("n.jpg"));
  }

  @Test
  public void otherMailUsesArrivalYearAndStaysOutOfNewYearCounts() throws Exception {
    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.DECEMBER, 24);
    JSONObject temp = new JSONObject().put("created", c.getTimeInMillis()).put("pages", new JSONArray());
    JSONObject xmas = Nenga.greeting(temp, new JSONObject().put("kind", "xmas"));
    assertEquals(2026, xmas.getInt("year")); // a December New Year card would be 2027
    assertEquals(2027, Nenga.greeting(temp, new JSONObject().put("kind", "nenga")).getInt("year"));
    JSONObject p = new JSONObject().put("greetings", new JSONArray().put(xmas));
    assertFalse(Nenga.has(p, 2026, "received"));
    p.getJSONArray("greetings").put(new JSONObject().put("year", 2026).put("dir", "received")); // legacy entry, no kind
    assertTrue(Nenga.has(p, 2026, "received"));
  }
}
