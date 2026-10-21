# リリースの手順

`android/app/build.gradle` の `versionName`（と `versionCode`）、`desktop/package.json` の `version` を同じ版に揃え、
`docs/releases/v<版>.md`（リリースノート）を書いてコミットし、GitHubの Actions →「Release」→「Run workflow」を押します（タグ `v<版>` はワークフローが作ります）。手元からタグを送っても同じです。

```sh
git tag v0.14.0
git push origin v0.14.0
```

GitHub Actions（`.github/workflows/release.yml`）が、テスト → Android APK・Windowsインストーラー・Mac（dmg/zip、Appleシリコン/Intel）を作り、
そのタグのGitHub Releaseとして公開します。版がずれている・リリースノートが無いと最初で止まります。

## 自動更新のしくみ

- **Windows**：`electron-updater` がReleasesの `latest.yml` を見て、新しい版をバックグラウンドでダウンロードし、次の起動（またはトレイの「再起動して更新」）で入れ替える。
- **Mac**：Appleの開発者署名が無いと自動入れ替えができないため、`latest-mac.yml` で新しい版を知らせ、ダウンロードページを開く。
- **Android**：起動時（12時間に1回まで）に最新のReleaseを確認し、Wi-FiならAPKを先にダウンロード（GitHubが公開するSHA-256で確認）。ホーム画面のお知らせを押すとアプリ内でインストール（`UpdateInstaller`：PackageInstaller。署名が違うAPKはAndroidが拒否）。初回は「この提供元のアプリを許可」と確認が要るが、Android 12以降は一度アプリ自身で更新すると、次からはアプリを閉じている間に確認なしで更新される。
- **Web版**：開いたときにService Workerが新しい版に入れ替える（次に開いたときから新しい版）。
- どれも **リポジトリが公開** であることが前提（非公開だとReleasesを読めない）。

## AndroidのAPK署名（初回だけ設定）

署名鍵はリポジトリに入れず、GitHubの Secrets に置きます。
リポジトリの Settings → Secrets and variables → Actions → New repository secret で次の2つを登録：

| 名前 | 中身 |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | 署名鍵 `meishiclaude-release.jks` をBase64にした文字列（Mac：`base64 -i meishiclaude-release.jks`、Windows PowerShell：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("meishiclaude-release.jks"))`） |
| `ANDROID_KEYSTORE_PASSWORD` | 署名鍵のパスワード（`keystore-password.txt` の中身） |

未設定でもリリースは作られますが、APKは付きません。設定後にそのタグのワークフローを「Re-run all jobs」すると、APKが追加されます。
**同じ鍵で署名し続けること**（鍵が変わると、上書きインストールできなくなります）。
