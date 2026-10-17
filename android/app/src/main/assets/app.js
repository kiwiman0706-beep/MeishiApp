"use strict";
const $ = (id) => document.getElementById(id),
  esc = (x) =>
    String(x ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
const paths = {
  card: "M3 5h18v14H3z M7 9h4v4H7z M14 9h4 M14 12h4 M7 16h11",
  search: "M10 3a7 7 0 1 0 0 14a7 7 0 0 0 0-14 M15 15l6 6",
  plus: "M12 4v16 M4 12h16",
  scan: "M3 8V3h5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M7 7h10v10H7z",
  camera: "M3 7h4l2-3h6l2 3h4v13H3z M12 9a4 4 0 1 0 0 8a4 4 0 0 0 0-8",
  folder: "M3 6h7l2 2h9v12H3z M3 6V4h7l2 2",
  image: "M3 3h18v18H3z M3 17l6-6 4 4 3-3 5 5 M15 7h.1",
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  list: "M8 6h13 M8 12h13 M8 18h13 M3 6h.1 M3 12h.1 M3 18h.1",
  table: "M3 4h18v16H3z M3 9h18 M3 14h18 M10 4v16",
  settings:
    "M12 3l2 3 4-1 1 4 3 3-3 2 1 4-4 1-4 3-2-3-4 1-1-4-3-3 3-2-1-4 4-1z M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6",
  back: "M15 5l-7 7 7 7",
  next: "M9 5l7 7-7 7",
  down: "M5 9l7 7 7-7",
  edit: "M4 16l12-12 4 4L8 20H4z M13 7l4 4",
  check: "M4 12l5 5L20 6",
  trash: "M3 6h18 M8 6V3h8v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7",
  rotate: "M20 8A8 8 0 1 0 20 16 M20 3v5h-5",
  upload: "M12 16V3 M7 8l5-5 5 5 M4 14v7h16v-7",
  download: "M12 3v13 M7 11l5 5 5-5 M4 17v4h16v-4",
  mic: "M9 4a3 3 0 0 1 6 0v8a3 3 0 0 1-6 0z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8",
  stop: "M6 6h12v12H6z",
  play: "M7 4l14 8-14 8z",
  spark: "M12 2l3 7 7 3-7 3-3 7-3-7-7-3 7-3z",
  user: "M12 3a4 4 0 1 0 0 8a4 4 0 0 0 0-8 M4 21v-3a8 5 0 0 1 16 0v3",
  cloud:
    "M6 18a5 5 0 0 1-1-10 7 7 0 0 1 14-1 5 5 0 0 1-1 11 M12 20V10 M8 14l4-4 4 4",
  link: "M10 7l3-3a5 5 0 0 1 7 7l-3 3 M14 17l-3 3a5 5 0 0 1-7-7l3-3 M8 16l8-8",
  close: "M5 5l14 14 M19 5L5 19",
  share: "M12 16V3 M7 8l5-5 5 5 M5 12v9h14v-9",
  clock: "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18 M12 7v5l3 2",
  more: "M5 12h.1 M12 12h.1 M19 12h.1",
  menu: "M4 6h16 M4 12h16 M4 18h16",
  phone:
    "M5 3h4l2 5-3 2a11 11 0 0 0 6 6l2-3 5 2v4a2 2 0 0 1-2 2A17 17 0 0 1 3 5a2 2 0 0 1 2-2",
  mail: "M3 5h18v14H3z M3 6l9 7 9-7",
  map: "M12 21s-7-6-7-12a7 7 0 0 1 14 0c0 6-7 12-7 12 M12 7a2 2 0 1 0 0 4a2 2 0 0 0 0-4",
  globe:
    "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18 M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18",
};
const icon = (k) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[k] || paths.card}"/></svg>`;
const fields = [
  ["name", "氏名"],
  ["kana", "ふりがな"],
  ["company", "会社・組織"],
  ["department", "部署"],
  ["title", "肩書き"],
  ["email", "メール"],
  ["phone", "電話"],
  ["mobile", "携帯電話"],
  ["fax", "FAX"],
  ["postal", "郵便番号"],
  ["address", "住所"],
  ["website", "Webサイト"],
  ["tags", "タグ"],
  ["metDate", "名刺交換日"],
  ["metPlace", "交換場所"],
  ["genre", "ジャンル・業種"],
  ["hours", "営業時間"],
  ["holidays", "定休日"],
];
let state = { people: [], model: "gemini-2.5-flash", format: "legacy" },
  route = "home",
  selected = "",
  query = "",
  filter = "all",
  sort = "newest",
  layout = "cards",
  mode = "file",
  draft = null,
  noteDraft = "",
  editNoteId = "",
  pendingOpen = "",
  pendingEdit = "",
  modalOpen = false,
  settingsDraft = null;
const call = (action, params = {}) => {
  if (window.Native) Native.call(JSON.stringify({ action, ...params }));
  else toast("Android版で利用できます");
};
const getPerson = () => state.people.find((p) => p.id === selected),
  title = (p) => p.name || p.suggestion?.name || "氏名未登録",
  company = (p) =>
    p.company || p.suggestion?.company || (p.kind === "shop" ? p.genre || "お店" : "会社・組織 未登録"),
  shopOnly = ["genre", "hours", "holidays"],
  visible = (p) => p.kind !== "nengaInbox",
  img = (f) => "/media/" + encodeURIComponent(f),
  status = (p) =>
    ({ new: "未認識", review: "要確認", confirmed: "確認済み" })[p.status] ||
    "未認識";
function toast(t) {
  $("toast").textContent = t;
  $("toast").style.display = "block";
  clearTimeout(window.toastTimer);
  window.toastTimer = setTimeout(
    () => ($("toast").style.display = "none"),
    4200,
  );
}
function modal(html, viewer = false) {
  modalOpen = true;
  $("modal").innerHTML =
    `<div class="sheetback ${viewer ? "viewer" : ""}" onclick="if(event.target===this)closeModal()"><div class="sheet"><button class="close" onclick="closeModal()" aria-label="閉じる">${icon("close")}</button>${html}</div></div>`;
}
function closeModal() {
  modalOpen = false;
  $("modal").innerHTML = "";
}
function report(t) {
  modal(
    `<h2>お知らせ</h2><p class="pre">${esc(t)}</p><button class="primary full gap" onclick="closeModal()">OK</button>`,
  );
}
// ---- Navigation -------------------------------------------------------------
// Three destinations only: the list, the shutter, and a menu that holds everything else.
function go(r) {
  if (
    route === "edit" &&
    draft &&
    !confirm("未保存の編集を破棄して移動しますか？")
  )
    return;
  draft = null;
  noteDraft = "";
  editNoteId = "";
  settingsDraft = null;
  route = r;
  closeModal();
  render();
  window.scrollTo(0, 0);
}
function openPerson(id) {
  selected = id;
  draft = null;
  noteDraft = "";
  editNoteId = "";
  closeModal();
  // A pending Gemini result has only one sensible next step: review it.
  if (getPerson()?.suggestion) return beginEdit();
  route = "detail";
  render();
  window.scrollTo(0, 0);
}
function shutter() {
  if (route === "edit" && draft && !confirm("未保存の編集を破棄しますか？"))
    return;
  draft = null;
  call("quickScan");
}
function nav() {
  const onList = ["home", "detail", "edit"].includes(route);
  const onMenu = ["menu", "gemini", "nenga"].includes(route);
  return `<nav class="nav"><button class="${onList ? "active" : ""}" onclick="go('home')">${icon("card")}名刺</button><button class="shutter" onclick="shutter()" oncontextmenu="event.preventDefault();bulkSheet()" aria-label="名刺を撮影">${icon("camera")}<span>撮影</span></button><button class="${onMenu ? "active" : ""}" onclick="go('menu')">${icon("menu")}メニュー</button></nav>`;
}
function render() {
  const page =
    route === "home"
      ? home()
      : route === "menu"
        ? menuPage()
        : route === "gemini"
          ? geminiPage()
          : route === "nenga"
            ? nengaPage()
          : route === "edit"
            ? editPage()
            : detail();
  $("app").innerHTML = `<main class="shell">${page}</main>${nav()}`;
  afterRender();
}
function backbar(text, right = "") {
  return `<div class="backbar"><button onclick="handleBack()" aria-label="戻る">${icon("back")}</button><h2>${text}</h2>${right}</div>`;
}
function recordBanner() {
  return state.recording
    ? `<div class="recording"><span><i class="pulse"></i>録音中 <span id="recordtimer"></span></span><button onclick="call('stopRecord')">${icon("stop")} 保存して停止</button></div>`
    : "";
}

