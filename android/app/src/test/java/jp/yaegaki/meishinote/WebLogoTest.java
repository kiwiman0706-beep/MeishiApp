package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

// Same cases as desktop/test/weblogo.test.js.
public class WebLogoTest {
  @Test
  public void sitesFromWebAddressThenCompanyMail() throws Exception {
    assertEquals(
        Arrays.asList("https://www.sample.co.jp/", "https://sample.co.jp/"),
        WebLogo.sites(new JSONObject().put("website", "www.sample.co.jp/about").put("email", "taro@sample.co.jp\nx@gmail.com")));
    assertTrue(WebLogo.sites(new JSONObject().put("email", "a@docomo.ne.jp\nb@yahoo.co.jp")).isEmpty());
  }

  @Test
  public void candidatesBestFirst() {
    String html =
        "<html><head><link rel=\"icon\" href=\"/fav-16.png\" sizes=\"16x16\"><link rel=\"icon\" href=\"/fav-192.png\" sizes=\"192x192\">"
            + "<link rel=\"icon\" href=\"/logo.svg\" type=\"image/svg+xml\"><link rel='apple-touch-icon' href='img/touch.png'>"
            + "<script type=\"application/ld+json\">{\"@type\":\"Organization\",\"logo\":{\"url\":\"https://cdn.sample.jp/logo.png\"}}</script></head></html>";
    List<String> c = WebLogo.candidates(html, "https://www.sample.jp/top/");
    assertEquals(
        Arrays.asList("https://cdn.sample.jp/logo.png", "https://www.sample.jp/top/img/touch.png", "https://www.sample.jp/fav-192.png", "https://www.sample.jp/fav-16.png"),
        c.subList(0, 4));
    for (String u : c) assertFalse(u.endsWith(".svg"));
    assertTrue(c.contains("https://www.sample.jp/favicon.ico"));
  }
}
