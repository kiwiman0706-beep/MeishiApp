# ロードマップ（Meishi-Claude）

## 目標の全体像

1. **Android版**（0.3.0〜）：撮影 → 読み取り → Google連絡先登録までを最短にする。
2. **Google Drive同期**：Android・Windows・Macで同じ名刺データを使う。
3. **Windows / Mac版**：DeskHatchのプラグイン（ドロワー）として動く。

## 設計方針：UIは1つ、ネイティブ部分だけ差し替える

Android版のUIは既に `android/app/src/main/assets/`（HTML/CSS/JS）にあり、端末機能は `Native.call(JSON)` → `window.onNative({type,data})` の
メッセージ1本でやり取りしています。PC版はこのUIをそのまま使い、ブリッジだけをElectron（DeskHatchと同じ）の `ipcRenderer` に差し替えます。

```
shared/ui/        ← 今の assets/ を移す（画面は1か所で直す）
android/          ← Java: カメラ・ML Kitスキャン・Contacts Provider・録音
desktop/          ← Node: ファイル保存・Gemini・Drive同期・Google People API
deskhatch plugin  ← desktop/ をDeskHatchのドロワーとして登録
```

## フェーズ2：Google Drive同期

0.4.0で、Drive接続の土台（OAuth許可、Drive REST）とScanSnapフォルダからの取り込み・「登録済み」への移動を実装しました。次はこの土台を使ってデータ本体の同期を作ります。


- **保存場所**：Drive上の「Meishi-Claude」フォルダ（ユーザーが中身を見られる場所）。1人 = 1つのJSON（`people/<id>.json`）と画像（`media/<file>`）。
  今の `data.json` 1ファイル方式だと2台で同時に編集した時に全体が衝突するため、同期対応の時点で人ごとのファイルに分けます。
- **衝突の扱い**：更新日時（`updated`）が新しい方を採用。メモ・録音・画像は追加のみのリストとしてマージ（片方の追加が消えない）。削除は「削除済み」印を残して伝える。
- **認証**：Android は Google Sign-In（Drive の `drive.file` 権限）。Google Cloud で OAuth クライアントを作り、Meishi-Claude の署名鍵の SHA-1 を登録する必要があります（作業手順は実装時に案内）。
- **移行**：今のZIPバックアップ形式は残し、同期と別に手動バックアップも使えるようにします。

## フェーズ3：Windows / Mac版（DeskHatchプラグイン）

- DeskHatch の現行コードは、ドロワーの種類が「Webページ」と内蔵ツール（エディタ・電卓など）のみで、外部プラグイン用の仕組みはまだありません。
  DeskHatch側に「プラグイン型ドロワー」（ローカルのHTML＋preloadでipcを使えるもの）を追加し、Meishi-Claudeをその最初のプラグインにする想定です。
- PCでの取り込み：ScanSnapの保存フォルダを直接監視して自動取り込み（Android版ではできなかった点）。ドラッグ＆ドロップでDeskHatchのバーに名刺画像を落とすと取り込み。
- Google連絡先：PCには端末の連絡先DBが無いため、Google People API（OAuth）で登録します。
- 単体アプリとしても起動できるようにします（DeskHatchが無くても使える）。

## Android版の残り課題（0.2.0からの引き継ぎ含む）

1. 実機確認：自動読み取りの流れ、記憶したアカウントへの連絡先登録、着信時の名刺画像表示。
2. 画像内の複数名刺の人物別分割。
3. Google連絡先の既存人物選択、差分更新。二重登録のより詳しい候補表示。
4. 人物写真・会社ロゴ候補をアプリ内に表示し、出典付きで選択（現在はブラウザで検索して手動選択）。
5. 録音の文字起こし・要約（送信前に確認を挟む）。
6. XLSX / vCard出力。
7. 暗号化バックアップ、生体認証ロック。
8. 大量データ向けのSQLite移行、長時間処理のキャンセル・再開。

着信画面の見え方そのものは、このアプリでは制御できません。
