package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateCheckTest {
  @Test
  public void versionsCompareNumerically() {
    assertTrue(UpdateCheck.compare("0.14.0", "0.13.9") > 0);
    assertTrue(UpdateCheck.compare("v0.10.0", "0.9.1") > 0);
    assertEquals(0, UpdateCheck.compare("v1.2", "1.2.0"));
    assertTrue(UpdateCheck.compare("0.13.0", "0.13.1") < 0);
  }

  @Test
  public void releaseWithApk() throws Exception {
    JSONObject r =
        new JSONObject()
            .put("tag_name", "v0.15.0")
            .put(
                "assets",
                new JSONArray()
                    .put(new JSONObject().put("name", "ThetaMeishiHatch-Setup-0.15.0.exe").put("browser_download_url", "x"))
                    .put(new JSONObject().put("name", "theta-meishi-hatch-0.15.0.apk").put("browser_download_url", "https://github.com/a.apk").put("digest", "sha256:abc123")));
    JSONObject u = UpdateCheck.parse(r);
    assertEquals("0.15.0", u.getString("version"));
    assertEquals("https://github.com/a.apk", u.getString("url"));
    assertEquals("abc123", u.getString("sha256")); // the download is checked against it
    assertNull(UpdateCheck.parse(new JSONObject().put("tag_name", "v1").put("assets", new JSONArray())));
    assertNull(UpdateCheck.parse(r.put("draft", true)));
  }
}
