// Series settings (series.js); SeriesSettingsTest.java runs the same cases on Android.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const S = require("../lib/series");

const T1 = "2026-10-22T01:00:00.000Z", T2 = "2026-10-22T02:00:00.000Z", T3 = "2026-10-22T03:00:00.000Z";

test("the first app publishes its settings to the series (no file yet)", () => {
  const r = S.sync(S.initial(), null, { "gemini.model": "gemini-2.5-flash", "google.account": "a@x.jp" }, "meishi", T1);
  const doc = JSON.parse(r.write);
  assert.deepStrictEqual(doc.series["gemini.model"], { value: "gemini-2.5-flash", by: "meishi", at: T1 });
  assert.strictEqual(r.local.decided, true);
  assert.strictEqual(r.local.follow["gemini.model"], true);
  assert.deepStrictEqual(r.offers, []);
  // Nothing changed: nothing to write.
  assert.strictEqual(S.sync(r.local, r.write, { "gemini.model": "gemini-2.5-flash", "google.account": "a@x.jp" }, "meishi", T2).write, null);
});

test("a second app is offered the values once; chosen ones follow, the rest stay its own", () => {
  const first = S.sync(S.initial(), null, { "gemini.model": "gemini-2.5-pro", "google.account": "a@x.jp", "calendar": { account: "a@x.jp", name: "仕事" } }, "meishi", T1);
  const r = S.sync(S.initial(), first.write, { "gemini.model": "gemini-2.5-flash" }, "recipe", T2);
  assert.deepStrictEqual(r.offers.map((o) => o.key).sort(), ["calendar", "gemini.model", "google.account"]);
  assert.deepStrictEqual(r.apply, {}, "nothing applied before the choice");
  const local = S.adopt(r.local, r.offers, ["gemini.model", "google.account"]);
  const r2 = S.sync(local, first.write, { "gemini.model": "gemini-2.5-flash" }, "recipe", T3);
  assert.deepStrictEqual(r2.apply, { "gemini.model": "gemini-2.5-pro", "google.account": "a@x.jp" });
  assert.deepStrictEqual(r2.offers, [], "asked only once");
  assert.strictEqual(r2.local.follow.calendar, false);
});

test("a change in a following app reaches the others; 'this app only' items do not", () => {
  const a = S.sync(S.initial(), null, { "gemini.model": "m1" }, "meishi", T1);
  let b = S.sync(S.initial(), a.write, { "gemini.model": "m1" }, "recipe", T1);
  assert.strictEqual(b.local.decided, true, "same value: nothing to ask");
  let local = S.changed(b.local, "gemini.model", "m2", "recipe", T2);
  const out = S.sync(local, a.write, { "gemini.model": "m2" }, "recipe", T2);
  assert.strictEqual(JSON.parse(out.write).series["gemini.model"].value, "m2");
  const back = S.sync(a.local, out.write, { "gemini.model": "m1" }, "meishi", T3);
  assert.deepStrictEqual(back.apply, { "gemini.model": "m2" });
  // Meishi stops following: its own value stays, the series is untouched.
  const own = S.changed(S.setFollow(back.local, "gemini.model", false), "gemini.model", "m9", "meishi", T3);
  const r = S.sync(own, out.write, { "gemini.model": "m9" }, "meishi", T3);
  assert.strictEqual(r.write, null);
  assert.deepStrictEqual(r.apply, {});
});

test("newer edit wins per item; secrets, unknown sections and broken files are respected", () => {
  const text = JSON.stringify({
    spec: 1,
    series: { "gemini.model": { value: "old", by: "x", at: T1 }, "gemini.key": { value: "AIza-secret", by: "x", at: T1 } },
    apps: { recipe: { autoRead: true } },
    future: { keep: 1 },
  });
  const local = S.changed(S.adopt(S.initial(), [], []), "gemini.model", "new", "meishi", T2);
  const r = S.sync(local, text, { "gemini.model": "new" }, "meishi", T2);
  const doc = JSON.parse(r.write);
  assert.strictEqual(doc.series["gemini.model"].value, "new");
  assert.ok(!("gemini.key" in doc.series), "secrets are dropped");
  assert.deepStrictEqual(doc.apps.recipe, { autoRead: true });
  assert.deepStrictEqual(doc.future, { keep: 1 });
  const broken = S.sync(local, "{ not json", { "gemini.model": "new" }, "meishi", T2);
  assert.strictEqual(broken.write, null, "a file someone broke by hand is not overwritten");
  assert.ok(broken.broken);
});

test("a change before the first carry-over is decided is not published over the other apps", () => {
  const first = S.sync(S.initial(), null, { "gemini.model": "m1" }, "meishi", T1);
  const local = S.changed(S.initial(), "gemini.model", "m2", "recipe", T2);
  const r = S.sync(local, first.write, { "gemini.model": "m2" }, "recipe", T2);
  assert.deepStrictEqual(r.offers.map((o) => o.key), ["gemini.model"]);
  assert.strictEqual(r.write, null);
});
