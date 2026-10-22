# 開発引き継ぎ

## 0.18.0での変更（名刺のQRコード・vCardで共有）

- QRコード：読み取りの前に端末で解読（Android は ML Kit のバーコード読み取り（Play services、マニフェストの `com.google.mlkit.vision.DEPENDENCIES=barcode` で先に取得）、使えなければ ZXing。デスクトップ・Web は jsQR、`imaging.rgba`）。浮き出し・影のある印刷は ZXing では読めないことがあった（実物の名刺で確認）ため ML Kit を先にした。1600/1000/2400px で試す。
- 解読した文字は Gemini へ「QRコードの内容」として渡し（`Qr.hint` / `qr.hint`）、結果を `Qr.apply` / `qr.apply` で補正：メールは同じもの・3文字以内の違いなら QR の文字に置き換え、無ければ追加。電話は数字が同じものが無ければ種類（携帯・FAX）ごとに追加。URL・氏名・会社などは空欄だけ埋める。全文に `[QRコード] …` を足す（検索できる）。vCard・MECARD・MATMSG・mailto・tel・URL を解釈。
- vCard：`VCard.java` / `desktop/lib/vcard.js`（同じバイト列、`desktop/test/fixtures/contacts.vcf`）。3.0、CRLF、75バイトで折り返し、読み（`X-PHONETIC-*`）、お店は `X-ABShowAs:COMPANY`。アクション `vcard {ids}`：Android は共有シート（`text/x-vcard`、FilesProvider の `?name=` で受け取る側のファイル名を日本語に）、Web は `navigator.share`（タップの中で開くため `WebBackend.handle` で先に処理）、デスクトップは保存してフォルダを開く。
- 画面：名刺の「⋯」に「vCardで送る」、一覧の「選択」（`picking`、選択中はタップで選ぶ、「表示中をすべて」、下のバー）。

## 0.17.0での変更（手書きメモ）

- 共通UIの `inkOpen` / `inkSave`（全画面のパッド、`#inkpad` は `#app` の外なので再描画で消えない）。Pointer Events：ペンは筆圧で太さ（`0.35+1.1×pressure`）、`getCoalescedEvents` で細かく。ペンを一度使うと「指でも書く」がオフになり、指・手のひらは紙をスクロール（`touch-action:none` のまま自前でスクロール）。Sペンのサイドボタン（`buttons&2`）・Windowsペンの消しゴム側（`buttons&32` / `button 5`）で消す。元に戻す・やり直す・下に書き足す。書き足しは保存済みPNGを下地に描く。
- 保存は書いた範囲だけを白地のPNG（幅1600px以内）にして `inkNote {id, noteId?, png(base64), read}`。`notes[].ink` に画像名。Android `MainActivity`、デスクトップ・Web `Backend` に同じ処理。`read` か「文字にする」（`inkRead`）でGemini（`INK_PROMPT`、`Gemini.ink` / `gemini.generate(…, "ink", …, "image/png")`）が `text` に書き起こす（検索対象になる）。
- 画像の参照：`Sync.media` / `Store.referenced` / `model.media` / バックアップの名前の付け替えに `notes[].ink` を追加（無いと同期されず、掃除で消える）。

## 0.16.0での変更（シリーズの設定の引き継ぎ）

- 仕様は `docs/THETA_SHARED_SETTINGS.md`（§6に各版の対応表）。規則は `desktop/lib/series.js` と `SeriesSettings.java`（同じテスト）、Meishi側の配線は `Series.java`（Android）と `Backend.series*`（デスクトップ・Web共通、Webは `WebBackend` で上書き）。
- `.theta/settings.json`：Android/WebはDrive同期の後（`ProcessingService` → `Series.run`、`WebBackend.sync` → `seriesSync(true)`、Drive上は `DriveVault.settingsFile`）、デスクトップは起動時と設定の変更時（Vaultフォルダのファイル）。読めないファイルは書き換えず `seriesError`。
- 端末内の状態：`series`（最後の文書・`follow`・`decided`）、`seriesOffer`（引き継ぎ画面の待ち）。`seriesUses` / `Series.USES` が画面に出す項目。
- APIキーの貸し借り：`SeriesProvider`（権限 `jp.yaegaki.theta.permission.SHARE`、signature）。`state.keyLenders` → `importKey`。
- 画面：`seriesBanner` / `seriesOfferSheet` / `seriesSheet`、Geminiページの「○○ のキーを使う」。