// ---- List -------------------------------------------------------------------
function filtered() {
  const q = query.toLocaleLowerCase();
  return state.people
    .filter(
      (p) =>
        visible(p) &&
        (filter === "all" || (filter === "shop" ? p.kind === "shop" : p.status === filter)) &&
        (!q ||
          [
            ...fields.map(([k]) => p[k]),
            p.suggestion?.name,
            p.suggestion?.company,
            ...(p.notes || []).map((n) => n.text),
          ]
            .join(" ")
            .toLocaleLowerCase()
            .includes(q)),
    )
    .sort((a, b) =>
      sort === "name"
        ? title(a).localeCompare(title(b), "ja")
        : sort === "company"
          ? company(a).localeCompare(company(b), "ja")
          : sort === "updated"
            ? b.updated - a.updated
            : b.created - a.created,
    );
}
function listContent() {
  let a = filtered();
  if (!a.length)
    return state.people.length
      ? `<div class="empty"><h2>該当する名刺がありません</h2><p>検索条件を変えてみてください。</p></div>`
      : `<div class="empty"><div class="emptyart"><div class="miniCard back"></div><div class="miniCard"><i></i><i></i><i></i></div><div class="spark">${icon("camera")}</div></div><h2>はじめての1枚を、ここへ。</h2><p>下の「撮影」を押して名刺を映すだけ。<br>表・裏を撮って完了すると、自動で読み取ります。</p></div>`;
  if (layout === "calendar") return calendarView(a);
  if (layout === "map") return mapView(a);
  if (layout === "table")
    return `<div class="tablewrap"><table><thead><tr><th>氏名</th><th>会社・組織</th><th>部署・肩書き</th><th>電話</th><th>メール</th><th>状態</th></tr></thead><tbody>${a.map((p) => `<tr tabindex="0" role="button" onclick="openPerson('${p.id}')"><td>${esc(title(p))}</td><td>${esc(company(p))}</td><td>${esc([p.department, p.title].filter(Boolean).join(" / "))}</td><td>${esc(p.mobile || p.phone)}</td><td>${esc(p.email)}</td><td>${status(p)}</td></tr>`).join("")}</tbody></table></div><p class="caption">表は横にスクロールできます。CSVはメニューから書き出せます。</p>`;
  return `<div class="cardlist">${a.map((p) => `<button class="person" onclick="openPerson('${p.id}')">${p.pages?.length ? `<img loading="lazy" class="thumb" src="${img(p.pages[0].file)}" alt="名刺">` : `<div class="initial">${esc(title(p).slice(0, 1))}</div>`}<div class="personbody"><h3 class="ell">${esc(title(p))}</h3><div class="company ell">${esc(company(p))}</div><div class="personmeta">${p.kind === "shop" ? `<span class="badge shop">お店</span>` : ""}${p.queue ? `<span class="badge new">処理中</span>` : p.status === "confirmed" ? "" : `<span class="badge ${esc(p.status)}">${status(p)}</span>`}${p.contactRawId ? `<span class="badge confirmed">連絡先登録済み</span>` : ""}${p.notes?.length ? `<span>メモ ${p.notes.length}</span>` : ""}${p.audio?.length ? `<span>録音 ${p.audio.length}</span>` : ""}</div></div><span class="chevron">${icon("next")}</span></button>`).join("")}</div>`;
}
function home() {
  const count = (s) => state.people.filter((p) => p.status === s).length;
  const setup = !state.keySet
    ? `<button class="setup" onclick="go('gemini')">${icon("spark")}<span><b>Gemini APIキーを設定</b><small>設定すると、撮影後に自動で名刺を読み取ります</small></span>${icon("next")}</button>`
    : "";
  const chips = [
    ["all", "すべて", state.people.length],
    ["review", "要確認", count("review")],
    ["new", "未認識", count("new")],
    ["confirmed", "確認済み", count("confirmed")],
    ["shop", "お店", state.people.filter((p) => p.kind === "shop").length],
  ]
    .filter(([v, , n]) => v === "all" || n || filter === v)
    .map(
      ([v, l, n]) =>
        `<button class="chip ${filter === v ? "active" : ""} ${v === "review" ? "attn" : ""}" onclick="filter='${v}';render()">${l} ${n}</button>`,
    )
    .join("");
  const queued = state.people.filter((p) => p.queue).length;
  const busyNow = state.processing || queued
    ? `<div class="procbar"><i class="spin"></i><span>${esc(state.processing || "処理待ち")}${queued ? `<small>残り ${queued}件 ・ 撮影はそのまま続けられます</small>` : ""}</span></div>`
    : "";
  const dupCount = dupes().length;
  const dupBanner = dupCount
    ? `<button class="setup" onclick="dupSheet()">${icon("link")}<span><b>重複の可能性 ${dupCount}件</b><small>統合・削除するか、別人として扱えます</small></span>${icon("next")}</button>`
    : "";
  const month = new Date().getMonth();
  const replyNeeded = state.people.filter((p) => got(p, season(), "received").length && !got(p, season(), "sent").length).length;
  const nengaBanner =
    month >= 10 || month === 0
      ? `<button class="setup" onclick="go('nenga')">${icon("mail")}<span><b>${season()}年の年賀状</b><small>${replyNeeded ? `返礼が必要 ${replyNeeded}人` : "届いた年賀状を撮影して記録"}</small></span>${icon("next")}</button>`
      : "";
  const drive = state.driveFolderName
    ? `<button class="setup drive" onclick="call('driveImport')">${icon("cloud")}<span><b>Google Driveから取り込む</b><small>${esc(state.driveFolderName)} の新しい名刺</small></span>${icon("next")}</button>`
    : "";
  return `<header class="top"><div class="brand"><div class="mark">${icon("card")}</div><div><div class="brandline">THETA</div><h1>Meishi Hatch</h1></div></div></header>${recordBanner()}${busyNow}${setup}${dupBanner}${nengaBanner}${drive}<div class="toolbar"><div class="search">${icon("search")}<input id="search" placeholder="氏名・会社・メモを検索" value="${esc(query)}" oninput="query=this.value;updateList()"></div></div>${state.people.length ? `<div class="views" role="tablist">${[
    ["cards", "リスト", "list"],
    ["table", "表", "table"],
    ["calendar", "カレンダー", "clock"],
    ["map", "地図", "map"],
  ]
    .map(
      ([v, l, ic]) =>
        `<button role="tab" aria-selected="${layout === v}" class="${layout === v ? "active" : ""}" onclick="setLayout('${v}')">${icon(ic)}${l}</button>`,
    )
    .join("")}</div>` : ""}${state.people.length ? `<div class="chips">${chips}</div><div class="sectionline"><small id="count">${filtered().length} 件</small><select aria-label="並べ替え" onchange="sort=this.value;updateList()">${[
    ["newest", "追加日が新しい順"],
    ["updated", "更新日が新しい順"],
    ["name", "氏名順"],
    ["company", "会社順"],
  ]
    .map(
      ([v, l]) =>
        `<option value="${v}" ${sort === v ? "selected" : ""}>${l}</option>`,
    )
    .join("")}</select></div>` : ""}<div id="results">${listContent()}</div>`;
}
function updateList() {
  $("results").innerHTML = listContent();
  if ($("count")) $("count").textContent = filtered().length + " 件";
  afterRender();
}
function setLayout(v) {
  layout = v;
  if (v === "map") call("mapOpen");
  render();
}
function afterRender() {
  if (route === "home" && layout === "map" && $("mapslot")) mountMap();
}

// ---- Calendar ------------------------------------------------------------------
// Month grid of exchange dates; tapping a day lists who you met.
let calMonth = "",
  calDay = "";
function calendarView(list) {
  const dated = list.filter((p) => /^\d{4}-\d{2}-\d{2}$/.test(p.metDate || ""));
  const byDay = {};
  for (const p of dated) (byDay[p.metDate] = byDay[p.metDate] || []).push(p);
  const months = {};
  for (const d of Object.keys(byDay)) months[d.slice(0, 7)] = (months[d.slice(0, 7)] || 0) + byDay[d].length;
  const keys = Object.keys(months).sort();
  if (!calMonth) calMonth = keys.at(-1) || new Date().toISOString().slice(0, 7);
  const [y, m] = calMonth.split("-").map(Number);
  const first = new Date(y, m - 1, 1).getDay(),
    days = new Date(y, m, 0).getDate();
  const cells = [];
  for (let i = 0; i < first; i++) cells.push(`<span></span>`);
  for (let d = 1; d <= days; d++) {
    const key = `${calMonth}-${String(d).padStart(2, "0")}`;
    const n = byDay[key]?.length || 0;
    cells.push(
      `<button class="${n ? "has" : ""} ${calDay === key ? "sel" : ""}" ${n ? `onclick="calDay='${key}';updateList()"` : "disabled"}>${d}${n ? `<i>${n}</i>` : ""}</button>`,
    );
  }
  const shift = (k) => {
    const i = keys.indexOf(calMonth);
    return k < 0 ? keys.filter((x) => x < calMonth).at(-1) : keys.find((x) => x > calMonth);
  };
  const prev = shift(-1),
    next = shift(1);
  const picked = calDay.startsWith(calMonth) ? byDay[calDay] || [] : [];
  const pending = state.people.filter((p) => /^\d{4}-\d{2}-\d{2}$/.test(p.metDate || "") && !p.calendarRegistered && !p.calendarEventId).length;
  return `<div class="cal"><div class="calhead"><button class="iconbtn" ${prev ? `onclick="calMonth='${prev}';calDay='';updateList()"` : "disabled"} aria-label="前の月">${icon("back")}</button><select aria-label="月" onchange="calMonth=this.value;calDay='';updateList()">${(keys.includes(calMonth) ? keys : [...keys, calMonth].sort())
    .map((k) => `<option value="${k}" ${k === calMonth ? "selected" : ""}>${k.slice(0, 4)}年${Number(k.slice(5))}月${months[k] ? `（${months[k]}人）` : ""}</option>`)
    .join("")}</select><button class="iconbtn" ${next ? `onclick="calMonth='${next}';calDay='';updateList()"` : "disabled"} aria-label="次の月">${icon("next")}</button></div><div class="calgrid">${["日", "月", "火", "水", "木", "金", "土"].map((w) => `<b>${w}</b>`).join("")}${cells.join("")}</div></div>${
    picked.length
      ? `<p class="caption">${esc(calDay.replace(/-/g, "/"))} に名刺交換した人（${picked.length}人）</p><div class="cardlist">${picked.map((p) => `<button class="person compact" onclick="openPerson('${p.id}')"><div class="personbody"><h3 class="ell">${esc(title(p))}</h3><div class="company ell">${esc(company(p))}${p.metPlace ? " ・ " + esc(p.metPlace) : ""}</div></div><span class="chevron">${icon("next")}</span></button>`).join("")}</div>`
      : `<p class="caption center">色の付いた日を押すと、その日に会った人を表示します。名刺交換日のない名刺 ${list.length - dated.length}件は表示されません。</p>`
  }<button class="${pending ? "secondary" : "outline"} full gap" onclick="calendarRegister(${pending})">${icon("clock")}${pending ? `Googleカレンダーに登録（未登録 ${pending}件）` : "Googleカレンダーの設定"}</button>`;
}
function calendarRegister(pending) {
  if (!pending) return calendarSettings();
  if (confirm(`名刺交換日を${pending}件、Googleカレンダーに終日の予定「名刺交換：氏名（会社）」として登録します。続けますか？`))
    call("calendarRegister");
}
function calendarSettings() {
  call("calendarList");
}
function calendarSheet(data) {
  modal(
    `<h2>${icon("clock")} Googleカレンダー</h2><label class="mrow"><span class="mi">${icon("clock")}</span><span class="mt"><b>名刺交換を自動で登録</b><small>新しい名刺の交換日をカレンダーに追加</small></span><input type="checkbox" class="switch" ${state.autoCalendar ? "checked" : ""} onchange="call('settings',{autoCalendar:this.checked})"></label><label class="label">登録先のカレンダー</label><select onchange="call('calendarSelect',{calendarId:Number(this.value)})">${data.list
      .map((k) => `<option value="${k.id}" ${k.id === data.selected ? "selected" : ""}>${esc(k.name)}（${esc(k.account)}）</option>`)
      .join("") || "<option>Googleカレンダーが見つかりません</option>"}</select><p class="caption">予定は終日・「予定なし」扱いで登録します。名刺の交換日や氏名を直すと予定も更新し、名刺を削除すると予定も削除します。</p>`,
  );
}

