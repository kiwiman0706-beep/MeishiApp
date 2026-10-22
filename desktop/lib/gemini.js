// Gemini REST, same request shape and prompts as android/.../Gemini.java (prompts.json is
// generated from it by scripts/sync-ui.js).
"use strict";
const PROMPTS = require("./prompts.json");
const API = "https://generativelanguage.googleapis.com/v1beta/";

async function request(key, pathAndQuery, body, fetchImpl = fetch) {
  const res = await fetchImpl(API + pathAndQuery, {
    method: body ? "POST" : "GET",
    headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(90000),
  });
  const text = await res.text();
  if (!res.ok) {
    let message = "";
    try {
      message = JSON.parse(text).error.message;
    } catch {}
    message = String(message).split(key).join("[APIキー]");
    throw new Error(
      `Gemini HTTP ${res.status}\n${message}\n` +
        (res.status === 429 ? "利用上限または混雑です。時間を空けて再試行してください。" : res.status === 404 ? "モデル一覧を取得して利用可能なモデルに変更してください。" : "APIキー・モデル名・請求設定を確認してください。"),
    );
  }
  return JSON.parse(text);
}

async function models(key, fetchImpl) {
  const out = [];
  let token = "";
  for (let i = 0; i < 10; i++) {
    const r = await request(key, "models?pageSize=100" + (token ? "&pageToken=" + encodeURIComponent(token) : ""), null, fetchImpl);
    for (const m of r.models || [])
      if (String(m.name).includes("gemini") && (m.supportedGenerationMethods || []).includes("generateContent")) out.push(m.name.replace("models/", ""));
    token = r.nextPageToken || "";
    if (!token) break;
  }
  return out;
}

// images: Buffers of JPEG (already downscaled by the caller), or of `mime`; note: more text
// after the images (what the card's QR code says).
async function generate({ key, model, format }, kind, images, fetchImpl, mime = "image/jpeg", note = "") {
  if (!images.length) throw new Error("画像を追加してください");
  if (images.length > 20) throw new Error("1件の認識は20ページまでです");
  if (!/^[A-Za-z0-9._-]+$/.test(model)) throw new Error("モデル名が不正です");
  const { prompt, keys } = PROMPTS[kind];
  let bytes = 0;
  const parts = [{ text: prompt }];
  for (const b of images) {
    bytes += b.length;
    if (bytes > 12 * 1024 * 1024) throw new Error("画像データが大きすぎます。ページを分けてください");
    parts.push({ inlineData: { mimeType: mime, data: b.toString("base64") } });
  }
  if (note) parts.push({ text: note });
  const schema = { type: "object", properties: Object.fromEntries(keys.map((k) => [k, { type: "string" }])), required: keys };
  const config = { temperature: 0.1 };
  if (format === "modern") config.responseFormat = { text: { mimeType: "application/json", schema } };
  else Object.assign(config, { responseMimeType: "application/json", responseSchema: schema });
  const r = await request(key, `models/${model}:generateContent`, { contents: [{ role: "user", parts }], generationConfig: config }, fetchImpl);
  const cand = (r.candidates || [])[0];
  if (!cand) throw new Error("認識結果がありません。画像やAPI設定を確認してください");
  if ((cand.finishReason || "STOP") !== "STOP") throw new Error("認識が完了しませんでした: " + cand.finishReason);
  let t = (cand.content.parts || []).filter((x) => !x.thought).map((x) => x.text || "").join("").trim();
  if (t.startsWith("```")) t = t.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
  const out = JSON.parse(t);
  for (const k of keys) if (typeof out[k] !== "string") throw new Error("認識結果の形式が不正です。再試行してください");
  return out;
}

// The organisation's official site via Gemini with Google Search (only when the card has neither a
// web address nor a company e-mail). Returns a URL or "".
async function officialSite({ key, model }, p, fetchImpl) {
  if (!/^[A-Za-z0-9._-]+$/.test(model)) throw new Error("モデル名が不正です");
  const who = [p.company || (p.kind === "shop" ? p.name : ""), p.address].filter(Boolean).join(" / ");
  if (!String(p.company || p.name || "").trim()) return "";
  const prompt =
    "次の会社・団体・お店の公式ウェブサイトのトップページのURLを、Google検索で確認して1つだけ答えてください。" +
    "公式サイトが見つからない、または確信が持てないときは NONE とだけ答えてください。URL以外は書かないでください。\n" + who;
  const r = await request(key, `models/${model}:generateContent`, { contents: [{ role: "user", parts: [{ text: prompt }] }], tools: [{ google_search: {} }] }, fetchImpl);
  const t = ((r.candidates || [])[0]?.content?.parts || []).map((x) => x.text || "").join(" ");
  const m = /https?:\/\/[^\s"'<>)\]]+/.exec(t);
  return m && !/vertexaisearch|google\./.test(m[0]) ? m[0] : "";
}

module.exports = { models, generate, officialSite };
