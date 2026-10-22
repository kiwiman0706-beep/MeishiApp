package jp.yaegaki.meishinote;

import static org.junit.Assert.*;

import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

// Same card as desktop/test/logo-refine.test.js: white 800×500, red logo 100..300 × 50..150,
// black text 380..700 × 90..120.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LogoRefineTest {
  static int[] card(int bg) {
    int w = 800, h = 500;
    int[] px = new int[w * h];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int c = bg;
        if (x >= 100 && x < 300 && y >= 50 && y < 150) c = 0xffc8141e;
        else if (x >= 380 && x < 700 && y >= 90 && y < 120) c = 0xff000000;
        px[y * w + x] = c;
      }
    return px;
  }

  @Test
  public void boxCutThroughTheLogoGrowsToTheWholeLogoNotIntoText() {
    Rect r = Logo.refine(card(0xffffffff), 800, 500, new Rect(120, 60, 220, 120));
    assertTrue("left " + r.left, r.left <= 100 && r.left >= 80);
    assertTrue("top " + r.top, r.top <= 50 && r.top >= 30);
    assertTrue("right " + r.right, r.right >= 300 && r.right <= 330);
    assertTrue("bottom " + r.bottom, r.bottom >= 150 && r.bottom <= 175);
  }

  @Test
  public void looseBoxIsTrimmedOnAColouredCard() {
    Rect r = Logo.refine(card(0xff1e3c8c), 800, 500, new Rect(60, 20, 350, 190));
    assertTrue(r.toShortString(), r.left >= 80 && r.left <= 100 && r.right <= 330 && r.bottom <= 175);
  }

  @Test
  public void emptyAreaKeepsGeminisBox() {
    Rect box = new Rect(500, 300, 580, 360);
    assertEquals(box, Logo.refine(card(0xffffffff), 800, 500, box));
  }
}
