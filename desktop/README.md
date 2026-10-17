# Theta Meishi Hatch デスクトップ版（Windows / Mac）

スマホ版と同じ画面で、PCから名刺を見る・直す・取り込む・読み取るためのアプリです。
データベースは持たず、**Theta Vault フォルダ（Markdown＋画像）を直接読み書き**します。
スマホとは Google ドライブ経由で同じ Vault を共有します。

## 使い始める

1. **Googleドライブ（パソコン版）** を入れて、スマホと同じGoogleアカウントでログインします。
   - Windows：`winget install Google.GoogleDrive`
   - Mac：`brew install --cask google-drive`
2. スマホ版でメニュー →「今すぐ同期」を一度行い、マイドライブに `Theta Vault` フォルダを作っておきます。
3. [Releases](https://github.com/kiwiman0706-beep/MeishiApp/releases/latest) から入れます。
   - **Windows**：`ThetaMeishiHatch-Setup-<版>.exe` を実行（管理者権限は不要）。署名していないため SmartScreen が出たら「詳細情報」→「実行」。
     以後の更新は自動です（バックグラウンドでダウンロードし、次の起動で入れ替え）。
   - **Mac**：Apple シリコンは `…-mac-arm64.dmg`、Intel は `…-mac-x64.dmg`。`.app` を「アプリケーション」に入れ、
     初回だけ右クリック →「開く」（macOS 15以降は「システム設定」→「プライバシーとセキュリティ」→「このまま開く」）。
     新しい版が出ると通知とトレイのメニューで知らせ、ダウンロードページを開きます（Appleの開発者署名が無いため自動入れ替えはできません）。
4. 初回起動時、`マイドライブ/Theta Vault` を自動で探します（Windows は G:〜Z: ドライブ、Mac は `~/Library/CloudStorage/GoogleDrive-*`）。
   見つからなければフォルダを選ぶか、書類フォルダに新しく作れます。あとからメニュー →「Theta Vault フォルダ」で変更できます。

ウィンドウを閉じてもタスクトレイ（Macはメニューバー）に残ります。終了はトレイのメニューから。

## できること

| 機能 | デスクトップ | 備考 |
| --- | --- | --- |
| 一覧・検索・表・カレンダー・地図 | ✔ | 地図の住所変換は OpenStreetMap（1件/秒） |
| 画像・PDFの取り込み | ✔ | ファイル選択、またはウィンドウへドラッグ＆ドロップ。PDFは1ページ＝1画像 |
| ScanSnapの保存フォルダ取り込み | ✔ | フォルダを指定して一括。取り込み済み（同じ内容）は飛ばす |
| Geminiで読み取り（自動・まとめて） | ✔ | APIキーはOSの鍵保管（DPAPI / キーチェーン）で暗号化してPC内に保存 |
| 編集・メモ・統合・削除 | ✔ | 削除は Vault の `.theta/trash` へ |
| 年賀状・お便り・書類 | ✔ | |
| CSV書き出し | ✔ | |
| Google連絡先・Googleカレンダー登録 | — | スマホ版で行います（同期後に反映されます） |
| 撮影・録音・Driveフォルダ取り込み・ZIP取り込み | — | スマホ版で行います |

Obsidian で同じ Vault を開いて編集しても、数秒で画面に反映されます。

## DeskHatch で使う

デスクトップ版は `http://127.0.0.1:47860/` で同じ画面を配信しています（このPCの中からだけ・合言葉付き）。

1. トレイのメニュー →「DeskHatch用のURLをコピー」。
2. DeskHatch でドロワーを追加 →「Webページ」→ コピーしたURLを貼り付け。

DeskHatch のドロワーにファイルをドロップしても取り込めます。
ポート47860が他のアプリに使われていると、その回だけ別のポートになります（URLを取り直してください）。

## 開発

```sh
cd desktop
npm install
npm start            # android/ の共通UIを ui/ に取り込んで起動
npm test             # Vault読み書き・取り込み・HTTPブリッジのテスト
npm run test:e2e     # 画面（Playwright）と、実際のElectronでPDF取り込み（Linuxは xvfb-run -a を前に付ける）
npm run dist:win     # dist/ に Windows インストーラーとZIP（Windowsで実行。LinuxではWineが必要）
npm run dist:mac     # dist/ に Mac 用 dmg / zip（arm64 / x64。Macで実行）
```

| ファイル | 役割 |
| --- | --- |
| `main.js` | Electron本体：トレイ、ウィンドウ、Vaultの場所、APIキー保管、画像変換（PDFは隠しウィンドウのpdf.js） |
| `lib/server.js` | 127.0.0.1のHTTPサーバー。`Native.call` / `onNative` をHTTP＋SSEで中継、ドロップ取り込み |
| `lib/backend.js` | Android版 `MainActivity` に当たる操作の実装。`caps` でUIに使える機能を伝える |
| `lib/vault-store.js` | Vaultフォルダの読み書き・監視。端末固有の項目は Vault の外（`overlay.json`）へ |
| `lib/vault-codec.js` | Android版 `VaultCodec` と同じ Markdown 形式（相互にテスト済み） |
| `lib/importer.js` / `model.js` / `gemini.js` | 取り込み・レコード規則・Gemini（プロンプトは Android の `Gemini.java` から生成） |

画面は Android 版の `android/app/src/main/assets/` を共通で使います。直すときはそちらを直し、`npm run sync-ui` で取り込みます。
テスト用に `THETA_USER_DATA`（設定の場所）と `THETA_VAULT`（Vaultの場所）を環境変数で差し替えられます。
