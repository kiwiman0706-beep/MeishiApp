package jp.yaegaki.meishinote;

import java.util.*;
import org.json.*;

// Files imported from the Google Drive folder are named after the card once it has been read:
// 名刺｜会社｜氏名.jpg (お店｜店名.jpg for shop cards), in the 登録済み folder they were moved to.
// The import records which Drive files made each card (driveFiles: [{id, name}], device-only);
// a later edit of the name renames them again.
public final class DriveRename {
  static String clean(String s) {
    // Characters Windows / Drive for desktop cannot keep, line breaks, extra spaces.
    return s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ").replaceAll("[ \\u3000]{2,}", " ").trim();
  }

  // The name file `index` of `count` should have; null while the card is not read yet.
  public static String name(JSONObject p, int index, int count, String original) {
    if (p.optString("status").equals("new") || !p.optString("queue").isEmpty()) return null;
    String person = clean(p.optString("name")), company = clean(p.optString("company"));
    String base;
    if (p.optString("kind").equals("shop")) base = person.isEmpty() ? "" : "お店｜" + person;
    else if (person.isEmpty() && company.isEmpty()) base = "";
    else base = "名刺" + (company.isEmpty() ? "" : "｜" + company) + (person.isEmpty() ? "" : "｜" + person);
    if (base.isEmpty()) return null;
    if (base.length() > 120) base = base.substring(0, 120);
    String ext = original.matches("(?s).*\\.[A-Za-z0-9]{1,5}$") ? original.substring(original.lastIndexOf('.')) : "";
    return base + (count > 1 ? "（" + (index + 1) + "）" : "") + ext;
  }

  // After an import: the Drive files (by their name) behind each new card.
  public static void tag(Store s, JSONArray ids, List<JSONObject> files) throws Exception {
    for (int i = 0; i < ids.length(); i++) {
      JSONObject p = s.person(ids.getString(i));
      JSONArray tagged = p.optJSONArray("driveFiles") == null ? new JSONArray() : p.getJSONArray("driveFiles");
      Set<String> have = new HashSet<>();
      for (int k = 0; k < tagged.length(); k++) have.add(tagged.getJSONObject(k).optString("id"));
      JSONArray src = p.optJSONArray("sources");
      for (int k = 0; src != null && k < src.length(); k++) {
        // PDF pages are named "scan.pdf / 2".
        String n = src.getJSONObject(k).optString("name").replaceFirst(" / \\d+$", "");
        for (JSONObject f : files)
          if (f.optString("name").equals(n) && have.add(f.optString("id")))
            tagged.put(new JSONObject().put("id", f.getString("id")).put("name", f.optString("name")));
      }
      if (tagged.length() > 0) {
        p.put("driveFiles", tagged);
        s.put(p);
      }
    }
  }

  public static boolean pending(Store s) throws Exception {
    JSONArray all = s.snapshot().getJSONArray("people");
    for (int i = 0; i < all.length(); i++) if (!work(all.getJSONObject(i)).isEmpty()) return true;
    return false;
  }

  // [index, wanted name] of the files of p that need renaming.
  static List<Object[]> work(JSONObject p) {
    List<Object[]> out = new ArrayList<>();
    JSONArray f = p.optJSONArray("driveFiles");
    for (int i = 0; f != null && i < f.length(); i++) {
      JSONObject x = f.optJSONObject(i);
      String want = name(p, i, f.length(), x.optString("name"));
      if (want != null && !want.equals(x.optString("name"))) out.add(new Object[] {i, want});
    }
    return out;
  }

  public interface Renamer {
    void rename(String fileId, String name) throws Exception;
  }

  // Renames what is due; a file deleted on Drive is forgotten. Returns how many were renamed.
  public static int run(Store s, Renamer d, List<String> errors) throws Exception {
    int done = 0;
    JSONArray all = s.snapshot().getJSONArray("people");
    for (int i = 0; i < all.length(); i++) {
      String id = all.getJSONObject(i).getString("id");
      List<Object[]> todo = work(all.getJSONObject(i));
      if (todo.isEmpty()) continue;
      JSONObject p = s.person(id);
      JSONArray f = p.getJSONArray("driveFiles");
      List<Integer> gone = new ArrayList<>();
      for (Object[] t : todo) {
        JSONObject x = f.getJSONObject((Integer) t[0]);
        try {
          d.rename(x.getString("id"), (String) t[1]);
          x.put("name", t[1]);
          done++;
        } catch (Exception e) {
          if (String.valueOf(e.getMessage()).contains("404")) gone.add((Integer) t[0]);
          else errors.add(x.optString("name") + "：Driveのファイル名を変更できませんでした（" + e.getMessage() + "）");
        }
      }
      Collections.sort(gone, Collections.reverseOrder());
      for (int g : gone) f.remove(g);
      if (f.length() == 0) p.remove("driveFiles");
      s.put(p);
    }
    return done;
  }
}
