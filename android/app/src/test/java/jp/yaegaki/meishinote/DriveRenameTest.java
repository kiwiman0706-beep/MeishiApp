package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DriveRenameTest {
  static JSONObject card(String status, String name, String company) throws Exception {
    return Store.empty().put("status", status).put("name", name).put("company", company);
  }

  @Test
  public void namesAfterTheCard() throws Exception {
    assertEquals("名刺｜サンプル株式会社｜山田 太郎.jpg", DriveRename.name(card("review", "山田 太郎", "サンプル株式会社"), 0, 1, "scan_0012.jpg"));
    assertEquals("名刺｜山田 太郎（2）.pdf", DriveRename.name(card("confirmed", "山田 太郎", ""), 1, 2, "a.pdf"));
    assertEquals("お店｜炭火焼 さくら.jpg", DriveRename.name(card("confirmed", "炭火焼 さくら", "").put("kind", "shop"), 0, 1, "x.jpg"));
    assertEquals("名刺｜A B｜C D.jpg", DriveRename.name(card("review", "C/D", "A|B"), 0, 1, "x.jpg"));
    assertNull("not read yet", DriveRename.name(card("new", "", ""), 0, 1, "x.jpg"));
    assertNull("still in the queue", DriveRename.name(card("review", "山田", "").put("queue", "recognize"), 0, 1, "x.jpg"));
    assertNull("nothing to name it after", DriveRename.name(card("review", "", ""), 0, 1, "x.jpg"));
  }

  @Test
  public void importTagsThenRenamesOnceAndAfterAnEdit() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    Store s = Store.get(c);
    JSONObject p = card("new", "", "").put("sources", new JSONArray().put(new JSONObject().put("file", "a.pdf").put("name", "scan.pdf / 2")));
    s.put(p);
    try {
    DriveRename.tag(s, new JSONArray().put(p.getString("id")), Collections.singletonList(new JSONObject().put("id", "drv1").put("name", "scan.pdf")));
    assertEquals("drv1", s.person(p.getString("id")).getJSONArray("driveFiles").getJSONObject(0).getString("id"));
    assertFalse("not read yet", DriveRename.pending(s));

    List<String[]> calls = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    JSONObject read = s.person(p.getString("id")).put("status", "review").put("name", "山田 太郎").put("company", "サンプル株式会社");
    s.put(read);
    assertEquals(1, DriveRename.run(s, (id, name) -> calls.add(new String[] {id, name}), errors));
    assertEquals("名刺｜サンプル株式会社｜山田 太郎.pdf", calls.get(0)[1]);
    assertFalse("done once", DriveRename.pending(s));

    s.put(s.person(p.getString("id")).put("name", "山田 太一"));
    assertEquals(1, DriveRename.run(s, (id, name) -> calls.add(new String[] {id, name}), errors));
    assertEquals("名刺｜サンプル株式会社｜山田 太一.pdf", calls.get(1)[1]);

    // Deleted on Drive: forgotten, not retried.
    s.put(s.person(p.getString("id")).put("name", "山田 次郎"));
    DriveRename.run(s, (id, name) -> { throw new java.io.IOException("Google Drive HTTP 404"); }, errors);
    assertFalse(s.person(p.getString("id")).has("driveFiles"));
    assertTrue(errors.isEmpty());
    } finally {
      s.removeQuietly(p.getString("id")); // the store is shared with the other tests
    }
  }
}
