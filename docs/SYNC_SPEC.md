# Google Drive同期 仕様 v1

Android版（0.6.0〜）が実装済み。Windows/Mac版（DeskHatchプラグイン）も同じ仕様で実装する。

## Drive上の構成

```
マイドライブ/
└── Theta Meishi Hatch データ/   appProperties: meishiRoot=1（0.7.0以前に作ったものは「Meishi-Claude データ」）
    ├── people/                  appProperties: meishiPeople=1
    │   ├── <人物ID>.json         appProperties: pid=<人物ID>, h=<内容ハッシュ>
    │   └── ...
    └── media/                   appProperties: meishiMedia=1
        ├── <ファイル名>           画像・PDF原本・録音（ファイル名はUUIDベースで不変）
        └── ...
```

- フォルダは名前ではなく appProperties の印で探す（ユーザーが名前を変えても見失わない）。無ければ作る。
- 権限スコープ：`https://www.googleapis.com/auth/drive`。

## people/<ID>.json

- 中身は人物レコード（端末内 `data.json` の `people[]` の1要素）から、**端末固有の項目を除いたもの**。
  - 除く項目：`contactRawId`, `contactAccount`, `contactPhoto`, `queue`, `queueError`, `contactDuplicate`, `suggestion`, `contactUnlinked`
  - 共有する印：`contactRegistered: true`（どこかの端末でGoogle連絡先に登録済み。他端末は自動登録しない）
- 削除済み：`{"id": "<ID>", "deleted": true, "updated": <ms>}`、ハッシュは文字列 `deleted`。
- 画像・録音の実体は `media/` に置き、レコードからはファイル名で参照する（`pages[].file`, `sources[].file`, `audio[].file`, `portrait`, `logo`）。録音中（`audio[].state == "recording"`）のファイルは共有しない。

## 内容ハッシュ（h）

1. 端末固有の項目と `updated` を除く。
2. キーを辞書順に並べたJSON（空白なし、文字列はJSONエスケープ、数値はそのまま）にする。
3. UTF-8のSHA-256の先頭16バイトを16進文字列（32文字）。

`updated` を除くのは、端末内だけの更新（処理キューの出し入れ等）を変更として扱わないため。

## 端末側の記録

`sync.json`：`{"people": {"<ID>": {"h": "<最後に同期した時のハッシュ>", "f": "<DriveのファイルID>"}}}`

端末の削除は `data.json` の `tombstones: {"<ID>": <削除時刻ms>}` に残し、同期で伝えたら消す。

## 1回の同期の手順

1. フォルダを用意し、`people/` の一覧（id, name, appProperties）と `media/` の一覧（id, name）を取る。
2. 端末の人物・削除印・Driveの人物の全IDについて、次の3つを比べる。
   - L：端末のハッシュ（削除印なら `deleted`、無ければ空）
   - R：Driveのハッシュ（無ければ空）
   - I：前回同期時のハッシュ（初回は空）

| 条件 | 動作 |
| --- | --- |
| L・Rとも空 | 何もしない |
| L = R | 記録だけ更新 |
| Rが空 | Lが削除印なら印を消す。それ以外は送信 |
| Lが空 | Rが削除済みなら記録だけ。それ以外は受信 |
| Rが前回から変わっていない | 送信（端末の変更・削除を伝える） |
| Lが前回から変わっていない | 受信（Driveの変更・削除を反映） |
| 両方変わった・Lが削除印 | 受信（編集が削除に勝つ） |
| 両方変わった・Rが削除済み | 送信（編集が削除に勝つ） |
| 両方変わった（どちらも削除でない） | 統合して両方に書く |

3. **統合**：`updated` が新しい方を基準にし、基準側が空の項目だけもう一方で埋める。`notes`（idで）、`audio`・`pages`・`sources`（fileで）、`notDuplicate` は和集合。`updated` は両者の大きい方＋1。
4. **送信**：レコードが参照するmediaのうちDriveに無いものを先にアップロードし、その後JSONを作成・更新する（appPropertiesに pid と h）。
5. **受信**：レコードが参照するmediaのうち端末に無いものをダウンロードしてから、端末に保存する。端末固有の項目は端末側の値を残す。保存前に、端末のレコードが同期の途中で編集されていないか（ハッシュが変わっていないか）確かめ、編集されていれば次回に回す。
6. Driveから削除が届いても、その端末が登録したGoogle連絡先は消さない（連絡先の削除は明示操作のみ）。

## 同期のタイミング（Android）

- メニューの「今すぐ同期」（初回はここで同期を有効化し、Driveの許可を取る）
- アプリを開いた時（前回から10分以上経っていれば）
- アプリを離れる時（前回の同期以降に端末で変更があれば）
- バックグラウンド処理で名刺を読み取り・登録した後

## 既知の制限

- Drive上の不要になったmediaファイルは消さない（容量は増える一方）。
- 連絡先の更新は、その連絡先を登録した端末でのみ行える（Googleの連絡先IDは端末ごとに異なるため）。
