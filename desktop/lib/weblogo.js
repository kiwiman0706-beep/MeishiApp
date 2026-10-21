// A company's logo from its own website, for cards without one (WebLogo.java is the same).
// The site is the card's web address, else its e-mail domain (not a free-mail one); Gemini's
// search is asked only when neither exists, and its answer is used only if the page opens.
"use strict";

const FREE_MAIL =
  /(^|\.)(gmail\.com|googlemail\.com|yahoo\.co\.jp|yahoo\.com|ymail\.ne\.jp|outlook\.(com|jp)|hotmail\.(com|co\.jp)|live\.(com|jp)|msn\.com|icloud\.com|me\.com|mac\.com|docomo\.ne\.jp|ezweb\.ne\.jp|au\.com|softbank\.ne\.jp|i\.softbank\.jp|ymobile\.ne\.jp|nifty\.com|biglobe\.ne\.jp|so-net\.ne\.jp|ocn\.ne\.jp|plala\.or\.jp|dion\.ne\.jp|aol\.com|proton\.me|protonmail\.com|example\.(com|jp|org))$/i;

function host(u) {
  try {
    return new URL(/^https?:\/\//i.test(u) ? u : "https://" + u).hostname.toLowerCase();
  } catch {
    return "";
  }
}

// Home pages to try, best first.
function sites(p) {
  const out = [];
  for (const w of String(p.website || "").split(/[\n,;\s]+/)) {
    const h = host(w.trim());
    if (h && h.includes(".")) out.push("https://" + h + "/");
  }
  for (const e of String(p.email || "").split(/[\n,;\s]+/)) {
    const d = (e.split("@")[1] || "").trim().toLowerCase();
    if (d && d.includes(".") && !FREE_MAIL.test(d)) out.push("https://" + d.replace(/^(mail|mx|smtp)\./, "") + "/");
  }
  return [...new Set(out)];
}

const attr = (tag, name) => {
  const m = new RegExp(`\\s${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, "i").exec(tag);
  return m ? (m[1] ?? m[2] ?? m[3] ?? "").replace(/&amp;/g, "&").trim() : "";
};

// Logo images a page declares, best first: its structured-data logo, its touch icon, the largest
// icon, then the conventional locations.
function candidates(html, base) {
  const found = [];
  const add = (u, score) => {
    if (!u || /^data:/i.test(u)) return;
    try {
      const abs = new URL(u, base).href;
      if (/^https?:/i.test(abs)) found.push({ url: abs, score });
    } catch {}
  };
  for (const m of html.matchAll(/<script[^>]+application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi)) {
    const walk = (x) => {
      if (!x || typeof x !== "object") return;
      if (Array.isArray(x)) return x.forEach(walk);
      const l = x.logo;
      if (typeof l === "string") add(l, 100);
      else if (l && typeof l === "object") add(l.url || l.contentUrl || "", 100);
      for (const v of Object.values(x)) if (v && typeof v === "object") walk(v);
    };
    try {
      walk(JSON.parse(m[1].trim()));
    } catch {}
  }
  for (const m of html.matchAll(/<(link|meta)\b[^>]*>/gi)) {
    const tag = m[0];
    const rel = attr(tag, "rel").toLowerCase();
    if (m[1].toLowerCase() === "meta") {
      const prop = (attr(tag, "property") || attr(tag, "itemprop") || attr(tag, "name")).toLowerCase();
      if (prop === "og:logo" || prop === "logo") add(attr(tag, "content"), 90);
      continue;
    }
    const href = attr(tag, "href");
    if (/(^|\s)apple-touch-icon(-precomposed)?(\s|$)/.test(rel)) add(href, 80);
    else if (/(^|\s)icon(\s|$)/.test(rel) && !/\.svg(\?|$)/i.test(href)) {
      const size = Math.max(0, ...attr(tag, "sizes").split(/\s+/).map((s) => parseInt(s, 10) || 0));
      add(href, 40 + Math.min(size, 256) / 8);
    }
  }
  add("/apple-touch-icon.png", 30);
  add("/favicon.ico", 10);
  const seen = new Set();
  return found.sort((a, b) => b.score - a.score).filter((c) => !seen.has(c.url) && seen.add(c.url));
}

async function get(fetchImpl, url, max, accept) {
  const res = await fetchImpl(url, { redirect: "follow", headers: { Accept: accept, "User-Agent": "Mozilla/5.0 ThetaMeishiHatch" }, signal: AbortSignal.timeout(12000) });
  if (!res.ok) throw new Error("HTTP " + res.status);
  const len = +(res.headers.get("content-length") || 0);
  if (len > max) throw new Error("too large");
  const buf = new Uint8Array(await res.arrayBuffer());
  if (buf.length > max) throw new Error("too large");
  return { buf, type: String(res.headers.get("content-type") || ""), url: res.url || url };
}

// { bytes, domain } of the best logo found, or null. size(bytes) → { width, height } or null
// (null = not an image this platform can show).
async function find({ fetchImpl, size, searchSite }, p) {
  let pages = sites(p);
  if (!pages.length && searchSite) {
    const u = await searchSite(p).catch(() => "");
    if (u && host(u)) pages = ["https://" + host(u) + "/"];
  }
  for (const page of pages.slice(0, 3)) {
    let html = "", base = page;
    try {
      const r = await get(fetchImpl, page, 3 * 1024 * 1024, "text/html");
      html = new TextDecoder().decode(r.buf);
      base = r.url;
    } catch {
      continue; // the site does not open: try the next one
    }
    for (const c of candidates(html, base).slice(0, 6)) {
      try {
        const r = await get(fetchImpl, c.url, 2 * 1024 * 1024, "image/*");
        if (/svg/i.test(r.type)) continue;
        const s = await size(r.buf);
        if (!s || Math.min(s.width, s.height) < 32) continue;
        return { bytes: r.buf, domain: host(base), url: c.url };
      } catch {}
    }
  }
  return null;
}

module.exports = { sites, candidates, find, host };
