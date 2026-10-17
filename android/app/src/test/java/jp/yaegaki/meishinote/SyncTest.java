package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class SyncTest {
  static JSONObject person(String id, long updated) throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("updated", updated)
        .put("name", "")
        .put("company", "")
        .put("notes", new JSONArray())
        .put("audio", new JSONArray())
        .put("pages", new JSONArray())
        .put("sources", new JSONArray());
  }

  @Test
  public void hashIgnoresKeyOrderUpdatedAndDeviceFields() throws Exception {
    JSONObject a = new JSONObject().put("id", "1").put("name", "山田").put("company", "X").put("updated", 1);
    JSONObject b = new JSONObject().put("company", "X").put("updated", 99).put("name", "山田").put("id", "1");
    b.put("contactRawId", 42).put("queue", "contact").put("suggestion", new JSONObject().put("name", "?"));
    assertEquals(Sync.hash(a), Sync.hash(b));
    b.put("name", "山田 太郎");
    assertNotEquals(Sync.hash(a), Sync.hash(b));
  }

  @Test
  public void sharedDropsDeviceFields() throws Exception {
    JSONObject p = person("1", 1).put("contactRawId", 5).put("contactAccount", "a@example.com").put("queueError", "x");
    JSONObject s = Sync.shared(p);
    assertFalse(s.has("contactRawId"));
    assertFalse(s.has("contactAccount"));
    assertFalse(s.has("queueError"));
    assertTrue(s.has("pages"));
  }

  @Test
  public void mergeTakesNewerFieldsAndUnionsLists() throws Exception {
    JSONObject local = person("1", 100).put("name", "山田").put("title", "課長").put("email", "old@example.com");
    local.getJSONArray("notes").put(new JSONObject().put("id", "n1").put("text", "local note"));
    local.getJSONArray("pages").put(new JSONObject().put("file", "a.jpg"));
    JSONObject remote = person("1", 200).put("name", "山田").put("title", "部長").put("email", "");
    remote.getJSONArray("notes").put(new JSONObject().put("id", "n2").put("text", "remote note"));
    remote.getJSONArray("notes").put(new JSONObject().put("id", "n1").put("text", "local note"));
    remote.getJSONArray("pages").put(new JSONObject().put("file", "b.jpg"));
    remote.put("contactRawId", 7);

    JSONObject m = Sync.merge(local, remote);
    assertEquals("部長", m.getString("title")); // newer side wins
    assertEquals("old@example.com", m.getString("email")); // gap filled from the other side
    assertEquals(2, m.getJSONArray("notes").length());
    assertEquals(2, m.getJSONArray("pages").length());
    assertEquals("b.jpg", m.getJSONArray("pages").getJSONObject(0).getString("file"));
    assertFalse(m.has("contactRawId"));
    assertTrue(m.getLong("updated") > 200);
  }

  @Test
  public void mediaSkipsRecordingInProgress() throws Exception {
    JSONObject p = person("1", 1).put("portrait", "p.jpg");
    p.getJSONArray("audio").put(new JSONObject().put("file", "done.m4a").put("state", "saved"));
    p.getJSONArray("audio").put(new JSONObject().put("file", "live.m4a").put("state", "recording"));
    p.getJSONArray("sources").put(new JSONObject().put("file", "s.pdf"));
    assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("p.jpg", "done.m4a", "s.pdf")), Sync.media(p));
  }

  static final String D = Sync.DELETED;

  @Test
  public void decideCoversEveryCase() {
    // Nothing anywhere / already identical.
    assertEquals(Sync.Action.SKIP, Sync.decide(null, null, null));
    assertEquals(Sync.Action.SAME, Sync.decide("a", "a", null));
    assertEquals(Sync.Action.SAME, Sync.decide(D, D, "a"));
    // Only one side has it.
    assertEquals(Sync.Action.PUSH, Sync.decide("a", null, null));
    assertEquals(Sync.Action.FORGET_TOMBSTONE, Sync.decide(D, null, null));
    assertEquals(Sync.Action.PULL, Sync.decide(null, "a", null));
    assertEquals(Sync.Action.RECORD_REMOTE_DELETE, Sync.decide(null, D, null));
    // One side changed since the last sync.
    assertEquals(Sync.Action.PUSH, Sync.decide("b", "a", "a"));
    assertEquals(Sync.Action.PULL, Sync.decide("a", "b", "a"));
    assertEquals(Sync.Action.PUSH, Sync.decide(D, "a", "a")); // local delete travels
    assertEquals(Sync.Action.PULL, Sync.decide("a", D, "a")); // remote delete arrives
    // Both changed.
    assertEquals(Sync.Action.MERGE, Sync.decide("b", "c", "a"));
    assertEquals(Sync.Action.MERGE, Sync.decide("b", "c", null)); // first sync of a shared id
    assertEquals(Sync.Action.PULL, Sync.decide(D, "c", "a")); // edit beats delete
    assertEquals(Sync.Action.PUSH, Sync.decide("b", D, "a"));
  }
}
