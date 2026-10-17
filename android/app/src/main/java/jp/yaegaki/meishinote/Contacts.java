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
  public static void remove(Context c, JSONObject p) throws Exception {
    if (!registered(c, p)) return;
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
    String image = "";
    if (photo.equals("card") && p.getJSONArray("pages").length() > 0)
      image = p.getJSONArray("pages").getJSONObject(0).getString("file");
    if (photo.equals("portrait") || photo.equals("logo")) image = p.optString(photo);
    if (!image.isEmpty()) {
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
              ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
              ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
      try (OutputStream out = c.getContentResolver().openOutputStream(uri, "w")) {
        if (out == null || !square.compress(Bitmap.CompressFormat.JPEG, 92, out))
          throw new IOException("写真を登録できませんでした");
      } catch (Exception e) {
        throw new IOException("連絡先本体は登録済みですが、画像登録に失敗しました。連絡先アプリで画像を追加してください。");
      } finally {
        square.recycle();
      }
      p.put("contactPhoto", photo);
      Store.get(c).put(p);
    }
    return id;
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
