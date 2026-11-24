package jp.haruyalab.novelshelf;

import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real persisted queue + analyzer + compact SSE; only Android/SQLite/page/auth/HTTP boundaries are synthetic. */
public final class SpeechAnalysisRuntimeTest {
    private static int checks;
    private static SpeechStoreTestContext context;
    private static SpeechStore speech;
    private static final List<JSONObject> events = new ArrayList<>();
    private interface Run { void run() throws Exception; }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void denied(Run run) throws Exception { try { run.run(); throw new AssertionError("operation should be rejected"); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; } }
    private static String url(int work, int index) { return "https://kakuyomu.jp/works/" + work + "/episodes/" + (1000 + index); }
    private static void install(String id, int work, int[] indices, boolean complete, int units, boolean cache) {
        JSONArray catalog = new JSONArray(), chapters = new JSONArray();
        for (int n = 0; n < indices.length; n++) {
            int index = indices[n]; String url = url(work, index); JSONArray paragraphs = new JSONArray();
            for (int i = 0; i < units; i++) paragraphs.put(Json.obj("text", "「自作の試験台詞。" + id + "章" + index + "区間" + i + "。」"));
            catalog.put(Json.obj("url", url, "index", index, "title", id + "第" + (index + 1) + "話", "read", n == 0));
            chapters.put(Json.obj("url", url, "bookId", id, "index", index, "title", id + "第" + (index + 1) + "話", "paragraphs", paragraphs,
                    "nextUrl", n + 1 == indices.length ? "" : url(work, indices[n + 1])));
        }
        ReaderStore.install(Json.obj("id", id, "source", "kakuyomu", "url", "https://kakuyomu.jp/works/" + work,
                "title", id, "tocComplete", complete, "total", indices[indices.length - 1] + 1), catalog, chapters, cache);
        speech.saveCast(id, new JSONArray().put(Json.obj("id", "c_fixed", "name", "利用者の固定人物", "offlineVoice", "zundamon", "style", "利用者が指定した話し方")));
    }
    private static JSONObject response(JSONObject request, int call, boolean fail) {
        JSONObject data = Json.parse(request.optJSONArray("input").optJSONObject(0).optJSONArray("content").optJSONObject(0).optString("text"));
        JSONArray targets = Json.arr(data, "targetUnits"), assignments = new JSONArray();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i), assignment = Json.obj("unitId", target.optString("unitId"),
                    "speakerId", "c_fixed", "kind", "dialogue", "emotion", "calm", "intensity", .3, "rate", 1, "pitch", 1, "confidence", .95);
            if (target.has("parts")) {
                JSONArray parts = Json.arr(target, "parts"), acting = new JSONArray();
                for (int n = 0; n < parts.length(); n++) acting.put(Json.obj(
                        "partId", parts.optJSONObject(n).optString("partId"),
                        "emotion", "reviewing".equals(data.optString("analysisStage")) ? "relief" : "anger",
                        "delivery", "natural", "intensity", .3, "rate", 1, "pitch", 1, "intonation", 1, "volume", 1,
                        "pauseAfterMs", 120, "ending", "auto", "emphasis", .1, "confidence", .9));
                Json.put(assignment, "parts", acting);
            }
            assignments.put(assignment);
        }
        JSONObject person = Json.obj("id", "c_fixed", "name", "AIの改名候補", "aliases", new JSONArray(), "gender", "neutral", "tier", "main",
                "offlineVoice", "miko", "style", "別の話し方", "rate", 1, "pitch", 1);
        JSONObject metadata = Json.obj("cast", new JSONArray().put(person), "assignments", assignments);
        return Json.obj("id", "msg_" + call, "type", "message", "role", "assistant", "phase", "final_answer", "status", "completed",
                "content", new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())));
    }
    private static final class Transport implements SpeechAnalyzer.Transport {
        int calls, failCall;
        final Set<String> successfullyReturned = new HashSet<>();
        @Override public JSONObject send(JSONObject request, String token, SpeechAnalyzer.Cancel cancel) throws Exception {
            calls++; eq(token, ChatGptSession.TOKEN);
            JSONObject data = Json.parse(request.optJSONArray("input").optJSONObject(0).optJSONArray("content").optJSONObject(0).optString("text"));
            yes(!data.has("pronunciationTargets"), "a resumed pre-reading standard job keeps its original request contract");
            JSONArray targets = Json.arr(data, "targetUnits"), known = Json.arr(data, "knownCast");
            JSONObject fixed = null;
            for (int i = 0; i < known.length(); i++) if ("c_fixed".equals(known.optJSONObject(i).optString("id"))) fixed = known.optJSONObject(i);
            yes(fixed != null, "per-book fixed cast reaches every chapter/batch"); eq(fixed.optString("offlineVoice"), "zundamon");
            String stream = event("response.output_item.done", Json.obj("output_index", 0, "item", response(request, calls, calls == failCall)));
            if (calls == failCall) stream += event("response.failed", Json.obj("response", Json.obj("status", "failed", "error",
                    Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", "PRIVATE_QUOTA_BODY" + token))));
            else stream += event("response.completed", Json.obj("response", Json.obj("status", "completed", "usage", Json.obj("input_tokens", 2, "output_tokens", 1))));
            JSONObject result = ChatGptResponses.readCompleted(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), cancel, "req_range_" + calls);
            for (int i = 0; i < targets.length(); i++) yes(successfullyReturned.add(targets.optJSONObject(i).optString("unitId")), "completed unit is never resent on resume");
            return result;
        }
    }
    private static final class DetailedTransport implements SpeechAnalyzer.Transport {
        final String bookId;
        final String chapterUrl;
        int calls, failCall, planningCalls, reviewCalls;
        DetailedTransport(String bookId, int work) { this.bookId = bookId; chapterUrl = url(work, 0); }
        @Override public JSONObject send(JSONObject request, String token, SpeechAnalyzer.Cancel cancel) throws Exception {
            calls++; eq(token, ChatGptSession.TOKEN);
            JSONObject data = Json.parse(request.optJSONArray("input").optJSONObject(0).optJSONArray("content").optJSONObject(0).optString("text"));
            yes(!data.has("pronunciationTargets"), "a resumed legacy two-pass job does not add pronunciation requests");
            String phase = data.optString("analysisStage");
            if ("planning".equals(phase)) { planningCalls++; yes(!data.has("candidateDraft"), "planning starts from source context"); }
            else { eq(phase, "reviewing"); reviewCalls++; yes(data.has("candidateDraft"), "review receives uncommitted proposal"); }
            eq(speech.plan(bookId, chapterUrl).optString("analysisPhase"), phase);
            JSONArray saved = Json.arr(speech.plan(bookId, chapterUrl), "units");
            for (int i = 0; i < saved.length(); i++) yes(!SpeechPerformance.valid(saved.optJSONObject(i)), "draft is not stored as completed acting");
            String stream = event("response.output_item.done", Json.obj("output_index", 0, "item", response(request, calls, calls == failCall)));
            if (calls == failCall) stream += event("response.failed", Json.obj("response", Json.obj("status", "failed", "error",
                    Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", "fixture quota"))));
            else stream += event("response.completed", Json.obj("response", Json.obj("status", "completed", "usage", Json.obj("input_tokens", 2, "output_tokens", 1))));
            return ChatGptResponses.readCompleted(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), cancel, "req_fine_" + calls);
        }
    }
    private static class ChapterTransport implements SpeechAnalyzer.Transport {
        int calls, failCall, chapterCalls, historyCalls;
        final List<String> stages = new ArrayList<>();
        final List<Integer> historyBatchSizes = new ArrayList<>();
        final List<Integer> historyTextSizes = new ArrayList<>();
        final List<String> historySourceRefs = new ArrayList<>();
        final List<JSONObject> historyMemoInputs = new ArrayList<>(), chapterMemoInputs = new ArrayList<>();
        final List<JSONObject> chapterInputs = new ArrayList<>();
        String regeneratedMemoryId = "";
        final List<Integer> memoryThrough = new ArrayList<>();
        final List<String> targetUrls = new ArrayList<>();
        final Set<String> completedTargets = new HashSet<>();
        @Override public JSONObject send(JSONObject request, String token, SpeechAnalyzer.Cancel cancel) throws Exception {
            calls++; eq(token, ChatGptSession.TOKEN); yes(!request.has("max_output_tokens"), "account contract excludes API-only output limits");
            JSONObject input = Json.parse(Json.arr(Json.arr(request, "input").optJSONObject(0), "content").optJSONObject(0).optString("text"));
            String stage = input.optString("analysisStage"); stages.add(stage);
            JSONArray known = Json.arr(input, "knownCast"); JSONObject fixed = null;
            for (int i = 0; i < known.length(); i++) if ("c_fixed".equals(known.optJSONObject(i).optString("id"))) fixed = known.optJSONObject(i);
            yes(fixed != null, "fixed work cast reaches history and target inference"); eq(fixed.optString("offlineVoice"), "zundamon");
            JSONObject proposed = Json.obj("id", "c_fixed", "name", "AIの改名候補", "aliases", new JSONArray(), "gender", "neutral", "tier", "main",
                    "offlineVoice", "miko", "style", "自動変更は採用しない", "rate", 1, "pitch", 1);
            JSONObject metadata; JSONArray facts = new JSONArray(), targets = Json.arr(input, "targetUnits");
            if ("history_memory".equals(stage)) {
                historyCalls++; JSONArray chapters = Json.arr(input, "chapters"); historyBatchSizes.add(chapters.length());
                historyMemoInputs.add(Json.copy(input.optJSONObject("storyMemory")));
                yes(!input.has("targetUnits"), "history request does no per-line acting");
                yes(!input.has("pronunciationTargets"), "history bootstrap does not request unused readings");
                int textSize = 0;
                for (int c = 0; c < chapters.length(); c++) {
                    JSONObject chapter = chapters.optJSONObject(c); JSONArray source = Json.arr(chapter, "units");
                    for (int u = 0; u < source.length(); u++) {
                        JSONObject unit = source.optJSONObject(u); textSize += unit.optString("text").length();
                        historySourceRefs.add(chapter.optString("chapterUrl") + "\n" + unit.optString("unitId"));
                    }
                }
                historyTextSizes.add(textSize); yes(textSize > 0 && textSize <= 32000, "history calls respect the bounded text budget");
                for (int i = 0; i < chapters.length() && i < StoryMemory.MAX_DELTA_FACTS; i++) {
                    JSONObject chapter = chapters.optJSONObject(i); facts.put(memoryFact(chapter.optInt("chapterIndex"), chapter.optString("chapterUrl"),
                            Json.arr(chapter, "units").optJSONObject(0).optString("unitId")));
                }
                if (!regeneratedMemoryId.isEmpty() && facts.length() > 0) Json.put(facts.optJSONObject(0), "id", regeneratedMemoryId);
                metadata = Json.obj("cast", new JSONArray().put(proposed), "memoryDelta", Json.obj("facts", facts));
            } else {
                eq(stage, "chapter"); chapterCalls++; targetUrls.add(input.optString("chapterUrl"));
                chapterInputs.add(Json.copy(input));
                chapterMemoInputs.add(Json.copy(input.optJSONObject("storyMemory")));
                memoryThrough.add(input.optJSONObject("storyMemory").optInt("throughChapterIndex", -1));
                JSONArray assignments = new JSONArray();
                for (int i = 0; i < targets.length(); i++) assignments.put(Json.obj("unitId", targets.optJSONObject(i).optString("unitId"),
                        "speakerId", "c_fixed", "kind", "dialogue", "confidence", .95, "profileId", "calm", "changes", new JSONArray()));
                if (input.optBoolean("memoryEnabled") && targets.length() > 0) facts.put(memoryFact(input.optInt("chapterIndex"), input.optString("chapterUrl"), targets.optJSONObject(0).optString("sourceUnitId")));
                JSONObject profile = Json.obj("profileId", "calm", "emotion", "relief", "delivery", "natural", "intensity", .3,
                        "rate", 1, "pitch", 1, "intonation", 1, "volume", 1, "pauseAfterMs", 120, "ending", "auto", "emphasis", .1, "confidence", .9);
                metadata = Json.obj("cast", targets.length() == 0 ? new JSONArray() : new JSONArray().put(proposed),
                        "profiles", targets.length() == 0 ? new JSONArray() : new JSONArray().put(profile),
                        "assignments", assignments, "memoryDelta", Json.obj("facts", facts));
                if (input.has("pronunciationTargets")) Json.put(metadata, "pronunciations", fixturePronunciations(input));
            }
            metadata = metadata(input, metadata);
            JSONObject message = Json.obj("id", "msg_chapter_" + calls, "type", "message", "role", "assistant", "phase", "final_answer", "status", "completed",
                    "content", new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())));
            String stream = event("response.output_item.done", Json.obj("output_index", 0, "item", message));
            if (calls == failCall) stream += event("response.failed", Json.obj("response", Json.obj("status", "failed", "error",
                    Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", "fixture quota"))));
            else stream += event("response.completed", Json.obj("response", Json.obj("status", "completed", "usage", Json.obj("input_tokens", 3, "output_tokens", 2))));
            JSONObject result = ChatGptResponses.readCompleted(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), cancel, "req_chapter_" + calls);
            for (int i = 0; i < targets.length(); i++) yes(completedTargets.add(targets.optJSONObject(i).optString("sourceUnitId")), "completed source IDs are not resent");
            return result;
        }
        JSONObject metadata(JSONObject input, JSONObject value) { return value; }
    }
    private static final class AliasMemoryTransport extends ChapterTransport {
        String invalidStage = "", invalidReason = "";
        @Override JSONObject metadata(JSONObject input, JSONObject value) {
            JSONArray proposed = new JSONArray();
            for (String suffix : new String[]{"subject", "object"}) proposed.put(Json.obj(
                    "id", "c_alias_" + suffix, "name", "PRIVATE_ALIAS_" + suffix, "aliases", new JSONArray(),
                    "gender", "neutral", "tier", "main", "offlineVoice", "miko", "style", "PRIVATE_NEW_STYLE",
                    "rate", 1.3, "pitch", 1.2));
            Json.put(value, "cast", proposed);
            JSONArray assignments = Json.arr(value, "assignments");
            for (int i = 0; i < assignments.length(); i++) Json.put(assignments.optJSONObject(i), "speakerId", "c_alias_subject");
            JSONArray facts = Json.arr(value.optJSONObject("memoryDelta"), "facts");
            for (int i = 0; i < facts.length(); i++) {
                JSONObject fact = facts.optJSONObject(i);
                Json.put(fact, "type", "relationship"); Json.put(fact, "subjectId", "c_alias_subject");
                Json.put(fact, "objectId", "c_alias_object"); Json.put(fact, "text", "PRIVATE_FACT_BODY_自作の人物関係。");
            }
            if (invalidStage.equals(input.optString("analysisStage")) && facts.length() > 0) {
                JSONObject fact = facts.optJSONObject(0);
                if ("person".equals(invalidReason)) Json.put(fact, "objectId", "c_PRIVATE_UNSUPPORTED_PERSON");
                else if ("citation".equals(invalidReason)) {
                    JSONObject citation = Json.arr(fact, "citations").optJSONObject(0);
                    Json.put(citation, "unitId", "PRIVATE_UNSUPPLIED_UNIT");
                    Json.put(citation, "chapterUrl", "https://kakuyomu.jp/works/999999/episodes/888888");
                }
            }
            return value;
        }
    }
    private static final class HeldRequest {
        final JSONObject input;
        final CountDownLatch release = new CountDownLatch(1);
        final int number;
        HeldRequest(JSONObject request, int number) {
            input = Json.parse(Json.arr(Json.arr(request, "input").optJSONObject(0), "content").optJSONObject(0).optString("text"));
            this.number = number;
        }
    }
    /** A transport call is observable while its completed response is still unavailable. */
    private static final class GatedTransport implements SpeechAnalyzer.Transport {
        final ChapterTransport delegate;
        final AtomicInteger sends = new AtomicInteger();
        final LinkedBlockingQueue<HeldRequest> arrivals = new LinkedBlockingQueue<>();
        GatedTransport(ChapterTransport delegate) { this.delegate = delegate; }
        @Override public JSONObject send(JSONObject request, String token, SpeechAnalyzer.Cancel cancel) throws Exception {
            HeldRequest held = new HeldRequest(request, sends.incrementAndGet()); arrivals.put(held);
            while (!held.release.await(20, TimeUnit.MILLISECONDS)) if (cancel != null && cancel.isCancelled())
                throw new java.io.InterruptedIOException("fixture transport cancelled");
            if (cancel != null && cancel.isCancelled()) throw new java.io.InterruptedIOException("fixture transport cancelled");
            return delegate.send(request, token, cancel);
        }
        HeldRequest take() throws Exception {
            long end = System.nanoTime() + 5000000000L; HeldRequest held;
            while ((held = arrivals.poll()) == null && System.nanoTime() < end) Looper.runFixtureEvent(20);
            yes(held != null, "inference entered the controlled response boundary");
            Looper.drainFixtureQueue(); return held;
        }
    }
    private static JSONObject memoryFact(int index, String url, String unitId) {
        return Json.obj("id", "m_chapter_" + index, "type", "state", "subjectId", "c_fixed", "objectId", "",
                "text", "自作第" + (index + 1) + "話の状況。", "uncertain", false,
                "citations", new JSONArray().put(Json.obj("chapterUrl", url, "unitId", unitId)));
    }
    private static String event(String type, JSONObject body) { return "event: " + type + "\ndata: " + Json.merge(Json.obj("type", type), body) + "\n\n"; }
    private static void idle(boolean drain) throws Exception {
        long end = System.nanoTime() + 10000000000L;
        while (SpeechAnalysis.running() && System.nanoTime() < end) {
            if (drain) Looper.runFixtureEvent(20); else Thread.sleep(10);
        }
        yes(!SpeechAnalysis.running(), "queue exits within fixture deadline"); if (drain) Looper.drainFixtureQueue();
    }
    private static void waitHeld() throws Exception {
        long end = System.nanoTime() + 5000000000L;
        while (SpeechContent.held == null && System.nanoTime() < end) Looper.runFixtureEvent(20);
        yes(SpeechContent.held != null, "page loading reached the controlled boundary");
    }
    private static JSONObject start(String id, int start, long count, SpeechAnalyzer.Transport transport) {
        // These transports deliberately retain the saved 1.9.1 contracts. New jobs use ChapterTransport.
        if (transport instanceof Transport && !SpeechAnalysis.running()) return startLegacy(id, start, count, false, transport);
        return SpeechAnalysis.startRange(context, id, start, count, "", transport);
    }
    private static JSONObject startLegacy(String id, int start, long count, boolean detailed, SpeechAnalyzer.Transport transport) {
        ReaderStore books = new ReaderStore(context); JSONObject settings = speech.settings();
        JSONObject range = SpeechAnalysisRange.create(books.rawBook(id), books.chapters(id), start, count);
        Json.put(range, "jobId", java.util.UUID.randomUUID().toString()); Json.put(range, "status", "paused");
        Json.put(range, "provider", "chatgpt"); Json.put(range, "accountId", ChatGptSession.selectedAccount);
        Json.put(range, "model", ChatGptSession.selectedModel);
        Json.put(range, "defaultVoice", settings.optString("offlineDefaultVoice", OfflineVoices.defaultVoice()));
        Json.put(range, "useRuby", settings.optBoolean("useRuby", true)); Json.put(range, "detailedActing", detailed);
        Json.put(range, "analysisDetail", detailed ? "detailed" : "standard"); Json.put(range, "workflowVersion", 2);
        Json.put(range, "useStoryMemory", false); Json.put(range, "historyFromNumber", 0); Json.put(range, "historyToNumber", 0);
        Json.put(range, "historyTotal", 0); Json.put(range, "historyCompleted", 0); Json.put(range, "historyThrough", 0);
        Json.put(range, "memoryStatus", "disabled"); Json.put(range, "historyError", "");
        yes(!range.has("pronunciationVersion"), "saved legacy fixture has no reading opt-in");
        speech.beginAnalysisRange(id, range); return SpeechAnalysis.resumeRange(context, id, transport);
    }
    /** Readings for only the self-authored fixture corpus, never an automatic model-accuracy claim. */
    private static String fixtureReading(String source) {
        String reading = source.replace("自作の長文試験", "ジサクノチョーブンシケン")
                .replace("自作の試験台詞", "ジサクノシケンゼリフ")
                .replace("今日の生物は冷蔵庫に入れる", "キョーノナマモノワレーゾーコニイレル")
                .replace("今日は生物を学ぶ", "キョーワセーブツオマナブ")
                .replace("母は花を運ぶ", "ハハワハナオハコブ")
                .replace("は微笑んだ", "ワホホエンダ").replace("区間", "クカン").replace("章", "ショー");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < reading.length();) {
            int cp = reading.codePointAt(i); i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                throw new AssertionError("self-authored fixture needs an explicit reading: " + source);
            out.appendCodePoint(cp >= 0x3041 && cp <= 0x3096 ? cp + 0x60 : cp);
        }
        return out.toString();
    }
    private static JSONArray fixtureReadingParts(JSONArray parts) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            out.put(Json.obj("partId", part.optString("partId"), "reading", fixtureReading(part.optString("readingSource"))));
        }
        return out;
    }
    private static JSONArray fixturePronunciations(JSONObject input) {
        JSONArray targets = Json.arr(input, "pronunciationTargets"), out = new JSONArray();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            out.put(Json.obj("unitId", target.optString("unitId"), "parts", fixtureReadingParts(Json.arr(target, "parts"))));
        }
        return out;
    }
    private static void attachFixtureReadings(JSONObject plan) {
        JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            Json.put(unit, "pronunciation", SpeechPronunciation.validate(unit, fixtureReadingParts(SpeechPronunciation.modelParts(unit))));
            yes(SpeechPronunciation.valid(unit), "memo-only fixture already has a source-validated reading");
        }
        Json.put(plan, "pronunciationVersion", SpeechPronunciation.VERSION);
    }
    private static JSONObject withoutPresentation(JSONObject presented) {
        JSONObject copy = Json.copy(presented);
        for (String key : new String[]{"pronunciationUnits", "pronunciationTotalUnits", "missingPronunciationUnits", "missingWordUnits"}) copy.remove(key);
        JSONArray units = Json.arr(copy, "units");
        for (int i = 0; i < units.length(); i++) units.optJSONObject(i).remove("pronunciationReady");
        return copy;
    }
    private static void successfulRange() throws Exception {
        install("success", 201, new int[]{0, 1}, true, 3, false); ReaderStore books = new ReaderStore(context);
        String reading = books.position("success").toString(), catalog = books.chapters("success").toString();
        JSONObject first = ReaderStore.remote("success", url(201, 0)); JSONObject plan = speech.prepareForAnalysis("success", first);
        speech.savePosition("success", url(201, 0), 1, plan.optString("inputHash")); String playback = speech.position("success").toString();
        JSONObject preview = SpeechAnalysis.preview(context, "success", 2, 10);
        eq(preview.optLong("requestedCount"), 10L); eq(preview.optLong("count"), 1L); yes(preview.optBoolean("limitedToEnd"), "preview explicitly clamps complete TOC");
        Transport transport = new Transport(); JSONObject started = start("success", 1, 2, transport);
        eq(started.optString("status"), "running"); yes(!started.has("accountId"), "public job does not expose account storage");
        idle(true); JSONObject finished = SpeechAnalysis.snapshot(context, "success");
        eq(finished.optString("status"), "complete"); eq(finished.optInt("completedChapters"), 2); eq(transport.calls, 2);
        eq(books.position("success").toString(), reading); eq(books.chapters("success").toString(), catalog);
        eq(speech.position("success").toString(), playback); eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
        for (int i = 0; i < 2; i++) eq(speech.plan("success", url(201, i)).optString("analysisStatus"), "complete");
        yes(events.stream().anyMatch(value -> "success".equals(value.optString("bookId")) && "complete".equals(value.optJSONObject("analysisRange").optString("status"))), "completion event carries range state");
    }
    private static void failureAndResume() throws Exception {
        install("resume", 202, new int[]{0, 1}, true, 70, true); Transport transport = new Transport(); transport.failCall = 4;
        start("resume", 1, 2, transport); idle(true);
        JSONObject failed = SpeechAnalysis.snapshot(context, "resume"); eq(failed.optString("status"), "error");
        eq(failed.optInt("completedChapters"), 1); eq(failed.optInt("currentNumber"), 2); yes(failed.optBoolean("canResume"), "quota leaves resumable range");
        eq(speech.plan("resume", url(202, 0)).optInt("analyzedUnits"), 70); eq(speech.plan("resume", url(202, 1)).optInt("analyzedUnits"), 40);
        eq(failed.optJSONObject("analysisError").optString("code"), "subscription_sharing_usage_limit_exceeded");
        String first = speech.plan("resume", url(202, 0)).toString(), fixed = speech.cast("resume").toString();
        ChatGptSession.selectedAccount = "other-account"; denied(() -> SpeechAnalysis.resumeRange(context, "resume", transport)); ChatGptSession.selectedAccount = "fixture-account";
        ChatGptSession.selectedModel = "other-model"; denied(() -> SpeechAnalysis.resumeRange(context, "resume", transport)); ChatGptSession.selectedModel = "fixture-model";
        speech.saveSettings(Json.obj("analysisProvider", "api_key"));
        try { SpeechAnalysis.resumeRange(context, "resume", transport); throw new AssertionError("provider switch was silently accepted"); }
        catch (IllegalStateException expected) { yes(expected.getMessage().contains("開始した接続方法"), "provider mismatch explains the original selection before requesting any new credential"); }
        speech.saveSettings(Json.obj("analysisProvider", "chatgpt"));
        SpeechContent.failUrl = url(202, 1); SpeechAnalysis.resumeRange(context, "resume", transport); idle(true);
        JSONObject unavailable = SpeechAnalysis.snapshot(context, "resume"); eq(unavailable.optInt("completedChapters"), 1);
        yes(!unavailable.has("analysisError"), "page-load failure does not inherit the earlier quota diagnostic"); eq(transport.calls, 4);
        SpeechContent.failUrl = ""; JSONObject resumed = SpeechAnalysis.resumeRange(context, "resume", transport);
        yes(!resumed.optString("jobId").equals(failed.optString("jobId")), "resume receives a fresh session identity"); idle(true);
        JSONObject finished = SpeechAnalysis.snapshot(context, "resume"); eq(finished.optString("status"), "complete"); eq(finished.optInt("completedChapters"), 2);
        eq(transport.calls, 5); eq(transport.successfullyReturned.size(), 140);
        eq(speech.plan("resume", url(202, 0)).toString(), first); eq(speech.cast("resume").toString(), fixed);
    }
    private static void cancellationAndLateEvents() throws Exception {
        install("cancel", 203, new int[]{0}, true, 2, false); SpeechContent.blockedUrl = url(203, 0); Transport transport = new Transport();
        JSONObject started = start("cancel", 1, 1, transport); waitHeld(); yes(SpeechAnalysis.runningBook("cancel"), "loading owns the single active job");
        eq(SpeechAnalysis.activeSnapshot(context).optString("phase"), "loading");
        denied(() -> start("success", 1, 1, new Transport())); SpeechAnalysis.cancelJob(""); SpeechAnalysis.cancelJob(null); SpeechAnalysis.cancelJob("wrong-id");
        eq(SpeechAnalysis.activeSnapshot(context).optString("status"), "running");
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(false);
        JSONObject paused = SpeechAnalysis.snapshot(context, "cancel"); eq(paused.optString("status"), "paused"); eq(paused.optInt("completedChapters"), 0); eq(transport.calls, 0);
        SpeechContent.release(); int before = events.size(); JSONObject restarted = SpeechAnalysis.resumeRange(context, "cancel", transport);
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(true);
        eq(SpeechAnalysis.snapshot(context, "cancel").optString("status"), "complete");
        for (int i = before; i < events.size(); i++) if ("cancel".equals(events.get(i).optString("bookId")))
            eq(events.get(i).optJSONObject("analysisRange").optString("jobId"), restarted.optString("jobId"));
    }
    private static void invalidCompletedCheckpoint() throws Exception {
        for (boolean incompatible : new boolean[]{false, true}) {
            String id = incompatible ? "wrong-hash" : "incomplete-plan"; int work = incompatible ? 208 : 207;
            install(id, work, new int[]{0, 1}, true, 3, true); Transport first = new Transport(); first.failCall = 2;
            start(id, 1, 2, first); idle(true); eq(SpeechAnalysis.snapshot(context, id).optInt("completedChapters"), 1);
            JSONObject plan = speech.plan(id, url(work, 0));
            if (incompatible) Json.put(plan, "inputHash", "stale-plan-hash");
            else Json.put(Json.arr(plan, "units").optJSONObject(0), "analyzed", false);
            // The misleading complete flag alone must never justify skipping the chapter.
            eq(plan.optString("analysisStatus"), "complete"); speech.savePlan(id, url(work, 0), plan);
            Transport resumed = new Transport(); SpeechAnalysis.resumeRange(context, id, resumed); idle(true);
            eq(SpeechAnalysis.snapshot(context, id).optString("status"), "complete"); eq(resumed.calls, 2);
            eq(resumed.successfullyReturned.size(), incompatible ? 6 : 4);
            JSONObject repaired = speech.plan(id, url(work, 0));
            yes(SpeechPlan.compatible(repaired, new ReaderStore(context).chapter(id, url(work, 0))), "resumed completed chapter has a compatible body hash");
            eq(SpeechPlan.analyzedCount(repaired), 3);
        }
    }
    private static void deletionAndMissingLinks() throws Exception {
        install("delete", 204, new int[]{0}, true, 2, false); SpeechContent.blockedUrl = url(204, 0);
        Transport deleted = new Transport(); start("delete", 1, 1, deleted); waitHeld();
        SpeechAnalysis.cancelBook("delete"); speech.deleteBook("delete"); SpeechContent.release(); idle(true);
        File directory = new File(new File(context.getFilesDir(), "speech"), SpeechStore.key("delete"));
        yes(!directory.exists(), "worker cannot recreate speech files while deleted book row still exists"); eq(deleted.calls, 0);
        ReaderStore.removeBook("delete"); eq(SpeechAnalysis.snapshot(context, "delete").length(), 0);
        install("sparse", 205, new int[]{0, 2}, false, 2, true); Transport sparse = new Transport();
        int loaded; synchronized (SpeechContent.loads) { loaded = SpeechContent.loads.size(); }
        start("sparse", 1, 3, sparse); idle(true); JSONObject hole = SpeechAnalysis.snapshot(context, "sparse");
        eq(hole.optString("status"), "error"); eq(hole.optInt("completedChapters"), 1); eq(sparse.calls, 1);
        synchronized (SpeechContent.loads) { yes(!SpeechContent.loads.subList(loaded, SpeechContent.loads.size()).contains(url(205, 2)), "sparse next link never skips a missing chapter"); }
        install("unknown", 206, new int[]{0}, false, 2, true); Transport unknown = new Transport();
        start("unknown", 1, 10, unknown); idle(true); JSONObject end = SpeechAnalysis.snapshot(context, "unknown");
        eq(end.optString("status"), "error"); eq(end.optLong("count"), 10L); eq(end.optInt("completedChapters"), 1);
        yes(end.optBoolean("endedEarly") && end.optBoolean("canResume"), "unknown end reports the unfinished requested count");
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
    }
    private static void detailedRangeAndResume() throws Exception {
        install("fine-upgrade", 211, new int[]{0}, true, 3, true);
        Transport standard = new Transport(); start("fine-upgrade", 1, 1, standard); idle(true); eq(standard.calls, 1);
        JSONObject original = speech.plan("fine-upgrade", url(211, 0));
        speech.savePosition("fine-upgrade", url(211, 0), 1, original.optString("inputHash"));
        String position = speech.position("fine-upgrade").toString(), cast = speech.cast("fine-upgrade").toString();
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
        JSONObject preview = SpeechAnalysis.preview(context, "fine-upgrade", 1, 1);
        eq(preview.optBoolean("detailedActing"), true); eq(preview.optString("analysisDetail"), "detailed");
        ChapterTransport fine = new ChapterTransport();
        start("fine-upgrade", 1, 1, fine); idle(true);
        JSONObject refined = speech.plan("fine-upgrade", url(211, 0)), job = SpeechAnalysis.snapshot(context, "fine-upgrade");
        eq(job.optString("status"), "complete"); eq(job.optBoolean("detailedActing"), true);
        eq(fine.chapterCalls, 1); eq(fine.historyCalls, 0); eq(refined.optInt("analyzedUnits"), 3);
        eq(job.optInt("workflowVersion"), 2); eq(refined.optString("analysisMethod"), "chapter");
        eq(refined.optString("analysisDetail"), "detailed"); eq(refined.optString("inputHash"), original.optString("inputHash"));
        for (int i = 0; i < 3; i++) {
            JSONObject before = Json.arr(original, "units").optJSONObject(i), after = Json.arr(refined, "units").optJSONObject(i);
            eq(after.optString("unitId"), before.optString("unitId")); eq(after.optString("text"), before.optString("text"));
            eq(after.optString("spokenText"), before.optString("spokenText")); yes(SpeechPerformance.valid(after), "one-pass acting matches the unchanged text");
            eq(after.optJSONObject("performance").optBoolean("reviewed"), false);
            eq(after.optJSONObject("performance").optJSONArray("parts").optJSONObject(0).optString("emotion"), "relief");
        }
        eq(speech.position("fine-upgrade").toString(), position); eq(speech.cast("fine-upgrade").toString(), cast);
        start("fine-upgrade", 1, 1, fine); idle(true); eq(fine.calls, 1); // Explicit analysis reuses complete detailed annotations and memory delta.

        install("fine-resume", 212, new int[]{0}, true, 3, true);
        ChapterTransport interrupted = new ChapterTransport(); interrupted.failCall = 1;
        start("fine-resume", 1, 1, interrupted); idle(true);
        JSONObject failed = SpeechAnalysis.snapshot(context, "fine-resume");
        eq(failed.optString("status"), "error"); eq(failed.optInt("completedChapters"), 0);
        eq(speech.plan("fine-resume", url(212, 0)).optInt("analyzedUnits"), 0);
        for (int i = 0; i < 3; i++) yes(!Json.arr(speech.plan("fine-resume", url(212, 0)), "units").optJSONObject(i).has("performance"), "failed chapter response never commits its data");
        speech.saveSettings(Json.obj("detailedActing", false, "useStoryMemory", false));
        SpeechAnalysis.resumeRange(context, "fine-resume", interrupted); idle(true);
        JSONObject resumed = SpeechAnalysis.snapshot(context, "fine-resume");
        eq(resumed.optString("status"), "complete"); eq(resumed.optBoolean("detailedActing"), true);
        eq(interrupted.calls, 2); eq(speech.plan("fine-resume", url(212, 0)).optInt("analyzedUnits"), 3);
        eq(resumed.optInt("workflowVersion"), 2); eq(resumed.optBoolean("useStoryMemory"), true);

        install("legacy-mode", 213, new int[]{0}, true, 2, true);
        Transport legacy = new Transport(); legacy.failCall = 1; start("legacy-mode", 1, 1, legacy); idle(true);
        JSONObject legacyRange = speech.analysisRange("legacy-mode"); legacyRange.remove("detailedActing"); legacyRange.remove("analysisDetail");
        legacyRange.remove("workflowVersion"); legacyRange.remove("useStoryMemory"); legacyRange.remove("pronunciationVersion");
        speech.saveAnalysisRange("legacy-mode", legacyRange);
        speech.saveSettings(Json.obj("detailedActing", true));
        SpeechAnalysis.resumeRange(context, "legacy-mode", legacy); idle(true);
        eq(legacy.calls, 2); eq(SpeechAnalysis.snapshot(context, "legacy-mode").optBoolean("detailedActing"), false);
        eq(speech.plan("legacy-mode", url(213, 0)).optString("analysisDetail"), "standard");
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
    }
    private static void legacyDetailedResume() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
        install("legacy-fine", 217, new int[]{0}, true, 3, false);
        SpeechContent.blockedUrl = url(217, 0); ChapterTransport unused = new ChapterTransport();
        JSONObject started = start("legacy-fine", 1, 1, unused); waitHeld();
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(false); SpeechContent.release();
        JSONObject old = speech.analysisRange("legacy-fine"); old.remove("workflowVersion"); old.remove("useStoryMemory"); old.remove("pronunciationVersion");
        speech.saveAnalysisRange("legacy-fine", old);
        DetailedTransport legacy = new DetailedTransport("legacy-fine", 217);
        SpeechAnalysis.resumeRange(context, "legacy-fine", legacy); idle(true);
        JSONObject done = SpeechAnalysis.snapshot(context, "legacy-fine");
        eq(done.optString("status"), "complete"); eq(done.optInt("workflowVersion"), 1);
        eq(done.optInt("pronunciationVersion"), 0);
        eq(legacy.planningCalls, 1); eq(legacy.reviewCalls, 1); eq(unused.calls, 0);
        yes(!done.optBoolean("useStoryMemory"), "old detailed job does not gain unconfirmed history work");
        yes(Json.arr(speech.plan("legacy-fine", url(217, 0)), "units").optJSONObject(0).optJSONObject("performance").optBoolean("reviewed"), "old detailed resume remains reviewed two-pass");
    }
    private static void historyBootstrapAndReuse() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
        install("history", 214, new int[]{0, 1, 2, 3, 4}, true, 3, false);
        ReaderStore books = new ReaderStore(context); String reading = books.position("history").toString(), catalog = books.chapters("history").toString();
        JSONObject target = ReaderStore.remote("history", url(214, 3)), original = speech.prepareForAnalysis("history", target);
        speech.savePosition("history", url(214, 3), 1, original.optString("inputHash"));
        String playback = speech.position("history").toString(), fixed = speech.cast("history").toString();
        JSONObject preview = SpeechAnalysis.preview(context, "history", 4, 2);
        eq(preview.optInt("historyTotal"), 3); eq(preview.optInt("historyFromNumber"), 1); eq(preview.optInt("historyToNumber"), 3);
        ChapterTransport transport = new ChapterTransport(); start("history", 4, 2, transport); idle(true);
        JSONObject finished = SpeechAnalysis.snapshot(context, "history");
        eq(finished.optString("status"), "complete"); eq(finished.optInt("completedChapters"), 2);
        eq(transport.calls, 3); eq(transport.historyCalls, 1); eq(transport.chapterCalls, 2);
        eq(transport.stages.get(0), "history_memory"); eq(transport.historyBatchSizes.get(0), 3);
        eq(transport.memoryThrough.get(0), 2); eq(transport.memoryThrough.get(1), 3);
        eq(finished.optJSONObject("memoryMetrics").optInt("requestCount"), 1);
        eq(speech.plan("history", url(214, 3)).optJSONObject("analysisMetrics").optInt("requestCount"), 1);
        eq(speech.storyMemoryBefore("history", 5).optInt("throughChapterIndex"), 4);
        eq(speech.storyMemoryBefore("history", 3).optInt("throughChapterIndex"), 2); // Never use later facts for an earlier chapter.
        eq(books.position("history").toString(), reading); eq(books.chapters("history").toString(), catalog);
        eq(speech.position("history").toString(), playback); eq(speech.cast("history").toString(), fixed);
        long revision = speech.storyMemoryBefore("history", 5).optLong("revision");
        start("history", 4, 2, transport); idle(true); eq(transport.calls, 3);
        eq(speech.storyMemoryBefore("history", 5).optLong("revision"), revision);
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
    }
    private static void missingHistoryStillReadsTarget() throws Exception {
        install("history-hole", 215, new int[]{0, 1, 2, 3}, true, 2, false);
        SpeechContent.failUrl = url(215, 1); ChapterTransport transport = new ChapterTransport();
        start("history-hole", 4, 1, transport); idle(true);
        JSONObject finished = SpeechAnalysis.snapshot(context, "history-hole");
        eq(finished.optString("status"), "complete"); eq(finished.optString("memoryStatus"), "partial");
        yes(!finished.optString("historyError").isEmpty(), "inaccessible earlier chapter is visible");
        eq(transport.historyCalls, 1); eq(transport.chapterCalls, 1); eq(transport.memoryThrough.get(0), 0);
        eq(speech.storyMemoryBefore("history-hole", 4).optInt("throughChapterIndex"), 0);
        yes(speech.plan("history-hole", url(215, 3)).optBoolean("memoryAssessed"), "target retains its cited delta without claiming continuous historical coverage");
        SpeechContent.failUrl = "";
        start("history-hole", 4, 1, transport); idle(true);
        eq(transport.historyCalls, 2); eq(transport.chapterCalls, 1); // Fill missing history, then reuse the target's completed acting/delta.
        eq(speech.storyMemoryBefore("history-hole", 4).optInt("throughChapterIndex"), 3);
        eq(SpeechAnalysis.snapshot(context, "history-hole").optString("memoryStatus"), "ready");
    }
    private static void cancelHistoryAndResume() throws Exception {
        install("history-cancel", 216, new int[]{0, 1, 2, 3}, true, 2, false);
        SpeechContent.blockedUrl = url(216, 1); ChapterTransport transport = new ChapterTransport();
        JSONObject started = start("history-cancel", 4, 1, transport); waitHeld();
        eq(SpeechAnalysis.activeSnapshot(context).optString("analysisPhase"), "memory");
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(false); SpeechContent.release();
        eq(SpeechAnalysis.snapshot(context, "history-cancel").optString("status"), "paused"); eq(transport.calls, 0);
        eq(speech.storyMemoryBefore("history-cancel", 3).optInt("throughChapterIndex"), -1);
        speech.saveSettings(Json.obj("detailedActing", false, "useStoryMemory", false));
        SpeechAnalysis.resumeRange(context, "history-cancel", transport); idle(true);
        JSONObject finished = SpeechAnalysis.snapshot(context, "history-cancel");
        eq(finished.optString("status"), "complete"); eq(finished.optInt("workflowVersion"), 2); eq(finished.optBoolean("useStoryMemory"), true);
        eq(transport.historyCalls, 1); eq(transport.chapterCalls, 1);
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
    }
    private static JSONObject longChapter(String id, int work, int index, int count) {
        JSONObject chapter = ReaderStore.remote(id, url(work, index)); JSONArray paragraphs = new JSONArray();
        for (int i = 0; i < count; i++) {
            StringBuilder text = new StringBuilder("「自作の長文試験" + i + "：");
            for (int n = 0; n < 300; n++) text.append('あ');
            paragraphs.put(Json.obj("text", text.append("。」").toString()));
        }
        Json.put(chapter, "paragraphs", paragraphs); new ReaderStore(context).putChapter(id, chapter); return chapter;
    }
    private static List<String> sourceRefs(JSONObject... chapters) {
        List<String> out = new ArrayList<>();
        for (JSONObject chapter : chapters) {
            JSONObject plan = SpeechPlan.create(chapter.optString("bookId"), chapter, null); JSONArray units = Json.arr(plan, "units");
            for (int i = 0; i < units.length(); i++) out.add(chapter.optString("url") + "\n" + units.optJSONObject(i).optString("unitId"));
        }
        return out;
    }
    private static void boundedHistoryAndManualChapter() throws Exception {
        install("bounded-history", 218, new int[]{0, 1, 2, 3}, true, 2, false);
        JSONObject first = longChapter("bounded-history", 218, 0, 65), second = longChapter("bounded-history", 218, 1, 65),
                giant = longChapter("bounded-history", 218, 2, 240);
        ChapterTransport transport = new ChapterTransport(); start("bounded-history", 4, 1, transport); idle(true);
        eq(SpeechAnalysis.snapshot(context, "bounded-history").optString("status"), "complete");
        eq(transport.historyCalls, 5); eq(transport.chapterCalls, 1);
        eq(transport.historySourceRefs, sourceRefs(first, second, giant));
        eq(transport.memoryThrough.get(0), 2);
        eq(SpeechAnalysis.snapshot(context, "bounded-history").optJSONObject("memoryMetrics").optInt("requestCount"), 5);
        eq(speech.storyMemoryBefore("bounded-history", 4).optInt("throughChapterIndex"), 3);
        install("manual-history", 219, new int[]{0}, true, 2, false);
        JSONObject manualChapter = longChapter("manual-history", 219, 0, 240), manualPlan = speech.prepareForAnalysis("manual-history", manualChapter);
        JSONArray units = Json.arr(manualPlan, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i); Json.put(unit, "locked", true); Json.put(unit, "analyzed", true);
            Json.put(unit, "speakerId", "c_fixed"); Json.put(unit, "kind", "thought");
        }
        attachFixtureReadings(manualPlan);
        Json.put(manualPlan, "analysisStatus", "complete"); speech.savePlan("manual-history", url(219, 0), manualPlan);
        ChapterTransport manual = new ChapterTransport(); start("manual-history", 1, 1, manual); idle(true);
        eq(SpeechAnalysis.snapshot(context, "manual-history").optString("status"), "complete");
        eq(manual.chapterCalls, 0); eq(manual.historyCalls, 3); eq(manual.historySourceRefs, sourceRefs(manualChapter));
        JSONObject saved = speech.plan("manual-history", url(219, 0)); yes(saved.optBoolean("memoryAssessed"), "metadata-only completion marks memory assessed");
        eq(saved.optString("memoryDeltaInputHash"), saved.optString("inputHash"));
        eq(Json.arr(saved, "units").toString(), units.toString());
        eq(saved.optJSONObject("analysisMetrics").optInt("requestCount"), 0);
        start("manual-history", 1, 1, manual); idle(true); eq(manual.calls, 3);
    }
    private static void resumedHistoryConsentScope() throws Exception {
        install("history-scope", 220, new int[]{0, 1, 2}, true, 2, true);
        ChapterTransport transport = new ChapterTransport(); start("history-scope", 2, 1, transport); idle(true);
        eq(transport.calls, 2); eq(speech.storyMemoryBefore("history-scope", 2).optInt("throughChapterIndex"), 1);
        eq(SpeechAnalysis.preview(context, "history-scope", 3, 1).optInt("historyTotal"), 0);
        SpeechContent.blockedUrl = url(220, 2); start("history-scope", 3, 1, transport); waitHeld();
        SpeechAnalysis.cancel(); idle(false); SpeechContent.release(); Looper.drainFixtureQueue();
        JSONObject old = new ReaderStore(context).chapter("history-scope", url(220, 0));
        Json.put(old, "paragraphs", new JSONArray().put(Json.obj("text", "以前の範囲確定後に変更された自作本文。")));
        new ReaderStore(context).putChapter("history-scope", old);
        SpeechAnalysis.resumeRange(context, "history-scope", transport); idle(true);
        JSONObject stopped = SpeechAnalysis.snapshot(context, "history-scope");
        eq(stopped.optString("status"), "error"); eq(stopped.optInt("completedChapters"), 0); eq(transport.calls, 2);
        yes(!stopped.optString("error").isEmpty(), "invalidated history requires a new confirmed range before broader resend");
    }
    private static JSONObject memoryFactById(JSONObject memory, String id) {
        JSONArray facts = Json.arr(memory, "facts");
        for (int i = 0; i < facts.length(); i++) if (id.equals(facts.optJSONObject(i).optString("id"))) return facts.optJSONObject(i);
        return null;
    }
    private static String addManual(String id, int beforeIndex, String text) {
        JSONObject memory = speech.editStoryMemory(id, beforeIndex, new JSONArray().put(Json.obj("id", "", "text", text)), new JSONArray());
        JSONArray facts = Json.arr(memory, "facts");
        for (int i = 0; i < facts.length(); i++) if (text.equals(facts.optJSONObject(i).optString("text"))) return facts.optJSONObject(i).optString("id");
        throw new AssertionError("manual fixture was not created");
    }
    private static void futureManualMemoryScope() throws Exception {
        install("manual-scope", 221, new int[]{0, 1, 2, 3}, true, 2, false);
        longChapter("manual-scope", 221, 0, 240);
        String past = addManual("manual-scope", 0, "最初から使う手動メモ。"), future = addManual("manual-scope", 3, "第4話から使う手動メモ。"),
                hidden = addManual("manual-scope", 0, "削除済みの手動メモ。");
        speech.editStoryMemory("manual-scope", 0, new JSONArray(), new JSONArray().put(hidden));
        speech.editStoryMemory("manual-scope", 3, new JSONArray(), new JSONArray().put(past));
        ChapterTransport transport = new ChapterTransport(); transport.regeneratedMemoryId = hidden;
        start("manual-scope", 4, 1, transport); idle(true);
        eq(SpeechAnalysis.snapshot(context, "manual-scope").optString("status"), "complete");
        eq(transport.historyCalls, 4); eq(transport.chapterCalls, 1);
        for (JSONObject memory : transport.historyMemoInputs) {
            yes(memoryFactById(memory, future) == null, "target-chapter manual note never enters earlier bootstrap or later chunks of an old chapter");
            yes(memoryFactById(memory, past) != null, "historical input retains the manual value effective at its own boundary");
            yes(memoryFactById(memory, hidden) == null, "chunk merges cannot resurrect a manual deletion even when AI returns its ID");
        }
        JSONObject target = transport.chapterMemoInputs.get(0);
        yes(memoryFactById(target, future) != null, "target receives its own effective manual note");
        yes(memoryFactById(target, past) == null && memoryFactById(target, hidden) == null, "target applies effective deletions after completed history commits");
        install("manual-gap-scope", 222, new int[]{0, 1, 2, 3, 4}, true, 2, false);
        String earlier = addManual("manual-gap-scope", 0, "前半の手動メモ。"), later = addManual("manual-gap-scope", 4, "第5話だけが使える手動メモ。");
        speech.editStoryMemory("manual-gap-scope", 4, new JSONArray(), new JSONArray().put(earlier));
        SpeechContent.failUrl = url(222, 1); ChapterTransport gap = new ChapterTransport(); start("manual-gap-scope", 4, 2, gap); idle(true); SpeechContent.failUrl = "";
        eq(SpeechAnalysis.snapshot(context, "manual-gap-scope").optString("status"), "complete");
        eq(SpeechAnalysis.snapshot(context, "manual-gap-scope").optString("memoryStatus"), "partial");
        eq(gap.historyCalls, 1); eq(gap.chapterCalls, 2);
        yes(memoryFactById(gap.historyMemoInputs.get(0), later) == null, "partial old history excludes a future manual note");
        yes(memoryFactById(gap.chapterMemoInputs.get(0), later) == null && memoryFactById(gap.chapterMemoInputs.get(0), earlier) != null,
                "the earlier target uses its own manual boundary despite the historical gap");
        JSONObject next = gap.chapterMemoInputs.get(1);
        yes(memoryFactById(next, later) != null && memoryFactById(next, earlier) == null, "ephemeral prior-target merge re-applies the next target's notes and removals");
        yes(memoryFactById(next, "m_chapter_3") != null, "temporal filtering retains the useful prior target delta across a history gap");
        yes(memoryFactById(speech.storyMemoryBefore("manual-gap-scope", 3), later) == null, "future manual note remains absent when revisiting an earlier target");
    }
    private static void installAliasCast(String id) {
        speech.saveCast(id, new JSONArray()
                .put(Json.obj("id", "c_fixed", "name", "利用者の固定人物", "aliases", new JSONArray().put("PRIVATE_ALIAS_subject"),
                        "offlineVoice", "zundamon", "style", "利用者の固定演技", "rate", .9, "pitch", 1.1))
                .put(Json.obj("id", "c_other", "name", "利用者の相手人物", "aliases", new JSONArray().put("PRIVATE_ALIAS_object"),
                        "offlineVoice", "kotaro", "style", "相手の固定演技", "rate", 1.1, "pitch", .9)));
    }
    private static void canonicalFacts(JSONObject memory, int expectedCount) {
        int count = 0; JSONArray facts = Json.arr(memory, "facts");
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i); if (!fact.optString("id").startsWith("m_chapter_")) continue;
            count++; eq(fact.optString("subjectId"), "c_fixed"); eq(fact.optString("objectId"), "c_other");
            eq(fact.optString("type"), "relationship");
        }
        eq(count, expectedCount);
    }
    private static JSONObject eventFor(String id, String stage, int requestNumber) {
        for (int i = events.size() - 1; i >= 0; i--) {
            JSONObject value = events.get(i); if (!id.equals(value.optString("bookId"))) continue;
            JSONObject range = value.optJSONObject("analysisRange"), activity = range == null ? null : range.optJSONObject("analysisActivity");
            if (activity != null && stage.equals(activity.optString("stage")) && activity.optInt("requestNumber") == requestNumber) return value;
        }
        throw new AssertionError("public activity event missing: " + id + " / " + stage + " / " + requestNumber);
    }
    private static JSONObject assertActivity(String id, String stage, String scope, int requests, int first, int last, int units, int chapters) {
        JSONObject range = SpeechAnalysis.snapshot(context, id), activity = range.optJSONObject("analysisActivity");
        yes(activity != null, "public range carries observed activity");
        eq(activity.optString("stage"), stage); eq(activity.optString("scope"), scope); eq(activity.optInt("requestNumber", -1), requests);
        eq(activity.optInt("chapterFromNumber"), first); eq(activity.optInt("chapterToNumber"), last);
        if (units >= 0) eq(activity.optInt("targetUnits", -1), units);
        if (chapters >= 0) eq(activity.optInt("targetChapters", -1), chapters);
        yes(activity.optLong("runStartedAt") > 0 && activity.optLong("stageStartedAt") >= activity.optLong("runStartedAt"), "activity timestamps come from this run");
        yes(!range.has("accountId") && !range.has("activityRequests"), "public activity does not expose account or internal counter storage");
        Set<String> allowed = new HashSet<>();
        for (String key : new String[]{"stage", "scope", "stageStartedAt", "runStartedAt", "endedAt", "requestNumber", "chapterFromNumber", "chapterToNumber", "targetUnits", "targetChapters"}) allowed.add(key);
        java.util.Iterator<String> keys = activity.keys(); while (keys.hasNext()) yes(allowed.contains(keys.next()), "activity contains only stage/count/time fields");
        yes(!activity.toString().contains("PRIVATE_") && !activity.toString().contains("https://") && !activity.toString().contains(ChatGptSession.TOKEN), "activity contains no text, identity, source URL or credential");
        JSONObject publicStored = SpeechAnalysisRange.publicState(speech.analysisRange(id)).optJSONObject("analysisActivity");
        eq(publicStored.toString(), activity.toString());
        if (SpeechAnalysis.runningBook(id)) eq(SpeechAnalysis.activeSnapshot(context).optJSONObject("analysisActivity").toString(), activity.toString());
        return activity;
    }
    private static void waitingEvent(String id, HeldRequest request, String scope, int first, int last, int units, int chapters) {
        eq(request.input.optString("analysisStage"), "history".equals(scope) ? "history_memory" : "chapter");
        JSONObject activity = assertActivity(id, "waiting", scope, request.number, first, last, units, chapters);
        yes(!activity.has("endedAt"), "an unanswered request has no fabricated end time");
        JSONObject delivered = eventFor(id, "waiting", request.number);
        eq(delivered.optJSONObject("plan").optJSONObject("analysisActivity").toString(), activity.toString());
        eq(delivered.optJSONObject("analysisRange").optJSONObject("analysisActivity").toString(), activity.toString());
        JSONObject range = SpeechAnalysis.snapshot(context, id), saved = speech.plan(id, range.optString("currentChapterUrl"));
        String unchanged = saved.toString();
        JSONObject reopened = SpeechAnalysis.presentPlan(saved, range, range.optString("currentChapterUrl"));
        eq(reopened.optString("analysisStatus"), "running"); eq(reopened.optString("error"), "");
        yes(!reopened.has("analysisError"), "reopened active work clears stale error diagnostics");
        eq(reopened.optJSONObject("analysisActivity").toString(), activity.toString());
        eq(Json.arr(withoutPresentation(reopened), "units").toString(), Json.arr(saved, "units").toString());
        eq(saved.toString(), unchanged);
    }
    private static void terminalActivity(String id, String status, int requests) {
        JSONObject range = SpeechAnalysis.snapshot(context, id), activity = range.optJSONObject("analysisActivity");
        eq(range.optString("status"), status); yes(activity != null, "terminal state retains activity");
        eq(activity.optString("stage"), status); eq(activity.optInt("requestNumber", -1), requests);
        yes(activity.optLong("endedAt") >= activity.optLong("stageStartedAt") && activity.optLong("endedAt") >= activity.optLong("runStartedAt"), "terminal activity has an actual end time");
        JSONObject delivered = eventFor(id, status, requests);
        eq(delivered.optJSONObject("plan").optJSONObject("analysisActivity").toString(), activity.toString());
        JSONObject saved = speech.plan(id, range.optString("currentChapterUrl"));
        eq(saved.optJSONObject("analysisActivity").toString(), activity.toString());
        String unchanged = saved.toString();
        JSONObject reopened = SpeechAnalysis.presentPlan(saved, range, range.optString("currentChapterUrl"));
        eq(reopened.optString("analysisStatus"), status);
        eq(reopened.optJSONObject("analysisActivity").toString(), activity.toString());
        eq(Json.arr(withoutPresentation(reopened), "units").toString(), Json.arr(saved, "units").toString()); eq(saved.toString(), unchanged);
    }
    private static void aliasMemoryHistoryTargetAndReuse() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
        String id = "alias-memory"; int work = 231;
        install(id, work, new int[]{0, 1, 2}, true, 2, false); installAliasCast(id);
        ReaderStore books = new ReaderStore(context); String reading = books.position(id).toString(), catalog = books.chapters(id).toString();
        JSONObject original = speech.prepareForAnalysis(id, ReaderStore.remote(id, url(work, 1)));
        speech.savePosition(id, url(work, 1), 1, original.optString("inputHash"));
        String playback = speech.position(id).toString(), cast = speech.cast(id).toString();
        String future = addManual(id, 2, "PRIVATE_FUTURE_NOTE_対象話からだけ有効。");
        AliasMemoryTransport transport = new AliasMemoryTransport(); start(id, 2, 2, transport); idle(true);
        terminalActivity(id, "complete", 3); eq(transport.historyCalls, 1); eq(transport.chapterCalls, 2);
        canonicalFacts(transport.chapterMemoInputs.get(0), 1); canonicalFacts(transport.chapterMemoInputs.get(1), 2);
        yes(memoryFactById(transport.historyMemoInputs.get(0), future) == null, "future manual note is absent from earlier alias history request");
        yes(memoryFactById(transport.chapterMemoInputs.get(0), future) == null, "future manual note is absent from earlier target request");
        yes(memoryFactById(transport.chapterMemoInputs.get(1), future) != null, "manual note reaches its own effective target chapter");
        canonicalFacts(speech.storyMemoryBefore(id, 3), 3); canonicalFacts(speech.storyMemoryBefore(id, 1), 1);
        yes(memoryFactById(speech.storyMemoryBefore(id, 1), "m_chapter_1") == null, "canonicalized target fact cannot leak backward in time");
        for (int chapter = 1; chapter < 3; chapter++) {
            JSONObject saved = speech.plan(id, url(work, chapter)); JSONArray units = Json.arr(saved, "units");
            JSONObject ended = saved.optJSONObject("analysisActivity");
            yes(ended != null, "every finished chapter persists its own terminal activity");
            eq(ended.optString("stage"), "complete");
            yes(ended.optLong("endedAt") >= ended.optLong("stageStartedAt") && ended.optLong("endedAt") > 0, "past-chapter clock stops at its own saved end");
            eq(ended.optInt("chapterFromNumber"), chapter + 1);
            eq(withoutPresentation(SpeechAnalysis.presentPlan(saved, SpeechAnalysis.snapshot(context, id), url(work, chapter))).toString(), saved.toString());
            canonicalFacts(saved.optJSONObject("memoryDelta"), 1);
            for (int i = 0; i < units.length(); i++) {
                eq(units.optJSONObject(i).optString("speakerId"), "c_fixed");
                yes(SpeechPerformance.valid(units.optJSONObject(i)), "alias memory adoption retains valid detailed acting");
            }
        }
        eq(speech.cast(id).toString(), cast); eq(books.position(id).toString(), reading); eq(books.chapters(id).toString(), catalog);
        eq(speech.position(id).toString(), playback);
        String memory = speech.storyMemoryBefore(id, 3).toString();
        start(id, 2, 2, transport); idle(true); terminalActivity(id, "complete", 0); eq(transport.calls, 3);
        eq(speech.storyMemoryBefore(id, 3).toString(), memory); eq(speech.cast(id).toString(), cast);
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
    }
    private static void rejectedMemoryPreservesPriorState() throws Exception {
        int work = 232;
        for (String scope : new String[]{"history_memory", "chapter"}) for (String reason : new String[]{"person", "citation"}) {
            String id = "invalid-memory-" + scope + "-" + reason; int currentWork = work++;
            install(id, currentWork, new int[]{0, 1, 2}, true, 2, true); installAliasCast(id);
            AliasMemoryTransport seed = new AliasMemoryTransport(); start(id, 1, 1, seed); idle(true);
            eq(seed.calls, 1); canonicalFacts(speech.storyMemoryBefore(id, 3), 1);
            String memory = speech.storyMemoryBefore(id, 3).toString(), cast = speech.cast(id).toString();
            ReaderStore books = new ReaderStore(context); String reading = books.position(id).toString();
            AliasMemoryTransport bad = new AliasMemoryTransport(); bad.invalidStage = scope; bad.invalidReason = reason;
            int target = "history_memory".equals(scope) ? 3 : 2;
            start(id, target, 1, bad); idle(true); terminalActivity(id, "error", 1);
            JSONObject failed = SpeechAnalysis.snapshot(context, id), diagnostic = failed.optJSONObject("analysisError");
            yes(diagnostic != null, "rejected memory has a public diagnostic");
            eq(diagnostic.optString("code"), "invalid_memory"); eq(diagnostic.optString("stage"), "memory_" + reason);
            eq(diagnostic.optString("responseShape"), "memory"); eq(diagnostic.optInt("factIndex", -1), 0);
            if ("citation".equals(reason)) eq(diagnostic.optInt("citationIndex", -1), 0);
            String safe = diagnostic.toString();
            for (String forbidden : new String[]{"PRIVATE_", "c_alias", "c_fixed", "c_other", "https://", ChatGptSession.TOKEN, "自作", "利用者"})
                yes(!safe.contains(forbidden), "memory diagnostics exclude payload, names, identities, URLs and credential");
            eq(eventFor(id, "error", 1).optJSONObject("plan").optJSONObject("analysisError").toString(), safe);
            eq(speech.storyMemoryBefore(id, 3).toString(), memory); eq(speech.cast(id).toString(), cast); eq(books.position(id).toString(), reading);
            eq(speech.plan(id, url(currentWork, target - 1)).optInt("analyzedUnits"), 0);
            yes(failed.optBoolean("canResume"), "invalid memo remains resumable without losing prior valid memory");
            eq(bad.calls, 1);
        }
    }
    private static void liveActivityBeforeResponse() throws Exception {
        String id = "live-activity"; int work = 236;
        install(id, work, new int[]{0, 1, 2}, true, 2, false);
        ReaderStore books = new ReaderStore(context); String reading = books.position(id).toString(), catalog = books.chapters(id).toString();
        JSONObject original = speech.prepareForAnalysis(id, ReaderStore.remote(id, url(work, 2)));
        speech.savePosition(id, url(work, 2), 1, original.optString("inputHash")); String playback = speech.position(id).toString();
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        SpeechContent.blockedUrl = url(work, 2); start(id, 3, 1, gate); waitHeld(); Looper.drainFixtureQueue();
        assertActivity(id, "loading", "chapter", 0, 3, 3, -1, 1); eq(gate.sends.get(), 0);
        SpeechContent.release(); HeldRequest history = gate.take(); waitingEvent(id, history, "history", 1, 2, 0, 2);
        eq(Json.arr(history.input, "chapters").length(), 2);
        eq(Json.arr(Json.arr(history.input, "chapters").optJSONObject(0), "units").length(), 2);
        eq(responses.calls, 0); eq(speech.storyMemoryBefore(id, 3).optInt("throughChapterIndex"), -1);
        history.release.countDown(); HeldRequest chapter = gate.take(); waitingEvent(id, chapter, "chapter", 3, 3, 2, 1);
        eq(responses.calls, 1); eq(speech.storyMemoryBefore(id, 3).optInt("throughChapterIndex"), 1);
        eq(speech.plan(id, url(work, 2)).optInt("analyzedUnits"), 0);
        chapter.release.countDown(); idle(true); terminalActivity(id, "complete", 2);
        eq(speech.storyMemoryBefore(id, 3).optInt("throughChapterIndex"), 2); eq(responses.calls, 2);
        eq(speech.position(id).toString(), playback); eq(books.position(id).toString(), reading); eq(books.chapters(id).toString(), catalog);
        eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
    }
    private static void failedActivityResumeAndReuse() throws Exception {
        String id = "activity-failure"; int work = 237;
        install(id, work, new int[]{0}, true, 2, true);
        ChapterTransport responses = new ChapterTransport(); responses.failCall = 1; GatedTransport gate = new GatedTransport(responses);
        JSONObject started = start(id, 1, 1, gate); HeldRequest failed = gate.take(); waitingEvent(id, failed, "chapter", 1, 1, 2, 1);
        long firstStarted = SpeechAnalysis.snapshot(context, id).optJSONObject("analysisActivity").optLong("runStartedAt");
        failed.release.countDown(); idle(true); terminalActivity(id, "error", 1);
        eq(speech.storyMemoryBefore(id, 1).optInt("throughChapterIndex"), -1); eq(speech.plan(id, url(work, 0)).optInt("analyzedUnits"), 0);
        JSONObject resumed = SpeechAnalysis.resumeRange(context, id, gate); HeldRequest retry = gate.take(); waitingEvent(id, retry, "chapter", 1, 1, 2, 1);
        yes(!resumed.optString("jobId").equals(started.optString("jobId")), "resume isolates stale event callbacks under a new runtime job id");
        yes(SpeechAnalysis.snapshot(context, id).optJSONObject("analysisActivity").optLong("runStartedAt") >= firstStarted, "resume resets the current-run clock");
        retry.release.countDown(); idle(true); terminalActivity(id, "complete", 2); eq(responses.calls, 2);
        String memory = speech.storyMemoryBefore(id, 1).toString(); int before = events.size();
        JSONObject reused = start(id, 1, 1, gate); idle(true); terminalActivity(id, "complete", 0);
        eq(gate.sends.get(), 2); eq(responses.calls, 2); eq(speech.storyMemoryBefore(id, 1).toString(), memory);
        for (int i = before; i < events.size(); i++) {
            JSONObject range = events.get(i).optJSONObject("analysisRange");
            if (reused.optString("jobId").equals(range.optString("jobId"))) {
                JSONObject activity = range.optJSONObject("analysisActivity");
                yes(!"waiting".equals(activity.optString("stage")), "complete cache reuse never claims a provider request");
                eq(activity.optInt("requestNumber", -1), 0);
            }
        }
    }
    private static void cancelledWaitingAndResume() throws Exception {
        String id = "activity-cancel"; int work = 238;
        install(id, work, new int[]{0, 1}, true, 2, true);
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        JSONObject started = start(id, 2, 1, gate); HeldRequest aborted = gate.take(); waitingEvent(id, aborted, "history", 1, 1, 0, 1);
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(true); terminalActivity(id, "paused", 1);
        eq(responses.calls, 0); eq(speech.storyMemoryBefore(id, 2).optInt("throughChapterIndex"), -1);
        eq(speech.plan(id, url(work, 1)).optInt("analyzedUnits"), 0);
        SpeechAnalysis.resumeRange(context, id, gate); HeldRequest history = gate.take(); waitingEvent(id, history, "history", 1, 1, 0, 1);
        history.release.countDown(); HeldRequest target = gate.take(); waitingEvent(id, target, "chapter", 2, 2, 2, 1);
        target.release.countDown(); idle(true); terminalActivity(id, "complete", 3);
        eq(gate.sends.get(), 3); eq(responses.calls, 2); eq(speech.storyMemoryBefore(id, 2).optInt("throughChapterIndex"), 1);
    }
    private static void memoOnlyReopenedActivity() throws Exception {
        String id = "activity-memo-only"; int work = 239; String chapterUrl = url(work, 0);
        install(id, work, new int[]{0}, true, 2, true);
        JSONObject plan = speech.prepareForAnalysis(id, ReaderStore.remote(id, chapterUrl));
        for (int i = 0; i < Json.arr(plan, "units").length(); i++)
            speech.updateUnit(id, chapterUrl, Json.arr(plan, "units").optJSONObject(i).optString("unitId"),
                    Json.obj("speakerId", "c_fixed", "kind", "thought", "emotion", "calm", "rate", .9));
        plan = speech.plan(id, chapterUrl); attachFixtureReadings(plan);
        Json.put(plan, "analysisStatus", "complete"); speech.savePlan(id, chapterUrl, plan);
        String originalUnits = Json.arr(plan, "units").toString();
        ChapterTransport responses = new ChapterTransport(); responses.failCall = 1; GatedTransport gate = new GatedTransport(responses);
        start(id, 1, 1, gate); HeldRequest failed = gate.take(); waitingEvent(id, failed, "history", 1, 1, 0, 1);
        eq(speech.plan(id, chapterUrl).optString("analysisStatus"), "complete");
        eq(Json.arr(speech.plan(id, chapterUrl), "units").toString(), originalUnits);
        failed.release.countDown(); idle(true); terminalActivity(id, "error", 1);
        eq(speech.plan(id, chapterUrl).optString("analysisStatus"), "complete");
        yes(!SpeechAnalysis.presentPlan(speech.plan(id, chapterUrl), SpeechAnalysis.snapshot(context, id), chapterUrl).optString("error").isEmpty(),
                "memo-only failure is visible when reopening a previously complete acting plan");
        SpeechAnalysis.resumeRange(context, id, gate); HeldRequest retry = gate.take(); waitingEvent(id, retry, "history", 1, 1, 0, 1);
        eq(speech.plan(id, chapterUrl).optString("analysisStatus"), "complete");
        retry.release.countDown(); idle(true); terminalActivity(id, "complete", 2);
        JSONObject saved = speech.plan(id, chapterUrl);
        eq(saved.optString("analysisStatus"), "complete"); eq(saved.optJSONObject("analysisActivity").optString("stage"), "complete");
        yes(saved.optJSONObject("analysisActivity").optLong("endedAt") > 0, "memo-only completion persists a stopped clock");
        yes(saved.optBoolean("memoryAssessed"), "memo-only completion stores its validated delta");
        eq(Json.arr(saved, "units").toString(), originalUnits); eq(responses.chapterCalls, 0); eq(responses.historyCalls, 2);
    }
    private static void terminalActivityKeepsLatestManualUnits() throws Exception {
        String id = "activity-thin-save"; int work = 240; String chapterUrl = url(work, 0);
        install(id, work, new int[]{0}, true, 2, true);
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        JSONObject started = start(id, 1, 1, gate); HeldRequest held = gate.take(); waitingEvent(id, held, "chapter", 1, 1, 2, 1);
        JSONObject stale = speech.plan(id, chapterUrl); String unitId = Json.arr(stale, "units").optJSONObject(0).optString("unitId");
        speech.updateUnit(id, chapterUrl, unitId, Json.obj("speakerId", "c_fixed", "kind", "thought", "emotion", "whisper", "rate", .73, "pitch", 1.18));
        String edited = Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(0).toString();
        yes(!edited.equals(Json.arr(stale, "units").optJSONObject(0).toString()), "manual update is newer than the worker's in-flight plan");
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(true); terminalActivity(id, "paused", 1);
        eq(Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(0).toString(), edited);
        JSONObject range = speech.analysisRange(id); String activity = range.optJSONObject("analysisActivity").toString();
        // Exercise both possible lock orders: activity writes and fresh manual edits must commute.
        for (int iteration = 0; iteration < 4; iteration++) {
            final int n = iteration; CountDownLatch begin = new CountDownLatch(1), done = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread manual = new Thread(() -> {
                try { begin.await(); new SpeechStore(context).updateUnit(id, chapterUrl, unitId,
                        Json.obj("speakerId", "c_fixed", "emotion", "calm", "rate", .81 + n * .02, "pitch", 1.01 + n * .01)); }
                catch (Throwable error) { failure.compareAndSet(null, error); } finally { done.countDown(); }
            }, "fixture-manual-unit");
            Thread terminal = new Thread(() -> {
                try { begin.await(); new SpeechStore(context).saveAnalysisActivity(id, chapterUrl, range); }
                catch (Throwable error) { failure.compareAndSet(null, error); } finally { done.countDown(); }
            }, "fixture-terminal-activity");
            manual.start(); terminal.start(); begin.countDown();
            yes(done.await(5, TimeUnit.SECONDS), "manual and terminal updates both finish");
            if (failure.get() != null) throw new AssertionError("concurrent activity persistence failed", failure.get());
            JSONObject latest = new SpeechStore(context).plan(id, chapterUrl), unit = Json.arr(latest, "units").optJSONObject(0);
            eq(unit.optDouble("rate"), .81 + n * .02); eq(unit.optDouble("pitch"), 1.01 + n * .01);
            eq(unit.optString("emotion"), "calm"); yes(unit.optBoolean("locked"), "terminal update retains manual lock");
            eq(latest.optJSONObject("analysisActivity").toString(), activity);
        }
        JSONObject wrongOwner = Json.copy(range); Json.put(wrongOwner, "jobId", "fixture-stale-job");
        String before = speech.plan(id, chapterUrl).toString();
        denied(() -> speech.saveAnalysisActivity(id, chapterUrl, wrongOwner));
        denied(() -> speech.saveAnalysisActivity(id, url(work, 1), range));
        eq(speech.plan(id, chapterUrl).toString(), before); eq(responses.calls, 0);
    }
    private static void recoveredMemoActivitySurvivesRangeReplacement() throws Exception {
        String id = "activity-crash-recovery"; int work = 241; String oldUrl = url(work, 0), nextUrl = url(work, 1);
        install(id, work, new int[]{0, 1}, true, 2, true);
        JSONObject plan = speech.prepareForAnalysis(id, ReaderStore.remote(id, oldUrl));
        for (int i = 0; i < Json.arr(plan, "units").length(); i++)
            speech.updateUnit(id, oldUrl, Json.arr(plan, "units").optJSONObject(i).optString("unitId"),
                    Json.obj("speakerId", "c_fixed", "kind", "thought", "emotion", "calm"));
        plan = speech.plan(id, oldUrl); attachFixtureReadings(plan);
        Json.put(plan, "analysisStatus", "complete"); speech.savePlan(id, oldUrl, plan);
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        JSONObject started = start(id, 1, 1, gate); HeldRequest waiting = gate.take(); waitingEvent(id, waiting, "history", 1, 1, 0, 1);
        JSONObject crashedRange = speech.analysisRange(id);
        eq(speech.plan(id, oldUrl).optString("analysisStatus"), "complete");
        String unitId = Json.arr(speech.plan(id, oldUrl), "units").optJSONObject(0).optString("unitId");
        speech.updateUnit(id, oldUrl, unitId, Json.obj("emotion", "whisper", "rate", .79, "pitch", 1.17));
        String manualUnits = Json.arr(speech.plan(id, oldUrl), "units").toString();
        // Stop the fixture worker, then restore its real pre-response disk checkpoint as if the process died.
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(true);
        JSONObject crashedPlan = speech.plan(id, oldUrl);
        Json.put(crashedPlan, "analysisActivity", Json.copy(crashedRange.optJSONObject("analysisActivity")));
        speech.savePlan(id, oldUrl, crashedPlan); speech.saveAnalysisRange(id, crashedRange);
        yes(!SpeechAnalysis.running(), "process-recovery fixture has no active worker");
        eq(speech.plan(id, oldUrl).optString("analysisStatus"), "complete");
        eq(speech.plan(id, oldUrl).optJSONObject("analysisActivity").optString("stage"), "waiting");
        JSONObject recovered = SpeechAnalysis.snapshot(context, id), ended = recovered.optJSONObject("analysisActivity");
        eq(recovered.optString("status"), "paused"); eq(ended.optString("stage"), "paused");
        yes(ended.optLong("endedAt") >= ended.optLong("stageStartedAt") && ended.optLong("endedAt") > 0,
                "recovery records the last known stop time");
        JSONObject recoveredPlan = speech.plan(id, oldUrl);
        eq(recoveredPlan.optJSONObject("analysisActivity").toString(), ended.toString());
        eq(recoveredPlan.optString("analysisStatus"), "complete"); eq(Json.arr(recoveredPlan, "units").toString(), manualUnits);
        eq(SpeechAnalysis.presentPlan(recoveredPlan, recovered, oldUrl).optString("analysisStatus"), "paused");
        String persistedOldPlan = recoveredPlan.toString();
        GatedTransport nextGate = new GatedTransport(new ChapterTransport()); JSONObject replaced = start(id, 2, 1, nextGate);
        HeldRequest nextWaiting = nextGate.take(); waitingEvent(id, nextWaiting, "history", 1, 1, 0, 1);
        yes(!replaced.optString("jobId").equals(recovered.optString("jobId")), "a new chapter replaces the recovered range");
        JSONObject currentRange = SpeechAnalysis.snapshot(context, id); eq(currentRange.optString("currentChapterUrl"), nextUrl);
        JSONObject reopenedOld = SpeechAnalysis.presentPlan(speech.plan(id, oldUrl), currentRange, oldUrl);
        eq(reopenedOld.optJSONObject("analysisActivity").optString("stage"), "paused");
        eq(reopenedOld.optJSONObject("analysisActivity").optLong("endedAt"), ended.optLong("endedAt"));
        eq(reopenedOld.optString("analysisStatus"), "complete"); eq(Json.arr(withoutPresentation(reopenedOld), "units").toString(), manualUnits);
        eq(withoutPresentation(reopenedOld).toString(), persistedOldPlan);
        SpeechAnalysis.cancelJob(replaced.optString("jobId")); idle(true);
        eq(speech.plan(id, oldUrl).toString(), persistedOldPlan); eq(responses.calls, 0);

        // A failed secondary plan write must not undo the already persisted paused queue.
        JSONObject interruptedRange = speech.analysisRange(id), activity = Json.copy(interruptedRange.optJSONObject("analysisActivity"));
        Json.put(interruptedRange, "status", "running"); Json.put(activity, "stage", "waiting"); activity.remove("endedAt");
        Json.put(interruptedRange, "analysisActivity", activity); speech.saveAnalysisRange(id, interruptedRange);
        JSONObject latest = speech.plan(id, nextUrl); String nextUnit = Json.arr(latest, "units").optJSONObject(0).optString("unitId");
        speech.updateUnit(id, nextUrl, nextUnit, Json.obj("speakerId", "c_fixed", "emotion", "calm", "rate", .84));
        String edited = Json.arr(speech.plan(id, nextUrl), "units").toString();
        File file = new File(new File(new File(context.getFilesDir(), "speech"), SpeechStore.key(id)), SpeechStore.key(nextUrl) + ".json");
        android.util.AtomicFile.failNextStart(file);
        denied(() -> SpeechAnalysis.snapshot(context, id));
        eq(speech.analysisRange(id).optString("status"), "paused");
        eq(speech.analysisRange(id).optJSONObject("analysisActivity").optString("stage"), "paused");
        eq(Json.arr(speech.plan(id, nextUrl), "units").toString(), edited);
    }
    private static JSONObject readingChapter(String id, int work, int index, boolean ruby) {
        JSONObject chapter = ReaderStore.remote(id, url(work, index));
        JSONArray paragraphs = ruby
                ? new JSONArray().put(Json.obj("text", "青葉は微笑んだ。", "html", "<ruby>青葉<rt>あおば</rt></ruby>は微笑んだ。"))
                : new JSONArray().put(Json.obj("text", "「今日は生物を学ぶ。母は花を運ぶ。」"))
                    .put(Json.obj("text", "「今日の生物は冷蔵庫に入れる。」"));
        Json.put(chapter, "paragraphs", paragraphs); new ReaderStore(context).putChapter(id, chapter); return chapter;
    }
    private static JSONObject withoutReading(JSONObject unit) {
        JSONObject copy = Json.copy(unit);
        for (String key : new String[]{"pronunciation", "pronunciationLocked", "pronunciationEditRevision", "pronunciationReady"}) copy.remove(key);
        return copy;
    }
    private static void unchangedSource(JSONObject before, JSONObject after) {
        eq(after.optString("inputHash"), before.optString("inputHash"));
        JSONArray oldUnits = Json.arr(before, "units"), units = Json.arr(after, "units"); eq(units.length(), oldUnits.length());
        for (int i = 0; i < units.length(); i++) {
            JSONObject old = oldUnits.optJSONObject(i), current = units.optJSONObject(i);
            for (String key : new String[]{"unitId", "text", "spokenText", "index", "paragraph", "start", "end", "spokenRuby"})
                eq(String.valueOf(current.opt(key)), String.valueOf(old.opt(key)));
            eq(SpeechPerformance.parts(current).toString(), SpeechPerformance.parts(old).toString());
        }
    }
    private static void pronunciationPresentation(JSONObject plan, JSONObject range, String chapterUrl, int ready) {
        String original = plan.toString(); JSONObject presented = SpeechAnalysis.presentPlan(plan, range, chapterUrl);
        JSONArray units = Json.arr(presented, "units"); eq(presented.optInt("pronunciationUnits", -1), ready);
        eq(presented.optInt("pronunciationTotalUnits", -1), units.length()); eq(presented.optInt("missingPronunciationUnits", -1), units.length() - ready);
        for (int i = 0; i < units.length(); i++) eq(units.optJSONObject(i).optBoolean("pronunciationReady"), SpeechPronunciation.valid(units.optJSONObject(i)));
        eq(Json.arr(withoutPresentation(presented), "units").toString(), Json.arr(plan, "units").toString()); eq(plan.toString(), original);
    }
    private static void newReadingSharesChapterRequest() throws Exception {
        for (boolean detailed : new boolean[]{true, false}) {
            speech.saveSettings(Json.obj("detailedActing", detailed, "useStoryMemory", false));
            String id = detailed ? "pronunciation-new-detailed" : "pronunciation-new-standard";
            int work = detailed ? 251 : 252; String chapterUrl = url(work, 0);
            install(id, work, new int[]{0}, true, 2, true);
            JSONObject chapter = readingChapter(id, work, 0, false), original = speech.prepareForAnalysis(id, chapter);
            ReaderStore books = new ReaderStore(context); String reading = books.position(id).toString(), catalog = books.chapters(id).toString();
            speech.savePosition(id, chapterUrl, 1, original.optString("inputHash")); String playback = speech.position(id).toString(), cast = speech.cast(id).toString();
            JSONObject preview = SpeechAnalysis.preview(context, id, 1, 1); eq(preview.optInt("pronunciationVersion"), 1);
            ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
            start(id, 1, 1, gate); HeldRequest held = gate.take(); waitingEvent(id, held, "chapter", 1, 1, 2, 1);
            eq(Json.arr(held.input, "targetUnits").length(), 2); eq(Json.arr(held.input, "pronunciationTargets").length(), 2);
            yes(held.input.optBoolean("japanesePronunciation"), "Japanese chapter requests explicit pronunciations");
            eq(gate.sends.get(), 1); eq(responses.calls, 0); eq(speech.plan(id, chapterUrl).optInt("analyzedUnits"), 0);
            pronunciationPresentation(speech.plan(id, chapterUrl), SpeechAnalysis.snapshot(context, id), chapterUrl, 0);
            for (int i = 0; i < 2; i++) yes(!Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(i).has("pronunciation"), "unanswered requests never publish provisional readings");
            held.release.countDown(); idle(true); terminalActivity(id, "complete", 1);
            JSONObject saved = speech.plan(id, chapterUrl), range = SpeechAnalysis.snapshot(context, id); unchangedSource(original, saved);
            eq(saved.optInt("pronunciationVersion"), 1); eq(range.optInt("pronunciationVersion"), 1);
            eq(saved.optInt("analyzedUnits"), 2); eq(saved.optJSONObject("analysisMetrics").optInt("requestCount"), 1);
            eq(responses.calls, 1); eq(responses.chapterCalls, 1); eq(responses.historyCalls, 0);
            for (int i = 0; i < 2; i++) {
                JSONObject unit = Json.arr(saved, "units").optJSONObject(i);
                yes(SpeechPronunciation.valid(unit), "same completed chapter response adopts source-validated reading");
                eq(SpeechPronunciation.text(unit), fixtureReading(unit.optString("spokenText")));
                yes(unit.optBoolean("analyzed"), "coarse speaker/acting annotation is also committed");
                if (detailed) yes(SpeechPerformance.valid(unit), "detailed acting and original part offsets remain valid");
                else yes(!unit.has("performance"), "standard opt-in does not silently enable detailed acting");
            }
            pronunciationPresentation(saved, range, chapterUrl, 2); eq(speech.cast(id).toString(), cast);
            start(id, 1, 1, gate); idle(true); terminalActivity(id, "complete", 0);
            eq(gate.sends.get(), 1); eq(responses.calls, 1);
            eq(books.position(id).toString(), reading); eq(books.chapters(id).toString(), catalog); eq(speech.position(id).toString(), playback);
        }
    }
    private static void existingActingGetsOnlyReading() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", false));
        String id = "pronunciation-upgrade"; int work = 253; String chapterUrl = url(work, 0);
        install(id, work, new int[]{0}, true, 2, true); readingChapter(id, work, 0, false);
        ChapterTransport legacy = new ChapterTransport(); startLegacy(id, 1, 1, true, legacy); idle(true);
        eq(legacy.calls, 1); yes(!legacy.chapterInputs.get(0).has("pronunciationTargets"), "saved 1.9.1 detailed chapter keeps its four-field compact contract");
        eq(SpeechAnalysis.snapshot(context, id).optInt("pronunciationVersion"), 0);
        JSONObject old = speech.plan(id, chapterUrl); JSONArray oldUnits = Json.arr(old, "units");
        for (int i = 0; i < oldUnits.length(); i++) {
            yes(SpeechPerformance.valid(oldUnits.optJSONObject(i)), "upgrade seed has existing detailed acting");
            yes(!oldUnits.optJSONObject(i).has("pronunciation"), "legacy complete plan has no invented readings");
        }
        speech.updateUnit(id, chapterUrl, oldUnits.optJSONObject(0).optString("unitId"), Json.obj("speakerId", "c_fixed", "emotion", "whisper", "rate", .83));
        old = speech.plan(id, chapterUrl); oldUnits = Json.arr(old, "units");
        String cast = speech.cast(id).toString(), reading = new ReaderStore(context).position(id).toString();
        speech.savePosition(id, chapterUrl, 1, old.optString("inputHash")); String playback = speech.position(id).toString();
        ChapterTransport upgrade = new ChapterTransport() {
            @Override JSONObject metadata(JSONObject input, JSONObject value) {
                eq(Json.arr(input, "targetUnits").length(), 0); eq(Json.arr(input, "pronunciationTargets").length(), 2);
                // A reading-only response cannot introduce cast changes, even if it echoes a valid proposal.
                Json.put(value, "cast", new JSONArray().put(Json.obj("id", "c_new_reading_only", "name", "読みだけの未承認人物",
                        "aliases", new JSONArray(), "gender", "neutral", "tier", "extra", "offlineVoice", "miko", "style", "変更候補", "rate", 1, "pitch", 1)));
                return value;
            }
        };
        start(id, 1, 1, upgrade); idle(true); terminalActivity(id, "complete", 1);
        JSONObject saved = speech.plan(id, chapterUrl); unchangedSource(old, saved);
        for (int i = 0; i < oldUnits.length(); i++) {
            JSONObject unit = Json.arr(saved, "units").optJSONObject(i);
            eq(withoutReading(unit).toString(), withoutReading(oldUnits.optJSONObject(i)).toString());
            yes(SpeechPronunciation.valid(unit), "reading-only upgrade also covers manually locked acting");
        }
        eq(upgrade.calls, 1); eq(upgrade.completedTargets.size(), 0); eq(speech.cast(id).toString(), cast);
        eq(new ReaderStore(context).position(id).toString(), reading); eq(speech.position(id).toString(), playback);
        pronunciationPresentation(saved, SpeechAnalysis.snapshot(context, id), chapterUrl, 2);
    }
    private static void rejectedPronunciationIsAtomic() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", true));
        for (String reason : new String[]{"reading", "ruby"}) {
            final boolean ruby = "ruby".equals(reason); String id = "pronunciation-invalid-" + reason; int work = ruby ? 255 : 254;
            String chapterUrl = url(work, 0); install(id, work, new int[]{0}, true, 2, true);
            JSONObject chapter = readingChapter(id, work, 0, ruby), original = speech.prepareForAnalysis(id, chapter);
            addManual(id, 0, "PRIVATE_EXISTING_MEMORY_読みの拒否後も残す。");
            String memory = speech.storyMemoryBefore(id, 1).toString(), cast = speech.cast(id).toString();
            String reading = new ReaderStore(context).position(id).toString();
            ChapterTransport bad = new ChapterTransport() {
                @Override JSONObject metadata(JSONObject input, JSONObject value) {
                    JSONObject first = Json.arr(Json.arr(value, "pronunciations").optJSONObject(0), "parts").optJSONObject(0);
                    Json.put(first, "reading", ruby ? "アオバワホホエンダ。" : "PRIVATE_READING_未変換漢字。"); return value;
                }
            };
            start(id, 1, 1, bad); idle(true); terminalActivity(id, "error", 1);
            JSONObject failed = SpeechAnalysis.snapshot(context, id), diagnostic = failed.optJSONObject("analysisError");
            eq(diagnostic.optString("code"), "invalid_pronunciation"); eq(diagnostic.optString("stage"), "pronunciation_" + reason);
            eq(diagnostic.optString("responseShape"), "pronunciation");
            for (String forbidden : new String[]{"PRIVATE_", "青葉", "あおば", "アオバ", "生物", "https://", "c_fixed", ChatGptSession.TOKEN})
                yes(!diagnostic.toString().contains(forbidden), "pronunciation diagnostics contain no body, reading, person, URL or credential");
            eq(eventFor(id, "error", 1).optJSONObject("plan").optJSONObject("analysisError").toString(), diagnostic.toString());
            JSONObject rejected = speech.plan(id, chapterUrl); unchangedSource(original, rejected); eq(rejected.optInt("analyzedUnits"), 0);
            for (int i = 0; i < Json.arr(rejected, "units").length(); i++) {
                JSONObject unit = Json.arr(rejected, "units").optJSONObject(i);
                yes(!unit.has("pronunciation") && !unit.has("performance") && !unit.optBoolean("analyzed"), "invalid reading rejects the entire uncommitted acting/reading result");
            }
            eq(speech.storyMemoryBefore(id, 1).toString(), memory); eq(speech.cast(id).toString(), cast);
            eq(new ReaderStore(context).position(id).toString(), reading); eq(bad.calls, 1);
            ChapterTransport valid = new ChapterTransport(); SpeechAnalysis.resumeRange(context, id, valid); idle(true); terminalActivity(id, "complete", 2);
            JSONObject adopted = speech.plan(id, chapterUrl); eq(valid.calls, 1); unchangedSource(original, adopted);
            if (ruby) {
                JSONObject unit = Json.arr(adopted, "units").optJSONObject(0);
                eq(unit.optString("text"), "青葉は微笑んだ。"); eq(unit.optString("spokenText"), "あおばは微笑んだ。");
                eq(SpeechPronunciation.text(unit), "あおばワホホエンダ。");
                JSONArray targetParts = Json.arr(Json.arr(valid.chapterInputs.get(0), "pronunciationTargets").optJSONObject(0), "parts");
                eq(Json.arr(targetParts.optJSONObject(0), "rubyTokens").length(), 1);
            }
        }
    }
    private static void readingCancellationResumesOnlyMissingChapter() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", false));
        String id = "pronunciation-cancel"; int work = 256; install(id, work, new int[]{0, 1}, true, 2, true);
        readingChapter(id, work, 0, false); readingChapter(id, work, 1, false);
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        JSONObject started = start(id, 1, 2, gate); HeldRequest first = gate.take(); waitingEvent(id, first, "chapter", 1, 1, 2, 1);
        first.release.countDown(); HeldRequest second = gate.take(); waitingEvent(id, second, "chapter", 2, 2, 2, 1);
        String completed = speech.plan(id, url(work, 0)).toString();
        eq(SpeechAnalysis.snapshot(context, id).optInt("completedChapters"), 1); eq(Json.arr(second.input, "pronunciationTargets").length(), 2);
        SpeechAnalysis.cancelJob(started.optString("jobId")); idle(true); terminalActivity(id, "paused", 2);
        eq(responses.calls, 1); eq(speech.plan(id, url(work, 1)).optInt("analyzedUnits"), 0);
        SpeechAnalysis.resumeRange(context, id, gate); HeldRequest resumed = gate.take(); waitingEvent(id, resumed, "chapter", 2, 2, 2, 1);
        eq(resumed.input.optString("chapterUrl"), url(work, 1)); eq(Json.arr(resumed.input, "pronunciationTargets").length(), 2);
        resumed.release.countDown(); idle(true); terminalActivity(id, "complete", 3);
        eq(speech.plan(id, url(work, 0)).toString(), completed); eq(responses.calls, 2); eq(gate.sends.get(), 3);
        eq(SpeechAnalysis.snapshot(context, id).optInt("completedChapters"), 2); eq(responses.completedTargets.size(), 4);
    }
    private static void manualReadingWinsOverPendingResponse() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", true, "useStoryMemory", false));
        String id = "pronunciation-manual-race"; int work = 257; String chapterUrl = url(work, 0);
        install(id, work, new int[]{0}, true, 2, true); JSONObject original = speech.prepareForAnalysis(id, readingChapter(id, work, 0, false));
        speech.savePosition(id, chapterUrl, 1, original.optString("inputHash")); String playback = speech.position(id).toString();
        String reading = new ReaderStore(context).position(id).toString(), cast = speech.cast(id).toString();
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        start(id, 1, 1, gate); HeldRequest held = gate.take(); waitingEvent(id, held, "chapter", 1, 1, 2, 1);
        JSONObject unit = Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(0), manual = SpeechPronunciation.template(unit);
        Json.put(Json.arr(manual, "parts").optJSONObject(0), "reading", "テドーシテイノヨミ。");
        speech.savePronunciation(id, chapterUrl, unit.optString("unitId"), manual, false);
        JSONObject edited = Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(0);
        yes(edited.optBoolean("pronunciationLocked"), "manual reading has its own lock");
        yes(!edited.optBoolean("locked"), "manual reading does not lock or alter acting");
        eq(withoutReading(edited).toString(), withoutReading(unit).toString());
        String exactReading = edited.optJSONObject("pronunciation").toString(), revision = edited.optString("pronunciationEditRevision");
        yes(!revision.isEmpty(), "manual reading saves a revision for pending-response protection");
        held.release.countDown(); idle(true); terminalActivity(id, "complete", 1);
        JSONObject saved = speech.plan(id, chapterUrl); unchangedSource(original, saved);
        JSONObject after = Json.arr(saved, "units").optJSONObject(0);
        eq(after.optJSONObject("pronunciation").toString(), exactReading); eq(after.optString("pronunciationEditRevision"), revision);
        yes(after.optBoolean("pronunciationLocked") && SpeechPronunciation.valid(after), "validated response preserves the manual reading exactly");
        yes(SpeechPerformance.valid(after), "same response still applies the pending acting annotation");
        yes(SpeechPronunciation.valid(Json.arr(saved, "units").optJSONObject(1)), "unlocked reading is adopted from the response");
        eq(speech.cast(id).toString(), cast); eq(speech.position(id).toString(), playback); eq(new ReaderStore(context).position(id).toString(), reading);
        start(id, 1, 1, gate); idle(true); terminalActivity(id, "complete", 0); eq(gate.sends.get(), 1);
    }
    private static void resetReadingPausesWithoutAutomaticResend() throws Exception {
        speech.saveSettings(Json.obj("detailedActing", false, "useStoryMemory", false));
        String id = "pronunciation-reset-race"; int work = 258; String chapterUrl = url(work, 0);
        install(id, work, new int[]{0}, true, 2, true); readingChapter(id, work, 0, false);
        Transport legacy = new Transport(); start(id, 1, 1, legacy); idle(true);
        JSONObject seeded = speech.plan(id, chapterUrl); attachFixtureReadings(seeded);
        Json.arr(seeded, "units").optJSONObject(1).remove("pronunciation"); speech.savePlan(id, chapterUrl, seeded);
        String unitId = Json.arr(seeded, "units").optJSONObject(0).optString("unitId");
        speech.savePosition(id, chapterUrl, 1, seeded.optString("inputHash")); String playback = speech.position(id).toString();
        String reading = new ReaderStore(context).position(id).toString();
        speech.saveSettings(Json.obj("detailedActing", true));
        ChapterTransport responses = new ChapterTransport(); GatedTransport gate = new GatedTransport(responses);
        start(id, 1, 1, gate); HeldRequest held = gate.take(); waitingEvent(id, held, "chapter", 1, 1, 2, 1);
        eq(Json.arr(held.input, "targetUnits").length(), 2); eq(Json.arr(held.input, "pronunciationTargets").length(), 1);
        yes(!unitId.equals(Json.arr(held.input, "pronunciationTargets").optJSONObject(0).optString("sourceUnitId")), "reset fixture begins outside the request's reading targets");
        speech.savePronunciation(id, chapterUrl, unitId, null, true);
        JSONObject reset = Json.arr(speech.plan(id, chapterUrl), "units").optJSONObject(0);
        yes(!reset.has("pronunciation") && !reset.optBoolean("pronunciationLocked"), "reset clears only the chosen reading");
        String revision = reset.optString("pronunciationEditRevision"); yes(!revision.isEmpty(), "reset is an explicit revision even though it unlocks reading");
        held.release.countDown(); idle(true); terminalActivity(id, "paused", 1);
        JSONObject paused = SpeechAnalysis.snapshot(context, id), saved = speech.plan(id, chapterUrl);
        eq(paused.optString("analysisPauseReason"), "pronunciation_changed"); eq(paused.optString("error"), "");
        eq(saved.optString("analysisPauseReason"), "pronunciation_changed");
        eq(SpeechAnalysis.presentPlan(saved, paused, chapterUrl).optString("analysisPauseReason"), "pronunciation_changed");
        yes(SpeechPerformance.valid(Json.arr(saved, "units").optJSONObject(0)), "reading reset preserves completed acting from the valid response");
        yes(SpeechPronunciation.valid(Json.arr(saved, "units").optJSONObject(1)), "reading reset does not discard another unit's valid reading");
        eq(Json.arr(saved, "units").optJSONObject(0).optString("pronunciationEditRevision"), revision);
        yes(!Json.arr(saved, "units").optJSONObject(0).has("pronunciation"), "old pending response cannot undo a reading reset");
        eq(gate.sends.get(), 1); eq(responses.calls, 1); eq(paused.optInt("completedChapters"), 0);
        SpeechAnalysis.resumeRange(context, id, gate); HeldRequest resumed = gate.take();
        JSONObject active = SpeechAnalysis.snapshot(context, id);
        yes(!active.has("analysisPauseReason"), "explicit resume clears the former pause reason");
        yes(!SpeechAnalysis.presentPlan(speech.plan(id, chapterUrl), active, chapterUrl).has("analysisPauseReason"), "reopened running plan clears the former pause reason");
        eq(Json.arr(resumed.input, "targetUnits").length(), 0);
        eq(Json.arr(resumed.input, "pronunciationTargets").length(), 1);
        eq(Json.arr(resumed.input, "pronunciationTargets").optJSONObject(0).optString("sourceUnitId"), unitId);
        resumed.release.countDown(); idle(true); terminalActivity(id, "complete", 2);
        JSONObject completed = speech.plan(id, chapterUrl);
        yes(SpeechPronunciation.valid(Json.arr(completed, "units").optJSONObject(0)), "explicit resume adopts a fresh reading after reset");
        yes(!completed.has("analysisPauseReason") && !SpeechAnalysis.snapshot(context, id).has("analysisPauseReason"), "completed run retains no stale pause reason");
        eq(gate.sends.get(), 2); eq(responses.calls, 2); unchangedSource(seeded, completed);
        eq(speech.position(id).toString(), playback); eq(new ReaderStore(context).position(id).toString(), reading);
    }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("speech-range-runtime-").toFile(); context = new SpeechStoreTestContext(root); speech = new SpeechStore(context);
        SpeechAnalysis.setListener(value -> events.add(Json.copy(value)));
        try {
            speech.saveSettings(Json.obj("detailedActing", false));
            successfulRange(); failureAndResume(); cancellationAndLateEvents(); invalidCompletedCheckpoint(); deletionAndMissingLinks();
            detailedRangeAndResume();
            legacyDetailedResume(); historyBootstrapAndReuse(); missingHistoryStillReadsTarget(); cancelHistoryAndResume();
            boundedHistoryAndManualChapter(); resumedHistoryConsentScope();
            futureManualMemoryScope();
            yes(checks >= 717, "the existing 1.9.0 regression coverage still runs before new checks");
            aliasMemoryHistoryTargetAndReuse(); rejectedMemoryPreservesPriorState();
            liveActivityBeforeResponse(); failedActivityResumeAndReuse(); cancelledWaitingAndResume();
            memoOnlyReopenedActivity(); terminalActivityKeepsLatestManualUnits();
            recoveredMemoActivitySurvivesRangeReplacement();
            yes(checks >= 1736, "the existing 1.9.1 regression coverage still runs before pronunciation orchestration checks");
            newReadingSharesChapterRequest(); existingActingGetsOnlyReading(); rejectedPronunciationIsAtomic();
            readingCancellationResumesOnlyMissingChapter(); manualReadingWinsOverPendingResponse(); resetReadingPausesWithoutAutomaticResend();
            eq(ReaderStore.positionWrites, 0); eq(ReaderStore.readWrites, 0);
            System.out.println("SpeechAnalysisRuntime production checks passed: " + checks);
            System.out.println("Real SpeechAnalysis/SpeechAnalysisRange/SpeechStore/SpeechAnalyzer/ChatGptResponses/ChapterContent; synthetic SQLite, auth, main-loop and page/HTTP transports.");
            System.out.println("Sequential chapters, pinned legacy/new workflows, alias-canonical memory subject/object IDs, strict safe memory diagnostics, live request-wait activity, resume/reuse/cancellation, fixed cast and unchanged reader/playback positions.");
            System.out.println("New standard/detailed chapter acting + reading in one request, reading-only upgrades, unchanged source/acting offsets, strict Han/ruby rejection, manual reading/reset races and explicit resume. Synthetic reading fixtures do not measure GPT reading accuracy.");
            System.out.println("No Android service lifecycle, real SQLite/WebView, account, network request or inference charge was used.");
        } finally {
            SpeechAnalysis.cancel(); SpeechContent.release();
            java.lang.reflect.Field field = SpeechAnalysis.class.getDeclaredField("WORK"); field.setAccessible(true);
            ((ExecutorService)field.get(null)).shutdownNow(); SpeechAnalysis.setListener(null); remove(root);
        }
    }
}
