package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RecentWidgetTest {
  @Test
  public void newestOfRegisteredAndOpenedFirst() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    Store s = Store.get(c);
    String[] ids = new String[10];
    for (int i = 0; i < 10; i++) {
      JSONObject p = Store.empty().put("name", "人" + i).put("created", 1000L + i);
      s.put(p);
      ids[i] = p.getString("id");
    }
    s.put(Store.empty().put("name", "仕分け前").put("kind", Nenga.INBOX).put("created", 99999L));
    RecentWidget.opened(c, ids[2]); // opened just now: jumps to the top
    List<JSONObject> r = RecentWidget.recent(c);
    assertEquals(RecentWidget.SHOW, r.size());
    assertEquals("人2", r.get(0).getString("name"));
    assertTrue(r.get(0).getBoolean("_opened"));
    assertEquals("人9", r.get(1).getString("name"));
    for (JSONObject p : r) assertNotEquals("仕分け前", p.getString("name"));
  }
}
