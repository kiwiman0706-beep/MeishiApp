# 開発引き継ぎ

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
