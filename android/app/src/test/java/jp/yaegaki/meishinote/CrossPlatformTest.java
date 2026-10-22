package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;

// The desktop app (desktop/lib/vault-codec.js) must read what Android writes and vice versa.
// Fixtures live in desktop/test/fixtures: android-rendered.md is refreshed here, desktop-rendered.md
// is produced by the desktop test suite.
public class CrossPlatformTest {
  static File dir() {
    return new File(System.getProperty("user.dir"), "../../desktop/test/fixtures");
  }

  @Test
  public void writesAndroidFixture() throws Exception {
    File d = dir();
    if (!d.isDirectory()) return;
    Files.write(new File(d, "android-rendered.md").toPath(), VaultCodec.render(VaultCodecTest.sample()).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void readsDesktopFixture() throws Exception {
    File f = new File(dir(), "desktop-rendered.md");
    if (!f.isFile()) return;
    String md = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    assertFalse("desktop rev must verify on Android", VaultCodec.editedOutside(md));
    JSONObject p = VaultCodec.parse(md);
    JSONObject s = VaultCodecTest.sample();
    for (String k : new String[] {"name", "kana", "company", "title", "email", "mobile", "address", "tags", "metDate", "metPlace", "rawText"})
      assertEquals(k, s.getString(k), p.getString(k));
    assertEquals(s.getLong("created"), p.getLong("created"));
    assertEquals(4, p.getJSONArray("notes").length());
    assertEquals("ink1.png", p.getJSONArray("notes").getJSONObject(2).getString("ink"));
    assertEquals("4/1 訪問\n資料持参", p.getJSONArray("notes").getJSONObject(2).getString("text"));
    assertEquals("note-2", p.getJSONArray("notes").getJSONObject(1).getString("id"));
    assertEquals("見積もり送付\n来週フォロー", p.getJSONArray("notes").getJSONObject(1).getString("text"));
    assertEquals(Sync.hash(VaultCodec.parse(VaultCodec.render(s))), Sync.hash(p));
  }
}
