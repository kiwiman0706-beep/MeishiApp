package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ContactsNameTest {
  static ContentValues values(JSONObject p) throws Exception {
    ContentProviderOperation op =
        Contacts.name(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI), p).build();
    return op.resolveValueBackReferences(new ContentProviderResult[0], 0);
  }

  @Test
  public void japaneseNameAndReadingAreSplit() throws Exception {
    ContentValues v = values(new JSONObject().put("name", "山田　太郎").put("kana", "やまだ たろう"));
    assertEquals("山田　太郎", v.getAsString(StructuredName.DISPLAY_NAME));
    assertEquals("山田", v.getAsString(StructuredName.FAMILY_NAME));
    assertEquals("太郎", v.getAsString(StructuredName.GIVEN_NAME));
    assertEquals("やまだ", v.getAsString(StructuredName.PHONETIC_FAMILY_NAME));
    assertEquals("たろう", v.getAsString(StructuredName.PHONETIC_GIVEN_NAME));
    assertEquals(ContactsContract.PhoneticNameStyle.JAPANESE, (int) v.getAsInteger(StructuredName.PHONETIC_NAME_STYLE));
  }

  @Test
  public void readingWithoutSpaceAndWesternNames() throws Exception {
    ContentValues v = values(new JSONObject().put("name", "山田太郎").put("kana", "ﾔﾏﾀﾞﾀﾛｳ"));
    assertEquals("山田太郎", v.getAsString(StructuredName.FAMILY_NAME));
    assertEquals("ヤマダタロウ", v.getAsString(StructuredName.PHONETIC_FAMILY_NAME)); // half-width → full-width
    ContentValues w = values(new JSONObject().put("name", "Taro Yamada").put("kana", ""));
    assertEquals("Taro", w.getAsString(StructuredName.GIVEN_NAME));
    assertEquals("Yamada", w.getAsString(StructuredName.FAMILY_NAME));
    assertNull(w.getAsString(StructuredName.PHONETIC_FAMILY_NAME));
  }
}
