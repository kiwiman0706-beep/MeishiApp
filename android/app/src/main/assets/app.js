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
  route = "detail";
  draft = null;
  noteDraft = "";
  editNoteId = "";
  closeModal();
  render();
  window.scrollTo(0, 0);
}
function nav() {
  return `<nav class="nav"><button class="${["home", "detail", "edit"].includes(route) ? "active" : ""}" onclick="go('home')">${icon("card")}名刺</button><button class="${route === "import" ? "active" : ""}" onclick="go('import')">${icon("plus")}取り込み</button><button class="${route === "settings" ? "active" : ""}" onclick="go('settings')">${icon("settings")}設定・出力</button></nav>`;
}
function render() {
  const page =
    route === "home"
      ? home()
      : route === "import"
        ? importPage()
        : route === "settings"
          ? settingsPage()
          : route === "edit"
            ? editPage()
            : detail();
  $("app").innerHTML = `<main class="shell">${page}</main>${nav()}`;
}
function header(sub = "人とのつながりを、手元に。") {
  return `<header class="top"><div class="brand"><div class="mark">${icon("card")}</div><div><div class="brandline">MEISHI NOTE</div><h1>名刺ノート</h1></div></div><span class="pill">プライベート</span></header><p class="muted" style="font-size:12px;margin-top:-6px;margin-bottom:17px">${sub}</p>`;
}
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
    return `<div class="empty"><div class="emptyart"><div class="miniCard back"></div><div class="miniCard"><i></i><i></i><i></i></div><div class="spark">${icon(state.people.length ? "search" : "plus")}</div></div><h2>${state.people.length ? "該当する名刺がありません" : "はじめての1枚を、ここへ。"}</h2><p>${state.people.length ? "検索条件を変えてみてください。" : "名刺も、その後の会話も。<br>写真を撮るだけで整理を始められます。"}</p>${state.people.length ? "" : `<button class="primary" onclick="go('import')">${icon("plus")}名刺を取り込む</button>`}</div>`;
  if (layout === "table")
    return `<div class="tablewrap"><table><thead><tr><th>氏名</th><th>会社・組織</th><th>部署・肩書き</th><th>電話</th><th>メール</th><th>状態</th></tr></thead><tbody>${a.map((p) => `<tr tabindex="0" role="button" onclick="openPerson('${p.id}')"><td>${esc(title(p))}</td><td>${esc(company(p))}</td><td>${esc([p.department, p.title].filter(Boolean).join(" / "))}</td><td>${esc(p.mobile || p.phone)}</td><td>${esc(p.email)}</td><td>${status(p)}</td></tr>`).join("")}</tbody></table></div><p class="caption">表は横にスクロールできます。CSVは設定・出力から保存できます。</p>`;
  return `<div class="cardlist">${a.map((p) => `<button class="person" onclick="openPerson('${p.id}')">${p.pages?.length ? `<img loading="lazy" class="thumb" src="${img(p.pages[0].file)}" alt="名刺">` : `<div class="initial">${esc(title(p).slice(0, 1))}</div>`}<div class="personbody"><h3 class="ell">${esc(title(p))}</h3><div class="company ell">${esc(company(p))}</div>${p.title ? `<div class="company ell">${esc(p.title)}</div>` : ""}<div class="personmeta"><span class="badge ${esc(p.status)}">${status(p)}</span><span>${p.pages?.length || 0}枚</span>${p.notes?.length ? `<span>メモ ${p.notes.length}</span>` : ""}${p.audio?.length ? `<span>録音 ${p.audio.length}</span>` : ""}</div></div><span class="chevron">${icon("next")}</span></button>`).join("")}</div>`;
}
function home() {
  let review = state.people.filter((p) => p.status === "review").length;
  return `${header()}<div class="stats"><div><small>保存した名刺</small><div><strong>${state.people.length}</strong> <small>人</small></div></div><div class="statRight"><small>確認待ち</small><div><strong>${review}</strong> <small>人</small></div></div></div>${recordBanner()}<div class="toolbar"><div class="search">${icon("search")}<input id="search" placeholder="氏名・会社・メモを検索" value="${esc(query)}" oninput="query=this.value;updateList()"></div><button class="iconbtn toggle ${layout === "table" ? "active" : ""}" onclick="layout=layout==='table'?'cards':'table';render()" aria-label="カード表示と表表示を切り替え">${icon(layout === "table" ? "card" : "table")}</button></div><div class="chips">${[
    ["all", "すべて"],
    ["new", "未認識"],
    ["review", "要確認"],
    ["confirmed", "確認済み"],
  ]
    .map(
      ([v, l]) =>
        `<button class="chip ${filter === v ? "active" : ""}" onclick="filter='${v}';render()">${l}</button>`,
    )
    .join(
      "",
    )}</div><div class="sectionline"><small id="count">${filtered().length} 件の名刺</small><select aria-label="並べ替え" onchange="sort=this.value;updateList()">${[
    ["newest", "追加日が新しい順"],
    ["updated", "更新日が新しい順"],
    ["name", "氏名順"],
    ["company", "会社順"],
  ]
    .map(
      ([v, l]) =>
        `<option value="${v}" ${sort === v ? "selected" : ""}>${l}</option>`,
    )
    .join(
      "",
    )}</select></div><div id="results">${listContent()}</div>${state.people.length ? `<button class="fab primary" onclick="go('import')">${icon("plus")}取り込む</button>` : ""}`;
}
function updateList() {
  $("results").innerHTML = listContent();
  $("count").textContent = filtered().length + " 件の名刺";
}
function recordBanner() {
  return state.recording
    ? `<div class="recording"><span><i class="pulse"></i>録音中 <span id="recordtimer"></span></span><button onclick="call('stopRecord')">${icon("stop")} 保存して停止</button></div>`
    : "";
}
function backbar(text, right = "") {
  return `<div class="backbar"><button onclick="handleBack()" aria-label="戻る">${icon("back")}</button><h2>${text}</h2>${right}</div>`;
}
function detail() {
  const p = getPerson();
  if (!p) {
    route = "home";
    return home();
  }
  let info = fields.filter(([k]) => p[k] && !["name", "kana"].includes(k));
  return `${backbar("名刺の詳細", `<button onclick="beginEdit()" aria-label="編集">${icon("edit")}</button>`)}${recordBanner()}<div class="detailsHead">${p.portrait ? `<img class="avatar" src="${img(p.portrait)}" alt="人物写真">` : `<div class="avatar">${esc(title(p).slice(0, 1))}</div>`}<div class="content"><small>${esc(p.kana)}</small><h1>${esc(title(p))}</h1><p class="muted">${esc(company(p))}</p><span class="badge ${esc(p.status)}">${status(p)}</span></div></div><div class="pages">${p.pages.map((pg, i) => `<div class="page"><img class="pageimg" src="${img(pg.file)}" alt="名刺 ${i + 1}ページ" onclick="viewImage(${i})"><div class="pagecontrols"><span class="pageLabel">${i === 0 ? "表面" : i === 1 ? "裏面" : "追加 " + (i + 1)}</span><div><button onclick="call('rotate',{id:selected,index:${i}})" aria-label="右に回転">${icon("rotate")}</button>${i > 0 ? `<button onclick="call('reorder',{id:selected,from:${i},to:${i - 1}})" aria-label="前へ移動">${icon("back")}</button>` : ""}<button onclick="removeItem('pages',${i})" aria-label="画像を外す">${icon("trash")}</button></div></div></div>`).join("") || `<div class="tip full">画像はまだありません。撮影するか、画像・PDFを追加できます。</div>`}</div><div class="actions"><button class="outline" onclick="scanSheet(selected)">${icon("scan")}スキャンで追加</button><button class="outline" onclick="call('pick',{id:selected,mode:'all'})">${icon("image")}画像・PDF追加</button><button class="primary" onclick="recognize()">${icon("spark")}Geminiで認識</button><button class="secondary" onclick="contactSheet()">${icon("user")}Google連絡先</button></div>${p.suggestion ? recognition(p) : ""}${p.warnings ? `<div class="tip warning gap">認識時の注意：${esc(p.warnings)}</div>` : ""}<div class="detailcols"><div><section class="panel"><div class="sectionline" style="margin-top:0"><h3>連絡先情報</h3><button class="link" onclick="beginEdit()">編集</button></div><div class="infogrid">${info.map(([k, l]) => `<div class="inforow"><span class="k">${l}</span><span class="v">${esc(p[k])}</span></div>`).join("") || '<p class="caption">認識結果を反映するか、手入力してください。</p>'}</div>${p.contactRawId ? `<div class="tip gap">登録先：${esc(p.contactAccount)}<br>登録後の編集はGoogle連絡先側で行ってください。<button class="link" onclick="call('viewContact',{id:selected})">登録した連絡先を開く</button></div>` : ""}</section><section class="panel"><h3>人物写真・会社ロゴ</h3><p class="caption">検索で候補を見つけ、保存した画像を選んで登録します。本人・会社を確認して選んでください。</p><div class="assetrow">${[
    ["portrait", "人物写真", "user"],
    ["logo", "会社ロゴ", "card"],
  ]
    .map(
      ([k, l, ic]) =>
        `<div class="assetbox"><small>${l}</small>${p[k] ? `<img src="${img(p[k])}" alt="${l}">` : `<div class="placeholder">${icon(ic)}</div>`}<button class="outline" onclick="call('search',{id:selected,kind:'${k}'})">${icon("search")}画像検索</button><button class="secondary" onclick="call('asset',{id:selected,kind:'${k}'})">画像を選択</button>${p[k] ? `<button class="danger" onclick="if(confirm('画像の登録を解除しますか？'))call('clearAsset',{id:selected,kind:'${k}'})">登録を解除</button>` : ""}</div>`,
    )
    .join(
      "",
    )}</div></section></div><div><section class="panel"><h3>やり取りのメモ <small>${p.notes.length}件</small></h3><textarea id="note" placeholder="お会いした場所、相談内容、次回の約束など" oninput="noteDraft=this.value">${esc(noteDraft)}</textarea><button class="secondary full gap" onclick="addNote()">${icon("plus")}${editNoteId ? "メモの変更を保存" : "メモを追加"}</button>${editNoteId ? `<button class="full" onclick="noteDraft='';editNoteId='';render()">編集をキャンセル</button>` : ""}${p.notes
    .map(
      (n, i) =>
        `<div class="note"><div class="notehead"><small>${esc(date(n.time))}</small><div><button onclick="editNote(${i})" aria-label="メモ編集">${icon("edit")}</button><button onclick="removeItem('notes',${i})" aria-label="メモ削除">${icon("trash")}</button></div></div><p class="notebody">${esc(n.text)}</p></div>`,
    )
    .reverse()
    .join(
      "",
    )}</section><section class="panel"><h3>会話の録音 <small>${p.audio.length}件</small></h3><p class="caption">相手の了承を得て録音してください。録音は端末に保存され、Geminiへは送信しません。</p><button class="${state.recording ? "outline" : "secondary"} full" onclick="${state.recording ? "call('stopRecord')" : "call('record',{id:selected})"}">${icon(state.recording ? "stop" : "mic")}${state.recording ? "保存して録音を停止" : "録音を開始"}</button>${p.audio
    .map(
      (a, i) =>
        `<div class="note"><div class="notehead"><div><small>${esc(date(a.time))}</small><div>${a.state === "recording" ? (state.recording === p.id ? "録音中" : "中断された録音") : `${Math.floor(a.seconds / 60)}分${a.seconds % 60}秒`}</div></div><div>${a.state !== "recording" || state.recording !== p.id ? `<button onclick="call('play',{id:selected,index:${i}})" aria-label="録音を再生">${icon("play")}</button>` : ""}<button onclick="removeItem('audio',${i})" aria-label="録音を削除">${icon("trash")}</button></div></div></div>`,
    )
    .reverse()
    .join(
      "",
    )}</section></div></div>${p.rawText ? `<details class="panel"><summary>認識した全文を見る</summary><p class="pre gap">${esc(p.rawText)}</p></details>` : ""}<div class="row gap"><button class="outline" onclick="mergeSheet()">別の名刺を統合</button><button class="danger" onclick="deletePerson()">この名刺を削除</button></div><p class="footnote">画像の原本もバックアップに含まれます。</p>`;
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
    report("設定・出力でGemini APIキーを登録してください。");
    return;
  }
  if (
    confirm(
      "この人の名刺画像をGeminiへ送信して読み取ります。APIの利用料金が発生する場合があります。続けますか？",
    )
  )
    call("recognize", { id: selected });
}
function recognition(p) {
  return `<section class="panel" style="background:var(--amber);border-color:#e5d7ba"><h3>${icon("spark")} 認識結果を確認</h3><p class="caption">原画像と照合してから反映してください。既存の連絡先情報は、この結果で置き換わります。</p><div class="reviewgrid">${fields
    .filter(([k]) => p.suggestion[k])
    .map(
      ([k, l]) =>
        `<div class="reviewrow"><small>${l}</small><span>${esc(p.suggestion[k])}</span></div>`,
    )
    .join(
      "",
    )}</div>${p.suggestion.warnings ? `<p class="pre gap">注意：${esc(p.suggestion.warnings)}</p>` : ""}<div class="row gap"><button class="primary" onclick="call('applyRecognition',{id:selected})">反映して編集</button><button class="outline" onclick="if(confirm('この認識結果を破棄しますか？'))call('discardRecognition',{id:selected})">破棄</button></div></section>`;
}
function beginEdit() {
  draft = JSON.parse(JSON.stringify(getPerson()));
  route = "edit";
  render();
  window.scrollTo(0, 0);
}
function editPage() {
  if (!draft) {
    route = "detail";
    return detail();
  }
  return `${backbar("連絡先を編集")}<p class="caption">名刺の原画像と見比べて、氏名・電話番号・メールを確認してください。</p>${draft.pages.length ? `<img src="${img(draft.pages[0].file)}" class="pageimg" alt="名刺の表面">` : ""}<div class="panel"><div class="grid2">${fields.map(([k, l]) => `<label class="${["company", "department", "title", "email", "address", "website", "tags", "metPlace"].includes(k) ? "span2" : ""}"><span class="label">${l}</span>${["email", "phone", "mobile", "fax", "address"].includes(k) ? `<textarea style="min-height:${k === "address" ? 90 : 55}px" rows="${k === "address" ? 2 : 1}" oninput="draft['${k}']=this.value">${esc(draft[k])}</textarea>` : `<input type="${k === "metDate" ? "date" : "text"}" value="${esc(draft[k])}" oninput="draft['${k}']=this.value" ${k === "tags" ? 'placeholder="例：医療、取引先、学会"' : ""}>`}</label>`).join("")}</div><p class="caption">複数の電話・メールは改行で区切れます。</p></div><div class="fixedsave"><button class="primary full" onclick="savePerson(true)">${icon("check")}確認済みで保存</button><button class="full" onclick="savePerson(false)">確認を保留して保存</button></div>`;
}
function savePerson(confirmed) {
  if (confirmed && !draft.name.trim()) {
    toast("氏名を入力してください");
    return;
  }
  draft.status = confirmed ? "confirmed" : "review";
  call("save", { id: selected, person: draft });
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
function viewImage(i) {
  const pg = getPerson().pages[i];
  modal(
    `<img src="${img(pg.file)}" alt="名刺拡大"><p style="font-size:12px">${esc(pg.name)}<br>ピンチで拡大できます。</p><button class="outline full gap" onclick="call('shareImage',{id:selected,index:${i}})">${icon("share")}画像を共有・外部アプリで開く</button>`,
    true,
  );
}
function contactSheet() {
  const p = getPerson();
  if (p.status !== "confirmed") {
    report(
      "認識結果を反映し、編集画面で「確認済みで保存」してから登録してください。",
    );
    return;
  }
  modal(
    `<h2>Google連絡先に登録</h2><p class="caption">写真欄に使う画像を選びます。着信時の表示は端末・電話アプリによって異なり、丸く切り抜かれる場合があります。</p><label class="label">連絡先の写真</label><select id="contactphoto"><option value="card" ${!p.pages.length ? "disabled" : ""}>名刺の表面</option><option value="portrait" ${!p.portrait ? "disabled" : ""}>登録した人物写真</option><option value="logo" ${!p.logo ? "disabled" : ""}>登録した会社ロゴ</option><option value="none" ${!p.pages.length ? "selected" : ""}>画像なし</option></select><p class="tip gap">画像・電話・メール・会社・肩書き等を登録します。やり取りのメモや録音は連絡先に送りません。</p><button class="primary full gap" onclick="const photo=$('contactphoto').value;closeModal();call('contacts',{id:selected,photo})">Googleアカウントを選んで登録</button>${p.contactRawId ? '<p class="caption">登録済みの場合は、その連絡先を開きます。自動更新はしません。</p>' : ""}`,
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
function grouping() {
  return `<label class="label">取り込む画像のまとめ方</label><select id="grouping" onchange="mode=this.value">${[
    ["file", "1ファイル = 1人（複数ページPDFも1人）"],
    ["pair", "2ページずつ = 1人（表・裏）"],
    ["page", "1ページ = 1人"],
    ["all", "選んだ全ページ = 1人"],
  ]
    .map(
      ([v, l]) =>
        `<option value="${v}" ${mode === v ? "selected" : ""}>${l}</option>`,
    )
    .join(
      "",
    )}</select><p class="caption">2ページずつの場合はファイル名順、PDFはページ順で組み合わせます。複数人を1枚に並べた画像は、先に1名刺ずつ分割してください。</p>`;
}
function scanSheet(id = "") {
  const target = state.people.find((p) => p.id === id);
  modal(`<h2>${icon("scan")} スキャン撮影</h2>
    <p class="caption">名刺を1枚ずつカメラに映してください。輪郭を検出して切り抜き・台形補正します。</p>
    <div class="tip"><div class="step"><b>1</b><span>表面を撮影し、続けて裏面を追加。</span></div><div class="step"><b>2</b><span>切り抜き範囲・向き・ページ順を確認。</span></div><div class="step"><b>3</b><span>スキャン画面で完了すると画像を保存。</span></div></div>
    ${target ? `<p class="gap"><b>${esc(title(target))}</b> にすべてのページを追加します。</p>` : `<label class="label">スキャンしたページのまとめ方</label><select id="scanGrouping"><option value="all">全ページ = 1人（表・裏・追加ページ）</option><option value="page">1ページ = 1人（片面をまとめ撮り）</option><option value="pair">2ページずつ = 1人（表→裏の順）</option></select><p class="caption">表裏をまとめ撮りする場合は、1人目の表→裏→2人目の表→裏の順に撮影します。</p>`}
    <button id="startScan" class="primary full gap">${icon("scan")}スキャンを開始</button>
    <button id="normalCamera" class="outline full gap">${icon("camera")}通常のカメラで1枚撮影</button>
    <p class="caption">1回20ページまで。初回は通信とGoogle Play開発者サービスが必要です。補正後の画像を保存します。Gemini認識は保存後に実行できます。</p>`);
  $("startScan").onclick = () => {
    const scanMode = target ? "all" : $("scanGrouping").value;
    closeModal();
    call("scan", { id, mode: scanMode });
  };
  $("normalCamera").onclick = () => { closeModal(); call("camera", { id }); };
}
function importPage() {
  return `${backbar("名刺を取り込む")}<p class="muted" style="font-size:13px;margin:12px 0 20px">表も、裏も、出会いの記録も。</p><button class="importtile" onclick="scanSheet('')"><div class="tileicon">${icon("scan")}</div><div class="tilebody"><h3>スキャン撮影</h3><small>輪郭を検出して切り抜き・台形補正。表裏を続けて撮影。</small></div>${icon("next")}</button><div class="panel">${grouping()}</div><div class="importGrid"><button class="importtile" onclick="call('pick',{mode})"><div class="tileicon">${icon("image")}</div><div class="tilebody"><h3>画像・PDFを選択</h3><small>写真やスキャンをまとめて取り込む</small></div>${icon("next")}</button><button class="importtile" onclick="call('folder',{mode})"><div class="tileicon">${icon("folder")}</div><div class="tilebody"><h3>ScanSnapフォルダ</h3><small>フォルダ内の画像・PDFを一括取り込み</small></div>${icon("next")}</button></div>${state.folder ? `<button class="secondary full" onclick="call('rescan',{mode})">${icon("folder")}前回のフォルダから新しい名刺を取り込む</button><p class="caption">同じ内容のファイルはスキップします。自動監視ではなく、ボタンを押した時に確認します。</p>` : ""}<div class="panel"><h3>まとめて読み取る</h3><p class="caption">保存した「未認識」の名刺をGeminiで順番に認識します。結果は「要確認」に並びます。</p><button class="primary full" onclick="batchRecognize()">${icon("spark")}未認識の名刺を一括認識</button></div><div class="tip"><b>ScanSnapでの使い方</b><div class="step"><b>1</b><span>ScanSnap側で画像またはPDFを専用フォルダへ保存。</span></div><div class="step"><b>2</b><span>Androidから選べるフォルダにコピー・同期しておきます。PC/NASへの直接接続機能はありません。</span></div><div class="step"><b>3</b><span>フォルダとまとめ方を選んで取り込み、Geminiで一括認識。</span></div>PDFは1ファイル100ページ、1回200ファイルまで。1人のGemini認識は20ページまでです。</div><button class="outline full gap" onclick="call('new')">${icon("edit")}画像なしで手入力する</button>`;
}
function batchRecognize() {
  if (!state.keySet) {
    report("設定・出力でGemini APIキーを登録してください。");
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
        "人分の名刺画像をGeminiに送信します。APIの利用料金が発生する場合があります。認識中はアプリを開いておいてください。",
    )
  )
    call("recognizeAll");
}
function settingsPage() {
  if (!settingsDraft)
    settingsDraft = { key: "", model: state.model, format: state.format };
  return `${backbar("設定・出力")}<section class="panel"><div class="subheading">Recognition</div><h3 class="gap">Geminiで名刺を読み取る</h3><p class="caption">APIキーはこの端末に暗号化して保存します。バックアップには含めません。</p><label class="label">Gemini APIキー ${state.keySet ? "・登録済み" : ""}</label><input type="password" autocomplete="off" value="${esc(settingsDraft.key)}" placeholder="${state.keySet ? "変更する時だけ入力" : "AIza…"}" oninput="settingsDraft.key=this.value"><label class="label">モデル名</label><input id="model" list="modelslist" value="${esc(settingsDraft.model)}" oninput="settingsDraft.model=this.value" autocomplete="off"><datalist id="modelslist"></datalist><p class="caption">利用可能なモデルはAPIキーごとに異なります。保存後に一覧を取得して選べます。</p><label class="label">API互換設定（通常は変更不要）</label><select onchange="settingsDraft.format=this.value"><option value="legacy" ${settingsDraft.format === "legacy" ? "selected" : ""}>標準</option><option value="modern" ${settingsDraft.format === "modern" ? "selected" : ""}>新しいモデル向け</option></select><p class="caption">通常は標準のままで利用できます。新しいモデルで認識時にエラーが出る場合に切り替えてください。</p><div class="row gap"><button class="primary" onclick="saveSettings()">設定を保存</button><button class="outline" onclick="call('models')">モデル一覧</button></div>${state.keySet ? `<button class="danger full gap" onclick="if(confirm('保存しているAPIキーを削除しますか？'))call('settings',{clearKey:true,model:settingsDraft.model,format:settingsDraft.format})">APIキーを削除</button>` : ""}<div class="tip gap">認識を実行した時に名刺画像をGoogleのGemini APIへ送信します。APIの料金・データ利用条件は、ご自身のプロジェクト設定をご確認ください。</div></section><section class="panel"><div class="subheading">Export</div><h3 class="gap">表形式で書き出す</h3><p class="caption">全名刺の連絡先・メモ・画像と録音のファイル名を、Excelで開けるUTF-8 CSVに出力します。画像そのものはZIPバックアップに含まれます。</p><button class="secondary full" onclick="call('export',{kind:'csv'})">${icon("table")}一覧をCSVで保存</button></section><section class="panel"><div class="subheading">Backup & restore</div><h3 class="gap">Google Driveにバックアップ</h3><p class="caption">名刺データ・画像原本・メモ・録音をZIPにまとめて保存します。手動バックアップです。端末間の自動同期は行いません。</p><div class="stack"><button class="primary full" onclick="call('export',{kind:'zip'})">${icon("cloud")}保存先を選んでバックアップ</button><button class="outline full" onclick="call('export',{kind:'zip',share:true})">${icon("share")}Driveなどに共有して保存</button><button class="outline full" onclick="restoreConfirm()">${icon("download")}バックアップZIPから復元</button></div><div class="tip gap">保存先の選択画面で「Google Drive」を選んでください。見当たらない場合はDriveアプリにログイン後、「共有して保存」をお試しください。ZIPは暗号化されていないため、ご自身の非公開フォルダに保存してください。</div><p class="caption">バックアップは展開後2GBまで対応します。復元は未登録のIDだけを追加します。同じ名刺IDの現在の内容は上書きしません。APIキーは再入力が必要です。復元後、Google連絡先への再登録は重複にご注意ください。</p></section><section class="panel"><h3>このバージョンについて</h3><p class="caption">名刺ノート 0.2.0 ・ Android 8以上<br>端末内保存 / SNS・利用者ネットワークなし<br>名刺・メモ・録音はアンインストールで消えます。定期的にバックアップしてください。</p><p class="caption">人物写真・ロゴは検索ページを開いて手動で選択します。自動取得や人物の自動照合は行いません。画像は元の提供者の利用条件に従ってご利用ください。</p></section>`;
}
function saveSettings() {
  call("settings", { ...settingsDraft });
  settingsDraft.key = "";
}
function restoreConfirm() {
  if (
    confirm(
      "バックアップZIPから未登録の名刺を追加します。同じIDの名刺はスキップされ、現在のデータは上書きしません。続けますか？",
    )
  )
    call("restore");
}
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
  if (route === "detail" || route === "import" || route === "settings") {
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
      draft = JSON.parse(JSON.stringify(getPerson()));
      route = "edit";
    }
    render();
  } else if (type === "busy") {
    $("busy").hidden = !data;
    $("busytext").textContent = data;
  } else if (type === "toast") toast(data);
  else if (type === "error") report(data);
  else if (type === "report") report(data);
  else if (type === "open") openPerson(data);
  else if (type === "home") go("home");
  else if (type === "saved") {
    draft = null;
    route = "detail";
    render();
    toast("保存しました");
  } else if (type === "recognized") {
    pendingOpen = data;
  } else if (type === "applied") {
    selected = data;
    beginEdit();
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
      `<h2>利用可能なモデル</h2><p class="caption">名刺認識には画像入力対応のFlash系モデルを選んでください。下記はAPIが返したgenerateContent対応モデルです。</p><select id="chooseModel">${data.map((m) => `<option value="${esc(m)}">${esc(m)}</option>`).join("")}</select><button class="primary full gap" onclick="settingsDraft.model=$('chooseModel').value;closeModal();render();toast('モデルを選択しました。設定を保存してください')">このモデルを選択</button>`,
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