// ---- Map -----------------------------------------------------------------------
// The Leaflet map lives in one element reused across renders, so background updates
// (which re-render the page) don't reset the user's pan and zoom.
let mapEl = null,
  leaflet = null,
  cluster = null,
  mapSig = "",
  mapLoading = false;
function mapView(list) {
  const withAddr = list.filter((p) => (p.address || "").trim());
  const placed = withAddr.filter((p) => p.geo && p.geo.lat != null && p.geo.q === p.address.replace(/\n/g, " ").trim());
  const waiting = withAddr.filter((p) => !p.geo || p.geo.q !== p.address.replace(/\n/g, " ").trim()).length;
  return `<div id="mapslot" class="mapslot"></div><p class="caption">地図に表示 ${placed.length}人${waiting ? ` ・ 住所を変換中 ${waiting}人` : ""}${withAddr.length - placed.length - waiting ? ` ・ 場所が見つからない住所 ${withAddr.length - placed.length - waiting}人` : ""} ・ 住所なし ${list.length - withAddr.length}人</p><p class="caption">地図 © OpenStreetMap contributors。住所は端末の位置変換機能（Google）で緯度経度にします。</p>`;
}
function loadScript(src) {
  return new Promise((ok, ng) => {
    const s = document.createElement("script");
    s.src = src;
    s.onload = ok;
    s.onerror = ng;
    document.head.appendChild(s);
  });
}
function mountMap() {
  if (!window.L) {
    if (mapLoading) return;
    mapLoading = true;
    for (const href of ["/lib/leaflet.css", "/lib/MarkerCluster.css", "/lib/MarkerCluster.Default.css"]) {
      const l = document.createElement("link");
      l.rel = "stylesheet";
      l.href = href;
      document.head.appendChild(l);
    }
    loadScript("/lib/leaflet.js")
      .then(() => loadScript("/lib/leaflet.markercluster.js"))
      .then(() => {
        mapLoading = false;
        afterRender();
      })
      .catch(() => {
        mapLoading = false;
        toast("地図を読み込めませんでした");
      });
    return;
  }
  if (!mapEl) {
    mapEl = document.createElement("div");
    mapEl.className = "map";
    $("mapslot").appendChild(mapEl);
    leaflet = L.map(mapEl, { zoomControl: true }).setView([35.0, 135.5], 5);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap contributors",
    }).addTo(leaflet);
    cluster = L.markerClusterGroup({ showCoverageOnHover: false, maxClusterRadius: 45 });
    leaflet.addLayer(cluster);
  } else $("mapslot").appendChild(mapEl);
  leaflet.invalidateSize();
  const pts = filtered().filter((p) => p.geo && p.geo.lat != null);
  const sig = pts.map((p) => p.id + p.geo.lat + p.geo.lng + title(p) + company(p)).join("|");
  if (sig === mapSig) return;
  const first = !mapSig;
  mapSig = sig;
  cluster.clearLayers();
  for (const p of pts) {
    const m = L.circleMarker([p.geo.lat, p.geo.lng], { radius: 8, color: "#fff", weight: 2, fillColor: p.kind === "shop" ? "#c8872e" : "#176d61", fillOpacity: 0.9 });
    m.bindPopup(
      `<b>${esc(title(p))}</b><br>${esc(company(p))}${p.metDate ? `<br><small>${esc(p.metDate.replace(/-/g, "/"))}</small>` : ""}<br><button class="link" onclick="openPerson('${p.id}')">名刺を開く</button>`,
    );
    cluster.addLayer(m);
  }
  if (first && pts.length) leaflet.fitBounds(L.latLngBounds(pts.map((p) => [p.geo.lat, p.geo.lng])), { padding: [24, 24], maxZoom: 14 });
}

