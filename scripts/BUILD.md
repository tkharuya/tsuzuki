# Androidビルド

Android 8.0以上（minSdk 26）を対象とします。バージョンは `version.properties` に定義します。音声処理には **VOICEVOX CORE 0.17.1** と **VOICEVOX ONNX Runtime 1.23.2** を使用します。実行時の依存JARとJNIライブラリを同梱する構成です。

APKの対応ABIは `arm64-v8a` と `x86_64` です。32bit専用端末向けのネイティブライブラリは含めません。

## 同梱物と初回ダウンロード

| 種別 | 場所・扱い |
| --- | --- |
| VOICEVOX Java APIと依存JAR | `third_party/offline/libs/`。コンパイルとDEX生成に使用 |
| 公式Android AAR | `third_party/offline/voicevoxcore-android-0.17.1.aar` |
| JNI | `app/src/main/jniLibs/arm64-v8a/` と `x86_64/`。VOICEVOX Java API、ONNX Runtime、`libc++_shared.so` |
| Java依存の固定情報 | `third_party/offline/dependencies.json` |
| 公式JAR/AAR・JNIの固定情報 | `third_party/offline/native-dependencies.json` |
| ライセンス | `third_party/offline/licenses/` と `app/src/main/assets/voice-licenses.txt` |
| 音声モデル・辞書 | APKとソースZIPには含めず、アプリで利用者が初回取得を開始 |

各manifestには取得元、サイズ、SHA-256を記録しています。`build-local.sh` は同梱物をmanifestと照合してからビルドします。依存ファイルを更新する場合は、対応する固定情報とライセンスも更新します。

音声モデルと辞書の取得量は565,360,558 bytes（約565 MB）、準備後は649,018,528 bytes（約649 MB）です。初回取得・展開の空き容量の目安は約800 MB以上です。これらはAPKのサイズとは別で、生成した音声のキャッシュも別途最大256 MiBを目安に使用します。

## SDKツールによるビルド

必要なもの：

- Java 17以上（`jdk.compiler`、`keytool`を含むJDK）
- Python 3
- Android SDK Platform 36
- Android SDK Build Tools 36相当（`aapt2`、`d8.jar`、`zipalign`、`apksigner.jar`）
- 配布ソースに含まれる上記JAR・JNIとmanifest

SDKの標準配置では、プロジェクト直下で実行します。

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/Android/Sdk
bash build-local.sh
```

標準と異なる配置では次の環境変数を指定します。

```sh
export NOVELSHELF_BUILD_TOOLS=/absolute/path/to/build-tools/36.0.0
export NOVELSHELF_ANDROID_JAR=/absolute/path/to/platforms/android-36/android.jar
bash build-local.sh
```

生成先は `build/outputs/tsuzuki-<VERSION_NAME>-personal.apk` です。APKのSHA-256ファイルも生成します。SDK・JDKと同梱依存物が用意されていれば、**このローカルビルドはオフラインで実行できます**。

スクリプトは依存物の固定情報を確認し、Javaコンパイル、リソースリンク、DEX生成、両ABIのJNI同梱、APK署名検証、ZIPアラインメント検証を行います。

## Android Studio・Gradle

プロジェクトをAndroid Studioで開きます。Android Gradle Plugin 8.13.0、Gradle 8.13、JDK 17、Android SDK 36を使用します。アプリのJAR依存はローカルの `third_party/offline/libs/`、JNIは `app/src/main/jniLibs/` を参照します。

GradleやAndroid Gradle Plugin自体の初回導入・キャッシュ準備にはネットワーク接続が必要です。これは、準備済みSDKを使う `build-local.sh` のオフラインビルドとは別です。

Gradle 8.13が利用できる場合：

```sh
gradle :app:assembleDebug
```

AGPの互換性資料：<https://developer.android.com/build/releases/agp-8-13-0-release-notes>

## 署名鍵と上書き更新

既存アプリへの上書き更新には、同じパッケージ名と署名鍵が必要です。

`build-local.sh` は初回に `.signing/novelshelf-release.p12` と `.signing/password.txt` を作成し、以後はその鍵を再利用します。既存鍵を上書きしません。**署名鍵・パスワードは配布ソースZIPに含めません。** 別環境で同じアプリの更新を作る場合は、別途保管した元の署名情報を復元してください。新しく作った鍵では既存の個人用APKを上書きできません。

次の更新を作るときは `version.properties` の `VERSION_CODE` を増やします。Android Studioのreleaseビルドも署名情報があれば同じ鍵を使用します。

## 検証と動作確認の区別

今回の検証記録は [../VALIDATION.md](../VALIDATION.md) にまとめています。

- 「自然な会話」はLinuxで実際のVOICEVOX COREを用いて生成・測定しました。全10話者・41スタイルの旧版の網羅記録も同梱しています。
- 1.12.0の試聴画面は `cd tests && npm install && npm run test:dialogue-acting` で実行できます。本番HTML・JavaScript、jsdom、試験用Native bridgeを使用します。最新の実行件数と結果は検証記録を参照してください。
- **Android実機・エミュレーターでの実行は未検証です。** Linuxでの音声生成とブラウザー・JVM試験は、AndroidのJNIロード、初回取得、発声、速度・発熱、通知、Keystore、実アカウント認証の確認とは区別しています。

音声モデルと本文を保存した後のVOICEVOX生成は端末内で行います。GPTの新規解析と未保存本文の取得には通信が必要です。英語の読み上げにはAndroid TTSを引き続き選択できます。


### 1.12.0の試聴WAVと計測の再現

`VOICE_FIXTURES` は辞書・固定モデル・Linux版ランタイムの試験素材、`VOICE_INSTALLED` はその辞書とモデルを配置済みの `private/` とネイティブライブラリの `native/` を含むディレクトリです。前版で配布した `tsuzuki-1.11.0-anime.wav` を比較の基準に使います。計測スクリプトにはNumPyが必要です。

```sh
python3 tests/run-offline-runtime-tests.py --suite auditions --fixtures "$VOICE_FIXTURES" --installed "$VOICE_INSTALLED" --json-jar "$JSON_JAR"
samples=build/offline-runtime-tests
python3 tests/measure-dialogue-acting.py --natural "$samples/samples-natural" --anime "$samples/samples-anime" --standard "$samples/samples-standard" --published-anime /path/to/tsuzuki-1.11.0-anime.wav --out build/auditions --report tests/results/conversation-audio.json
```


### 1.12.1のスクロール検証

PlaywrightとChromiumを用意し、`node tests/reader-scroll-stability.cjs` で393px幅と720px幅のスワイプを再現できます。任意のChromium実行ファイルは `NOVELSHELF_CHROME`、APKから展開した `index.html`、`app.js`、`app.css` のディレクトリは `NOVELSHELF_READER_ASSETS` で指定できます。比較用の計測結果を別の場所に出すには `NOVELSHELF_SCROLL_REPORT` に出力JSONのパスを指定します。
