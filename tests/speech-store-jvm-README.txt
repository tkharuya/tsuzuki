SpeechStore JVM verification (1.7.0)
===================================

SpeechStoreTest compiles and executes production SpeechStore, SpeechPlan,
CastRegistry, OfflineVoices, SpeechAnalyzer and Json. The suite uses these Android
boundary doubles:

- Context: app files/cache directories and preferences lookup.
- SharedPreferences: strings persisted to fixture files; apply() is synchronous
  in this double for deterministic tests.
- AtomicFile: a disk-backed replacement contract with injected write failures.
- ReaderStore: only the rawBook() existence check; no simulated SQLite engine.

AndroidKeyStore classes come from android.jar. The tests do not implement or claim
to validate AndroidKeyStore encryption/decryption. An opaque non-secret marker is
inserted directly into fixture preferences to verify that settings() returns only
hasApiKey, never the stored GPT credential. The obsolete encrypted Gemini credential
and cloud TTS settings are removed on migration without altering the GPT credential.

The tests cover first-time migration to the offline engine, later explicit device
selection, stable per-book offline voices through global default changes, concurrent
manual edits and analysis checkpoints. Completed unit annotations, text hashes,
manual locks and resume positions are preserved and reused without a new GPT request.
Failures still cannot partially commit settings or overwrite locked profiles.

Run from the project directory with the complete dependency-aware test runner:

  python3 tests/run-jvm-tests.py --json-jar /path/to/json.jar \
    --android-jar /path/to/android.jar --java /path/to/java

Per-suite logs are written to build/jvm-tests/, including SpeechStoreTest.log.

For queued range execution and account coordination, also use:

  python3 tests/run-range-runtime-tests.py --json-jar /path/to/json.jar \
    --android-jar /path/to/android.jar --java /path/to/java
  python3 tests/run-session-tests.py --json-jar /path/to/json.jar \
    --android-jar /path/to/android.jar --java /path/to/java

These checks do not verify Android AtomicFile durability, real SharedPreferences
async scheduling, AndroidKeyStore, SQLite, the Android service lifecycle, native
VOICEVOX synthesis or audio playback. No real API credential, network request or
third-party novel text is used. The shared test-only VoicevoxEngine compilation
boundary throws if synthesis is attempted; it does not emulate generated audio.

The doubles directory must never enter an Android production build. The app's
normal build includes only app/src/main/java.
