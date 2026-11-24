# Tsuzuki / つづき

TsuzukiはAndroid向けの日本語小説リーダーです。本文とルビの対応を維持し、話者ごとに声を割り当てて読み上げます。音声モデルと辞書の取得後は、VOICEVOXで端末内の音声合成ができます。

Tsuzuki is an Android reader for Japanese long-form text with ruby-aware, speaker-specific text-to-speech. VOICEVOX audio synthesis runs locally once voice data is installed.

カクヨム・小説家になろう・Wuxiaworldの作品とTXTファイルを同じ本棚で管理できます。読書位置、既読、付箋、保存済み本文は端末に保持します。Android 8.0以降の64ビット端末が対象です。

## 1.12.2の変更

- 目次の取得が続いていても、入力中の検索語とフォーカスを維持します。話数は全角数字でも検索できます。
- 「現在の話へ」で読書位置の行を表示します。目次の読み込み中に閉じた場合、取得終了時に開き直す問題も修正しました。

## ソースとビルド

- [Androidソース](./app/src/main) · [テスト](./tests) · [ビルドとテスト手順](./BUILD.md) · [検証記録](./VALIDATION.md)
- [使い方](./docs/USER_GUIDE.md)
- [1.12.1のソースアーカイブ](./tsuzuki-1.12.1-github-source.zip) · [SHA-256](./tsuzuki-1.12.1-github-source.zip.sha256)

## 不具合報告

Issueにはアプリの版、Androidの版、再現手順、期待した動作、実際の動作を書いてください。読み上げの再現例には自作など公開可能な短い文章を使ってください。

Tsuzukiの独自コードは[MITライセンス](./LICENSE)です。同梱の依存ライブラリと追加取得する音声データには、各提供元の条件が適用されます。[依存物の出典](./third_party/offline/README.md) · [ライセンス一覧](./third_party/offline/licenses)
