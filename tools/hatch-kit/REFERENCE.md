# reference/ の地図

✔＝ほぼそのまま使える共通部品（パッケージ名・アプリ名・フォルダ名を変える程度）
△＝形を流用（中身は新しいアプリの項目に合わせて書き換える）
✖＝名刺専用（参考のみ）

## Android（reference/android/）

| ファイル | 区分 | 役割 |
| --- | --- | --- |
| `build.gradle` `AndroidManifest.xml` `build.sh` | △ | 依存（snakeyaml-engine、ML Kit スキャナ、Play services auth）、権限、署名付きビルド |
| `MainActivity.java` | △ | WebView＋ブリッジ（`Native.call` / `onNative`）、アクションの振り分け、Google の許可（`withDriveToken`）、初期設定ガイドへの誘導、自動更新、ウィジェット |
| `Store.java` | △ | 端末内のデータ（1つのJSON、原子的な書き込み、墓標） |
| `Pipeline.java` `ProcessingService.java` | △ | 項目ごとの `queue` を順に処理するフォアグラウンドサービス（連結工程、429で停止） |
| `VaultCodec.java` | △ | レコード ⇄ Markdown（frontmatter・本文の管理区画・自由記述の保持・`rev`）。項目名を新しいアプリに合わせる |
| `VaultSync.java` `VaultRemote.java` `Sync.java` | ✔ | Theta Vault との同期（md5＋端末の記録、統合・削除・移動・大量欠落・手書きノート）。`Sync.LOCAL`（端末だけの項目）と `merge`/`media` を合わせる |
| `Drive.java` `DriveAuth.java` | ✔ | Drive REST（一覧・取得・アップロード・移動・ゴミ箱）、バックグラウンドのトークン |
| `Contacts.java` | △ | 連絡先の登録・更新・重複・既存との統合（姓名と読みの分割） |
| `CalendarSync.java` | ✔ | 端末のカレンダー（Google）に終日予定 |
| `Gemini.java` | △ | Gemini REST（構造化JSON、プロンプト、モデル一覧） |
| `UpdateCheck.java` `UpdateInstaller.java` | ✔ | GitHub Release を見て APK を取得・検証・アプリ内インストール（12以降は無確認） |
| `RecentWidget.java` ＋ `res/layout/widget_*` `res/xml/recent_widget_info.xml` | △ | ホーム画面ウィジェット |
| `VaultKey.java` | ✔ | APIキーを Android Keystore で暗号化 |
| `Importer.java` `BulkImport.java` `FilesProvider.java` | △ | 画像・PDF・ZIP の取り込み、共有用 FileProvider |
| `Logo.java` `WebLogo.java` `Company.java` `Nenga.java` `Geo.java` `Backup.java` | ✖ | 名刺専用（ロゴ、会社、年賀状、地図、CSV）。ロゴの画素補正（`Logo.refine`）は画像の切り出し一般に流用可 |
| `test/…` | △ | Vault の往復、2台同期（Robolectric）、Java⇄JS の相互読み込み |
| `tests/ui.test.cjs` | △ | 共通UIのブラウザテスト（`Native` をモック） |

## 共通UI（reference/ui/）

| `index.html` `app.js` `style.css` | △ | 1つのUI。`caps`・`platform` による出し分け、モーダル、メニュー、一覧／表／カレンダー／地図、初期設定ガイド（`setupGuide`）、更新のお知らせ、過去データの更新（`upgradeSheet`） |

## デスクトップ（reference/desktop/）

| ファイル | 区分 | 役割 |
| --- | --- | --- |
| `main.js` | ✔ | トレイ常駐、Vaultの場所（Googleドライブ for desktop を自動検出）、APIキー（safeStorage）、PDF描画（隠しウィンドウの pdf.js）、自動更新、DeskHatch 用URL |
| `lib/server.js` | ✔ | 127.0.0.1 の HTTP ブリッジ（トークン、Host 検査、SSE、ドロップ取り込み） |
| `lib/vault-store.js` `lib/vault-codec.js` | ✔/△ | Vault フォルダが DB（読み込み・監視・原子的書き込み・ゴミ箱、端末だけの項目は overlay） |
| `lib/backend.js` | △ | アクションの実装（`platform`/`caps` を受け取り Web 版と共有） |
| `lib/model.js` `lib/importer.js` `lib/gemini.js` | △ | レコードの規則・取り込み・Gemini |
| `lib/weblogo.js` | ✖ | 会社サイトのロゴ |
| `scripts/sync-ui.js` | ✔ | 共通UIのコピーと、Java からプロンプトを抜き出す |
| `scripts/after-pack.js` `package.json`（build 欄） | ✔ | electron-builder（NSIS・dmg・zip、Mac アドホック署名、GitHub 公開情報） |

## Web（reference/web/）

| ファイル | 区分 | 役割 |
| --- | --- | --- |
| `build.js` | ✔ | 共通UI＋desktop/lib を esbuild でブラウザ用に（fs/path/crypto の代替、Buffer 注入）、CSP、マニフェスト、SW、pdf.js |
| `src/idb.js` `src/web-store.js` | ✔ | IndexedDB のストア（墓標、メディアは初回表示時に Vault から） |
| `src/drive-vault.js` | ✔ | Drive API での Vault 同期（Android と同じ規則） |
| `src/google.js` | ✔ | Google Identity Services（タップの中でトークン）、Drive・People の呼び出し |
| `src/sw.js` | ✔ | オフライン、`media/` を IndexedDB から |
| `src/web-backend.js` `src/contacts.js` `src/imaging.js` `src/main.js` | △ | デスクトップのバックエンド＋Google、People API の形、canvas の画像処理、ページのつなぎ |
| `test/` | ✔ | 偽の Drive での同期テスト、実ブラウザの通しテスト |

## CI（reference/workflows/）

| `release.yml` | ✔ | 手動実行で版を確認→テスト→APK・Windows・Mac→Release（タグも作る） |
| `pages.yml` | ✔ | Web版を GitHub Pages へ |