## 0.15.4での変更（ロゴの切り出しの補正）

- Geminiのロゴ枠を画像で補正（`Logo.refine` / `model.refineLogoRect`、同じ処理）：枠の周り（枠と同じ大きさ、最低でも名刺の8%）を探索範囲とし、その境界で最も多い色を背景とみなす。背景と色差が大きい画素を「インク」として、各辺の外側にインクが続く間は広げ、余白を詰め、最後に6%＋2pxの余白。背景の隙間で止まるので隣の文字は入らない。画素が無ければGeminiの枠のまま。`imaging.crop(bytes, rectOf, max, refine)`（デスクトップは `nativeImage.toBitmap`、Webはcanvas）。
- 補正後の切り出しは `logoSource: "card2"`、以前のものは `"card"`。「過去の名刺を最新の状態にする」で `card` の会社を `relogo`（`Logo.recut` / `model.recutPicks`）。`card*` はサイトのロゴ（`web:`）を置き換える。プロンプトに「名刺の会社のロゴを選ぶ・普通の文字の社名は囲まない・全体を囲む」。

## 0.15.1での変更（過去データの更新を1つに）

- メニュー「過去の名刺を最新の状態にする」（`upgradeAll`）：ロゴの無い会社（`Logo.pick` / `model.logoPicks`）と読み仮名が空の名刺（`needsKana`）をまとめてキューへ。Androidのキューは連結できる（`"kana,logo"`：先頭の工程を処理し、残りを次に回す。読み仮名の後に連絡先更新が入れば `"contact,logo"`）。件数は `state.upgradeLogos` / `kanaMissing`、費用の目安はロゴ0.5円・読み0.05円で計算。デスクトップ・Webはロゴだけ（Webは名刺からの切り出しのみ）。

## 0.15.0での変更（ロゴの自動化・Web版・Androidの自動更新）

- Androidの自動更新：`UpdateCheck` が新しい版を見つけるとWi-FiならAPKを `files/updates/` へダウンロード（`UpdateInstaller.download`、Releaseのdigestで検証、URLはこのリポジトリのReleaseに限定）。お知らせを押すと `PackageInstaller` でインストール（未許可なら「不明なアプリ」の設定画面へ）。`setRequireUserAction(NOT_REQUIRED)` と `UPDATE_PACKAGES_WITHOUT_USER_ACTION` で、Android 12以降・自分がインストール元のときは `onStop`（画面を離れた時、録音中・処理中でなければ）に確認なしで入れる。失敗理由は `updateError` としてお知らせに出す。

- ロゴ：`CARD_KEYS` に `logoBox`。読み取りと同じ回答でロゴ位置が来るので、読み取り直後に切り出す（`Pipeline` / デスクトップ `work`）。名刺に無ければ会社サイト（`WebLogo.java` / `desktop/lib/weblogo.js`：Web欄→会社メールのドメイン→（まとめて処理の時だけ）`Gemini.officialSite` のGoogle検索）。構造化データのlogo・apple-touch-icon・最大のicon・既定の場所の順、SVG除外、32px未満除外、PNG保存。出どころは `logoSource`（Vaultでは `logo_source`：`card` / `web:<ドメイン>`、無し＝手動）。名刺のロゴはサイトのロゴを置き換え、手動は置き換えない（`Logo.share` / `shareLogo`）。
- メディアの読み書きはストア経由（`readMedia` / `writeMedia`）、取り込みの入力はパスか `{name, read()}`。デスクトップの `Backend` は `platform` / `caps` / `webLogos` を受け取り、読み取り後のフック `afterRecognized` を持つ（Web版が連絡先登録に使う）。共通UIのパスは相対（`media/…`、`lib/…`）。
- Web版（`web/`）：`build.js` が共通UI・ブリッジ（`src/main.js` をesbuildで。`fs`/`path`/`crypto` は小さな代替、`Buffer` は注入）・Service Worker（アプリの保存と、`media/` をIndexedDBから返す）・pdf.js・マニフェストを `dist/` に作る。データは `WebStore`（IndexedDB。削除は墓標→次の同期でVaultのゴミ箱へ）。同期は `DriveVault`（Driveのmd5と、最後に読み書きした内容のハッシュで判定。両方変更は `model.syncMerge`。編集は削除に勝つ、大量欠落で停止、手書きノートにid、`nengaInbox` は送らない。画像は初めて表示したときにVaultから取得）。連絡先は `WebBackend`＋`contacts.body`（People API、写真は900px四角）。Googleのトークンはメモリだけ、切れたらホームに「Googleにログイン」。テスト：`npm test`（同期をDriveの偽物で、連絡先の形）、`test/e2e.cjs`（実ブラウザで取り込み→読み取り→ロゴ→再読み込み）。GitHub Pagesへは `.github/workflows/pages.yml`。

