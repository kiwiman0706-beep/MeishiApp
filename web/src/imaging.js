// Pictures in the browser: what desktop/main.js does with Electron's nativeImage, with a canvas.
// Photos keep their orientation (createImageBitmap reads EXIF); PDFs are drawn by pdf.js.
"use strict";

async function bitmap(bytes) {
  return createImageBitmap(new Blob([bytes]), { imageOrientation: "from-image" });
}

function canvas(w, h) {
  const c = document.createElement("canvas");
  c.width = Math.max(1, Math.round(w));
  c.height = Math.max(1, Math.round(h));
  return c;
}

async function encode(c, type, quality) {
  const blob = await new Promise((ok) => c.toBlob(ok, type, quality));
  if (!blob) throw new Error("画像を変換できませんでした");
  return Buffer.from(await blob.arrayBuffer());
}

// Scaled to fit max, drawn onto white (JPEG has no transparency).
async function draw(src, sx, sy, sw, sh, max, type, quality) {
  const s = Math.min(1, max / Math.max(sw, sh));
  const c = canvas(sw * s, sh * s);
  const g = c.getContext("2d");
  if (type === "image/jpeg") {
    g.fillStyle = "#fff";
    g.fillRect(0, 0, c.width, c.height);
  }
  g.imageSmoothingQuality = "high";
  g.drawImage(src, sx, sy, sw, sh, 0, 0, c.width, c.height);
  return encode(c, type, quality);
}

let pdfjs = null;
function loadPdf() {
  if (!pdfjs)
    pdfjs = import(new URL("pdf/pdf.min.mjs", document.baseURI).href).then((m) => {
      m.GlobalWorkerOptions.workerSrc = new URL("pdf/pdf.worker.min.mjs", document.baseURI).href;
      return m;
    });
  return pdfjs;
}

const imaging = {
  async toJpeg(bytes, max) {
    let b;
    try {
      b = await bitmap(bytes);
    } catch {
      throw new Error("この画像形式は読み込めません（JPEG・PNGをお使いください）");
    }
    try {
      return await draw(b, 0, 0, b.width, b.height, max, "image/jpeg", 0.85);
    } finally {
      b.close();
    }
  },
  async crop(bytes, rectOf, max) {
    const b = await bitmap(bytes);
    try {
      const r = rectOf(b.width, b.height);
      if (!r || r.width < 8 || r.height < 8) return null;
      return await draw(b, r.x, r.y, r.width, r.height, max, "image/jpeg", 0.92);
    } finally {
      b.close();
    }
  },
  async size(bytes) {
    try {
      const b = await bitmap(bytes);
      const s = { width: b.width, height: b.height };
      b.close();
      return s;
    } catch {
      return null;
    }
  },
  async toPng(bytes, max) {
    const b = await bitmap(bytes);
    try {
      return await draw(b, 0, 0, b.width, b.height, max, "image/png");
    } finally {
      b.close();
    }
  },
  async pdfPages(bytes, max) {
    const pdf = await loadPdf();
    const doc = await pdf.getDocument({
      data: new Uint8Array(bytes),
      isEvalSupported: false,
      cMapUrl: new URL("pdf/cmaps/", document.baseURI).href,
      cMapPacked: true,
      standardFontDataUrl: new URL("pdf/standard_fonts/", document.baseURI).href,
    }).promise;
    if (doc.numPages > 100) throw new Error("PDFは100ページまでです");
    const out = [];
    for (let n = 1; n <= doc.numPages; n++) {
      const page = await doc.getPage(n);
      const base = page.getViewport({ scale: 1 });
      const vp = page.getViewport({ scale: max / Math.max(base.width, base.height) });
      const c = canvas(vp.width, vp.height);
      const g = c.getContext("2d");
      g.fillStyle = "#fff";
      g.fillRect(0, 0, c.width, c.height);
      await page.render({ canvasContext: g, viewport: vp }).promise;
      out.push(await encode(c, "image/jpeg", 0.88));
    }
    return out;
  },
};

module.exports = { imaging };
