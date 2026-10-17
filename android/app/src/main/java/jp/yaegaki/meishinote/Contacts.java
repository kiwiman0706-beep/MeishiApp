package jp.yaegaki.meishinote;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class Contacts {
  static final String MARKER = "vnd.android.cursor.item/vnd.jp.yaegaki.meishinote";

  static ContentProviderOperation.Builder data(int index, String mime) {
    return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
        .withValue(ContactsContract.Data.MIMETYPE, mime);
  }

  public static boolean registered(Context c, JSONObject p) {
    long id = p.optLong("contactRawId", -1);
    if (id < 0) return false;
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContactsContract.Data.CONTENT_URI,
                new String[] {ContactsContract.Data._ID},
                ContactsContract.Data.RAW_CONTACT_ID
                    + "=? AND "
                    + ContactsContract.Data.MIMETYPE
                    + "=? AND "
                    + ContactsContract.Data.DATA1
                    + "=?",
                new String[] {"" + id, MARKER, p.optString("id")},
                null)) {
      return q != null && q.moveToFirst();
    } catch (Exception e) {
      return false;
    }
  }

  public static String duplicate(Context c, JSONObject p) {
    String phone = p.optString("mobile").isEmpty() ? p.optString("phone") : p.optString("mobile");
    if (!phone.isEmpty()) {
      String first = phone.split("[\n,;]")[0].replaceAll("[^0-9+]", "");
      if (!first.isEmpty())
        try (Cursor q =
            c.getContentResolver()
                .query(
                    Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(first)),
                    new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null,
                    null,
                    null)) {
          if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Exception ignored) {
        }
    }
    String email = p.optString("email").split("[\n,;]")[0].trim();
    if (!email.isEmpty())
      try (Cursor q =
          c.getContentResolver()
              .query(
                  Email.CONTENT_URI,
                  new String[] {Email.DISPLAY_NAME},
                  Email.ADDRESS + "=?",
                  new String[] {email},
                  null)) {
        if (q != null && q.moveToFirst()) return q.getString(0);
      } catch (Exception ignored) {
      }
    return "";
  }

  // Data rows this app owns on its raw contact; update() replaces exactly these kinds.
  static final String[] OWNED = {
    StructuredName.CONTENT_ITEM_TYPE,
    Organization.CONTENT_ITEM_TYPE,
    Phone.CONTENT_ITEM_TYPE,
    Email.CONTENT_ITEM_TYPE,
    StructuredPostal.CONTENT_ITEM_TYPE,
    Website.CONTENT_ITEM_TYPE,
    Note.CONTENT_ITEM_TYPE
  };

  interface Row {
    ContentProviderOperation.Builder make(String mime);
  }

  static void rows(JSONObject p, ArrayList<ContentProviderOperation> ops, Row row) {
    ops.add(
        row.make(StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, p.optString("name"))
            .withValue(StructuredName.PHONETIC_GIVEN_NAME, p.optString("kana"))
            .build());
    ops.add(
        row.make(Organization.CONTENT_ITEM_TYPE)
            .withValue(Organization.COMPANY, p.optString("company"))
            .withValue(Organization.DEPARTMENT, p.optString("department"))
            .withValue(Organization.TITLE, p.optString("title"))
            .withValue(Organization.TYPE, Organization.TYPE_WORK)
            .build());
    for (String field : new String[] {"phone", "mobile", "fax"})
      for (String v : p.optString(field).split("[\n,;]"))
        if (!v.trim().isEmpty())
          ops.add(
              row.make(Phone.CONTENT_ITEM_TYPE)
                  .withValue(Phone.NUMBER, v.trim())
                  .withValue(
                      Phone.TYPE,
                      field.equals("mobile")
                          ? Phone.TYPE_MOBILE
                          : field.equals("fax") ? Phone.TYPE_FAX_WORK : Phone.TYPE_WORK)
                  .build());
    for (String v : p.optString("email").split("[\n,;]"))
      if (!v.trim().isEmpty())
        ops.add(
            row.make(Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, v.trim())
                .withValue(Email.TYPE, Email.TYPE_WORK)
                .build());
    if (!p.optString("address").isEmpty() || !p.optString("postal").isEmpty())
      ops.add(
          row.make(StructuredPostal.CONTENT_ITEM_TYPE)
              .withValue(StructuredPostal.FORMATTED_ADDRESS, p.optString("address"))
              .withValue(StructuredPostal.POSTCODE, p.optString("postal"))
              .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
              .build());
    if (!p.optString("website").isEmpty())
      ops.add(
          row.make(Website.CONTENT_ITEM_TYPE)
              .withValue(Website.URL, p.optString("website"))
              .withValue(Website.TYPE, Website.TYPE_WORK)
              .build());
    ops.add(
        row.make(Note.CONTENT_ITEM_TYPE)
            .withValue(
                Note.NOTE,
                "Theta Meishi Hatchから登録\n交換日: " + p.optString("metDate") + "\n交換場所: " + p.optString("metPlace"))
            .build());
  }

  // Rewrites the contact this app created so edits made in the app reach Google.
  // Edits made directly in the Contacts app to these fields are overwritten.
  public static void update(Context c, JSONObject p) throws Exception {
    long rawId = p.optLong("contactRawId", -1);
    if (rawId < 0 || !registered(c, p)) throw new IOException("登録した連絡先が見つかりません（連絡先アプリで削除された可能性があります）");
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    if (p.optBoolean("contactMerged")) {
      // The person's own contact: never rewrite it, only add what it lacks.
      fill(c, p, rawId, ops);
      if (!ops.isEmpty()) c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
      return;
    }
    for (String mime : OWNED)
      ops.add(
          ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
              .withSelection(
                  ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                  new String[] {"" + rawId, mime})
              .build());
    rows(
        p,
        ops,
        mime ->
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mime));
    c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
  }

  // Deletes only a contact this app created (checked by its marker row); sync removes it from Google.
  // A contact merged into the person's existing one is only unlinked (the marker row goes).
  public static void remove(Context c, JSONObject p) throws Exception {
    if (!registered(c, p)) return;
    if (p.optBoolean("contactMerged")) {
      c.getContentResolver()
          .delete(
              ContactsContract.Data.CONTENT_URI,
              ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DATA1 + "=?",
              new String[] {"" + p.getLong("contactRawId"), MARKER, p.optString("id")});
      return;
    }
    c.getContentResolver()
        .delete(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, p.getLong("contactRawId")),
            null,
            null);
  }

  public static long add(Context c, JSONObject p, String account, String photo) throws Exception {
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    ops.add(
        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, "com.google")
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account)
            .build());
    rows(p, ops, mime -> data(0, mime));
    ops.add(data(0, MARKER).withValue(ContactsContract.Data.DATA1, p.getString("id")).build());
    ContentProviderResult[] results =
        c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    long id = ContentUris.parseId(results[0].uri);
    // contactRawId is per phone; contactRegistered syncs so other devices don't add it again.
    p.put("contactRawId", id).put("contactAccount", account).put("contactRegistered", true);
    Store.get(c).put(p);
    if (writePhoto(c, p, id, photo)) {
      p.put("contactPhoto", photo);
      Store.get(c).put(p);
    }
    return id;
  }

  // Card image (or portrait / logo) as the contact photo; false when there is nothing to use.
  static boolean writePhoto(Context c, JSONObject p, long rawId, String photo) throws Exception {
    String image = "";
    if (photo.equals("card") && p.getJSONArray("pages").length() > 0)
      image = p.getJSONArray("pages").getJSONObject(0).getString("file");
    if (photo.equals("portrait") || photo.equals("logo")) image = p.optString(photo);
    if (image.isEmpty()) return false;
    Bitmap b = Importer.image(Store.get(c).mediaFile(image), 900);
    Bitmap square = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(square);
    canvas.drawColor(Color.WHITE);
    float scale = Math.min(820f / b.getWidth(), 820f / b.getHeight());
    float w = b.getWidth() * scale, h = b.getHeight() * scale;
    canvas.drawBitmap(
        b,
        null,
        new RectF((900 - w) / 2, (900 - h) / 2, (900 + w) / 2, (900 + h) / 2),
        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    b.recycle();
    Uri uri =
        Uri.withAppendedPath(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
            ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
    try (OutputStream out = c.getContentResolver().openOutputStream(uri, "w")) {
      if (out == null || !square.compress(Bitmap.CompressFormat.JPEG, 92, out))
        throw new IOException("写真を登録できませんでした");
    } catch (Exception e) {
      throw new IOException("連絡先本体は登録済みですが、画像登録に失敗しました。連絡先アプリで画像を追加してください。");
    } finally {
      square.recycle();
    }
    return true;
  }

  // ---- contacts that were already on the phone ----------------------------------------------

  static String digits(String s) {
    String d = s.replaceAll("[^0-9]", "");
    // +81 90… and 090… are the same number.
    return d.startsWith("81") && s.trim().startsWith("+") ? "0" + d.substring(2) : d;
  }

  static List<String> values(JSONObject p, String... fields) {
    ArrayList<String> out = new ArrayList<>();
    for (String f : fields)
      for (String v : p.optString(f).split("[\n,;]")) if (!v.trim().isEmpty()) out.add(v.trim());
    return out;
  }

  // Raw contacts made by this app (any card); never offered for merging or deleting here.
  static Set<Long> ours(Context c) {
    HashSet<Long> out = new HashSet<>();
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContactsContract.Data.CONTENT_URI,
                new String[] {ContactsContract.Data.RAW_CONTACT_ID},
                ContactsContract.Data.MIMETYPE + "=?",
                new String[] {MARKER},
                null)) {
      while (q != null && q.moveToNext()) out.add(q.getLong(0));
    }
    return out;
  }

  // Other contacts that look like this person (same phone, e-mail or name), for the person to
  // pick from: a company's main number can also match colleagues.
  public static JSONArray candidates(Context c, JSONObject p) throws Exception {
    ContentResolver r = c.getContentResolver();
    LinkedHashMap<Long, Set<String>> why = new LinkedHashMap<>(); // contact id -> reasons
    for (String v : values(p, "mobile", "phone")) {
      String n = v.replaceAll("[^0-9+]", "");
      if (n.length() < 5) continue;
      try (Cursor q =
          r.query(
              Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n)),
              new String[] {ContactsContract.PhoneLookup._ID},
              null,
              null,
              null)) {
        while (q != null && q.moveToNext()) why.computeIfAbsent(q.getLong(0), k -> new LinkedHashSet<>()).add("電話");
      } catch (Exception ignored) {
      }
    }
    for (String v : values(p, "email"))
      try (Cursor q =
          r.query(
              Email.CONTENT_URI,
              new String[] {Email.CONTACT_ID},
              "lower(" + Email.ADDRESS + ")=?",
              new String[] {v.toLowerCase(Locale.ROOT)},
              null)) {
        while (q != null && q.moveToNext()) why.computeIfAbsent(q.getLong(0), k -> new LinkedHashSet<>()).add("メール");
      } catch (Exception ignored) {
      }
    String name = p.optString("name").replaceAll("[\\s　]", "");
    if (name.length() >= 2)
      try (Cursor q =
          r.query(
              ContactsContract.Data.CONTENT_URI,
              new String[] {ContactsContract.Data.CONTACT_ID},
              ContactsContract.Data.MIMETYPE + "=? AND replace(replace(" + StructuredName.DISPLAY_NAME + ",' ',''),'　','')=?",
              new String[] {StructuredName.CONTENT_ITEM_TYPE, name},
              null)) {
        while (q != null && q.moveToNext()) why.computeIfAbsent(q.getLong(0), k -> new LinkedHashSet<>()).add("氏名");
      } catch (Exception ignored) {
      }
    JSONArray out = new JSONArray();
    if (why.isEmpty()) return out;
    Set<Long> mine = ours(c);
    StringBuilder in = new StringBuilder();
    for (Long id : why.keySet()) in.append(in.length() == 0 ? "" : ",").append(id);
    try (Cursor q =
        r.query(
            ContactsContract.RawContacts.CONTENT_URI,
            new String[] {
              ContactsContract.RawContacts._ID,
              ContactsContract.RawContacts.CONTACT_ID,
              ContactsContract.RawContacts.ACCOUNT_NAME,
              ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY
            },
            ContactsContract.RawContacts.CONTACT_ID + " IN (" + in + ") AND " + ContactsContract.RawContacts.DELETED + "=0",
            null,
            null)) {
      while (q != null && q.moveToNext()) {
        long raw = q.getLong(0);
        if (mine.contains(raw)) continue;
        JSONObject x =
            new JSONObject()
                .put("rawId", raw)
                .put("name", q.isNull(3) ? "（名前なし）" : q.getString(3))
                .put("account", q.isNull(2) ? "この端末" : q.getString(2))
                .put("why", String.join("・", why.get(q.getLong(1))));
        describe(c, raw, x);
        out.put(x);
      }
    }
    return out;
  }

  // Company, numbers, addresses and whether it has a photo, so the person can tell contacts apart.
  static void describe(Context c, long raw, JSONObject x) throws Exception {
    ArrayList<String> lines = new ArrayList<>();
    boolean photo = false;
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContactsContract.Data.CONTENT_URI,
                new String[] {ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA15},
                ContactsContract.Data.RAW_CONTACT_ID + "=?",
                new String[] {"" + raw},
                null)) {
      while (q != null && q.moveToNext()) {
        String mime = q.getString(0), v = q.isNull(1) ? "" : q.getString(1);
        if (mime.equals(Photo.CONTENT_ITEM_TYPE)) photo |= !q.isNull(2);
        else if ((mime.equals(Organization.CONTENT_ITEM_TYPE) || mime.equals(Phone.CONTENT_ITEM_TYPE) || mime.equals(Email.CONTENT_ITEM_TYPE)) && !v.isEmpty())
          lines.add(v);
      }
    }
    x.put("detail", String.join(" / ", lines)).put("photo", photo);
  }

  static void requireCandidate(Context c, long raw) throws Exception {
    if (ours(c).contains(raw)) throw new IOException("このアプリが登録した連絡先は、名刺の「1人にまとめる」で統合してください");
  }

  // Adds to an existing raw contact what the card has and it lacks. Nothing there is changed.
  static void fill(Context c, JSONObject p, long raw, ArrayList<ContentProviderOperation> ops) throws Exception {
    HashSet<String> phones = new HashSet<>(), emails = new HashSet<>(), sites = new HashSet<>();
    HashSet<String> kinds = new HashSet<>();
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContactsContract.Data.CONTENT_URI,
                new String[] {ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1},
                ContactsContract.Data.RAW_CONTACT_ID + "=?",
                new String[] {"" + raw},
                null)) {
      while (q != null && q.moveToNext()) {
        String mime = q.getString(0), v = q.isNull(1) ? "" : q.getString(1);
        kinds.add(mime);
        if (mime.equals(Phone.CONTENT_ITEM_TYPE)) phones.add(digits(v));
        if (mime.equals(Email.CONTENT_ITEM_TYPE)) emails.add(v.trim().toLowerCase(Locale.ROOT));
        if (mime.equals(Website.CONTENT_ITEM_TYPE)) sites.add(v.trim());
      }
    }
    ArrayList<ContentProviderOperation> all = new ArrayList<>();
    rows(
        p,
        all,
        mime ->
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                .withValue(ContactsContract.Data.MIMETYPE, mime));
    // rows() is the single source of what a card writes; keep the inserts the contact lacks.
    for (ContentProviderOperation op : all) {
      ContentValues v = op.resolveValueBackReferences(new ContentProviderResult[0], 0);
      String mime = v.getAsString(ContactsContract.Data.MIMETYPE);
      boolean add;
      if (mime.equals(Phone.CONTENT_ITEM_TYPE)) add = phones.add(digits(v.getAsString(Phone.NUMBER)));
      else if (mime.equals(Email.CONTENT_ITEM_TYPE)) add = emails.add(v.getAsString(Email.ADDRESS).toLowerCase(Locale.ROOT));
      else if (mime.equals(Website.CONTENT_ITEM_TYPE)) add = sites.add(v.getAsString(Website.URL));
      else if (mime.equals(Note.CONTENT_ITEM_TYPE)) add = false; // the person's own note stays as it is
      else add = !kinds.contains(mime); // name, company, address: only when missing
      if (add) ops.add(op);
    }
  }

  // Merges the card into a contact that was already there: fills what it lacks (and the photo if
  // it has none), deletes the contact this app added, and links the card to the existing one.
  public static void mergeInto(Context c, JSONObject p, long raw, String photo) throws Exception {
    requireCandidate(c, raw);
    String account = "";
    try (Cursor q =
        c.getContentResolver()
            .query(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raw),
                new String[] {ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.DELETED},
                null,
                null,
                null)) {
      if (q == null || !q.moveToFirst() || q.getInt(1) != 0) throw new IOException("その連絡先は見つかりません");
      account = q.isNull(0) ? "" : q.getString(0);
    }
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    fill(c, p, raw, ops);
    ops.add(
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
            .withValue(ContactsContract.Data.MIMETYPE, MARKER)
            .withValue(ContactsContract.Data.DATA1, p.getString("id"))
            .build());
    c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    JSONObject probe = new JSONObject();
    describe(c, raw, probe);
    boolean photoAdded = !probe.optBoolean("photo") && writePhoto(c, p, raw, photo);
    remove(c, p); // the one this app added (no-op when there is none)
    p.put("contactRawId", raw).put("contactAccount", account).put("contactMerged", true).put("contactRegistered", true);
    p.remove("contactDuplicate");
    p.remove("contactUnlinked");
    if (photoAdded) p.put("contactPhoto", photo);
    else p.remove("contactPhoto");
  }

  // Deletes a contact that was already there (Google keeps it in its trash for 30 days).
  public static void deleteExisting(Context c, long raw) throws Exception {
    requireCandidate(c, raw);
    c.getContentResolver().delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raw), null, null);
  }

  // A card taking over another card's contact (card merge): the marker follows the card id.
  public static void relink(Context c, long raw, String fromId, String toId) {
    ContentValues v = new ContentValues();
    v.put(ContactsContract.Data.DATA1, toId);
    c.getContentResolver()
        .update(
            ContactsContract.Data.CONTENT_URI,
            v,
            ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DATA1 + "=?",
            new String[] {"" + raw, MARKER, fromId});
  }

  public static void view(Context c, long rawId) throws Exception {
    Uri u =
        ContactsContract.RawContacts.getContactLookupUri(
            c.getContentResolver(),
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId));
    if (u == null) throw new IOException("連絡先が見つかりません");
    c.startActivity(new Intent(Intent.ACTION_VIEW, u));
  }
}
