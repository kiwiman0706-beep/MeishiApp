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
  company = (p) => p.company || p.suggestion?.company || "会社・組織 未登録",
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
  const onMenu = ["menu", "gemini"].includes(route);
  return `<nav class="nav"><button class="${onList ? "active" : ""}" onclick="go('home')">${icon("card")}名刺</button><button class="shutter" onclick="shutter()" aria-label="名刺を撮影">${icon("camera")}<span>撮影</span></button><button class="${onMenu ? "active" : ""}" onclick="go('menu')">${icon("menu")}メニュー</button></nav>`;
}
function render() {
  const page =
    route === "home"
      ? home()
      : route === "menu"
        ? menuPage()
        : route === "gemini"
          ? geminiPage()
          : route === "edit"
            ? editPage()
            : detail();
  $("app").innerHTML = `<main class="shell">${page}</main>${nav()}`;
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
        (filter === "all" || p.status === filter) &&
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
  if (layout === "table")
    return `<div class="tablewrap"><table><thead><tr><th>氏名</th><th>会社・組織</th><th>部署・肩書き</th><th>電話</th><th>メール</th><th>状態</th></tr></thead><tbody>${a.map((p) => `<tr tabindex="0" role="button" onclick="openPerson('${p.id}')"><td>${esc(title(p))}</td><td>${esc(company(p))}</td><td>${esc([p.department, p.title].filter(Boolean).join(" / "))}</td><td>${esc(p.mobile || p.phone)}</td><td>${esc(p.email)}</td><td>${status(p)}</td></tr>`).join("")}</tbody></table></div><p class="caption">表は横にスクロールできます。CSVはメニューから書き出せます。</p>`;
  return `<div class="cardlist">${a.map((p) => `<button class="person" onclick="openPerson('${p.id}')">${p.pages?.length ? `<img loading="lazy" class="thumb" src="${img(p.pages[0].file)}" alt="名刺">` : `<div class="initial">${esc(title(p).slice(0, 1))}</div>`}<div class="personbody"><h3 class="ell">${esc(title(p))}</h3><div class="company ell">${esc(company(p))}</div><div class="personmeta">${p.status === "confirmed" ? "" : `<span class="badge ${esc(p.status)}">${status(p)}</span>`}${p.contactRawId ? `<span class="badge confirmed">連絡先登録済み</span>` : ""}${p.notes?.length ? `<span>メモ ${p.notes.length}</span>` : ""}${p.audio?.length ? `<span>録音 ${p.audio.length}</span>` : ""}</div></div><span class="chevron">${icon("next")}</span></button>`).join("")}</div>`;
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
  ]
    .filter(([v, , n]) => v === "all" || n || filter === v)
    .map(
      ([v, l, n]) =>
        `<button class="chip ${filter === v ? "active" : ""} ${v === "review" ? "attn" : ""}" onclick="filter='${v}';render()">${l} ${n}</button>`,
    )
    .join("");
  const drive = state.driveFolderName
    ? `<button class="setup drive" onclick="call('driveImport')">${icon("cloud")}<span><b>Google Driveから取り込む</b><small>${esc(state.driveFolderName)} の新しい名刺</small></span>${icon("next")}</button>`
    : "";
  return `<header class="top"><div class="brand"><div class="mark">${icon("card")}</div><div><div class="brandline">MEISHI CLAUDE</div><h1>名刺ノート</h1></div></div></header>${recordBanner()}${setup}${drive}<div class="toolbar"><div class="search">${icon("search")}<input id="search" placeholder="氏名・会社・メモを検索" value="${esc(query)}" oninput="query=this.value;updateList()"></div><button class="iconbtn toggle ${layout === "table" ? "active" : ""}" onclick="layout=layout==='table'?'cards':'table';render()" aria-label="カード表示と表表示を切り替え">${icon(layout === "table" ? "card" : "table")}</button></div>${state.people.length ? `<div class="chips">${chips}</div><div class="sectionline"><small id="count">${filtered().length} 件</small><select aria-label="並べ替え" onchange="sort=this.value;updateList()">${[
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
  if (p.suggestion)
    return `<button class="primary full" onclick="beginEdit()">${icon("check")}読み取り結果を確認して保存</button>`;
  if (!p.pages.length && p.status !== "confirmed")
    return `<button class="primary full" onclick="scanAdd()">${icon("camera")}名刺を撮影して追加</button>`;
  if (p.status === "new")
    return `<button class="primary full" onclick="recognize()">${icon("spark")}Geminiで読み取る</button>`;
  if (p.status === "review")
    return `<button class="primary full" onclick="beginEdit()">${icon("check")}内容を確認して保存</button>`;
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
  const label = (i) => (i === 0 ? "表面" : i === 1 ? "裏面" : "追加 " + (i + 1));
  return `${backbar("", `<button onclick="beginEdit()" aria-label="編集">${icon("edit")}</button><button onclick="detailMenu()" aria-label="その他の操作">${icon("more")}</button>`)}${recordBanner()}${
    p.pages.length
      ? `<div class="pages">${p.pages.map((pg, i) => `<button class="page" onclick="viewImage(${i})"><img class="pageimg" src="${img(pg.file)}" alt="名刺 ${label(i)}"><span class="pageLabel">${label(i)}</span></button>`).join("")}</div>`
      : ""
  }<div class="detailsHead">${p.portrait ? `<img class="avatar" src="${img(p.portrait)}" alt="人物写真">` : p.logo ? `<img class="avatar logo" src="${img(p.logo)}" alt="会社ロゴ">` : `<div class="avatar">${esc(title(p).slice(0, 1))}</div>`}<div class="content"><small>${esc(p.kana)}</small><h1>${esc(title(p))}</h1><p class="muted">${esc(company(p))}${p.title ? " ・ " + esc(p.title) : ""}</p>${p.status === "confirmed" ? "" : `<span class="badge ${esc(p.status)}">${status(p)}</span>`}${p.contactRawId ? `<span class="badge confirmed">連絡先登録済み</span>` : ""}</div></div><div class="gap">${primaryAction(p)}</div>${p.warnings ? `<div class="tip warning gap">読み取り時の注意：${esc(p.warnings)}</div>` : ""}<div class="detailcols"><div><section class="panel"><h3>連絡先情報</h3><div class="infogrid">${info.map(([k, l]) => infoRow(k, l, p[k])).join("") || '<p class="caption">まだ情報がありません。読み取るか、右上の編集から入力してください。</p>'}</div></section></div><div><section class="panel"><h3>やり取りのメモ <small>${p.notes.length}件</small></h3><textarea id="note" placeholder="お会いした場所、相談内容、次回の約束など" oninput="noteDraft=this.value">${esc(noteDraft)}</textarea><div class="row gap"><button class="secondary" onclick="addNote()">${icon("plus")}${editNoteId ? "変更を保存" : "メモを追加"}</button>${editNoteId ? `<button class="noflex" onclick="noteDraft='';editNoteId='';render()">キャンセル</button>` : ""}<button class="${state.recording ? "danger" : "outline"} noflex" onclick="${state.recording ? "call('stopRecord')" : "call('record',{id:selected})"}" aria-label="${state.recording ? "録音を停止" : "録音を開始"}">${icon(state.recording ? "stop" : "mic")}${state.recording ? "停止" : "録音"}</button></div>${timeline(p)}</section></div></div><p class="footnote">録音は相手の了承を得て行ってください。端末に保存され、Geminiへは送信しません。</p>`;
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
      p.pages.length ? menuRow("spark", "Geminiで読み取り直す", "結果は保存前に確認できます", "closeModal();recognize()") : "",
      p.status === "confirmed" && !p.contactRawId ? menuRow("user", "写真を選んで連絡先に登録", "名刺・人物写真・ロゴから選択", "contactSheet()") : "",
      menuRow("user", "人物写真・会社ロゴ", p.portrait || p.logo ? "登録済み" : "画像検索して登録", "assetSheet()"),
      state.people.length > 1 ? menuRow("link", "別の名刺と統合", "同じ人の名刺をまとめる", "mergeSheet()") : "",
      p.rawText ? menuRow("list", "読み取った全文を見る", "", "report(getPerson().rawText)") : "",
      menuRow("trash", "この名刺を削除", "Google連絡先は削除されません", "closeModal();deletePerson()", "dangerrow"),
    ].join("")}</div>`,
  );
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
  return `${backbar(draft.fromSuggestion ? "読み取り結果の確認" : "連絡先を編集")}${draft.pages.length ? `<div class="pages compact">${draft.pages.map((pg, i) => `<button class="page" onclick="viewImage(${i},true)"><img class="pageimg" src="${img(pg.file)}" alt="名刺 ${i + 1}ページ"></button>`).join("")}</div>` : ""}${draft.fromSuggestion ? `<div class="tip gap">${icon("spark")} Geminiの読み取り結果です。画像と見比べて、氏名・電話・メールを確認してください。<button class="link" onclick="discardSuggestion()">結果を破棄</button></div>` : ""}${warn ? `<div class="tip warning gap">注意：${esc(warn)}</div>` : ""}<div class="panel gap"><div class="grid2">${fields.map(([k, l]) => `<label class="${["company", "department", "title", "email", "address", "website", "tags", "metPlace"].includes(k) ? "span2" : ""}"><span class="label">${l}</span>${["email", "phone", "mobile", "fax", "address"].includes(k) ? `<textarea style="min-height:${k === "address" ? 90 : 55}px" rows="${k === "address" ? 2 : 1}" oninput="draft['${k}']=this.value">${esc(draft[k])}</textarea>` : `<input type="${k === "metDate" ? "date" : "text"}" value="${esc(draft[k])}" oninput="draft['${k}']=this.value" ${k === "tags" ? 'placeholder="例：医療、取引先、学会"' : ""}>`}</label>`).join("")}</div><p class="caption">複数の電話・メールは改行で区切れます。</p></div><div class="fixedsave">${registered ? `<button class="primary full" onclick="savePerson(false)">${icon("check")}保存</button><p class="caption">Google連絡先には反映されません。連絡先アプリで編集してください。</p>` : `<button class="primary full" onclick="savePerson(true)">${icon("user")}保存して連絡先に登録</button><button class="outline full" onclick="savePerson(false)">保存のみ</button>${state.contactAccount ? `<p class="caption center">登録先：${esc(state.contactAccount)}</p>` : ""}`}</div>`;
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
  if (
    confirm(
      "この名刺とメモ・録音の登録を削除しますか？Google連絡先は削除されません。復旧にはバックアップが必要です。",
    )
  )
    call("delete", { id: selected });
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
      menuRow("image", "画像・PDFから取り込む", "写真やスキャンをまとめて", "importSheet('pick')"),
      menuRow("folder", "ScanSnapフォルダから取り込む", "フォルダ内を一括で", "importSheet('folder')"),
      state.folder ? menuRow("rotate", "前回のフォルダを再確認", "新しい名刺だけ取り込む", "importSheet('rescan')") : "",
      menuRow("camera", "通常のカメラで撮影", "スキャン画面が使えない場合", "call('camera',{id:'',auto:true})"),
      menuRow("edit", "手入力で追加", "画像なしで登録", "manualAdd()"),
    ],
  )}${section("読み取り", [
    menuRow("spark", "未認識をまとめて読み取る", unread ? unread + "件の名刺が未認識" : "未認識の名刺はありません", "batchRecognize()"),
  ])}${section("書き出し・バックアップ", [
    menuRow("table", "一覧をCSVで書き出す", "Excelで開けるUTF-8 CSV", "call('export',{kind:'csv'})"),
    menuRow("cloud", "バックアップ・復元", "Google DriveへZIP保存", "backupSheet()"),
  ])}${section("設定", [
    menuRow("spark", "Gemini APIキー・モデル", state.keySet ? "登録済み ・ " + esc(state.model) : "未設定", "go('gemini')", state.keySet ? "" : "attnrow"),
    `<label class="mrow"><span class="mi">${icon("camera")}</span><span class="mt"><b>取り込み後に自動で読み取る</b><small>撮影・Drive・ファイルの取り込み後すぐGeminiへ送信</small></span><input type="checkbox" class="switch" ${state.autoRecognize !== false ? "checked" : ""} onchange="call('settings',{autoRecognize:this.checked})"></label>`,
    menuRow("user", "連絡先の登録設定", (state.contactAccount || "アカウント未選択") + " ・ 写真：" + ({ card: "名刺", portrait: "人物写真", logo: "ロゴ", none: "なし" }[state.contactPhoto || "card"]), "contactSettingsSheet()"),
  ])}<p class="footnote">Meishi-Claude 0.4.0 ・ Android 8以上<br>端末内保存 / 利用者ネットワークなし。アンインストールでデータが消えるため、定期的にバックアップしてください。</p>`;
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
  if (route === "gemini") {
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
