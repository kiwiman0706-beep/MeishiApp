# 新しい Theta ○○ Hatch の開発スレッドに貼る文

`【 】` の部分を書き換えて、このキットの ZIP と一緒に最初のメッセージとして貼ってください。

---

Theta ○○ Hatch シリーズの新しいアプリ **「Theta 【名前（英字1語）】 Hatch」** を作ってください。
シリーズ最初のアプリ「Theta Meishi Hatch」（名刺管理）で作った仕組みと約束ごとを、添付の ZIP（Theta Hatch キット）にまとめてあります。

## このアプリについて
- 扱うもの：【例：レシピ。1件 = 1つのレシピ（材料・手順・写真・作った日のメモ）】
- 主な使い方：【例：料理の写真や本のページを撮影 → Gemini で読み取って登録 → 一覧・タグで探す】
- Google との連携：【例：買い物リストを Google カレンダーに／不要なら「なし」】
- 他のアプリとのつながり：【例：Meishi の人（[[Meishi/People/…]] のリンク）を、誰からもらったレシピかとして付けたい／なし】

## まず読むもの（キットの中）
1. `README.md`、`spec/THETA_HATCH_SPEC.md`（シリーズ共通仕様 v2）、`spec/THETA_VAULT_SPEC.md`（データの形）、`spec/THETA_SHARED_SETTINGS.md`（設定の引き継ぎ）
2. `LESSONS.md`（Meishi で踏んだ落とし穴。同じことを繰り返さないように）
3. `REFERENCE.md` と `reference/`（Meishi の実際のコード。✔ の部品はそのまま流用、△ は形を流用、✖ は名刺専用）

## 守ってほしいこと
- 共通仕様に従う：データの正本は Theta Vault（Googleドライブの `Theta Vault/【英字のフォルダ名】/` に 1件 = 1つの Markdown、添付は `_files/`）。Obsidian で開けて、Obsidian での編集や手書きのノートも取り込めること。Vault の `.theta/` は Meishi と共有（`vault.json` の `apps` に自分を足す、`settings.json` は消さずに項目単位で更新）。
- 画面は1つ（HTML/CSS/JS）を Android・Windows/Mac・Web で共有し、`platform`/`caps` で出し分け。日本語の画面。ボタンの羅列にせず、よく使う操作が少ない手数で済むように。
- **設定の引き継ぎ（`THETA_SHARED_SETTINGS.md`）を最初から入れる**：初回に `.theta/settings.json` があれば、項目ごとにチェックして引き継ぐ画面。Gemini のモデル・Google アカウント・カレンダー登録先などはシリーズ共通にでき、アプリだけの値にもできる。APIキーは同じ端末の Meishi からだけ（利用者の承認つき）。
- Google は **Meishi と同じ Google Cloud プロジェクト**を使う（2つ目以降は Android クライアントの追加だけ）。アプリ内に「Google 連携の初期設定ガイド」（Meishi の `setupGuide` と同じ形、済んだ手順は畳む）。
- 秘密（署名鍵・パスワード・APIキー）はコミットしない。Android の署名鍵は **Meishi と同じ鍵**を使う（私が渡します。CI には Secret で）。
- applicationId は `jp.yaegaki.theta.【名前】`、版番号は Android・デスクトップ・Web で同じ。
- リリースは GitHub Actions（`reference/workflows/`）で、Android の自動更新（アプリ内インストール）・Windows の自動更新・Web 版の自動入れ替えを最初から。
- テスト：Vault の往復、2台同期（偽の Drive / Robolectric）、共通 UI のブラウザテスト、Web と Electron の起動テスト。実機でしか確かめられないことは `docs/TESTING.md` に「未確認」と書く。
- 私の環境：Windows と Mac（Python 3.12、Node.js LTS、VS Code、Git、Android Studio、Flutter）、スマホは Android（Galaxy）と iPhone（Web 版）。

## 進め方
1. このアプリのデータの形（frontmatter の項目、本文の区画）を `THETA_VAULT_SPEC.md` の形で提案して、私に確認してから実装。
2. Android（撮影 → 読み取り → 一覧 → Vault 同期）→ Web（iPhone）→ Windows/Mac の順で。各段階で APK・ZIP・URL を渡して、確認できていないことを書いて報告。
3. 新しい共通部品を作ったら、シリーズで使えるように仕様（`THETA_HATCH_SPEC.md` 等）に書き足す。
