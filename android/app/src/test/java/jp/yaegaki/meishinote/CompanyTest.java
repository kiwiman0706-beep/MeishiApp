package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class CompanyTest {
  @Test
  public void spellingsOfOneCompanyShareAKey() {
    String k = Company.key("株式会社 サンプル");
    assertEquals("サンプル", k);
    for (String s : new String[] {"(株)サンプル", "㈱サンプル", "サンプル株式会社", "（株）サンプル", "サンプル"})
      assertEquals(s, k, Company.key(s));
    assertEquals("sample", Company.key("Sample Co., Ltd."));
    assertEquals("sample", Company.key("SAMPLE Inc."));
    assertNotEquals(k, Company.key("サンプル商事"));
    assertEquals("", Company.key(""));
  }

  @Test
  public void shopsWithoutCompanyUseTheirName() throws Exception {
    assertEquals("やきとり大吉", Company.key(new JSONObject().put("kind", "shop").put("name", "やきとり 大吉").put("company", "")));
    assertEquals("", Company.key(new JSONObject().put("name", "山田 太郎").put("company", "")));
  }
}
