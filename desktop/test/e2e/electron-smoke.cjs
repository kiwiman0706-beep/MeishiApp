// Starts the real Electron app against throwaway folders, drops a 2-page PDF and a PNG on it over
// the HTTP bridge, and checks that pdf.js / nativeImage turned them into pages in the vault.
// Run (Linux needs a display): xvfb-run -a node test/e2e/electron-smoke.cjs
"use strict";
const { spawn } = require("child_process");
const assert = require("assert");
const fs = require("fs"), os = require("os"), path = require("path"), http = require("http");

// Smallest valid PDF with two text pages.
function pdf() {
  const objs = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 252 144] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> >>",
    null,
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 252 144] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>",
    null,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  const stream = (t) => `<< /Length ${t.length} >>\nstream\n${t}\nendstream`;
  objs[3] = stream("BT /F1 18 Tf 20 80 Td (Taro Yamada - front) Tj ET");
  objs[5] = stream("BT /F1 18 Tf 20 80 Td (back side) Tj ET");
  let out = "%PDF-1.4\n";
  const offs = [];
  objs.forEach((o, i) => {
    offs.push(out.length);
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const x = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offs.map((n) => String(n).padStart(10, "0") + " 00000 n \n").join("");
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${x}\n%%EOF\n`;
  return Buffer.from(out, "latin1");
}
// 2x2 red PNG.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==", "base64");

function req(port, p, { method = "GET", headers = {}, body } = {}) {
  return new Promise((ok, ng) => {
    const r = http.request({ host: "127.0.0.1", port, path: p, method, headers: { Host: `127.0.0.1:${port}`, ...headers } }, (res) => {
      let d = "";
      res.on("data", (c) => (d += c));
      res.on("end", () => ok({ status: res.statusCode, headers: res.headers, body: d }));
    });
    r.on("error", ng);
    r.setTimeout(30000, () => r.destroy(new Error("timeout " + p)));
    if (body) r.write(body);
    r.end();
  });
}

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "theta-smoke-"));
  const userData = path.join(dir, "userData"), vault = path.join(dir, "Theta Vault");
  fs.mkdirSync(userData);
  fs.writeFileSync(path.join(userData, "settings.json"), JSON.stringify({ token: "5a0e", port: 47000 + Math.floor(Math.random() * 900), autoRecognize: false }));
  // THETA_APP: a packaged executable (e.g. dist/linux-unpacked/theta-meishi-hatch) instead of the source tree.
  const [cmd, ...args] = process.env.THETA_APP ? [process.env.THETA_APP] : [require("electron"), path.join(__dirname, "../..")];
  const child = spawn(cmd, [...args, "--hidden", "--no-sandbox"], {
    env: { ...process.env, THETA_USER_DATA: userData, THETA_VAULT: vault, THETA_NO_UPDATE: process.env.THETA_NO_UPDATE ?? "1" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let log = "";
  child.stdout.on("data", (d) => (log += d));
  child.stderr.on("data", (d) => (log += d));
  try {
    let port = 0;
    for (let i = 0; i < 300 && !port; i++) {
      const m = /serving http:\/\/127\.0\.0\.1:(\d+)/.exec(log);
      if (m) port = +m[1];
      else await new Promise((r) => setTimeout(r, 100));
    }
    assert.ok(port, "app did not start:\n" + log);
    const login = await req(port, "/?token=5a0e");
    const cookie = login.headers["set-cookie"][0].split(";")[0];
    const up = (name, buf, last, batch) => req(port, `/api/upload?batch=${batch}&last=${last}&name=${name}`, { method: "POST", headers: { Cookie: cookie }, body: buf });
    await up("card.pdf", pdf(), 0, "b1");
    await up("card.png", PNG, 1, "b1");
    const notes = () => {
      try {
        return fs.readdirSync(path.join(vault, "Meishi", "People"));
      } catch {
        return [];
      }
    };
    for (let i = 0; i < 300 && notes().length < 2; i++) await new Promise((r) => setTimeout(r, 100));
    assert.strictEqual(notes().length, 2, "two notes expected:\n" + log);
    const files = fs.readdirSync(path.join(vault, "Meishi", "_files"));
    const jpgs = files.filter((f) => f.endsWith(".jpg"));
    assert.strictEqual(jpgs.length, 3, "2 PDF pages + 1 image: " + files.join(", "));
    for (const f of jpgs) assert.strictEqual(fs.readFileSync(path.join(vault, "Meishi", "_files", f)).subarray(0, 2).toString("hex"), "ffd8");
    const big = jpgs.map((f) => fs.statSync(path.join(vault, "Meishi", "_files", f)).size).sort((a, b) => b - a)[0];
    assert.ok(big > 5000, "PDF page rendered at full size");
    for (const p of ["/pdf/cmaps/UniJIS-UCS2-H.bcmap", "/pdf/standard_fonts/LiberationSans-Regular.ttf", "/pdf/standard_fonts/FoxitSerif.pfb"])
      assert.strictEqual((await req(port, p, { headers: { Cookie: cookie } })).status, 200, p);
    assert.strictEqual((await req(port, "/pdf/standard_fonts/Nope.pfb", { headers: { Cookie: cookie } })).status, 404);
    assert.strictEqual((await req(port, "/pdf/../package.json", { headers: { Cookie: cookie } })).status, 404);
    console.log("PASS electron smoke", dir);
  } finally {
    child.kill();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
