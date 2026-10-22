// refineLogoRect: Gemini's box corrected with the picture (a logo cut at an edge grows back,
// margins are trimmed, separate text next to it stays out).
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { refineLogoRect } = require("../lib/model");

// A white 800×500 card with a red logo (100..300 × 50..150) and black text further right.
function card(bg = [255, 255, 255]) {
  return (x, y) => {
    if (x >= 100 && x < 300 && y >= 50 && y < 150) return [200, 20, 30];
    if (x >= 380 && x < 700 && y >= 90 && y < 120) return [0, 0, 0];
    return bg;
  };
}

test("a box cut through the logo grows to the whole logo, not into the text", () => {
  const r = refineLogoRect(card(), 800, 500, { x: 120, y: 60, width: 100, height: 60 });
  assert.ok(r.x <= 100 && r.x >= 80, "left edge " + r.x);
  assert.ok(r.y <= 50 && r.y >= 30, "top " + r.y);
  assert.ok(r.x + r.width >= 300 && r.x + r.width <= 330, "right edge " + (r.x + r.width));
  assert.ok(r.y + r.height >= 150 && r.y + r.height <= 175, "bottom " + (r.y + r.height));
});

test("a loose box is trimmed to the logo; a coloured card works the same", () => {
  const r = refineLogoRect(card([30, 60, 140]), 800, 500, { x: 60, y: 20, width: 290, height: 170 });
  assert.ok(r.x >= 80 && r.x <= 100 && r.x + r.width <= 330 && r.y + r.height <= 175, JSON.stringify(r));
});

test("an empty area keeps Gemini's box", () => {
  const box = { x: 500, y: 300, width: 80, height: 60 };
  assert.deepStrictEqual(refineLogoRect(card(), 800, 500, box), box);
});

test("logos cut the old way are offered again, one card per company", () => {
  const { recutPicks } = require("../lib/model");
  const people = [
    { id: "a", company: "株式会社サンプル", logo: "1.jpg", logoSource: "card", pages: [{ file: "a.jpg" }], created: 1 },
    { id: "b", company: "(株)サンプル", logo: "1.jpg", logoSource: "card", pages: [{ file: "b.jpg" }], created: 2 },
    { id: "c", company: "新方式", logo: "2.jpg", logoSource: "card2", pages: [{ file: "c.jpg" }], created: 3 },
    { id: "d", company: "手動", logo: "3.jpg", pages: [{ file: "d.jpg" }], created: 4 },
  ];
  assert.deepStrictEqual(recutPicks(people), ["b"]);
});
