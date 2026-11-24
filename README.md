# Tsuzuki / つづき

TsuzukiはAndroid向けの日本語小説リーダーです。本文とルビの対応を維持し、話者ごとに声を割り当てて読み上げます。音声モデルと辞書の取得後は、VOICEVOXで端末内の音声合成ができます。

Tsuzuki is an Android reader for Japanese long-form text with ruby-aware, speaker-specific text-to-speech. VOICEVOX audio synthesis runs locally once voice data is installed.

## ソースとビルド

- [Tsuzuki 1.12.1のソース一式](./tsuzuki-1.12.1-github-source.zip)
- [ビルド手順](./BUILD.md)
- [1.12.1の検証記録](./VALIDATION.md)
- [ソースZIPのSHA-256](./tsuzuki-1.12.1-github-source.zip.sha256)

ソースZIPには、AndroidのJavaコードと画面、ビルドスクリプト、テスト、依存ライブラリの配布元とライセンスが含まれています。Android 8.0以降の64ビット端末を対象としています。

## 不具合報告

Issueにはアプリの版、Androidの版、再現手順、期待した動作、実際の動作を書いてください。読み上げの再現例には自作など公開可能な短い文章を使ってください。

Tsuzukiの独自コードは[MITライセンス](./LICENSE)です。同梱の依存ライブラリと追加取得する音声データには、各提供元の条件が適用されます。詳細はソース内の `third_party/offline/README.md` と `third_party/offline/licenses/` にあります。