## 0.14.0での変更（GitHubリリース・自動更新）

- 版はAndroid・デスクトップで共通（0.14.0から）。タグ `v<版>` を送ると `.github/workflows/release.yml` がテスト→APK・Windowsインストーラー（NSIS、ユーザー単位）・Mac dmg/zip を作りReleaseを公開。手順と署名Secretは `docs/RELEASING.md`。
- デスクトップ：`electron-updater`（GitHub provider）。Windowsは自動ダウンロード→終了時に更新、Macは通知のみ（Apple署名が無いため）。Macは `scripts/after-pack.js` でアドホック署名。状態の `update`/`version` をUIへ（`Backend` の `info`）、`installUpdate` で更新。
- Android：`UpdateCheck` が起動時（12時間に1回まで）に最新ReleaseのAPKを確認、`state.update` でホームにお知らせ、`installUpdate` でブラウザからAPKをダウンロード（このリポジトリのReleaseのURLだけ開く）。

## 0.13.0での変更（読み仮名・ウィジェット）

- 連絡先の氏名：`Contacts.name` で表示名に加えて姓・名（日本語は「姓 名」、漢字・かなを含まなければ「名 姓」）と読みの姓・名（`PHONETIC_FAMILY/GIVEN_NAME`、`PhoneticNameStyle.JAPANESE`）を書く。以前は読み全体を `PHONETIC_GIVEN_NAME` だけに入れていた。起動時に1回、登録済みの連絡先をこの形で書き直す（`contactNameRule`=1、キュー工程 `contact`）。統合済み（`contactMerged`）の連絡先は、読みが空のときだけ読みを足す。
- 読み取り：`CARD_PROMPT` で振り仮名が無くてもローマ字表記からひらがなの読みを作る。読み取り済みで読みが空の名刺は、メニュー「読み仮名を補う」（`kanaFill`、工程 `kana`：全文テキストだけをGeminiに送る `Gemini.kana`）。補えたら登録済みの連絡先も更新。試した名刺は `kanaTried`（端末固有）。
- ウィジェット：メニュー「ホーム画面にウィジェットを追加」（`pinWidget` → `requestPinAppWidget`、非対応のランチャーでは手順を表示）。ピッカー用に見本入りの `widget_preview` と `previewImage`。受信側は `exported="true"`。`state.widgets` に設置数。

## 0.12.0での変更（重複の一括統合・会社一覧・ロゴ切り出し）

