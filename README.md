# Theta Meishi Hatch（旧 Meishi-Claude）

個人向けの名刺管理アプリ。名刺を撮影 → Geminiで読み取り → Google連絡先に登録。名刺画像・やり取りのメモ・録音を人ごとに保存します。ChatGPTで作った「名刺ノート 0.2.0」を引き継いだものです。名刺交換日のカレンダー表示・Googleカレンダー登録、地図表示、Google Drive同期にも対応しています。

## ダウンロード

[Releases](https://github.com/kiwiman0706-beep/MeishiApp/releases/latest) から、Android（APK）・Windows（インストーラー）・Mac（dmg）を入手できます。新しい版はアプリが知らせます（Windowsは自動更新）。リリースの作り方は [`docs/RELEASING.md`](docs/RELEASING.md)。

| フォルダ | 中身 |
| --- | --- |
| `android/` | Androidアプリ（Java + WebView UI）。ビルド方法は `android/README.md` |
| `desktop/` | Windows / Mac版（Electron）。Vaultフォルダを直接読み書きし、DeskHatchのドロワーにも表示できる。使い方は `desktop/README.md` |
| `docs/` | 引き継ぎメモ・検証記録・ロードマップ |

Theta Hatch シリーズ共通の約束は [`docs/THETA_HATCH_SPEC.md`](docs/THETA_HATCH_SPEC.md)、今後の予定は [`docs/ROADMAP.md`](docs/ROADMAP.md) を参照してください。
