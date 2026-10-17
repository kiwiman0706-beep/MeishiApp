package jp.yaegaki.meishinote;

import java.text.Normalizer;
import java.util.Locale;
import org.json.JSONObject;

// One company, however the card spells it: 「株式会社サンプル」「(株)サンプル」「サンプル Co., Ltd.」.
// The same rule lives in app.js (companyKey) for the company view.
public final class Company {
  static final String FORMS =
      "株式会社|有限会社|合同会社|合資会社|合名会社|一般社団法人|一般財団法人|公益社団法人|公益財団法人"
          + "|特定非営利活動法人|npo法人|医療法人社団|医療法人財団|医療法人|社会福祉法人|学校法人|宗教法人|独立行政法人"
          + "|\\(株\\)|\\(有\\)|\\(同\\)|\\(社\\)|\\(財\\)";

  public static String key(String company) {
    String s = Normalizer.normalize(company == null ? "" : company, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    s = s.replaceAll(FORMS, "");
    s = s.replaceAll("\\b(co\\.?,?\\s*ltd\\.?|inc\\.?|corp\\.?|corporation|llc|ltd\\.?|k\\.k\\.)", "");
    return s.replaceAll("[\\s・.,'\"()\\-]", "");
  }

  // Shops without an operating company are grouped by the shop name.
  public static String key(JSONObject p) {
    String c = p.optString("company");
    if (c.trim().isEmpty() && p.optString("kind").equals("shop")) c = p.optString("name");
    return key(c);
  }
}