- 連絡先の重複判定：携帯（mobile欄、または070/080/090）・メール・同じ氏名だけを根拠にする。会社の代表番号だけの一致は重複にしない（`Contacts.candidates` の `strong`）。候補一覧では代表番号だけの一致を最後に「同じ会社の別の人かも」と表示。既存の通知は起動時に1回だけ新ルールで再判定（`contactDupRule`=2）。このアプリが作った連絡先は候補から除く。
- 重複画面：各組にチェック、「すべて選択」「選択したN件を統合」（`mergeBatch`：名刺の組は新しい方へ統合、連鎖も追跡。連絡先の重複は同じ人と分かる候補が1件だけなら自動で `mergeInto`、それ以外は報告）。1件ずつの操作の後も重複画面に戻る（`dupReturn`。タスクはbusy解除時、同期的な操作はstate受信時。エラー・報告はそちらを優先）。`merge` は `stay:true` で詳細画面へ移らない。名刺統合の処理は `mergeNow` に集約。
- 会社一覧（ホームの表示切替「会社」）：`companyKey`（app.js）/`Company.key`（Java）/`model.companyKey`（デスクトップ）で「株式会社」「(株)」「Co., Ltd.」等を除いて同じ会社をまとめる。お店は会社欄が空なら店名。タイルはロゴ（メンバーの `logo`）か頭文字。
- ロゴ切り出し：`Gemini.logoBox`（表面1枚だけ送る、`LOGO_PROMPT` が "ymin,xmin,ymax,xmax"(0–1000) を返す）→ `Logo.crop` で切り出し → `Logo.share` で同じ会社のロゴ未設定の人へ。手で選んだロゴは変えない。キュー工程 `logo`（まとめて：1社1枚、`Logo.pick`）と `relogo`（この名刺から切り出し直す：前回の切り出しを置き換え）。試した名刺は `logoTried`（端末固有）。

## 0.11.1での変更（既存のGoogle連絡先との統合・削除）

- 重複表示（`contactDuplicate`）に「既存と統合」「既存を削除」を追加。押すと `contactCandidates` で端末の連絡先から一致するもの（電話・メール・氏名）を探し、`Contacts.candidates` が候補（raw contact・アカウント・会社/電話/メール・写真の有無）を返す。会社の代表番号で同僚も一致しうるので、必ず利用者が選ぶ。このアプリが作った連絡先（MARKER行あり）は候補に出さない。
- 統合（`Contacts.mergeInto`）：既存の連絡先に、名刺にあって既存に無いもの（電話・メール・Web、無ければ氏名・会社・住所）だけを書き足す。メモ欄と既存の値は変えない。写真が無ければ名刺画像を入れる。アプリが追加した方は削除し、既存の方にMARKER行を付けてつなぐ（`contactMerged`、端末固有の項目）。以後の編集反映（`update`）は書き足しのみ、名刺削除時（`remove`）はMARKER行を外すだけで連絡先は残す。
- 既存を削除（`Contacts.deleteExisting`）：選んだ連絡先を削除（Googleのゴミ箱に30日）。
- 修正：名刺の「1人にまとめる」で統合元の連絡先を引き継ぐとき、MARKER行のidを統合先へ付け替える（`Contacts.relink`）。以前は引き継ぎ後の連絡先更新が「見つかりません」になっていた。

## デスクトップ版 0.1.0（Windows / Mac）

- `desktop/`：Electron。DBを持たず、Theta Vault フォルダ（Googleドライブ for desktop の同期フォルダ）を直接読み書きする。端末固有の項目（`Sync.LOCAL` 相当）は userData の `overlay.json`。
- 画面は Android の `assets/` をそのまま使う（`npm run sync-ui` で `desktop/ui/` へコピー。Geminiのプロンプトも `Gemini.java` から `lib/prompts.json` に抽出）。UIは `state.platform` / `state.caps` を見て、使えない操作（連絡先・カレンダー登録・撮影・録音・Drive・ZIP）を出さない。
- ブリッジは HTTP：`127.0.0.1:47860` で配信し、`/api/call`（UI→）と `/api/events`（SSE、→UI）。合言葉（token）は初回 `?token=` → Cookie。Hostヘッダ検査でDNSリバインディングを防ぐ。同じURLをDeskHatchの「Webページ」ドロワーに入れて使う。
- 画像はElectronの `nativeImage`、PDFは隠しウィンドウの pdf.js（`assets/render.html`。CMap・標準フォントもサーバーから配信）。
- `lib/vault-codec.js` は `VaultCodec.java` の移植。`CrossPlatformTest`（Android）と `vault-codec.test.js`（Node）が互いの出力を読み合う。
- 未実装：Google連絡先・カレンダー（People API / Calendar API のOAuthが要る。今はスマホ版で）、ScanSnapフォルダの自動監視（今は手動の一括取り込み）、インストーラー形式と署名。

