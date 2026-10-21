// Service worker: the app works offline (its files are cached per version) and media/ is served
// from IndexedDB, where the page keeps card images.
"use strict";
const { Idb } = require("./idb");

/* global VERSION, SHELL */
const CACHE = "theta-meishi-" + VERSION;
const TYPES = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", pdf: "application/pdf", m4a: "audio/mp4" };

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith("theta-meishi-") && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

let db = null;
async function media(name) {
  db = db || (await Idb.open());
  const b = await db.get("media", name);
  if (b === undefined) return new Response("not found", { status: 404 });
  const type = TYPES[name.split(".").pop().toLowerCase()] || "application/octet-stream";
  return new Response(new Blob([b], { type }), { headers: { "Content-Type": type, "Cache-Control": "no-store" } });
}

self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  if (u.origin !== location.origin || e.request.method !== "GET") return;
  const m = /\/media\/([^/?#]+)$/.exec(u.pathname);
  if (m) return e.respondWith(media(decodeURIComponent(m[1])));
  // The app's own files: from the cache of this version, else the network.
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then((hit) => hit || fetch(e.request)));
});
