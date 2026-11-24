# Tsuzukiのビルドとテスト

ローカルビルドにはJDK 17以上、Python 3、Android SDK Platform 36、Android SDK Build Tools 36.0.0が必要です。

```sh
git clone https://github.com/tkharuya/tsuzuki.git
cd tsuzuki
export ANDROID_SDK_ROOT=/absolute/path/to/Android/Sdk
bash build-local.sh
```

現在の出力先は `build/outputs/tsuzuki-1.12.2-personal.apk` です。初回はローカルの署名鍵を生成します。Android Studioの構成、依存ライブラリ、更新用署名の詳細は[Androidビルドガイド](./scripts/BUILD.md)を参照してください。

## ローカルテスト

Node.js 20以上を用意してリポジトリ直下から実行します。ブラウザ試験はテスト用のNative bridgeとサンプル本文を使います。

```sh
npm --prefix tests install
npm --prefix tests test
(cd tests && npx playwright install chromium)
npm --prefix tests run test:ui
npm --prefix tests run test:reader-display-ui
npm --prefix tests run test:reader-scroll
```

既存のChromiumを使うときはインストールの行を省略し、実行ファイルを `NOVELSHELF_CHROME` に指定します。実行結果の詳細は[検証記録](./VALIDATION.md)にあります。