## 0.10.0での変更（Theta Vault）

- `VaultCodec`：人物レコード ⇄ Markdown。frontmatterは snakeyaml-engine で読み書き。管理外のfrontmatter項目は `vaultExtra`、管理外の本文は `vaultFree` に保持して書き戻す。メモは `^id`（Obsidianのブロック参照）で同一性を保つ。`rev` は本文の指紋で、`editedOutside` が真ならObsidian等での編集と判断。
- `VaultSync`：`VaultRemote`（今は `OnDrive`）越しに同期。リモート状態を Drive md5 と端末の記録（`vault-sync.json`）で `Sync.decide` の語彙に写して判定。リモートに無い既知ノートは `meta` で所在確認（サブフォルダ移動に対応）、多数欠落時は削除しない。手書きノートは `vaultNewId` で検出してidを書き戻す。
- `Sync` は共通ルール（hash・merge・decide・media）だけに縮小。hashは表示用の細部（ページ名・音声のstate等）を除外。
- テスト：`VaultCodecTest`（往復・Obsidian編集・手書きノート）、`VaultSyncTest`（Robolectricで2台の端末＋仮のVault：送受信・統合・削除・移動・大量欠落・改名・Obsidian優先）。Robolectricの実行時jarは `ROBOLECTRIC_REPO` で取得先を指定できる。

## 0.9.0での変更

- 年賀状：撮影・取り込み時に `nenga:true, year` を付けると、新しいレコードは `kind:"nengaInbox"` になり、キュー工程 `nenga` で `Gemini.recognizeNenga` → `Nenga.file`。差出人の氏名（連名のどれか）が一意に一致、同名が複数なら住所で絞り込み、氏名なしなら住所の一致で既存の人へ `greetings[]` として追加し、仮レコードは削除（画像は先に移すので消えない）。一致しなければ仮レコードを人に変換（`noAutoContact`、タグ「年賀状」）。`nengaInbox` は一覧・重複・同期・CSVの対象外。年の既定は10月以降なら翌年（`Nenga.season`）。
- `greetings[].files` の画像は `Store.referenced` と `Sync.media` に含む（バックアップ・同期・不要ファイル削除が正しく扱う）。
- お店：`Gemini.CARD_PROMPT` が `kind`（person/shop）と `genre` `hours` `holidays` を返す。`Store.FIELDS` に3項目を追加し、`kind` は `Store.edit` で保存。
- `NengaTest` で突き合わせ・年度・画像参照を固定。

## 0.8.0での変更（Theta Meishi Hatchに改名）

- 表示名のみ変更。applicationId `jp.yaegaki.meishiclaude`・署名・Driveの同期フォルダ（印で検索）・OAuth設定は従来どおり。
- `CalendarSync`：CalendarContractで終日予定を1人1件登録。`calendarEventId` は端末ごと（Sync.LOCAL）、`calendarRegistered` は共有。保存で更新、削除で削除、統合時は統合元の予定を削除。登録先は選択済み → 連絡先アカウントの主カレンダー → 任意の書き込み可能なGoogleカレンダー。自動登録（`autoCalendar`）と一括登録（`calendarOnce`）は `ProcessingService.extras` で実行。
- `Geo`：Android Geocoderで住所→緯度経度、`geo: {lat, lng, q}`（共有）。地図を初めて開いた時（`geoEnabled`）から有効。同じ住所はキャッシュ。
- 地図：`assets/lib/` にLeaflet 1.9.4・markercluster 1.5.3を同梱（ライセンス同梱）。WebViewは `/lib/*.js|css` を配信し、`tile.openstreetmap.org` のGETだけ外部読み込みを許可（CSP img-src も同様）。地図要素は再描画をまたいで使い回す（`mountMap`）。アプリ全体の `svg {22px}` 指定は `.leaflet-container svg` で打ち消している。

