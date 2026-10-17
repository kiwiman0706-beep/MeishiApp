package jp.yaegaki.meishinote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

// Shared sync rules: content hash, merge of concurrent edits and the decision table.
// Used by VaultSync (docs/THETA_VAULT_SPEC.md) and by the sync-folder ZIP import.
public final class Sync {
  // Device-specific fields: contact row ids differ per phone, queues are local work.
  static final String[] LOCAL = {
    "contactRawId", "contactAccount", "contactPhoto", "queue", "queueError",
    "contactDuplicate", "suggestion", "contactUnlinked", "calendarEventId", "contactMerged"
  };
  static final String DELETED = "deleted";

  static JSONObject shared(JSONObject p) throws Exception {
    JSONObject s = new JSONObject(p.toString());
    for (String k : LOCAL) s.remove(k);
    return s;
  }

  // Sorted-key JSON so the same content always hashes the same on every platform.
  static String canonical(Object o) throws Exception {
    if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      List<String> keys = new ArrayList<>();
      for (Iterator<String> it = j.keys(); it.hasNext(); ) keys.add(it.next());
      Collections.sort(keys);
      StringBuilder b = new StringBuilder("{");
      for (String k : keys) {
        if (b.length() > 1) b.append(',');
        b.append(JSONObject.quote(k)).append(':').append(canonical(j.get(k)));
      }
      return b.append('}').toString();
    }
    if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < a.length(); i++) {
        if (i > 0) b.append(',');
        b.append(canonical(a.get(i)));
      }
      return b.append(']').toString();
    }
    if (o instanceof String) return JSONObject.quote((String) o);
    if (o == null || o == JSONObject.NULL) return "null";
    return String.valueOf(o);
  }

  // "updated" is excluded: a local-only touch (queue bookkeeping) is not a change to share.
  static String hash(JSONObject p) throws Exception {
    JSONObject s = shared(p);
    s.remove("updated");
    // Display-only details that the vault does not carry must not count as edits.
    strip(s.optJSONArray("pages"), "name", "source");
    strip(s.optJSONArray("sources"), "mime");
    strip(s.optJSONArray("audio"), "id", "state");
    JSONArray g = s.optJSONArray("greetings");
    for (int i = 0; g != null && i < g.length(); i++) strip(g.getJSONObject(i).optJSONArray("files"), "name");
    byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(s).getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i]));
    return b.toString();
  }

  static void strip(JSONArray a, String... keys) throws Exception {
    for (int i = 0; a != null && i < a.length(); i++) for (String k : keys) a.getJSONObject(i).remove(k);
  }

  // Both sides changed: the newer edit wins field by field where it has a value; lists
  // (notes, recordings, pages, sources) are unioned so nothing captured on either side is lost.
  static JSONObject merge(JSONObject local, JSONObject remote) throws Exception {
    boolean remoteNewer = remote.optLong("updated") > local.optLong("updated");
    JSONObject base = shared(remoteNewer ? remote : local), other = shared(remoteNewer ? local : remote);
    for (String k : Store.FIELDS)
      if (base.optString(k).isEmpty() && !other.optString(k).isEmpty()) base.put(k, other.optString(k));
    for (String k : new String[] {"portrait", "logo", "rawText", "warnings"})
      if (base.optString(k).isEmpty() && !other.optString(k).isEmpty()) base.put(k, other.optString(k));
    union(base, other, "notes", "id");
    union(base, other, "audio", "file");
    union(base, other, "pages", "file");
    union(base, other, "sources", "file");
    union(base, other, "greetings", "id");
    JSONArray nd = base.optJSONArray("notDuplicate"), ond = other.optJSONArray("notDuplicate");
    if (ond != null) {
      if (nd == null) base.put("notDuplicate", nd = new JSONArray());
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < nd.length(); i++) seen.add(nd.getString(i));
      for (int i = 0; i < ond.length(); i++) if (seen.add(ond.getString(i))) nd.put(ond.getString(i));
    }
    base.put("updated", Math.max(local.optLong("updated"), remote.optLong("updated")) + 1);
    return base;
  }

  static void union(JSONObject base, JSONObject other, String key, String idKey) throws Exception {
    JSONArray a = base.optJSONArray(key), b = other.optJSONArray(key);
    if (b == null) return;
    if (a == null) base.put(key, a = new JSONArray());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < a.length(); i++) seen.add(a.getJSONObject(i).optString(idKey));
    for (int i = 0; i < b.length(); i++)
      if (seen.add(b.getJSONObject(i).optString(idKey))) a.put(b.getJSONObject(i));
  }

  static Set<String> media(JSONObject p) throws Exception {
    Set<String> files = new HashSet<>();
    for (String k : new String[] {"portrait", "logo"}) if (!p.optString(k).isEmpty()) files.add(p.getString(k));
    for (String k : new String[] {"pages", "sources", "audio"}) {
      JSONArray a = p.optJSONArray(k);
      if (a == null) continue;
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        // A recording still being written is shared once it is finished.
        if (k.equals("audio") && o.optString("state").equals("recording")) continue;
        if (!o.optString("file").isEmpty()) files.add(o.getString("file"));
      }
    }
    JSONArray greetings = p.optJSONArray("greetings");
    if (greetings != null)
      for (int i = 0; i < greetings.length(); i++) {
        JSONArray fs = greetings.getJSONObject(i).optJSONArray("files");
        if (fs != null)
          for (int k = 0; k < fs.length(); k++)
            if (!fs.getJSONObject(k).optString("file").isEmpty()) files.add(fs.getJSONObject(k).getString("file"));
      }
    return files;
  }

  static String mime(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".jpg")) return "image/jpeg";
    if (n.endsWith(".pdf")) return "application/pdf";
    if (n.endsWith(".m4a")) return "audio/mp4";
    return "application/octet-stream";
  }

  enum Action { SKIP, SAME, FORGET_TOMBSTONE, RECORD_REMOTE_DELETE, PUSH, PULL, MERGE }

  // lh/rh: hash of the local / remote record ("deleted" for a tombstone, null if absent);
  // ih: hash both sides had at the last successful sync (null if never synced).
  static Action decide(String lh, String rh, String ih) {
    if (lh == null && rh == null) return Action.SKIP;
    if (lh != null && lh.equals(rh)) return Action.SAME;
    if (rh == null) return lh.equals(DELETED) ? Action.FORGET_TOMBSTONE : Action.PUSH;
    if (lh == null) return rh.equals(DELETED) ? Action.RECORD_REMOTE_DELETE : Action.PULL;
    boolean localChanged = !lh.equals(ih), remoteChanged = !rh.equals(ih);
    if (!remoteChanged) return Action.PUSH;
    if (!localChanged) return Action.PULL;
    if (lh.equals(DELETED)) return Action.PULL; // an edit elsewhere beats a delete here
    if (rh.equals(DELETED)) return Action.PUSH; // and a local edit beats a delete elsewhere
    return Action.MERGE;
  }
}
