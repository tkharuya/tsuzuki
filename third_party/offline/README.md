# Offline VOICEVOX dependencies

This directory contains the official VOICEVOX CORE 0.17.1 Android Java API and its Java dependencies. Native libraries are in `app/src/main/jniLibs/arm64-v8a` and `app/src/main/jniLibs/x86_64`. No NDK build, Python process, local web server or external inference service is required on the phone.

## Pinned upstream artifacts

- CORE Java package: <https://github.com/VOICEVOX/voicevox_core/releases/tag/0.17.1>
- ONNX Runtime 1.23.2: <https://github.com/VOICEVOX/onnxruntime-builder/releases/tag/voicevox_onnxruntime-1.23.2>
- Voice models: <https://github.com/VOICEVOX/voicevox_vvm/releases/tag/0.17.1>
- Japanese dictionary: <https://github.com/r9y9/open_jtalk/releases/tag/v1.11.1>

`native-dependencies.json` records each packaged native/API file's size, SHA-256, upstream archive URL and archive SHA-256. The Android AAR was extracted from `java_packages.zip`. Upstream GitHub release digests were verified before extraction. `dependencies.json` records the Maven Java dependencies.

`manifest.json` records the nine model files and dictionary archive fetched on the user's explicit request. Their URLs, sizes and SHA-256 values are fixed. The dictionary URL is the same source used by the official CORE 0.17.1 downloader. That dictionary release does not publish an asset digest, so its SHA-256 was calculated from the downloaded upstream archive and pinned in the app.

## Storage and runtime behavior

The initial download is 565,360,558 bytes. The installed dictionary and nine model files occupy 649,018,528 bytes, before filesystem overhead and generated audio caches. About 800 MB of free space is recommended for the initial installation. Voice data is stored under the app's private no-backup directory. It is not included in this source archive or APK.

The installer uses a data-sync foreground service, a visible progress notification and a pause action. HTTPS redirects are restricted to the official GitHub asset hosts. Interrupted requests can resume with HTTP Range; an origin that returns a complete response starts the partial file over. Every completed archive/model is checked for exact length and SHA-256 before atomic promotion. Already verified files survive a pause or process restart. Archive extraction only permits the nine expected dictionary files, with their exact sizes, and rejects links and path traversal. Network access is not needed after installation for synthesis.

The engine uses CPU inference and keeps at most two models loaded. Cast and emotion processing select a style within the chosen character; the engine applies bounded speed, pitch, intonation, volume and pause values. WAV output is 24 kHz mono PCM16. Native inference cannot be interrupted in the middle of one call: cancellation is checked around the native calls, and cancelled audio is discarded. Analysis through GPT still requires the existing online account flow.

CORE 0.17.1's Java `Synthesizer` and `OpenJtalk` have no public `close()` method. The wrapper explicitly unloads resident voice models and drops its synthesizer reference; upstream JNI owner cleanup occurs through upstream finalization.

## Voices and attribution

The selectable characters are 四国めたん, ずんだもん, 春日部つむぎ, 雨晴はう, 玄野武宏, 白上虎太郎, 青山龍星, 冥鳴ひまり, 九州そら and 櫻歌ミコ. All selected IDs are talk styles from the official VVM table. Several VVM files also contain other characters, which are not exposed by this app.

The full upstream voice-library README, terms, CORE license, dictionary COPYING, ONNX Runtime license/notices and C++ runtime license are retained under `licenses/`. Individual character terms apply to generated audio. The app displays the credits and provides an offline copy of these terms. These are existing licensed character voice libraries, not imitations of a particular actor.

## Validation scope

`tests/VoicevoxNativeSmoke.java` ran real upstream desktop JNI with ONNX Runtime 1.23.2 and the same nine VVM files and dictionary. It synthesized all 41 supported styles across the ten characters and checked WAV structure, 24 kHz mono PCM16 format and non-silent output. `tests/OfflineVoicePacksTest.java` exercises production installer/wrapper code with test-only Android and HTTP boundaries. The native inference and dictionary extraction in that test are real.

The logs under `tests/results` distinguish this Linux x86_64 execution from Android testing. Android packaging, ABI/ELF compatibility and Java/Dex compilation are separate checks. These results do not claim execution on a physical Android device or measurement of mobile latency, battery consumption or thermal behavior.
