# Theta Hatch シリーズ 設定の引き継ぎ（共通設定）v1

一つのアプリで設定したことを、シリーズの他のアプリでも使えるようにする約束ごと。
**無条件に共有はしない**。項目ごとに「シリーズに合わせる／このアプリだけの値にする」を選べ、秘密（APIキー等）はクラウドに置かない。

## 1. 3つの範囲

| 範囲 | どこに置くか | 例 | 他のアプリへ |
| --- | --- | --- | --- |
| **シリーズ**（series） | Vaultの `.theta/settings.json` の `series` | Geminiのモデル、Googleアカウント（どのアカウントを使うか）、Web用OAuthクライアントID、Google Cloudのプロジェクト名、カレンダーの登録先、地図の利用 | 各アプリが「シリーズに合わせる」を選んだ項目だけ |
| **アプリ**（app） | Vaultの `.theta/settings.json` の `apps.<アプリ>`（端末間で同じにしたいもの）／端末内 | 取り込み後に自動で読み取る、連絡先の写真の種類、ScanSnapフォルダ | 引き継がない（同じアプリの別の端末には届く） |
| **端末**（device） | 端末の中だけ | Gemini APIキー、OAuthトークン、連絡先の行ID、ウィジェット、通知の許可 | 原則なし。**同じ端末の中で、利用者が承認したときだけ**コピー（§4） |

- Vaultは Google ドライブ等で全端末・全アプリが共有しているので、`series` と `apps` はそのまま全端末に届く。
- 端末の範囲に置くものは、Vault・バックアップ・Gitに入れない。

## 2. `.theta/settings.json`

```json
{
  "spec": 1,
  "updated": "2026-10-22T10:00:00Z",
  "series": {
    "gemini.model":        { "value": "gemini-2.5-flash", "by": "meishi", "at": "2026-10-22T10:00:00Z" },
    "gemini.format":       { "value": "legacy",           "by": "meishi", "at": "…" },
    "google.account":      { "value": "taro@example.com", "by": "meishi", "at": "…" },
    "google.webClientId":  { "value": "1234-abc.apps.googleusercontent.com", "by": "meishi", "at": "…" },
    "google.cloudProject": { "value": "meishi",           "by": "meishi", "at": "…" },
    "calendar.id":         { "value": "taro@example.com", "by": "meishi", "at": "…" },
    "map.enabled":         { "value": true,               "by": "meishi", "at": "…" }
  },
  "apps": {
    "meishi": { "autoRecognize": true, "contactPhoto": "card" }
  }
}
```

- キーは `<分類>.<名前>`。値はJSONの値。`by`（書いたアプリ）と `at`（書いた時刻）を付け、同時編集は `at` が新しい方を採用（項目単位）。
- 知らない項目は消さずに残す（新しいアプリが足した項目を古いアプリが壊さない）。
- **秘密は書かない**（APIキー、トークン、パスワード、クライアントシークレット）。読み込み時にそれらしい項目（`key`、`token`、`secret` を含む名前）があれば無視する。
- 書き込みは「読み込み → 1項目更新 → 書き戻し」。ファイルが無ければ作る。Obsidianで手で直してもよい（JSONとして読めなければ無視して警告）。

## 3. 各アプリの持ち方（「シリーズに合わせる」）

各アプリは自分の設定（端末内）に、シリーズ項目ごとの **追従フラグ** を持つ。

```
follow["gemini.model"] = true   … シリーズの値を使う。このアプリで変えたら「他のアプリにも反映しますか？」と聞き、はいなら series に書く
follow["gemini.model"] = false  … このアプリだけの値を使う。series は読むだけ・変えない
```

- 画面では、シリーズ項目の設定の横に小さく「シリーズ共通」か「このアプリだけ」を表示し、切り替えられる。
- 同期で series の値が変わったら、`follow=true` の項目だけ反映する（反映したことを一度だけ知らせる）。

### 初回起動（引き継ぎ）

新しいアプリを初めて開き、Vaultを選んだ（同期を始めた）ときに `.theta/settings.json` があれば：

```
Theta Hatch の設定を引き継ぎますか？         （Meishi Hatch で設定済み）
 ☑ Geminiのモデル            gemini-2.5-flash
 ☑ Googleアカウント          taro@example.com
 ☑ Googleカレンダーの登録先   taro@example.com
 ☐ 地図を使う                 はい
 ☑ Gemini APIキー（この端末の Meishi Hatch から）   ← §4、端末にあるときだけ
              [選んだ項目を引き継ぐ]  [引き継がない]
```

- 既定でチェックするのは、困らないもの（モデル・アカウント・登録先）。秘密は毎回チェックを外した状態で出してもよい。
- チェックした項目は `follow=true`、外した項目は `follow=false` で始まる。あとから設定画面で変えられる。

## 4. 秘密（APIキー）を同じ端末の中で引き継ぐ

クラウドには置かず、**同じ端末にある別のシリーズアプリから、利用者が承認したときだけ** コピーする。

| 端末 | しくみ |
| --- | --- |
| Android | 各アプリが `ContentProvider`（authority `<applicationId>.theta`）を公開し、権限 `jp.yaegaki.theta.permission.SHARE`（`protectionLevel="signature"`）で守る。**同じ署名鍵**のアプリだけが読める。新しいアプリは `<queries>` で `jp.yaegaki.theta.HATCH` アクションを宣言したアプリを探し、引き継ぎ画面で「Meishi Hatch のAPIキーを使う」を選ばれたときだけ `content://<id>.theta/secret/gemini.key` を読む。提供側は、読まれたことを通知などで知らせる。 |
| Windows / Mac | 将来はデスクトップを1つの「Theta Hatch」ホスト（トレイ常駐、各アプリはその中のモジュール。DeskHatch連携と同じ形）にまとめ、設定と鍵を1か所に持つ。それまでは各アプリの設定画面に「他のTheta Hatchアプリから読み込む」を置き、`<ユーザーの設定フォルダ>/ThetaHatch/shared-secrets.json`（ユーザーだけが読める権限）を経由して、利用者の操作でコピーする。 |
| Web（PWA） | シリーズのWeb版を同じオリジン（`https://<user>.github.io/`）で配信すると、IndexedDB の共通データベース `theta-hatch-series` を全アプリが読める。秘密はそこに置き、各アプリは利用者の承認で読む。 |

- これに合わせ、**シリーズの署名鍵は共通**にする（Android の署名鍵を全アプリで同じにする。別にする場合は `protectionLevel="knownSigner"`（Android 12以降）で許可する鍵を列挙）。

## 5. Google Cloud は1つのプロジェクトで

- シリーズで1つのプロジェクト（`google.cloudProject` に名前を記録）。APIの有効化（Drive・People・Calendar）、同意画面、テストユーザーは**最初のアプリで1回だけ**。
- 2つ目以降のアプリで要るのは、**Android用OAuthクライアントの追加だけ**（パッケージ名が違うため。SHA-1は共通鍵なら同じ）。
- Web版は同じオリジンなら**同じWebクライアントID**をそのまま使える（`google.webClientId` を引き継ぐ）。
- アプリ内の「Google連携の初期設定ガイド」は、`series` に `google.cloudProject` があれば「プロジェクト作成」「API有効化」「同意画面」「テストユーザー」を済みとして畳み、残りの手順だけを出す。

## 6. Theta Meishi Hatch（最初のアプリ）の対応状況

- 0.15.4時点：未実装（この文書が仕様）。実装するときは、Meishiの設定画面のモデル・アカウント・カレンダー登録先を `series` に書き出し、`follow` を持つ。ContentProvider（§4）も追加する。