## 0.6.0での変更

- `Sync.java`：Drive同期（仕様は `SYNC_SPEC.md`）。判定は純粋関数 `Sync.decide(lh, rh, ih)` に集約し、`SyncTest` で全パターンを固定。端末側の前回状態は `vault/sync.json`、削除は `data.json` の `tombstones`。
- `Store.putSynced` は受信したレコードの `updated` を保つ。`Store.removeQuietly` は削除印を残さない削除（同期由来）。`Store.changedAt` で未同期の変更を判定。
- `DriveAuth.silentToken`：サービスからの無言トークン取得。再許可が要る時は失敗させ、`syncError` に表示 → 「今すぐ同期」（Activity経由）で許可を取り直す。
- 同期は `ProcessingService` の最後に実行（`requestSync`）。起動時（10分以上経過）、離脱時（未同期の変更あり）、読み取り・登録の後。
- `contactRegistered` は共有、`contactRawId` は端末ごと。

## 0.5.0での変更

- **処理キュー**：人物の `queue` フィールド（"recognize" → "contact"）が未処理の工程。`Pipeline.run` が順に処理し、`ProcessingService`（前面サービス、dataSync）がそれを回す。プロセスが落ちても `onResume` で再開。429/401/403やAPIキー不備では中断し、キューを残す。
- 自動読み取りでは `Pipeline.apply` が結果を直接反映（suggestionを経由しない）。氏名が空または warnings ありは `review`、それ以外は `confirmed`。手動の「読み取り直す」（`reread`）だけは従来どおり確認画面を経由。
- 自動登録：`contactAccount` が端末上にあり、連絡先の権限がある時だけ。無い場合は `queueError` を付けてスキップし、アカウント設定時に `requeueContactless` で再投入。登録時にGoogle連絡先に同じ電話・メールの人がいれば `contactDuplicate` に名前を記録。
- `Contacts.update` はこのアプリが作った連絡先のデータ行（氏名・組織・電話・メール・住所・Web・メモ）を置き換える。連絡先アプリ側で同じ項目を直した内容は上書きされる。`Contacts.remove` はマーカー行で自作の連絡先と確認してから削除。
- 重複検出はUI（`dupes()`）で計算：メール、携帯番号（下10桁）、氏名＋会社（会社なしなら氏名のみ）。会社の代表番号は同僚と共有されるため使わない。「別人」は両者の `notDuplicate` に記録。統合は新しい名刺を基準にし、連絡先は1件だけ残す。
- `BulkImport`：ZIP内のCSV（UTF-8/Shift_JIS）＋画像。列名は別名表で緩く対応。二重取り込みは `importKey`（ID列、無ければ氏名・会社・メール・表面画像名のハッシュ）で防ぐ。

## 0.4.0での変更

- `Drive.java`：Drive REST v3の最小クライアント（フォルダ一覧、カード一覧、ダウンロード、「登録済み」作成、移動）。トークンはPlay servicesの `AuthorizationClient` から毎回取得し、保存しない。スコープは `drive`（ScanSnapが作ったファイルを移動するため）。
- `MainActivity.driveImport`：まとめ方が file/page なら1ファイルずつ取り込み、成功（または取り込み済みの重複）したファイルだけ移動。pair/all は全ファイルを一度に取り込み、1件でも取得失敗があれば中止して何も移動しない。
- `afterImport`：全取り込み経路の共通後処理。自動読み取りがオンなら、1人は確認画面へ、複数人は `recognizeIds` で順に読み取り `batchDone` で「要確認」一覧へ。
- prefs：driveFolderId / driveFolderName / driveMode。

## 0.3.0（Meishi-Claude）での変更

