"use strict";
const fs = require("fs");
const path = require("path");

// A "card" 900x600 with the code small in a corner (4px a module) and a dark band of text.
function card() {
  const rows = fs.readFileSync(path.join(__dirname, "qr-vcard.txt"), "utf8").trim().split("\n");
  const w = 900, h = 600, s = 4;
  const data = new Uint8ClampedArray(w * h * 4).fill(246);
  const dark = (x, y) => data.fill(17, (y * w + x) * 4, (y * w + x) * 4 + 3);
  for (let y = 40; y < 60; y++) for (let x = 40; x < 600; x++) dark(x, y);
  const ox = w - rows[0].length * s - 30, oy = h - rows.length * s - 30;
  for (let y = 0; y < rows.length * s; y++) for (let x = 0; x < rows[0].length * s; x++) if (rows[(y / s) | 0][(x / s) | 0] === "1") dark(ox + x, oy + y);
  return { data, width: w, height: h };
}

module.exports = { card };
