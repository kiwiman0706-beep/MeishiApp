# 旧同期形式（0.6〜0.9）とZIP取り込み形式

> **0.10.0以降の同期は `THETA_VAULT_SPEC.md`（Obsidian Vault形式）です。** この文書は、0.6〜0.9で使っていたDrive同期の記録と、今も使える「同期形式のZIP取り込み」（`people/*.json` + `media/`）の仕様として残しています。


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
  - 除く項目：`contactRawId`, `contactAccount`, `contactPhoto`, `queue`, `queueError`, `contactDuplicate`, `suggestion`, `contactUnlinked`, `calendarEventId`, `contactMerged`, `logoTried`, `kanaTried`
- `kind` が `nengaInbox`（差出人を読み取る前の年賀状）のレコードは同期しない
- 年賀状の画像は `greetings[].files[].file` で参照する（`media/` に置く）。統合時は `greetings` も id で和集合
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

## ZIPで取り込む（同期形式のZIP）

この構成を外部で作った場合（他のツールやAIで生成した場合など）は、**ZIPにしてアプリの「メニュー → ZIPから一括取り込み」で読み込みます**。DriveのWeb画面からフォルダへ直接アップロードする方法は使いません（フォルダとファイルに必要な目印〔appProperties〕が付かないため）。

```
任意の名前.zip
├── people/
│   ├── <ID>.json
│   └── ...
└── media/
    ├── <ファイル名>
    └── ...
```

- `people/` と `media/` はZIPの最上位でも、1つ下のフォルダ（例：`Theta Meishi Hatch データ/people/`）でも構いません。
- 取り込むと、そのまま端末に保存されます（Geminiの読み取り・連絡先の自動登録はしません）。同期がオンなら、次の同期でDriveにも送られます。
- 端末に既にあるIDは読み飛ばします（上書きしません）。`"deleted": true` のファイルも読み飛ばします。

### 1人分のJSONの最低限の形

```json
{
  "id": "c0001",
  "name": "山田 太郎",
  "kana": "やまだ たろう",
  "company": "サンプル株式会社",
  "department": "営業部",
  "title": "部長",
  "email": "taro@example.com",
  "phone": "03-1234-5678",
  "mobile": "090-1234-5678",
  "fax": "",
  "postal": "100-0001",
  "address": "東京都千代田区丸の内1-1-1",
  "website": "",
  "tags": "取引先",
  "metDate": "2024-03-05",
  "metPlace": "展示会",
  "status": "confirmed",
  "created": 1709596800000,
  "pages": [
    {"file": "c0001-front.jpg", "name": "表面"},
    {"file": "c0001-back.jpg", "name": "裏面"}
  ],
  "notes": [
    {"id": "n1", "time": 1709596800000, "text": "次回見積もり送付"}
  ]
}
```

| 項目 | 規則 |
| --- | --- |
| `id` | 必須。英数字・`_`・`-`、80文字以内。ファイル名 `people/<id>.json` と同じにする |
| 名刺の項目 | `name` `kana` `company` `department` `title` `email` `phone` `mobile` `fax` `postal` `address` `website` `tags` `metDate`（YYYY-MM-DD）`metPlace`。省略時は空 |
| `status` | `confirmed`（確認済み）/ `review`（要確認）/ `new`（未認識）。省略時は氏名があれば `confirmed` |
| `created` / `updated` | ミリ秒のUNIX時刻。省略時は取り込んだ時刻 |
| `pages` | 名刺画像。`file` は `media/` 内のファイル名（英数字・`_`・`-`・`.` のみ）。表→裏の順 |
| `notes` | メモ。`id`（重複しない文字列）・`time`（ミリ秒）・`text` |
| `audio` `sources` `portrait` `logo` | 任意。`file` は `media/` 内のファイル名 |
| `kind` | 任意。`"shop"` でお店のカード（`genre` `hours` `holidays` も使える）。省略で人 |
| `greetings` | 任意。年賀状の記録の配列：`{"id", "year": 2027, "dir": "received"または"sent", "date": "YYYY-MM-DD", "kind": "nenga"/"mochu"/"kanchu"/"shochu"/"other", "files": [{"file"}], "senders", "message"}`。`files` は `media/` 内のファイル名 |
| `geo` | 任意。`{"lat": 緯度, "lng": 経度, "q": 住所と同じ文字列}` があれば地図の変換を省略 |

`media/` に無いファイルを参照している場合、その画像だけ表示されず、取り込み結果にエラーとして出ます。

## 既知の制限

- Drive上の不要になったmediaファイルは消さない（容量は増える一方）。
- 連絡先の更新は、その連絡先を登録した端末でのみ行える（Googleの連絡先IDは端末ごとに異なるため）。