- applicationId を `jp.yaegaki.meishiclaude` に変更（Javaのpackage/namespaceは `jp.yaegaki.meishinote` のまま）。署名鍵は新規作成した `meishiclaude-release.jks`（alias `meishiclaude`）。**リポジトリには入れていません。** 以後の更新は同じ鍵で署名すること。
- 撮影ボタンは `quickScan`（mode=all, auto=true）。`MainActivity.afterCapture` が1人分の取り込み直後にGeminiを呼び、`review` イベントでUIを確認画面へ送る。失敗時は画像を残して詳細画面を開く。
- 確認画面＝編集画面。`suggestion` を下書きに重ねて表示し、保存時に `acceptSuggestion` で `Store.edit` が rawText/warnings を確定して suggestion を消す。旧「反映して編集」ステップは廃止。
- `save` に `thenContact:true` を付けると保存後に連絡先登録へ進む。登録先アカウントは `prefs.contactAccount` に記憶し、端末に残っている場合は選択画面と確認ダイアログを省略（`registerContact`）。
- 設定 `settings` は部分更新（model / autoRecognize / contactPhoto / clearAccount を個別に送れる）。
- UIルートは home / detail / edit / menu / gemini。旧 import / settings 画面はメニューに統合。


## 依頼の目的

Eightを拡張したような個人向けAndroid名刺管理。SNS機能は不要。名刺画像を確実に残し、Google連絡先・Google Driveと組み合わせ、面談メモや録音を人単位で保管したい。

## 構成

- MainActivity: ローカルWebView、JSON bridge、権限・SAF・カメラ・Googleアカウント選択・アプリ内録音再生。
- Store: schema1のJSONとAtomicFile。person/notes/audio/pages/sources、コピーによる読み書き分離、削除後の参照されない添付ファイルの削除。
- Importer: 原本保持、表示用JPEG、EXIF方向、PDFレンダリング、SHA256重複検出、表裏グループ化。
- Gemini: 固定HTTPSホストへのREST。キーはヘッダ。20秒接続/90秒応答タイムアウト、構造化JSON、モデル一覧。
- VaultKey: Android Keystore AES-GCM。バックアップ対象外。
- Contacts: GoogleアカウントへのRawContact/データ行登録、独自IDマーカー、DisplayPhoto。実アカウント同期の実機確認が必要。
- RecordingService: マイクのforeground service。通知から停止。音声を外部へ送信しない。
- Backup: UTF8-BOM CSV、参照される添付ファイル付きZIP、検査後の追加復元。復元時はファイル名を再生成し、既存ファイルを上書きしない。
- FilesProvider: 非公開プロバイダ。URI単位の一時権限。書込許可はcache/camera-*だけ。

## 0.2.0 スキャン撮影

ML Kit Document Scanner 16.0.0をGradle依存で使用。MainActivity.startScannerで初回ダウンロード待ち・キャンセル・起動失敗フォールバックを扱う。SCANの返却URIはImporter.runのpreserveOrder=trueで処理し、ファイル名で並び替えない。通常のファイル取り込みは従来どおりファイル名順。pendingには人物IDとまとめ方を保存し、Activity再作成後も取り込み先を維持する。スキャン補正後JPEGのみを受け取り、切り抜き前画像は取得できない。SDK画面自体の実機テストは未完了。

## 更新時の注意

（0.2.0時点）package `jp.yaegaki.meishinote` を維持し、versionCodeを増やす。※0.3.0以降は `jp.yaegaki.meishiclaude`。署名鍵は別の非公開ZIP。公開ソースに鍵・APIキー・実ユーザーデータを入れない。初版にデモデータは同梱していない。UI検証に使う人物は架空。

schema変更前にバックアップ互換性と移行を設計する。自動同期・Google既存連絡先更新・自動画像取得は現在の実装機能ではないため、案内とUI表記を混同しない。録音中はメモ・テキスト編集を許可し、画像取込やバックアップなどは停止を促す。

## 検証の入口

`tests/ui.test.cjs` はPlaywright。`CHROME`でChromeヘッドレスのパスを指定する。
`tests/android/Smoke.java` はAndroid Instrumentationで実データ処理を行う。隔離したエミュレータでアプリを初期化して実行する。**利用中の実機データに対してテストを実行しない**。

実機のAPIキーを受け取った場合はビルドに内包せず、端末の設定画面から入力する。
