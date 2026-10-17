package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Two phones and one vault, end to end: everything VaultSync does to records and files.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VaultSyncTest {
  // In-memory stand-in for Google Drive.
  static final class Fake implements VaultRemote {
    static final class F {
      String id, name, parent;
      byte[] body;
      boolean folder, trashed;
      long modified = System.currentTimeMillis();
    }

    final Map<String, F> all = new LinkedHashMap<>();
    int seq = 0;

    F add(String parent, String name, boolean folder, byte[] body) {
      F f = new F();
      f.id = "f" + (++seq);
      f.parent = parent;
      f.name = name;
      f.folder = folder;
      f.body = body;
      all.put(f.id, f);
      return f;
    }

    static String md5(byte[] b) throws Exception {
      StringBuilder s = new StringBuilder();
      for (byte x : MessageDigest.getInstance("MD5").digest(b)) s.append(String.format("%02x", x));
      return s.toString();
    }

    JSONObject json(F f) throws Exception {
      return new JSONObject().put("id", f.id).put("name", f.name).put("md5Checksum", md5(f.body))
          .put("modifiedTime", VaultCodec.iso(f.modified)).put("parents", new JSONArray().put(f.parent)).put("trashed", f.trashed);
    }

    public String folder(String parent, String name) {
      for (F f : all.values()) if (f.folder && !f.trashed && f.parent.equals(parent) && f.name.equals(name)) return f.id;
      return add(parent, name, true, new byte[0]).id;
    }

    public JSONArray files(String folder) throws Exception {
      JSONArray a = new JSONArray();
      for (F f : all.values()) if (!f.folder && !f.trashed && f.parent.equals(folder)) a.put(json(f));
      return a;
    }

    public String read(String id) {
      return new String(all.get(id).body, StandardCharsets.UTF_8);
    }

    public JSONObject put(String id, String parent, String name, byte[] body, String moveTo, String moveFrom) throws Exception {
      F f = id.isEmpty() ? add(parent, name, false, body) : all.get(id);
      f.name = name;
      f.body = body;
      f.modified = System.currentTimeMillis();
      if (!moveTo.isEmpty()) f.parent = moveTo;
      return json(f);
    }

    public String upload(String folder, String name, String mime, File file) throws Exception {
      return add(folder, name, false, Files.readAllBytes(file.toPath())).id;
    }

    public void download(String id, File to) throws Exception {
      Files.write(to.toPath(), all.get(id).body);
    }

    public void trash(String id, String from, String trashFolder) {
      all.get(id).parent = trashFolder;
    }

    public JSONObject meta(String id) throws Exception {
      F f = all.get(id);
      return f == null ? null : json(f);
    }

    // A live note (not in Drive's trash nor in the vault's .theta/trash).
    F note(String nameContains) {
      for (F f : all.values())
        if (!f.folder && !f.trashed && f.name.contains(nameContains) && f.name.endsWith(".md") && !path(f).contains("/.theta/"))
          return f;
      return null;
    }

    String path(F f) {
      return f.parent.equals("root") ? f.name : path(all.get(f.parent)) + "/" + f.name;
    }
  }

  Fake vault;
  Store a, b;

  @Before
  public void setUp() throws Exception {
    vault = new Fake();
    a = Store.openForTest(Files.createTempDirectory("phoneA").toFile());
    b = Store.openForTest(Files.createTempDirectory("phoneB").toFile());
  }

  VaultSync.Result sync(Store s) throws Exception {
    VaultSync.Result r = VaultSync.run(s, vault, t -> {});
    assertTrue("errors: " + r.errors, r.errors.isEmpty());
    return r;
  }

  static JSONObject person(Store s, String name, String company) throws Exception {
    JSONObject p = Store.empty().put("name", name).put("company", company).put("status", "confirmed");
    s.put(p);
    return p;
  }

  static JSONObject find(Store s, String name) throws Exception {
    JSONArray a = s.snapshot().getJSONArray("people");
    for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optString("name").equals(name)) return a.getJSONObject(i);
    return null;
  }

  static int count(Store s) throws Exception {
    return s.snapshot().getJSONArray("people").length();
  }

  @Test
  public void notesFilesAndEditsTravelBothWays() throws Exception {
    JSONObject yamada = person(a, "山田 太郎", "サンプル株式会社");
    Files.write(a.mediaFile("card-1.jpg").toPath(), new byte[] {1, 2, 3});
    yamada = a.person(yamada.getString("id"));
    yamada.getJSONArray("pages").put(new JSONObject().put("file", "card-1.jpg"));
    a.put(yamada);
    a.note(yamada.getString("id"), "展示会でご挨拶");
    person(a, "炭火焼 さくら", "").put("kind", "shop");
    JSONObject shop = find(a, "炭火焼 さくら").put("kind", "shop");
    a.put(shop);

    assertEquals(2, sync(a).up);
    String root = "Theta Vault/Meishi/";
    assertNotNull(vault.note("山田 太郎（サンプル株式会社）"));
    assertEquals(root + "People/山田 太郎（サンプル株式会社）.md", vault.path(vault.note("山田 太郎")));
    assertEquals(root + "Shops/炭火焼 さくら.md", vault.path(vault.note("さくら")));
    assertTrue(vault.read(vault.note("山田").id).contains("展示会でご挨拶"));

    VaultSync.Result rb = sync(b);
    assertEquals(2, rb.down);
    assertEquals(1, rb.mediaDown);
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(b.mediaFile("card-1.jpg").toPath()));
    assertEquals("shop", find(b, "炭火焼 さくら").optString("kind"));
    assertEquals(1, find(b, "山田 太郎").getJSONArray("notes").length());

    // Nothing changed: a second round is quiet on both sides.
    VaultSync.Result quietA = sync(a), quietB = sync(b);
    assertEquals(0, quietA.up + quietA.down + quietB.up + quietB.down);

    // B adds a note; A picks it up.
    b.note(find(b, "山田 太郎").getString("id"), "見積もり送付");
    assertEquals(1, sync(b).up);
    assertEquals(1, sync(a).down);
    assertEquals(2, find(a, "山田 太郎").getJSONArray("notes").length());
  }

  @Test
  public void concurrentEditsMerge() throws Exception {
    person(a, "佐藤 花子", "テスト商事");
    sync(a);
    sync(b);
    JSONObject pa = find(a, "佐藤 花子").put("title", "課長");
    a.put(pa);
    a.note(pa.getString("id"), "電話した");
    Thread.sleep(5);
    JSONObject pb = find(b, "佐藤 花子").put("title", "部長"); // B's edit is the later one
    b.put(pb);
    b.note(pb.getString("id"), "昇進のお祝い");
    sync(a);
    VaultSync.Result rb = sync(b);
    assertEquals(1, rb.merged);
    sync(a);
    for (Store s : new Store[] {a, b}) {
      JSONObject p = find(s, "佐藤 花子");
      assertEquals("部長", p.getString("title")); // the later edit wins the field
      assertEquals(2, p.getJSONArray("notes").length()); // both notes survive
    }
  }

  @Test
  public void obsidianEditsAndHandWrittenNotesComeIn() throws Exception {
    person(a, "鈴木 一郎", "");
    sync(a);
    Fake.F f = vault.note("鈴木");
    String md = vault.read(f.id).replace("name: 鈴木 一郎", "name: 鈴木 一朗").replace("## メモ\n", "## メモ\n- 2026-10-17 12:00 Obsidianで追記\n");
    vault.put(f.id, f.parent, f.name, md.getBytes(StandardCharsets.UTF_8), "", "");
    assertEquals(1, sync(a).down);
    JSONObject p = find(a, "鈴木 一朗");
    assertNotNull(p);
    assertEquals("Obsidianで追記", p.getJSONArray("notes").getJSONObject(0).getString("text"));

    // A note typed from scratch in the People folder becomes a person and gets an id written back.
    String people = f.parent;
    Fake.F hand = vault.add(people, "新しい人.md", false, "---\nname: 高橋 さん\ncompany: '[[自作社]]'\n---\n\n電話で紹介された\n".getBytes(StandardCharsets.UTF_8));
    sync(a);
    JSONObject t = find(a, "高橋 さん");
    assertNotNull(t);
    assertEquals("自作社", t.getString("company"));
    assertTrue(vault.read(hand.id).contains("id: " + t.getString("id")));
    assertTrue(vault.read(hand.id).contains("電話で紹介された"));
    VaultSync.Result again = sync(a);
    assertEquals(0, again.up + again.down);
  }

  @Test
  public void deletionsTravelButMovesAndMassLossDoNot() throws Exception {
    for (int i = 0; i < 10; i++) person(a, "人" + i, "社" + i);
    sync(a);
    sync(b);
    assertEquals(10, count(b));

    // Deleted on B → trashed in the vault → removed on A.
    b.remove(find(b, "人0").getString("id"));
    assertEquals(1, sync(b).deleted);
    assertNull(vault.note("人0（"));
    assertEquals(1, sync(a).deleted);
    assertEquals(9, count(a));

    // Moved into a subfolder in Obsidian: still there, not deleted, not moved back.
    Fake.F f = vault.note("人1（");
    String sub = vault.folder(f.parent, "取引先");
    f.parent = sub;
    VaultSync.Result r = sync(a);
    assertEquals(0, r.deleted);
    assertEquals(9, count(a));
    assertEquals(sub, vault.note("人1（").parent);

    // Most notes vanish at once (wrong folder / account): nothing is deleted on the phone.
    for (Fake.F x : vault.all.values()) if (x.name.endsWith(".md") && !x.name.startsWith("人1")) x.trashed = true;
    VaultSync.Result guard = VaultSync.run(a, vault, t -> {});
    assertFalse(guard.errors.isEmpty());
    assertEquals(9, count(a));
  }

  @Test
  public void editBeatsDelete() throws Exception {
    person(a, "中村 さん", "");
    sync(a);
    sync(b);
    a.remove(find(a, "中村 さん").getString("id"));
    JSONObject p = find(b, "中村 さん").put("title", "社長");
    b.put(p);
    sync(b); // the edit reaches the vault first
    VaultSync.Result r = sync(a);
    assertEquals(1, r.down);
    assertEquals("社長", find(a, "中村 さん").getString("title"));
  }

  @Test
  public void renamesAndRefilesFollowTheRecord() throws Exception {
    person(a, "伊藤 さん", "旧社");
    sync(a);
    Fake.F f = vault.note("伊藤 さん（旧社）");
    JSONObject p = find(a, "伊藤 さん").put("company", "新社");
    a.put(p);
    sync(a);
    assertEquals(f.id, vault.note("伊藤 さん（新社）").id); // same file, new name
    p = find(a, "伊藤 さん").put("kind", "shop");
    a.put(p);
    sync(a);
    assertTrue(vault.path(vault.all.get(f.id)).endsWith("/Meishi/Shops/伊藤 さん.md"));
  }

  @Test
  public void laterObsidianEditWinsOverEarlierAppEdit() throws Exception {
    person(a, "小林 さん", "");
    sync(a);
    JSONObject p = find(a, "小林 さん").put("title", "アプリで入力");
    a.put(p);
    Thread.sleep(5);
    Fake.F f = vault.note("小林");
    String md = vault.read(f.id).replace("status: confirmed", "status: confirmed\ntitle: Obsidianで入力");
    vault.put(f.id, f.parent, f.name, md.getBytes(StandardCharsets.UTF_8), "", ""); // edited later, in Obsidian
    VaultSync.Result r = sync(a);
    assertEquals(1, r.merged);
    assertEquals("Obsidianで入力", find(a, "小林 さん").getString("title"));
  }
}
