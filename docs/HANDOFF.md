# 開発引き継ぎ

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

package `jp.yaegaki.meishinote` を維持し、versionCodeを増やす。署名鍵は別の非公開ZIP。公開ソースに鍵・APIキー・実ユーザーデータを入れない。初版にデモデータは同梱していない。UI検証に使う人物は架空。

schema変更前にバックアップ互換性と移行を設計する。自動同期・Google既存連絡先更新・自動画像取得は現在の実装機能ではないため、案内とUI表記を混同しない。録音中はメモ・テキスト編集を許可し、画像取込やバックアップなどは停止を促す。

## 検証の入口

`tests/ui.test.cjs` はPlaywright。`CHROME`でChromeヘッドレスのパスを指定する。
`tests/android/Smoke.java` はAndroid Instrumentationで実データ処理を行う。隔離したエミュレータでアプリを初期化して実行する。**利用中の実機データに対してテストを実行しない**。

実機のAPIキーを受け取った場合はビルドに内包せず、端末の設定画面から入力する。
