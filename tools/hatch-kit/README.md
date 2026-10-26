# Theta Hatch キット（Theta Meishi Hatch から）

Theta ○○ Hatch シリーズの新しいアプリを作るための資料一式です。最初のアプリ Theta Meishi Hatch（名刺）で
Google ドライブ同期・Google 連絡先・カレンダー・Android/Windows/Mac/Web・リリースと自動更新までを作り、確かめたものを集めました。

| 場所 | 中身 |
| --- | --- |
| `PROMPT.md` | **新しいアプリの開発スレッドに最初に貼る文**（アプリ名などを書き換えて使う） |
| `spec/THETA_HATCH_SPEC.md` | シリーズ共通仕様 v2（原則・識別子・プラットフォーム・ブリッジ・Google・リリース・品質） |
| `spec/THETA_VAULT_SPEC.md` | データの形（Theta Vault：Obsidian で開ける Markdown＋添付、同期の規則） |
| `spec/THETA_SHARED_SETTINGS.md` | 設定の引き継ぎ（シリーズ／アプリ／端末の3範囲、項目ごとに選ぶ、秘密は端末内だけ） |
| `guides/` | Google Cloud の設定（`THETA_GOOGLE_SETUP.md`：シリーズ共通とアプリごとの分け方。Android・Web の詳しい手順）、リリースの手順 |
| `LESSONS.md` | 作りながら分かった落とし穴と対処（ここを読むと同じ失敗を避けられる） |
| `REFERENCE.md` | `reference/` の地図：どのファイルがそのまま使える共通部品で、どれが名刺専用か |
| `reference/` | Theta Meishi Hatch の実際のコード（Android / 共通UI / デスクトップ / Web / CI） |

元のリポジトリ：https://github.com/kiwiman0706-beep/MeishiApp （公開）
秘密（署名鍵・パスワード・APIキー）と個人データは入っていません。
