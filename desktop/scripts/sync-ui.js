// Single source of truth stays in android/: copy the shared UI, and lift Gemini's prompts and
// result keys out of Gemini.java so the desktop reads cards exactly like the phone.
const fs = require("fs"), path = require("path");
const from = path.resolve(__dirname, "../../android/app/src/main/assets");
const to = path.resolve(__dirname, "../ui");
fs.rmSync(to, { recursive: true, force: true });
fs.cpSync(from, to, { recursive: true });

const java = fs.readFileSync(path.resolve(__dirname, "../../android/app/src/main/java/jp/yaegaki/meishinote/Gemini.java"), "utf8");
const literal = (name) => {
  const m = new RegExp(`static final String ${name}\\s*=([\\s\\S]*?);\\n`).exec(java);
  if (!m) throw new Error("missing " + name);
  return [...m[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => JSON.parse('"' + x[1] + '"')).join("");
};
const keys = (name) => {
  const m = new RegExp(`static final String\\[\\] ${name}\\s*=\\s*\\{([\\s\\S]*?)\\};`).exec(java);
  if (!m) throw new Error("missing " + name);
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
};
const prompts = {
  card: { prompt: literal("CARD_PROMPT"), keys: keys("CARD_KEYS") },
  nenga: { prompt: literal("NENGA_PROMPT"), keys: keys("NENGA_KEYS") },
};
fs.writeFileSync(path.resolve(__dirname, "../lib/prompts.json"), JSON.stringify(prompts, null, 2));
console.log("UI and prompts synced from android/");