// ---- Detail -----------------------------------------------------------------
let links = [];
function link(url) {
  links.push(url);
  return links.length - 1;
}
function infoRow(k, l, v) {
  const parts = String(v).split(/\n/).filter((x) => x.trim());
  const act = (x) => {
    const t = x.trim();
    if (["phone", "mobile"].includes(k))
      return [link("tel:" + t.replace(/[^0-9+]/g, "")), "phone"];
    if (k === "email") return [link("mailto:" + t), "mail"];
    if (k === "website")
      return [link(/^https?:\/\//i.test(t) ? t : "https://" + t), "globe"];
    if (k === "address")
      return [
        link(
          "https://www.google.com/maps/search/?api=1&query=" +
            encodeURIComponent(t),
        ),
        "map",
      ];
    return null;
  };
  return `<div class="inforow"><span class="k">${l}</span><span class="v">${parts
    .map((x) => {
      const a = act(x);
      return a
        ? `<button class="vlink" onclick="openLink(${a[0]})">${icon(a[1])}<span>${esc(x)}</span></button>`
        : `<span>${esc(x)}</span>`;
    })
    .join("")}</span></div>`;
}
function openLink(i) {
  call("openUrl", { url: links[i] });
}
function primaryAction(p) {
  if (p.queue)
    return `<button class="outline full" disabled><i class="spin"></i>${p.queue === "contact" ? "連絡先に登録中…" : "読み取り中…"}</button>`;
  if (p.suggestion)
    return `<button class="primary full" onclick="beginEdit()">${icon("check")}読み取り結果を確認して保存</button>`;
  if (!p.pages.length && p.status !== "confirmed")
    return `<button class="primary full" onclick="scanAdd()">${icon("camera")}名刺を撮影して追加</button>`;
  if (p.status === "new")
    return `<button class="primary full" onclick="recognize()">${icon("spark")}Geminiで読み取る</button>`;
  if (p.status === "review")
    return `<button class="primary full" onclick="beginEdit()">${icon("check")}内容を確認して保存</button>`;
  if (!p.contactRawId && p.contactRegistered)
    return `<p class="caption center">別の端末でGoogle連絡先に登録済みです</p>`;
  if (!p.contactRawId)
    return `<button class="primary full" onclick="registerContact()">${icon("user")}Google連絡先に登録</button>`;
  return `<button class="outline full" onclick="call('viewContact',{id:selected})">${icon("user")}登録した連絡先を開く</button>`;
}
function detail() {
  const p = getPerson();
  if (!p) {
    route = "home";
    return home();
  }
  links = [];
  const info = fields.filter(([k]) => p[k] && !["name", "kana"].includes(k));
  const nengaHtml = greetingsSection(p);
  const label = (i) => (i === 0 ? "表面" : i === 1 ? "裏面" : "追加 " + (i + 1));
  return `${backbar("", `<button onclick="beginEdit()" aria-label="編集">${icon("edit")}</button><button onclick="detailMenu()" aria-label="その他の操作">${icon("more")}</button>`)}${recordBanner()}${
    p.pages.length
      ? `<div class="pages">${p.pages.map((pg, i) => `<button class="page" onclick="viewImage(${i})"><img class="pageimg" src="${img(pg.file)}" alt="名刺 ${label(i)}"><span class="pageLabel">${label(i)}</span></button>`).join("")}</div>`
      : ""
  }<div class="detailsHead">${p.portrait ? `<img class="avatar" src="${img(p.portrait)}" alt="人物写真">` : p.logo ? `<img class="avatar logo" src="${img(p.logo)}" alt="会社ロゴ">` : `<div class="avatar">${esc(title(p).slice(0, 1))}</div>`}<div class="content"><small>${esc(p.kana)}</small><h1>${esc(title(p))}</h1><p class="muted">${esc(company(p))}${p.title ? " ・ " + esc(p.title) : ""}</p>${p.kind === "shop" ? `<span class="badge shop">お店</span>` : ""}${p.status === "confirmed" ? "" : `<span class="badge ${esc(p.status)}">${status(p)}</span>`}${p.contactRawId ? `<span class="badge confirmed">連絡先登録済み</span>` : ""}</div></div><div class="gap">${primaryAction(p)}</div>${p.warnings ? `<div class="tip warning gap">読み取り時の注意：${esc(p.warnings)}</div>` : ""}${p.queueError ? `<div class="tip warning gap">処理できませんでした：${esc(p.queueError)}</div>` : ""}${p.contactDuplicate ? `<div class="tip warning gap">Google連絡先に「${esc(p.contactDuplicate)}」が既にあります（同じ電話・メール）。<div class="row gap"><button class="outline" onclick="unlinkContact('${p.id}')">追加した方を削除</button><button class="noflex" onclick="call('dismissContactDup',{id:'${p.id}'})">問題なし</button></div></div>` : ""}<div class="detailcols"><div><section class="panel"><h3>${p.kind === "shop" ? "お店の情報" : "連絡先情報"}</h3><div class="infogrid">${info.map(([k, l]) => infoRow(k, l, p[k])).join("") || '<p class="caption">まだ情報がありません。読み取るか、右上の編集から入力してください。</p>'}</div></section>${nengaHtml}</div><div><section class="panel"><h3>やり取りのメモ <small>${p.notes.length}件</small></h3><textarea id="note" placeholder="お会いした場所、相談内容、次回の約束など" oninput="noteDraft=this.value">${esc(noteDraft)}</textarea><div class="row gap"><button class="secondary" onclick="addNote()">${icon("plus")}${editNoteId ? "変更を保存" : "メモを追加"}</button>${editNoteId ? `<button class="noflex" onclick="noteDraft='';editNoteId='';render()">キャンセル</button>` : ""}<button class="${state.recording ? "danger" : "outline"} noflex" onclick="${state.recording ? "call('stopRecord')" : "call('record',{id:selected})"}" aria-label="${state.recording ? "録音を停止" : "録音を開始"}">${icon(state.recording ? "stop" : "mic")}${state.recording ? "停止" : "録音"}</button></div>${timeline(p)}</section></div></div><p class="footnote">録音は相手の了承を得て行ってください。端末に保存され、Geminiへは送信しません。</p>`;
}
// Notes and recordings share one timeline so the page has a single "history" section.
function timeline(p) {
  const items = [
    ...p.notes.map((n, i) => ({ t: n.time, html: `<div class="note"><div class="notehead"><small>${esc(date(n.time))}</small><div><button onclick="editNote(${i})" aria-label="メモ編集">${icon("edit")}</button><button onclick="removeItem('notes',${i})" aria-label="メモ削除">${icon("trash")}</button></div></div><p class="notebody">${esc(n.text)}</p></div>` })),
    ...p.audio.map((a, i) => ({ t: a.time, html: `<div class="note"><div class="notehead"><div><small>${esc(date(a.time))}</small><div>${icon("mic")} ${a.state === "recording" ? (state.recording === p.id ? "録音中" : "中断された録音") : `録音 ${Math.floor(a.seconds / 60)}分${a.seconds % 60}秒`}</div></div><div>${a.state !== "recording" || state.recording !== p.id ? `<button onclick="call('play',{id:selected,index:${i}})" aria-label="録音を再生">${icon("play")}</button>` : ""}<button onclick="removeItem('audio',${i})" aria-label="録音を削除">${icon("trash")}</button></div></div></div>` })),
  ];
  return items
    .sort((a, b) => b.t - a.t)
    .map((x) => x.html)
    .join("");
}
function menuRow(ic, label, sub, onclick, cls = "") {
  return `<button class="mrow ${cls}" onclick="${onclick}"><span class="mi">${icon(ic)}</span><span class="mt"><b>${label}</b>${sub ? `<small>${sub}</small>` : ""}</span>${icon("next")}</button>`;
}
function detailMenu() {
  const p = getPerson();
  modal(
    `<h2>${esc(title(p))}</h2><div class="mlist">${[
      menuRow("camera", "スキャンで画像を追加", "裏面や追加ページを撮影", "closeModal();scanAdd()"),
      menuRow("image", "画像・PDFを追加", "", "closeModal();call('pick',{id:selected,mode:'all'})"),
      p.pages.length ? menuRow("spark", "Geminiで読み取り直す", "結果は保存前に確認できます", "closeModal();reread()") : "",
      p.status === "confirmed" && !p.contactRawId ? menuRow("user", "写真を選んで連絡先に登録", "名刺・人物写真・ロゴから選択", "contactSheet()") : "",
      menuRow("download", "書類・手紙を添付", "契約書・案内状・手紙などを撮影・PDFで", "attachSheet()"),
      menuRow("user", "人物写真・会社ロゴ", p.portrait || p.logo ? "登録済み" : "画像検索して登録", "assetSheet()"),
      state.people.length > 1 ? menuRow("link", "別の名刺と統合", "同じ人の名刺をまとめる", "mergeSheet()") : "",
      p.rawText ? menuRow("list", "読み取った全文を見る", "", "report(getPerson().rawText)") : "",
      menuRow("trash", "この名刺を削除", "Google連絡先は削除されません", "closeModal();deletePerson()", "dangerrow"),
    ].join("")}</div>`,
  );
}
function attachSheet() {
  modal(
    `<h2>${icon("download")} 書類・手紙を添付</h2><label class="label">件名（任意）</label><input id="atitle" placeholder="例：見積書、お礼状、案内状"><label class="label">やり取り</label><select id="adir"><option value="received">受け取った</option><option value="sent">送った</option></select><div class="mlist gap">${[
      menuRow("camera", "撮影して添付", "何ページでも続けて撮影", "attachGo('scan')"),
      menuRow("image", "画像・PDFを選んで添付", "PDFは原本も保存します", "attachGo('pick')"),
    ].join("")}</div><p class="caption">添付した書類は詳細画面の「年賀状・お便り・書類」に日付つきで並び、同期・バックアップにも含まれます。</p>`,
  );
}
function attachGo(how) {
  const opts = { id: "", mode: "all", attachTo: selected, title: $("atitle").value.trim(), dir: $("adir").value };
  closeModal();
  call(how, opts);
}
function scanAdd() {
  call("scan", { id: selected, mode: "all" });
}
function assetSheet() {
  const p = getPerson();
  modal(
    `<h2>人物写真・会社ロゴ</h2><p class="caption">「画像検索」でブラウザが開きます。候補を端末に保存してから「画像を選択」で登録してください。本人・会社であることを確認し、提供元の利用条件に従ってください。</p><div class="assetrow">${[
      ["portrait", "人物写真", "user"],
      ["logo", "会社ロゴ", "card"],
    ]
      .map(
        ([k, l, ic]) =>
          `<div class="assetbox"><small>${l}</small>${p[k] ? `<img src="${img(p[k])}" alt="${l}">` : `<div class="placeholder">${icon(ic)}</div>`}<button class="outline" onclick="call('search',{id:selected,kind:'${k}'})">${icon("search")}画像検索</button><button class="secondary" onclick="closeModal();call('asset',{id:selected,kind:'${k}'})">画像を選択</button>${p[k] ? `<button class="danger" onclick="if(confirm('画像の登録を解除しますか？')){closeModal();call('clearAsset',{id:selected,kind:'${k}'})}">解除</button>` : ""}</div>`,
      )
      .join("")}</div>`,
  );
}
function date(t) {
  return new Date(t).toLocaleString("ja-JP", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
function recognize() {
  if (!state.keySet) {
    report("メニュー → Gemini APIキー・モデル で、APIキーを登録してください。");
    return;
  }
  call("recognize", { id: selected });
}
function reread() {
  if (!state.keySet) {
    report("メニュー → Gemini APIキー・モデル で、APIキーを登録してください。");
    return;
  }
  call("reread", { id: selected });
}
function unlinkContact(id) {
  if (confirm("このアプリが追加した連絡先を削除します。もともとあった連絡先はそのまま残ります。続けますか？"))
    call("unlinkContact", { id });
}

// ---- Review / edit ------------------------------------------------------------
// Edit doubles as the review screen: a pending Gemini result is pre-filled into the form.
function beginEdit() {
  const p = getPerson();
  draft = JSON.parse(JSON.stringify(p));
  draft.fromSuggestion = !!p.suggestion;
  if (p.suggestion)
    for (const [k] of fields)
      if (p.suggestion[k]) draft[k] = p.suggestion[k];
  if (!draft.metDate && draft.fromSuggestion)
    draft.metDate = new Date(p.created).toLocaleDateString("sv-SE");
  route = "edit";
  closeModal();
  render();
  window.scrollTo(0, 0);
}
function editPage() {
  if (!draft) {
    route = "detail";
    return detail();
  }
  const p = getPerson();
  const warn = draft.fromSuggestion && p.suggestion?.warnings;
  const registered = !!p.contactRawId;
  return `${backbar(draft.fromSuggestion ? "読み取り結果の確認" : "連絡先を編集")}${draft.pages.length ? `<div class="pages compact">${draft.pages.map((pg, i) => `<button class="page" onclick="viewImage(${i},true)"><img class="pageimg" src="${img(pg.file)}" alt="名刺 ${i + 1}ページ"></button>`).join("")}</div>` : ""}${draft.fromSuggestion ? `<div class="tip gap">${icon("spark")} Geminiの読み取り結果です。画像と見比べて、氏名・電話・メールを確認してください。<button class="link" onclick="discardSuggestion()">結果を破棄</button></div>` : ""}${warn ? `<div class="tip warning gap">注意：${esc(warn)}</div>` : ""}<div class="panel gap"><label class="span2"><span class="label">種類</span><select onchange="draft.kind=this.value;render()"><option value="person" ${draft.kind !== "shop" ? "selected" : ""}>人の名刺</option><option value="shop" ${draft.kind === "shop" ? "selected" : ""}>お店のカード</option></select></label><div class="grid2">${fields.filter(([k]) => draft.kind === "shop" || !shopOnly.includes(k) || draft[k]).map(([k, l]) => `<label class="${["company", "department", "title", "email", "address", "website", "tags", "metPlace", "genre", "hours", "holidays"].includes(k) ? "span2" : ""}"><span class="label">${k === "name" && draft.kind === "shop" ? "店名" : l}</span>${["email", "phone", "mobile", "fax", "address", "hours"].includes(k) ? `<textarea style="min-height:${k === "address" ? 90 : 55}px" rows="${k === "address" ? 2 : 1}" oninput="draft['${k}']=this.value">${esc(draft[k])}</textarea>` : `<input type="${k === "metDate" ? "date" : "text"}" value="${esc(draft[k])}" oninput="draft['${k}']=this.value" ${k === "tags" ? 'placeholder="例：医療、取引先、学会"' : ""}>`}</label>`).join("")}</div><p class="caption">複数の電話・メールは改行で区切れます。</p></div><div class="fixedsave">${registered ? `<button class="primary full" onclick="savePerson(false)">${icon("check")}保存（Google連絡先にも反映）</button><p class="caption">このアプリで登録した連絡先を、ここでの内容で上書きします。</p>` : `<button class="primary full" onclick="savePerson(true)">${icon("user")}保存して連絡先に登録</button><button class="outline full" onclick="savePerson(false)">保存のみ</button>${state.contactAccount ? `<p class="caption center">登録先：${esc(state.contactAccount)}</p>` : ""}`}</div>`;
}
function savePerson(thenContact) {
  if (!String(draft.name || "").trim()) {
    toast("氏名を入力してください");
    return;
  }
  draft.status = "confirmed";
  draft.acceptSuggestion = !!draft.fromSuggestion;
  call("save", { id: selected, person: draft, thenContact });
}
function discardSuggestion() {
  if (!confirm("この読み取り結果を破棄しますか？")) return;
  draft = null;
  route = "detail";
  call("discardRecognition", { id: selected });
}
function registerContact() {
  call("contacts", { id: selected });
}
function contactSheet() {
  const p = getPerson();
  const def = state.contactPhoto || "card";
  modal(
    `<h2>写真を選んで登録</h2><p class="caption">着信時の表示は端末・電話アプリによって異なり、丸く切り抜かれる場合があります。</p><label class="label">連絡先の写真</label><select id="contactphoto">${[
      ["card", "名刺の表面", p.pages.length],
      ["portrait", "登録した人物写真", p.portrait],
      ["logo", "登録した会社ロゴ", p.logo],
      ["none", "画像なし", true],
    ]
      .map(
        ([v, l, ok]) =>
          `<option value="${v}" ${!ok ? "disabled" : ""} ${v === def && ok ? "selected" : ""}>${l}</option>`,
      )
      .join("")}</select><button class="primary full gap" onclick="const photo=$('contactphoto').value;closeModal();call('contacts',{id:selected,photo})">${icon("user")}Google連絡先に登録</button>`,
  );
}
function addNote() {
  const text = noteDraft.trim();
  if (!text) return;
  call(editNoteId ? "editNote" : "note", {
    id: selected,
    text,
    noteId: editNoteId,
  });
  noteDraft = "";
  editNoteId = "";
}
function editNote(i) {
  const n = getPerson().notes[i];
  noteDraft = n.text;
  editNoteId = n.id;
  render();
  $("note").focus();
  $("note").scrollIntoView({ block: "center" });
}
function removeItem(key, index) {
  if (
    confirm(
      key === "pages"
        ? "この人から画像を外しますか？元のスキャン原本は保管されます。"
        : "この項目を削除しますか？",
    )
  )
    call("removeItem", { id: selected, key, index });
}
function deletePerson() {
  const p = getPerson();
  if (!p.contactRawId) {
    if (confirm("この名刺とメモ・録音を削除しますか？復旧にはバックアップが必要です。"))
      call("delete", { id: selected });
    return;
  }
  modal(
    `<h2>${esc(title(p))} を削除</h2><p class="caption">名刺・メモ・録音を削除します。復旧にはバックアップが必要です。</p><div class="mlist">${menuRow("trash", "名刺と連絡先を削除", "このアプリで登録したGoogle連絡先も消します", "closeModal();call('delete',{id:selected,withContact:true})", "dangerrow")}${menuRow("trash", "名刺だけ削除", "Google連絡先は残します", "closeModal();call('delete',{id:selected})", "dangerrow")}</div>`,
  );
}

// ---- Duplicates -----------------------------------------------------------------
// Flags pairs sharing an email, a mobile number or the same name (+company). Office
// main lines are ignored on purpose: colleagues share them.
function dupes() {
  const n = (x) => String(x || "").normalize("NFKC").toLowerCase().replace(/[\s　]/g, "");
  const index = {};
  const add = (k, id) => (index[k] = index[k] || []).push(id);
  for (const p of state.people.filter(visible)) {
    for (const e of String(p.email || "").split(/[\n,;]/)) if (n(e).includes("@")) add("m:" + n(e), p.id);
    for (const t of String(p.mobile || "").split(/[\n,;]/)) {
      const d = t.replace(/\D/g, "");
      if (d.length >= 10) add("t:" + d.slice(-10), p.id);
    }
    if (n(p.name).length >= 2) add((n(p.company) ? "c:" + n(p.company) + "|" : "n:") + n(p.name), p.id);
  }
  const reason = { m: "同じメールアドレス", t: "同じ携帯番号", c: "同じ氏名・会社", n: "同じ氏名" };
  const pairs = new Map();
  for (const [k, ids] of Object.entries(index)) {
    const u = [...new Set(ids)];
    for (let i = 0; i < u.length; i++)
      for (let j = i + 1; j < u.length; j++) {
        const key = [u[i], u[j]].sort().join("|");
        if (!pairs.has(key)) pairs.set(key, { a: u[i], b: u[j], why: new Set() });
        pairs.get(key).why.add(reason[k[0]]);
      }
  }
  const byId = Object.fromEntries(state.people.map((p) => [p.id, p]));
  const out = [...pairs.values()].filter(
    (x) => !(byId[x.a].notDuplicate || []).includes(x.b) && !byId[x.a].queue && !byId[x.b].queue,
  );
  for (const p of state.people) if (p.contactDuplicate) out.push({ contact: p.id });
  return out;
}
function dupSheet() {
  const byId = Object.fromEntries(state.people.map((p) => [p.id, p]));
  const who = (p) =>
    `<button class="duprow" onclick="openPerson('${p.id}')">${p.pages?.length ? `<img src="${img(p.pages[0].file)}" alt="">` : `<span class="initial">${esc(title(p).slice(0, 1))}</span>`}<span><b>${esc(title(p))}</b><small>${esc(company(p))}${p.contactRawId ? " ・ 連絡先登録済み" : ""}<br>追加 ${esc(date(p.created))}</small></span></button>`;
  const items = dupes()
    .map((d) => {
      if (d.contact) {
        const p = byId[d.contact];
        return `<div class="dupitem">${who(p)}<p class="caption">Google連絡先に既存の「${esc(p.contactDuplicate)}」があります（同じ電話・メール）。</p><div class="row"><button class="outline" onclick="closeModal();unlinkContact('${p.id}')">追加した連絡先を削除</button><button class="noflex" onclick="call('dismissContactDup',{id:'${p.id}'});closeModal()">問題なし</button></div></div>`;
      }
      // Keep the newer card as the base: it carries the current title and contact details.
      const [older, newer] = [byId[d.a], byId[d.b]].sort((x, y) => x.created - y.created);
      return `<div class="dupitem">${who(newer)}${who(older)}<p class="caption">${[...d.why].join("・")}</p><div class="row"><button class="primary" onclick="mergePair('${newer.id}','${older.id}')">1人にまとめる</button><button class="noflex" onclick="call('notDuplicate',{id:'${d.a}',other:'${d.b}'});closeModal()">別人</button></div></div>`;
    })
    .join("");
  modal(
    `<h2>${icon("link")} 重複の可能性</h2><p class="caption">「1人にまとめる」は新しい名刺の内容を優先し、古い名刺の画像・メモ・録音を引き継ぎます。Google連絡先も1件にまとめます。</p>${items || '<p class="caption">重複の可能性はありません。</p>'}`,
  );
}
function mergePair(target, source) {
  if (!confirm("2枚の名刺を1人にまとめますか？")) return;
  closeModal();
  selected = target;
  call("merge", { id: target, sourceId: source });
}
// Page tools (rotate / reorder / remove / share) live in the viewer, not under every image.
function viewImage(i, readOnly = false) {
  const p = getPerson(),
    pg = p.pages[i];
  const tools = readOnly
    ? ""
    : `<div class="viewtools"><button onclick="closeModal();call('rotate',{id:selected,index:${i}})">${icon("rotate")}回転</button>${i > 0 ? `<button onclick="closeModal();call('reorder',{id:selected,from:${i},to:${i - 1}})">${icon("back")}前へ</button>` : ""}<button onclick="call('shareImage',{id:selected,index:${i}})">${icon("share")}共有</button><button onclick="closeModal();removeItem('pages',${i})">${icon("trash")}外す</button></div>`;
  modal(
    `<img src="${img(pg.file)}" alt="名刺拡大"><p style="font-size:12px">${esc(pg.name)} ・ ピンチで拡大できます</p>${tools}`,
    true,
  );
}
function mergeSheet() {
  const candidates = state.people.filter((p) => p.id !== selected);
  if (!candidates.length) {
    toast("統合できる別の名刺がありません");
    return;
  }
  modal(
    `<h2>同じ人の名刺をまとめる</h2><p class="caption">選んだ名刺の画像・メモ・録音を、いま開いている人へ移します。空欄の連絡先情報だけ補います。Google連絡先は統合されません。</p><select id="mergeid">${candidates.map((p) => `<option value="${p.id}">${esc(title(p))} / ${esc(company(p))}</option>`).join("")}</select><button class="primary full gap" onclick="if(confirm('名刺を1人に統合しますか？')){const sourceId=$('mergeid').value;closeModal();call('merge',{id:selected,sourceId})}">この人にまとめる</button>`,
  );
}

// ---- Menu -------------------------------------------------------------------
const groupings = [
  ["file", "1ファイル = 1人", "両面を1つのPDFにした場合など"],
  ["pair", "2ページずつ = 1人", "表→裏の順に並んだ画像・PDF"],
  ["page", "1ページ = 1人", "片面名刺をまとめてスキャンした場合"],
  ["all", "選んだ全部 = 1人", "1人分の複数ファイル"],
];
// Choosing how pages group into people *is* the start button, so it costs no extra tap.
function importSheet(action) {
  const heading = {
    pick: "画像・PDFから取り込む",
    folder: "ScanSnapフォルダから取り込む",
    rescan: "前回のフォルダを再確認",
  }[action];
  modal(
    `<h2>${heading}</h2><p class="caption">ページのまとめ方を選ぶと開始します。ファイル名順・PDFはページ順で組み合わせます。${action === "rescan" ? "同じ内容のファイルはスキップします。" : ""}</p><div class="mlist">${groupings
      .map(([v, l, sub]) =>
        menuRow(
          v === "pair" ? "card" : v === "page" ? "list" : v === "all" ? "grid" : "image",
          l + (mode === v ? "（前回）" : ""),
          sub,
          `mode='${v}';closeModal();call('${action}',{mode:'${v}'})`,
        ),
      )
      .join("")}</div>`,
  );
}
function backupSheet() {
  modal(
    `<h2>${icon("cloud")} バックアップ</h2><p class="caption">名刺データ・画像原本・メモ・録音をZIPにまとめます。保存先で「Google Drive」を選んでください。ZIPは暗号化されないため、非公開のフォルダに保存してください。</p><div class="mlist">${[
      menuRow("cloud", "保存先を選んで保存", "Google Driveなど", "closeModal();call('export',{kind:'zip'})"),
      menuRow("share", "共有して保存", "保存先が見つからない場合", "closeModal();call('export',{kind:'zip',share:true})"),
      menuRow("download", "バックアップから復元", "未登録の名刺だけ追加。ChatGPT版のZIPも可", "closeModal();restoreConfirm()"),
    ].join("")}</div>`,
  );
}
function contactSettingsSheet() {
  const photo = state.contactPhoto || "card";
  modal(
    `<h2>連絡先の登録設定</h2><label class="label">連絡先の写真（標準）</label><select onchange="call('settings',{contactPhoto:this.value})">${[
      ["card", "名刺の表面"],
      ["portrait", "人物写真（登録済みの人のみ）"],
      ["logo", "会社ロゴ（登録済みの人のみ）"],
      ["none", "画像なし"],
    ]
      .map(
        ([v, l]) =>
          `<option value="${v}" ${photo === v ? "selected" : ""}>${l}</option>`,
      )
      .join("")}</select><label class="label">登録先のGoogleアカウント</label>${state.contactAccount ? `<div class="row"><input value="${esc(state.contactAccount)}" readonly><button class="outline noflex" onclick="call('settings',{clearAccount:true});closeModal()">変更</button></div><p class="caption">「変更」を押すと、次回の登録時にアカウントを選び直します。</p>` : `<p class="caption">初回の登録時に選んだアカウントを記憶します。</p>`}<p class="tip gap">メモ・録音は連絡先に送りません。登録後の自動更新はありません。</p>`,
  );
}
let driveStack = [];
function driveBrowse() {
  driveStack = [{ id: "root", name: "マイドライブ" }];
  call("driveFolders", { parent: "root" });
}
function driveOpen(i) {
  const f = driveFolders[i];
  driveStack.push({ id: f.id, name: f.name });
  call("driveFolders", { parent: f.id });
}
function driveUp() {
  driveStack.pop();
  call("driveFolders", { parent: driveStack.at(-1).id });
}
let driveFolders = [];
function driveSheet(list) {
  driveFolders = list;
  const here = driveStack.at(-1) || { id: "root", name: "マイドライブ" };
  const atRoot = driveStack.length <= 1;
  modal(
    `<h2>${icon("cloud")} フォルダを選ぶ</h2><p class="caption">ScanSnapの保存先フォルダを開いて「このフォルダを使う」を押してください。取り込みが済んだファイルは、その中の「登録済み」フォルダへ移動します。</p><div class="crumb">${driveStack.map((x) => esc(x.name)).join(" › ")}</div><div class="mlist">${atRoot ? "" : menuRow("back", "上のフォルダへ", "", "driveUp()")}${list.map((f, i) => menuRow("folder", esc(f.name), "", `driveOpen(${i})`)).join("") || '<p class="caption" style="padding:12px 14px">サブフォルダはありません</p>'}</div><label class="label">ファイルのまとめ方</label><select id="drivemode">${groupings.map(([v, l]) => `<option value="${v}" ${(state.driveMode || "file") === v ? "selected" : ""}>${l}</option>`).join("")}</select><p class="caption">ScanSnapで表裏を1つのPDFにしている場合は「1ファイル = 1人」です。</p><button class="primary full gap" ${atRoot ? "disabled" : ""} onclick="call('driveSelect',{folderId:'${esc(here.id)}',folderName:driveStack.at(-1).name,mode:$('drivemode').value});closeModal()">${icon("check")}「${esc(here.name)}」を使う</button>`,
  );
}
function driveSettingsSheet() {
  modal(
    `<h2>${icon("cloud")} Driveフォルダの設定</h2><label class="label">取り込むフォルダ</label><p><b>${esc(state.driveFolderName)}</b></p><p class="caption">取り込みが済んだファイルは、このフォルダの中の「登録済み」へ移動します（無ければ作ります）。</p><label class="label">ファイルのまとめ方</label><select onchange="call('driveSelect',{folderId:state.driveFolderId,folderName:state.driveFolderName,mode:this.value})">${groupings.map(([v, l]) => `<option value="${v}" ${(state.driveMode || "file") === v ? "selected" : ""}>${l}</option>`).join("")}</select><div class="row gap"><button class="outline" onclick="closeModal();driveBrowse()">フォルダを変更</button><button class="danger noflex" onclick="if(confirm('Driveフォルダの設定を解除しますか？')){closeModal();call('driveClear')}">解除</button></div>`,
  );
}
function bulkSheet() {
  modal(
    `<h2>${icon("camera")} まとめて撮影</h2><p class="caption">スキャン画面で何枚も続けて撮影し、最後に完了を押します。保存後はバックグラウンドで読み取り・登録するので、すぐ次を撮影できます。</p><div class="mlist">${[
      menuRow("card", "表・裏の順に撮る", "1人目の表→裏→2人目の表→裏…", "closeModal();call('bulkScan',{mode:'pair'})"),
      menuRow("list", "片面だけ撮る", "1枚 = 1人", "closeModal();call('bulkScan',{mode:'page'})"),
    ].join("")}</div><p class="caption">1回20ページまで。1人ずつなら撮影ボタンを押すだけです。</p>`,
  );
}
function importZipSheet() {
  modal(
    `<h2>${icon("download")} ZIPから一括取り込み</h2><p class="caption">名刺画像をまとめたZIPを読み込み、1人ずつに分けて保存します。読み取りはバックグラウンドのGeminiが行うので、終わるまで他の操作をしていて大丈夫です。</p><div class="tip"><b>画像だけのZIP</b><div class="step"><b>・</b><span>Eightの画像（<code>交換日_氏名_会社_ID.jpg</code>、裏は末尾<code>_裏</code>）は表裏を自動でまとめ、氏名・会社・交換日も入れます。</span></div><div class="step"><b>・</b><span><code>001_1.jpg</code>／<code>001_2.jpg</code>、<code>_表</code>／<code>_裏</code> の組も1人にまとめます。それ以外は1枚 = 1人。</span></div><b>CSV付きZIP</b>：CSVの項目と画像をそのまま登録します（仕様：docs/IMPORT_SPEC.md）。<br><b>同期形式のZIP</b>：<code>people/</code>（1人1つのJSON）と <code>media/</code> の組をそのまま登録します（仕様：docs/SYNC_SPEC.md）。</div><label class="mrow"><span class="mi">${icon("user")}</span><span class="mt"><b>読み取り後にGoogle連絡先へ登録</b><small>Eight等から既に連絡先へ入っているならオフのまま</small></span><input type="checkbox" class="switch" id="zipcontacts"></label><p class="caption">同じZIPを2回読み込んでも、同じ人は追加されません。読み取りの費用の目安は1人1円以下です。</p><button class="primary full gap" onclick="const toContacts=$('zipcontacts').checked;closeModal();call('importZip',{toContacts})">${icon("download")}ZIPファイルを選ぶ</button>`,
  );
}
// ---- New Year cards ------------------------------------------------------------------
const nengaKinds = {
  nenga: "年賀状",
  mochu: "喪中",
  kanchu: "寒中見舞い",
  shochu: "暑中見舞い",
  xmas: "クリスマスカード",
  letter: "手紙",
  other: "はがき",
  doc: "書類",
};
// The yearly New Year exchange counts only these; everything else is general mail.
const NENGA = ["nenga", "mochu", "kanchu"];
const season = () => {
  const d = new Date();
  return d.getMonth() >= 9 ? d.getFullYear() + 1 : d.getFullYear();
};
let nengaYear = 0,
  nengaFilter = "all",
  nengaQuery = "";
const greets = (p) => (Array.isArray(p.greetings) ? p.greetings : []);
const got = (p, y, dir) => greets(p).filter((g) => g.year === y && g.dir === dir && NENGA.includes(g.kind || "nenga"));
const mourning = (p, y) => greets(p).some((g) => g.kind === "mochu" && (g.year === y || g.year === y - 1));
function greetingsSection(p) {
  if (p.kind === "shop") return "";
  const list = [...greets(p)].sort((a, b) => b.year - a.year || (a.dir > b.dir ? 1 : -1));
  const y = season();
  const r = got(p, y, "received").length,
    s = got(p, y, "sent").length;
  return `<section class="panel"><h3>年賀状・お便り・書類 <small>${list.length}件</small></h3><div class="row"><button class="${r ? "secondary" : "outline"}" onclick="toggleGreeting('${p.id}',${y},'received')">${r ? icon("check") : ""}${y}年 届いた</button><button class="${s ? "secondary" : "outline"}" onclick="toggleGreeting('${p.id}',${y},'sent')">${s ? icon("check") : ""}${y}年 送った</button></div>${list
    .map(
      (g) =>
        `<div class="note"><div class="notehead"><div><small>${esc((g.date || "").replace(/-/g, "/"))}</small><div>${NENGA.includes(g.kind || "nenga") ? `<b>${g.year}年</b> ` : ""}${g.dir === "sent" ? "送った" : "届いた"}${g.kind && g.kind !== "nenga" ? ` ・ <span class="badge ${g.kind === "doc" ? "confirmed" : "review"}">${esc(nengaKinds[g.kind] || "")}</span>` : ""}${g.title ? ` ・ <b>${esc(g.title)}</b>` : ""}</div></div><div><button onclick="greetingDate('${p.id}','${g.id}','${esc(g.date || "")}')" aria-label="日付を変更">${icon("edit")}</button><button onclick="if(confirm('この記録を削除しますか？'))call('greetingEdit',{id:'${p.id}',gid:'${g.id}',remove:true})" aria-label="記録を削除">${icon("trash")}</button></div></div>${(g.files || []).some((f) => !f.original) ? `<div class="gthumbs">${g.files.filter((f) => !f.original).map((f) => `<img src="${img(f.file)}" alt="${esc(nengaKinds[g.kind] || "お便り")}" onclick="viewFile('${esc(f.file)}')">`).join("")}</div>` : ""}${(g.files || []).filter((f) => f.original).map((f) => `<button class="link" onclick="call('openMedia',{file:'${esc(f.file)}'})">${icon("download")}原本を開く（${esc(f.name || "PDF")}）</button>`).join("")}${g.senders ? `<small>差出人：${esc(g.senders.replace(/\n/g, "・"))}</small>` : ""}${g.message ? `<p class="notebody">${esc(g.message)}</p>` : ""}</div>`,
    )
    .join("")}</section>`;
}
function toggleGreeting(id, year, dir) {
  const p = state.people.find((x) => x.id === id);
  const on = !got(p, year, dir).length;
  if (!on && got(p, year, dir).some((g) => (g.files || []).length)) {
    toast("画像付きの記録は、名刺の詳細の年賀状履歴から削除してください");
    return;
  }
  call("greeting", { id, year, dir, on });
}
function greetingDate(id, gid, current) {
  const v = prompt("日付（YYYY-MM-DD）", current || new Date().toLocaleDateString("sv-SE"));
  if (v && /^\d{4}-\d{2}-\d{2}$/.test(v)) call("greetingEdit", { id, gid, date: v });
}
function viewFile(file) {
  modal(`<img src="${img(file)}" alt="拡大"><p style="font-size:12px">ピンチで拡大できます</p>`, true);
}
function nengaSearch(v) {
  nengaQuery = v;
  $("nlist").innerHTML = nengaList();
}
function nengaList() {
  const y = nengaYear;
  const people = state.people.filter((p) => visible(p) && p.kind !== "shop");
  const q = nengaQuery.toLocaleLowerCase();
  const list = q
    ? people.filter((p) => [p.name, p.kana, p.company, p.address].join(" ").toLocaleLowerCase().includes(q))
    : people.filter(nengaGroups(y)[nengaFilter]);
  const row = (p) => {
    const r = got(p, y, "received")[0],
      s = got(p, y, "sent")[0];
    const last = [got(p, y - 1, "sent").length ? "送" : "", got(p, y - 1, "received").length ? "届" : ""].filter(Boolean).join("・");
    const day = (g) => `<small>${esc((g.date || "").slice(5).replace("-", "/"))}</small>`;
    return `<div class="nrow"><button class="nwho" onclick="openPerson('${p.id}')"><b class="ell">${esc(title(p))}</b><small class="ell">${esc(p.company || p.address || "")}${last ? ` ・ 昨年 ${last}` : ""}${mourning(p, y) ? " ・ 喪中" : ""}</small></button><button class="ntog ${r ? "on" : ""}" onclick="toggleGreeting('${p.id}',${y},'received')">${r ? icon("check") : ""}届いた${r ? day(r) : ""}</button><button class="ntog ${s ? "on" : ""}" onclick="toggleGreeting('${p.id}',${y},'sent')">${s ? icon("check") : ""}送った${s ? day(s) : ""}</button></div>`;
  };
  return list.map(row).join("") || `<p class="caption center">${q ? "該当する人がいません" : "まだ記録がありません。届いた年賀状を撮影するか、名前で探して「送った」を記録してください。"}</p>`;
}
function nengaGroups(y) {
  const any = (p, yr) => got(p, yr, "received").length || got(p, yr, "sent").length;
  return {
    all: (p) => any(p, y) || any(p, y - 1),
    reply: (p) => got(p, y, "received").length && !got(p, y, "sent").length,
    lastyear: (p) => any(p, y - 1) && !got(p, y, "sent").length && !mourning(p, y),
    unanswered: (p) => got(p, y, "sent").length && !got(p, y, "received").length,
    mochu: (p) => mourning(p, y),
  };
}
function nengaPage() {
  if (!nengaYear) nengaYear = season();
  const y = nengaYear;
  const people = state.people.filter((p) => visible(p) && p.kind !== "shop");
  const years = new Set([season(), season() - 1]);
  for (const p of people) for (const g of greets(p)) years.add(g.year);
  const inbox = state.people.filter((p) => p.kind === "nengaInbox");
  const groups = nengaGroups(y);
  const count = (k) => people.filter(groups[k]).length;
  const chip = (k, l) =>
    `<button class="chip ${nengaFilter === k ? "active" : ""} ${k === "reply" && count(k) ? "attn" : ""}" onclick="nengaFilter='${k}';nengaQuery='';render()">${l} ${count(k)}</button>`;
  const mail = [];
  for (const p of people) for (const g of greets(p)) if (!NENGA.includes(g.kind || "nenga")) mail.push({ p, g });
  mail.sort((a, b) => (b.g.date || "").localeCompare(a.g.date || ""));
  return `${backbar("年賀状・お便り")}<div class="calhead"><select aria-label="年" onchange="nengaYear=Number(this.value);render()">${[...years]
    .sort((a, b) => b - a)
    .map((v) => `<option value="${v}" ${v === y ? "selected" : ""}>${v}年の年賀状</option>`)
    .join("")}</select></div><div class="stats"><div><small>届いた</small><div><strong>${people.filter((p) => got(p, y, "received").length).length}</strong> <small>通</small></div></div><div class="statRight"><small>送った</small><div><strong>${people.filter((p) => got(p, y, "sent").length).length}</strong> <small>通</small></div></div></div><div class="mlist">${[
    menuRow("camera", "届いた年賀状を撮影", "1通ずつ（両面を撮って完了）", `call('scan',{id:'',mode:'all',nenga:true,year:${y}})`),
    menuRow("card", "まとめて撮影", "1通目の両面→2通目の両面…の順に", `call('bulkScan',{mode:'pair',nenga:true,year:${y}})`),
    menuRow("image", "画像・PDFから取り込む", "スキャン済みの年賀状（2ページずつ = 1通）", `call('pick',{mode:'pair',nenga:true,year:${y}})`),
    menuRow("mail", "お便り・手紙を撮影", "暑中見舞い・クリスマスカード・手紙など（差出人を読み取り）", `call('scan',{id:'',mode:'all',nenga:true})`),
  ].join("")}</div>${
    inbox.length
      ? `<div class="tip gap">${inbox.map((p) => `<div class="step"><b>${p.queue ? '<i class="spin"></i>' : "!"}</b><span>${p.queue ? "差出人を読み取り中…" : `読み取れませんでした${p.queueError ? "：" + esc(p.queueError) : ""} <button class="link" onclick="call('nengaRetry',{id:'${p.id}'})">再試行</button>`}</span></div>`).join("")}</div>`
      : ""
  }<div class="toolbar gap"><div class="search">${icon("search")}<input placeholder="名前・住所で探して記録" value="${esc(nengaQuery)}" oninput="nengaSearch(this.value)"></div></div><div class="chips">${chip("all", "やり取りあり")}${chip("reply", "返礼が必要")}${chip("lastyear", "昨年あり・今年未送")}${chip("unanswered", "送ったが未着")}${chip("mochu", "喪中")}</div><div class="nlist" id="nlist">${nengaList()}</div>${
    mail.length
      ? `<h3 class="gap">最近のお便り・書類</h3><div class="nlist">${mail
          .slice(0, 20)
          .map(({ p, g }) => `<button class="nrow mailrow" onclick="openPerson('${p.id}')"><span class="nwho"><b class="ell">${esc(title(p))}</b><small class="ell">${esc((g.date || "").replace(/-/g, "/"))} ・ ${esc(nengaKinds[g.kind] || "お便り")} ・ ${g.dir === "sent" ? "送った" : "届いた"}${g.title ? " ・ " + esc(g.title) : ""}</small></span>${icon("next")}</button>`)
          .join("")}</div>`
      : ""
  }<button class="outline full gap" onclick="call('export',{kind:'nenga',year:${y}})">${icon("table")}${y}年の宛名リストをCSVで書き出す</button><p class="caption">宛名リストは、今年か昨年にやり取りのある人（郵便番号・住所・送受信・喪中）です。宛名印刷ソフトに読み込めます。</p>`;
}
function syncStart() {
  if (
    confirm(
      "Google Driveのマイドライブに「Theta Meishi Hatch データ」フォルダを作り、名刺・画像・メモ・録音を同期します。他の端末で同じGoogleアカウントの同期をオンにすると、データが共有されます。始めますか？",
    )
  )
    call("syncNow");
}
function menuPage() {
  const unread = state.people.filter(
    (p) => p.status === "new" && p.pages.length,
  ).length;
  const section = (h, rows) =>
    `<div class="msection"><small>${h}</small><div class="mlist">${rows.join("")}</div></div>`;
  return `<header class="top"><div class="brand"><div class="mark">${icon("menu")}</div><div><div class="brandline">MENU</div><h1>メニュー</h1></div></div></header>${recordBanner()}${section(
    "取り込み",
    [
      ...(state.driveFolderName
        ? [
            menuRow("cloud", "Google Driveから取り込む", esc(state.driveFolderName) + " ・ 済んだファイルは「登録済み」へ", "call('driveImport')"),
            menuRow("folder", "Driveフォルダの設定", groupings.find((g) => g[0] === (state.driveMode || "file"))[1], "driveSettingsSheet()"),
          ]
        : [menuRow("cloud", "Google DriveのScanSnapフォルダを設定", "PDFを取り込み、済んだら「登録済み」へ移動", "driveBrowse()")]),
      menuRow("camera", "まとめて撮影", "何枚も続けて撮影（撮影ボタンの長押しでも開けます）", "bulkSheet()"),
      menuRow("image", "画像・PDFから取り込む", "写真やスキャンをまとめて", "importSheet('pick')"),
      menuRow("folder", "ScanSnapフォルダから取り込む", "フォルダ内を一括で", "importSheet('folder')"),
      state.folder ? menuRow("rotate", "前回のフォルダを再確認", "新しい名刺だけ取り込む", "importSheet('rescan')") : "",
      menuRow("camera", "通常のカメラで撮影", "スキャン画面が使えない場合", "call('camera',{id:'',auto:true})"),
      menuRow("edit", "手入力で追加", "画像なしで登録", "manualAdd()"),
      menuRow("download", "ZIPから一括取り込み", "名刺画像だけのZIP（Eightの画像など）・CSV付きZIP", "importZipSheet()"),
    ],
  )}${section("年賀状", [
    menuRow("mail", "年賀状・お便り", "年賀状の送受信、暑中見舞い・カード・手紙", "go('nenga')"),
  ])}${section("読み取り", [
    menuRow("spark", "未認識をまとめて読み取る", unread ? unread + "件の名刺が未認識" : "未認識の名刺はありません", "batchRecognize()"),
  ])}${section("同期", [
    state.syncEnabled
      ? menuRow("rotate", "今すぐ同期", state.syncError ? "⚠ " + esc(state.syncError) : state.lastSync ? "最終同期 " + esc(date(state.lastSync)) : "まだ同期していません", "call('syncNow')", state.syncError ? "attnrow" : "")
      : menuRow("cloud", "Google Driveで同期を始める", "スマホ・PCで名刺・画像・メモ・録音を共有", "syncStart()"),
    state.syncEnabled ? menuRow("close", "同期をやめる", "Drive上のデータは残ります", "if(confirm('同期をやめますか？Drive上のデータは削除されません。'))call('syncStop')") : "",
  ])}${section("書き出し・バックアップ", [
    menuRow("table", "一覧をCSVで書き出す", "Excelで開けるUTF-8 CSV", "call('export',{kind:'csv'})"),
    menuRow("cloud", "バックアップ・復元", "Google DriveへZIP保存", "backupSheet()"),
  ])}${section("設定", [
    menuRow("spark", "Gemini APIキー・モデル", state.keySet ? "登録済み ・ " + esc(state.model) : "未設定", "go('gemini')", state.keySet ? "" : "attnrow"),
    `<label class="mrow"><span class="mi">${icon("camera")}</span><span class="mt"><b>取り込み後に自動で読み取る</b><small>撮影・Drive・ファイルの取り込み後、バックグラウンドで処理</small></span><input type="checkbox" class="switch" ${state.autoRecognize !== false ? "checked" : ""} onchange="call('settings',{autoRecognize:this.checked})"></label>`,
    `<label class="mrow"><span class="mi">${icon("user")}</span><span class="mt"><b>読み取り後に連絡先へ自動登録</b><small>重複は後から一覧でお知らせします</small></span><input type="checkbox" class="switch" ${state.autoContact !== false ? "checked" : ""} onchange="call('settings',{autoContact:this.checked})"></label>`,
    menuRow("clock", "Googleカレンダー", state.autoCalendar ? "名刺交換を自動で登録" + (state.calendarName ? " ・ " + esc(state.calendarName) : "") : "名刺交換日の予定を登録", "calendarSettings()"),
    menuRow("user", "連絡先の登録設定", (state.contactAccount || "アカウント未選択") + " ・ 写真：" + ({ card: "名刺", portrait: "人物写真", logo: "ロゴ", none: "なし" }[state.contactPhoto || "card"]), "contactSettingsSheet()"),
  ])}<p class="footnote">Theta Meishi Hatch 0.9.1 ・ Android 8以上<br>端末内保存 / 利用者ネットワークなし。アンインストールでデータが消えるため、定期的にバックアップしてください。</p>`;
}
function geminiPage() {
  if (!settingsDraft)
    settingsDraft = { key: "", model: state.model, format: state.format };
  return `${backbar("Gemini APIキー・モデル")}<section class="panel"><p class="caption">APIキーはこの端末に暗号化して保存します。バックアップには含めません。</p><label class="label">Gemini APIキー ${state.keySet ? "・登録済み" : ""}</label><input type="password" autocomplete="off" value="${esc(settingsDraft.key)}" placeholder="${state.keySet ? "変更する時だけ入力" : "AIza…"}" oninput="settingsDraft.key=this.value"><label class="label">モデル名</label><input id="model" value="${esc(settingsDraft.model)}" oninput="settingsDraft.model=this.value" autocomplete="off"><p class="caption">利用可能なモデルはAPIキーごとに異なります。保存後に「モデル一覧」から選べます。</p><details class="gap"><summary class="caption">詳細設定</summary><label class="label">API互換設定（通常は変更不要）</label><select onchange="settingsDraft.format=this.value"><option value="legacy" ${settingsDraft.format === "legacy" ? "selected" : ""}>標準</option><option value="modern" ${settingsDraft.format === "modern" ? "selected" : ""}>新しいモデル向け</option></select><p class="caption">新しいモデルで認識時にエラーが出る場合に切り替えてください。</p></details><div class="row gap"><button class="primary" onclick="saveSettings()">保存</button><button class="outline" onclick="call('models')">モデル一覧</button></div>${state.keySet ? `<button class="danger full gap" onclick="if(confirm('保存しているAPIキーを削除しますか？'))call('settings',{clearKey:true})">APIキーを削除</button>` : ""}</section><div class="tip">読み取り時に名刺画像をGoogleのGemini APIへ送信します。APIの料金・データ利用条件は、ご自身のプロジェクト設定をご確認ください。</div>`;
}
let editOnOpen = false;
function manualAdd() {
  editOnOpen = true;
  call("new");
}
function saveSettings() {
  call("settings", { ...settingsDraft });
  settingsDraft.key = "";
}
function batchRecognize() {
  if (!state.keySet) {
    report("先に「Gemini APIキー・モデル」でAPIキーを登録してください。");
    return;
  }
  let count = state.people.filter(
    (p) => p.status === "new" && p.pages.length,
  ).length;
  if (!count) {
    toast("未認識の名刺はありません");
    return;
  }
  if (
    confirm(
      count +
        "人分の名刺画像をGeminiに送信します。APIの利用料金が発生する場合があります。読み取り中はアプリを開いておいてください。",
    )
  )
    call("recognizeAll");
}
function restoreConfirm() {
  if (
    confirm(
      "バックアップZIPから未登録の名刺を追加します。同じIDの名刺はスキップされ、現在のデータは上書きしません。続けますか？",
    )
  )
    call("restore");
}

// ---- Native bridge ------------------------------------------------------------
window.handleBack = () => {
  if (!$("busy").hidden) {
    toast("処理が終わるまでお待ちください");
    return;
  }
  if (modalOpen) {
    closeModal();
    return;
  }
  if (route === "edit") {
    if (confirm("未保存の編集を破棄しますか？")) {
      draft = null;
      route = "detail";
      render();
    }
    return;
  }
  if (route === "gemini" || route === "nenga") {
    go("menu");
    return;
  }
  if (route === "detail" || route === "menu") {
    go("home");
    return;
  }
  toast("ホームボタンでアプリを閉じられます");
};
window.onNative = ({ type, data }) => {
  if (type === "state") {
    state = data;
    if (pendingOpen) {
      selected = pendingOpen;
      route = "detail";
      pendingOpen = "";
    }
    if (pendingEdit) {
      selected = pendingEdit;
      pendingEdit = "";
      if (getPerson()) return beginEdit();
    }
    if (route === "edit" && !getPerson()) draft = null;
    render();
  } else if (type === "busy") {
    $("busy").hidden = !data;
    $("busytext").textContent = data;
  } else if (type === "toast") toast(data);
  else if (type === "error") report(data);
  else if (type === "report") report(data);
  else if (type === "open") {
    // Inside a background task the new person only arrives with the next state push.
    if (editOnOpen && state.people.some((p) => p.id === data)) {
      editOnOpen = false;
      selected = data;
      beginEdit();
    } else if (state.people.some((p) => p.id === data)) openPerson(data);
    else pendingOpen = data;
  }
  else if (type === "review") pendingEdit = data;
  else if (type === "driveFolders") driveSheet(data);
  else if (type === "calendars") calendarSheet(data);
  else if (type === "queued") {
    route = route === "edit" ? route : "home";
    toast(
      data.ids.length +
        "人を保存しました。読み取り・登録はバックグラウンドで進めます" +
        (data.skipped ? `（重複スキップ ${data.skipped}）` : ""),
    );
    if (data.errors.length)
      report("取り込めなかったファイル：\n" + data.errors.join("\n"));
  }
  else if (type === "batchDone") {
    filter = "review";
    if (route !== "edit") route = "home";
    render();
    report(data);
  }
  else if (type === "home") go("home");
  else if (type === "saved") {
    draft = null;
    route = "detail";
    render();
    toast("保存しました");
  } else if (type === "imported") {
    if (data.ids.length === 1) pendingOpen = data.ids[0];
    else route = "home";
    const message =
      `${data.ids.length}人・${data.pages}ページを保存しました。` +
      (data.skipped ? `\n重複スキップ：${data.skipped}ファイル` : "");
    if (data.errors.length)
      report(
        message + "\n\n取り込めなかったファイル：\n" + data.errors.join("\n"),
      );
    else toast(message);
  } else if (type === "models") {
    modal(
      `<h2>利用可能なモデル</h2><p class="caption">名刺の読み取りには画像入力対応のFlash系モデルを選んでください。</p><select id="chooseModel">${data.map((m) => `<option value="${esc(m)}">${esc(m)}</option>`).join("")}</select><button class="primary full gap" onclick="settingsDraft.model=$('chooseModel').value;closeModal();render();toast('モデルを選択しました。保存を押してください')">このモデルを選択</button>`,
    );
  }
};
setInterval(() => {
  if ($("recordtimer") && state.recording) {
    let s = Math.floor((Date.now() - state.recordingStart) / 1000);
    $("recordtimer").textContent =
      Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  }
}, 1000);
render();
call("boot");
