# Tsuzuki 1.12.1のビルド

[ソースZIP](./tsuzuki-1.12.1-github-source.zip)を展開し、`tsuzuki` ディレクトリをAndroid Studioで開きます。JDK 17以上、Android SDK Platform 36、Android SDK Build Tools 36相当が必要です。Gradleでビルドする場合はGradle 8.13とAndroid Gradle Plugin 8.13.0を使います。

ローカルビルドは、Android SDKを標準の配置で用意し、環境変数 `ANDROID_SDK_ROOT` にSDKの場所を設定してから実行します。

```sh
cd tsuzuki
bash build-local.sh
```

生成ファイルは `build/outputs/tsuzuki-1.12.1-personal.apk` です。初回はローカルの署名鍵を生成します。詳細なSDK設定、依存物の確認、テスト実行方法は展開したソースの `scripts/BUILD.md` にあります。
