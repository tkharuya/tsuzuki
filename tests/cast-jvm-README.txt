Cast, offline voice and GPT analysis regression checks (1.7.0)
============================================================

These tests execute the production Java classes with a JVM org.json implementation.
All novel text, speakers, provider responses and credentials are synthetic fixtures.
No live provider API is called. OfflineVoices is the same fixed catalog and local
prosody mapping used by the Android app.

From the project root, choose a JVM org.json jar and Android SDK android.jar:

  python3 tests/run-jvm-tests.py --json-jar /path/to/json.jar \
    --android-jar /path/to/android.jar --java /path/to/java

The runner compiles all required production dependencies and runs CastRegistryTest,
SpeechPlanTest, SpeechAnalyzerTest, SpeechStoreTest, OfflineVoicesTest and the retained
account/transport/cache regression suites. Per-suite logs go to build/jvm-tests/.

Covered behavior:
- The first offline migration assigns a stable supported voice to each old identity.
  Names, aliases, manual flags, old device voice settings and speaking baselines survive.
- Existing offline voice assignments remain fixed across global default changes,
  subsequent GPT analysis, and model changes; only explicit user edits replace them.
- A shared display name never causes an automatic identity merge.
- Exact existing aliases recover stable IDs; ambiguous legacy aliases use unknown.
- Legacy alias collisions remain editable; newly introduced collisions are rejected.
- Reserved narrator and unknown profiles keep user choices.
- Manual rate and pitch keep the app's wider ranges; model prosody stays bounded.
- An edit made during a GPT request is refreshed before its response is applied.
- GPT's strict metadata schema accepts the ten offline IDs, not cloud voice IDs.
- Each emotion selects only a style of the already pinned voice; absent styles use
  bounded local AudioQuery parameters. Numeric controls and pause lengths are bounded.
- Local query signatures are stable regardless of JSONObject insertion order.
- Source coverage, ruby, unit identity, locked annotations, pause/resume, failure
  checkpoints and 1,003-unit / 26-batch analysis retain the existing behavior.
- Completed annotations and saved reading positions survive the voice migration,
  with zero new analysis transport calls when reusing a completed plan.

This is logic/fixture verification, not a claim of live provider calls or Android
playback. The test-only VoicevoxEngine boundary refuses synthesis. Native model
loading/synthesis and actual Android service/playback integration require separate
verification; test doubles never enter the production build.
