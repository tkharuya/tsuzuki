package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic transport: reading and acting share one request, with independent fixed annotations. */
public final class SpeechPronunciationAnalysisTest {
    private static int checks;
    private static void yes(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    static JSONObject plan(int count) {
        JSONArray paragraphs = new JSONArray();
        for (int i = 0; i < count; i++) paragraphs.put(Json.obj("text", "「母は花を運ぶ。今日は生物を学ぶ。」"));
        return SpeechPlan.create("pronunciation-fixture", Json.obj("url", "local://pronunciation/0", "title", "自作読み検査", "index", 0, "paragraphs", paragraphs), null);
    }
    private static String fixtureReading(String source) {
        // These are exact, author-written fixture readings, not a production conversion dictionary.
        return source.replace("母は花を運ぶ。", "ハハワハナオハコブ。")
                .replace("今日は生物を学ぶ。", "キョーワセーブツオマナブ。")
                .replace("は来た。", "ワキタ。").replace("話", "ハナシ").replace("次", "ツギ")
                .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
    static JSONObject metadata(JSONObject request) {
        JSONObject input = SpeechChapterAnalysisTest.data(request); JSONArray assignments = new JSONArray();
        for (int i = 0; i < Json.arr(input, "targetUnits").length(); i++) {
            JSONObject target = Json.arr(input, "targetUnits").optJSONObject(i);
            assignments.put(Json.obj("unitId", target.optString("unitId"), "speakerId", "unknown", "kind", "dialogue",
                    "confidence", .4, "profileId", "p_neutral", "changes", new JSONArray()));
        }
        JSONObject result = Json.obj("cast", new JSONArray(), "profiles", new JSONArray(), "assignments", assignments, "memoryDelta", StoryMemory.emptyDelta());
        if (input.has("pronunciationTargets")) {
            JSONArray readings = new JSONArray(), targets = Json.arr(input, "pronunciationTargets");
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.optJSONObject(i); JSONArray parts = Json.arr(target, "parts"), output = new JSONArray();
                for (int p = 0; p < parts.length(); p++) {
                    JSONObject part = parts.optJSONObject(p);
                    output.put(Json.obj("partId", part.optString("partId"), "reading", fixtureReading(part.optString("readingSource"))));
                }
                readings.put(Json.obj("unitId", target.optString("unitId"), "parts", output));
            }
            Json.put(result, "pronunciations", readings);
        }
        return result;
    }
    private static SpeechAnalyzer analyzer(SpeechAnalyzer.Transport transport) {
        return new SpeechAnalyzer(transport).withDetailedActing(true).withChapterWorkflow(true).withPronunciation(true).withStoryMemory(null);
    }
    private static JSONObject run(SpeechAnalyzer analyzer, JSONObject plan, JSONArray cast, SpeechAnalyzer.Progress progress) throws Exception {
        return analyzer.analyze(plan, cast, "fixture-private-key", "gpt-6-luna", () -> false, progress);
    }
    private static JSONObject finished(JSONObject source) {
        JSONObject plan = Json.copy(source); Json.put(plan, "analysisDetail", "detailed"); Json.put(plan, "analysisModel", "gpt-6-luna");
        Json.put(plan, "analysisMethod", "chapter"); Json.put(plan, "analysisStatus", "complete");
        for (int i = 0; i < Json.arr(plan, "units").length(); i++) {
            JSONObject unit = Json.arr(plan, "units").optJSONObject(i); Json.put(unit, "analyzed", true); Json.put(unit, "speakerId", "c_alice");
            Json.put(unit, "performance", SpeechPerformance.validate(unit, SpeechPerformanceTest.annotations(unit), true));
        }
        return plan;
    }
    public static void main(String[] args) throws Exception {
        wholeChapterAndReuse(); readingOnlyKeepsActing(); independentManualLocks(); lateReset(); invalidIsAtomic(); rubyAndLanguages();
        outputCapAndResume(); standardAndHistory(); whitespaceUnits(); wordBackfill();
        System.out.println("SpeechPronunciationAnalysisTest: PASS (" + checks + " combined-request/241-units/cache/manual/ruby/atomicity/resume checks; synthetic responses, no model accuracy benchmark)");
    }
    private static void wordBackfill() throws Exception {
        JSONObject old = finished(plan(2));
        JSONArray units = Json.arr(old,"units");
        for (int i=0; i<units.length(); i++) {
            JSONObject unit=units.optJSONObject(i);
            JSONArray parts=SpeechPronunciation.modelParts(unit), result=new JSONArray();
            for (int j=0; j<parts.length(); j++) {
                JSONObject p=parts.optJSONObject(j);
                result.put(Json.obj("partId",p.optString("partId"),"reading",fixtureReading(p.optString("readingSource"))));
            }
            JSONObject pronunciation=SpeechPronunciation.validate(unit,result,true);
            for (int j=0;j<result.length();j++) pronunciation.optJSONArray("parts").optJSONObject(j).remove("words");
            Json.put(unit,"pronunciation",pronunciation);
        }
        Json.put(units.optJSONObject(1),"pronunciationLocked",true);
        String reading=SpeechPronunciation.text(units.optJSONObject(0)), cast=units.optJSONObject(0).optString("speakerId");
        final int[] calls={0};
        SpeechAnalyzer analyzer=analyzer((request,key,cancel)->{
            calls[0]++;
            JSONObject data=SpeechChapterAnalysisTest.data(request);
            eq(Json.arr(data,"targetUnits").length(),0);
            JSONArray targets=Json.arr(data,"pronunciationTargets"); eq(targets.length(),1);
            yes(Json.arr(targets.optJSONObject(0),"parts").optJSONObject(0).optString("existingReading").contains("ハナ"),"verified reading is sent with original surface context");
            JSONObject response=metadata(request);
            JSONArray proposed=Json.arr(Json.arr(response,"pronunciations").optJSONObject(0),"parts");
            JSONObject first=proposed.optJSONObject(0);
            Json.put(first,"reading","カッテニカエタ。");
            Json.put(first,"words",new JSONArray().put(Json.obj("word","ハナ","occurrence",1,"tone","focus","strength",3))
                .put(Json.obj("word","ムカンケイ","occurrence",1,"tone","focus","strength",3)));
            return SpeechChapterAnalysisTest.envelope(response);
        });
        JSONObject result=run(analyzer,old,new JSONArray(),null), saved=result.optJSONObject("plan"), scored=Json.arr(saved,"units").optJSONObject(0);
        eq(saved.optString("analysisStatus"),"complete"); eq(calls[0],1); eq(SpeechPronunciation.text(scored),reading); eq(scored.optString("speakerId"),cast);
        JSONArray words=scored.optJSONObject("pronunciation").optJSONArray("parts").optJSONObject(0).optJSONArray("words");
        eq(words.length(),1); eq(words.optJSONObject(0).optString("word"),"ハナ");
        yes(!WordProsody.scored(Json.arr(saved,"units").optJSONObject(1)),"manually locked reading excluded");
        run(analyzer,saved,result.optJSONArray("cast"),null); eq(calls[0],1);
    }
    private static void wholeChapterAndReuse() throws Exception {
        final int[] calls = {0}; JSONObject original = plan(241); String untouched = original.toString(); List<String> stages = new ArrayList<>();
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
            calls[0]++; eq(key, "fixture-private-key"); JSONObject input = SpeechChapterAnalysisTest.data(request);
            eq(Json.arr(input, "chapterUnits").length(), 241); eq(Json.arr(input, "targetUnits").length(), 241); eq(Json.arr(input, "pronunciationTargets").length(), 241);
            yes(input.optBoolean("japanesePronunciation"), "chapter Japanese mode");
            for (String phrase : new String[]{"SAME response", "母は花を運ぶ", "キョー", "セーブツ", "ナマモノ", "exactly ONCE", "2000 UTF-16"})
                yes(request.optString("instructions").contains(phrase), "explicit sound/ruby/length instruction");
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        }).withActivity(value -> stages.add(value.optString("stage")));
        JSONObject result = run(analyzer, original, new JSONArray(), null), saved = result.optJSONObject("plan");
        eq(calls[0], 1); eq(original.toString(), untouched); eq(saved.optInt("pronunciationVersion"), 1); eq(saved.optInt("analyzedUnits"), 241);
        eq(saved.optString("analysisStatus"), "complete"); eq(saved.optJSONObject("analysisMetrics").optInt("requestCount"), 1);
        eq(String.join(",", stages), "preparing,preparing,authorizing,waiting,validating,saving");
        for (int i = 0; i < 241; i++) {
            JSONObject unit = Json.arr(saved, "units").optJSONObject(i), before = Json.arr(original, "units").optJSONObject(i);
            for (String field : new String[]{"unitId", "text", "spokenText", "start", "end", "index"}) eq(unit.opt(field), before.opt(field));
            yes(SpeechPerformance.valid(unit), "existing acting representation retained"); yes(SpeechPronunciation.valid(unit), "complete pronunciation saved");
            eq(unit.optJSONObject("performance").optBoolean("reviewed"), false);
            eq(SpeechPronunciation.text(unit), "「ハハワハナオハコブ。キョーワセーブツオマナブ。」");
        }
        stages.clear(); JSONObject reused = run(analyzer, saved, result.optJSONArray("cast"), null).optJSONObject("plan");
        eq(calls[0], 1); eq(reused.optJSONObject("analysisMetrics").optInt("requestCount"), 0); eq(String.join(",", stages), "preparing,saving");
        JSONObject stale = Json.copy(saved); Json.put(Json.arr(stale, "units").optJSONObject(4).optJSONObject("pronunciation"), "sourceHash", "stale");
        final int[] repairs = {0}; JSONObject repaired = run(analyzer((request, key, cancel) -> {
            repairs[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request); eq(Json.arr(input, "targetUnits").length(), 0);
            eq(Json.arr(input, "pronunciationTargets").length(), 1); eq(Json.arr(input, "pronunciationTargets").optJSONObject(0).optString("unitId"), "u4");
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        }), stale, result.optJSONArray("cast"), null).optJSONObject("plan");
        eq(repairs[0], 1); eq(repaired.optInt("analyzedUnits"), 241);
    }
    private static void readingOnlyKeepsActing() throws Exception {
        JSONObject old = finished(plan(3)); JSONArray cast = SpeechMemoryIdentityTest.known();
        JSONObject first = Json.arr(old, "units").optJSONObject(0); Json.put(first, "locked", true);
        String beforeCast = cast.toString(); final int[] calls = {0};
        JSONObject result = run(analyzer((request, key, cancel) -> {
            calls[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request); eq(Json.arr(input, "targetUnits").length(), 0); eq(Json.arr(input, "pronunciationTargets").length(), 3);
            JSONObject output = metadata(request);
            // A syntactically valid new cast suggestion still cannot change a reading-only job's established cast.
            Json.put(output, "cast", new JSONArray().put(Json.obj("id", "c_new", "name", "新しい候補", "aliases", new JSONArray(), "gender", "neutral", "tier", "extra", "offlineVoice", "miko", "style", "", "rate", 1, "pitch", 1)));
            return SpeechChapterAnalysisTest.envelope(output);
        }), old, cast, null);
        eq(calls[0], 1); eq(result.optJSONArray("cast").toString(), beforeCast);
        JSONArray units = Json.arr(result.optJSONObject("plan"), "units"), previous = Json.arr(old, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i), before = previous.optJSONObject(i);
            eq(unit.optJSONObject("performance").toString(), before.optJSONObject("performance").toString());
            for (String field : new String[]{"speakerId", "kind", "emotion", "intensity", "rate", "pitch", "confidence", "locked", "analyzed"}) eq(unit.opt(field), before.opt(field));
            yes(SpeechPronunciation.valid(unit), "reading added without acting regeneration");
        }
        final int[] modelCalls = {0};
        JSONObject updated = analyzer((request, key, cancel) -> {
            modelCalls[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request);
            eq(Json.arr(input, "targetUnits").length(), 2); eq(Json.arr(input, "pronunciationTargets").length(), 0);
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        }).analyze(result.optJSONObject("plan"), cast, "fixture-private-key", "gpt-6.1-sol", () -> false, null).optJSONObject("plan");
        eq(modelCalls[0], 1); eq(Json.arr(updated, "units").optJSONObject(0).optString("speakerId"), "c_alice");
        for (int i = 0; i < units.length(); i++) eq(SpeechPronunciation.signature(Json.arr(updated, "units").optJSONObject(i)), SpeechPronunciation.signature(units.optJSONObject(i)));
    }
    private static void independentManualLocks() throws Exception {
        JSONObject source = plan(3); JSONArray originals = Json.arr(source, "units");
        Json.put(originals.optJSONObject(0), "locked", true); Json.put(originals.optJSONObject(0), "speakerId", "c_alice");
        JSONObject fixedReading = originals.optJSONObject(1); Json.put(fixedReading, "pronunciationLocked", true);
        Json.put(fixedReading, "pronunciation", SpeechPronunciation.validateManual(fixedReading, SpeechPronunciationTest.readings(fixedReading, "「シュドー。", "コテイ。」")));
        AtomicBoolean responseReturned = new AtomicBoolean(); final int[] calls = {0};
        JSONObject result = run(analyzer((request, key, cancel) -> {
            calls[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request); eq(Json.arr(input, "targetUnits").length(), 2); eq(Json.arr(input, "pronunciationTargets").length(), 2);
            eq(Json.arr(input, "targetUnits").optJSONObject(0).optString("unitId"), "u1");
            eq(Json.arr(input, "pronunciationTargets").optJSONObject(0).optString("unitId"), "u0");
            responseReturned.set(true); return SpeechChapterAnalysisTest.envelope(metadata(request));
        }), source, SpeechMemoryIdentityTest.known(), (plan, cast) -> {
            if (responseReturned.get()) {
                JSONObject late = Json.arr(plan, "units").optJSONObject(2); Json.put(late, "locked", true); Json.put(late, "speakerId", "c_bob");
                Json.put(late, "pronunciationLocked", true); Json.put(late, "pronunciation", SpeechPronunciation.validateManual(late,
                        SpeechPronunciationTest.readings(late, "「アトカラ。", "コテイ。」")));
            }
        }).optJSONObject("plan");
        eq(calls[0], 1); JSONArray units = Json.arr(result, "units");
        eq(units.optJSONObject(0).optString("speakerId"), "c_alice"); yes(!units.optJSONObject(0).has("performance"), "acting lock remains intact"); yes(SpeechPronunciation.valid(units.optJSONObject(0)), "acting lock does not block reading");
        yes(SpeechPerformance.valid(units.optJSONObject(1)), "reading lock does not block acting"); eq(SpeechPronunciation.text(units.optJSONObject(1)), "「シュドー。コテイ。」");
        eq(units.optJSONObject(2).optString("speakerId"), "c_bob"); yes(!units.optJSONObject(2).has("performance"), "late manual acting protected"); eq(SpeechPronunciation.text(units.optJSONObject(2)), "「アトカラ。コテイ。」");
        eq(result.optInt("analyzedUnits"), 3);
    }
    private static void invalidIsAtomic() throws Exception {
        for (int variant = 0; variant < 8; variant++) {
            final int which = variant; final JSONObject[] saved = {null};
            try {
                run(analyzer((request, key, cancel) -> {
                    JSONObject output = metadata(request); JSONArray rows = output.optJSONArray("pronunciations");
                    JSONObject part = rows.optJSONObject(1).optJSONArray("parts").optJSONObject(0);
                    if (which == 0) rows.remove(1);
                    if (which == 1) Json.put(rows.optJSONObject(1), "unitId", "u_unknown");
                    if (which == 2) Json.put(rows.optJSONObject(1), "unitId", "u0");
                    if (which == 3) Json.put(part, "partId", "p_unknown");
                    if (which == 4) Json.put(part, "reading", "母は花を運ぶ。");
                    if (which == 5) Json.put(part, "reading", "ハハ\nワ");
                    if (which == 6) Json.put(part, "reading", new String(new char[2001]).replace('\0', 'ア'));
                    if (which == 7) Json.put(part, "start", 0);
                    return SpeechChapterAnalysisTest.envelope(output);
                }), plan(2), new JSONArray(), (plan, cast) -> saved[0] = Json.copy(plan));
                throw new AssertionError("invalid pronunciation adopted");
            } catch (IOException expected) {
                yes(expected.getCause() instanceof SpeechPronunciation.ValidationFailure, "typed pronunciation error survives analyzer");
                eq(saved[0].optString("analysisStatus"), "error"); eq(saved[0].optInt("analyzedUnits"), 0);
                eq(saved[0].optJSONObject("analysisError").optString("code"), "invalid_pronunciation");
                for (int i = 0; i < 2; i++) {
                    JSONObject unit = Json.arr(saved[0], "units").optJSONObject(i);
                    yes(!unit.optBoolean("analyzed"), "valid acting draft not adopted with invalid reading"); yes(!unit.has("performance") && !unit.has("pronunciation"), "batch is atomic");
                }
                yes(!saved[0].optJSONObject("analysisError").toString().contains("fixture-private-key"), "diagnostics omit credential");
            }
        }
        JSONObject lockedInvalid = finished(plan(1)); Json.put(Json.arr(lockedInvalid, "units").optJSONObject(0), "pronunciationLocked", true);
        try { run(analyzer((r,k,c) -> { throw new AssertionError("fixed missing reading sent"); }), lockedInvalid, new JSONArray(), null); throw new AssertionError("missing fixed reading marked complete"); }
        catch (IOException expected) { yes(expected.getCause() instanceof SpeechPronunciation.ValidationFailure, "invalid fixed reading is not silently overwritten"); }
    }
    private static void lateReset() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int which = mode; AtomicBoolean returned = new AtomicBoolean(), edited = new AtomicBoolean(); final int[] sends = {0};
            JSONObject source = plan(2);
            if (mode == 2) {
                JSONObject unit = Json.arr(source, "units").optJSONObject(0);
                Json.put(unit, "pronunciation", SpeechPronunciation.validate(unit, SpeechPronunciationTest.readings(unit, "「ハハワハナオハコブ。", "キョーワセーブツオマナブ。」")));
            }
            JSONObject paused = run(analyzer((request,key,cancel) -> {
                sends[0]++; yes(sends[0] <= 1, "manual reset never triggers automatic resend"); returned.set(true);
                return SpeechChapterAnalysisTest.envelope(metadata(request));
            }), source, new JSONArray(), (p,c) -> {
                if (returned.get() && (which != 1 || p.optInt("batchIndex") > 0) && edited.compareAndSet(false, true)) {
                    JSONObject unit = Json.arr(p, "units").optJSONObject(0); unit.remove("pronunciation");
                    Json.put(unit, "pronunciationLocked", false); Json.put(unit, "pronunciationEditRevision", "fixture-user-reset");
                }
            }).optJSONObject("plan");
            eq(sends[0], 1); eq(paused.optString("analysisStatus"), "paused"); eq(paused.optInt("analyzedUnits"), 1);
            eq(paused.optString("analysisPauseReason"), "pronunciation_changed");
            JSONObject reset = Json.arr(paused, "units").optJSONObject(0);
            yes(!reset.has("pronunciation"), "in-flight user reset remains clear"); yes(SpeechPerformance.valid(reset), "reading reset does not discard completed acting");
            JSONObject resumed = run(analyzer((request,key,cancel) -> {
                JSONObject input = SpeechChapterAnalysisTest.data(request); eq(Json.arr(input, "targetUnits").length(), 0); eq(Json.arr(input, "pronunciationTargets").length(), 1);
                return SpeechChapterAnalysisTest.envelope(metadata(request));
            }), paused, new JSONArray(), null).optJSONObject("plan");
            eq(resumed.optInt("analyzedUnits"), 2); eq(resumed.optString("analysisStatus"), "complete"); yes(!resumed.has("analysisPauseReason"), "resume clears old reason");
        }
    }
    private static void rubyAndLanguages() throws Exception {
        JSONObject chapter = Json.obj("url", "local://pronunciation/ruby", "paragraphs", new JSONArray().put(Json.obj("text", "青葉は来た。", "html", "<ruby>青葉<rt>あお、ば</rt></ruby>は来た。")));
        JSONObject plan = SpeechPlan.create("ruby", chapter, null); final int[] calls = {0};
        JSONObject result = run(analyzer((request, key, cancel) -> { calls[0]++; return SpeechChapterAnalysisTest.envelope(metadata(request)); }), plan, new JSONArray(), null).optJSONObject("plan");
        eq(calls[0], 1); eq(SpeechPronunciation.text(Json.arr(result, "units").optJSONObject(0)), "あお、ばワキタ。");
        final JSONObject[] failed = {null};
        try {
            run(analyzer((request, key, cancel) -> {
                JSONObject output = metadata(request); Json.put(Json.arr(Json.arr(output, "pronunciations").optJSONObject(0), "parts").optJSONObject(0), "reading", "アオ、");
                return SpeechChapterAnalysisTest.envelope(output);
            }), plan, new JSONArray(), (p,c) -> failed[0] = Json.copy(p)); throw new AssertionError("author ruby replaced");
        } catch (IOException expected) { eq(failed[0].optJSONObject("analysisError").optString("stage"), "pronunciation_ruby"); }
        JSONObject mixed = plan(1); JSONObject name = SpeechPlan.create("name", Json.obj("url", "local://name", "paragraphs", new JSONArray().put("青葉").put("母は花を運ぶ。")), null);
        try { run(analyzer((request, key, cancel) -> SpeechChapterAnalysisTest.envelope(metadata(request))), name, new JSONArray(), (p,c) -> failed[0] = Json.copy(p)); throw new AssertionError("Japanese name Han unconverted"); }
        catch (IOException expected) { eq(failed[0].optJSONObject("analysisError").optString("stage"), "pronunciation_reading"); }
        JSONObject chinese = SpeechPlan.create("zh", Json.obj("url", "local://chinese", "paragraphs", new JSONArray().put("今天是星期一。")), null);
        JSONObject originalLanguage = run(analyzer((request,key,cancel) -> SpeechChapterAnalysisTest.envelope(metadata(request))), chinese, new JSONArray(), null).optJSONObject("plan");
        eq(SpeechPronunciation.text(Json.arr(originalLanguage, "units").optJSONObject(0)), "今天是星期一。");
    }
    private static void outputCapAndResume() throws Exception {
        final int[] sends = {0}; AtomicBoolean stop = new AtomicBoolean();
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
            sends[0]++; if (sends[0] == 1) return Json.obj("status", "incomplete", "incomplete_details", Json.obj("reason", "max_output_tokens"));
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        });
        JSONObject paused = analyzer.analyze(plan(4), new JSONArray(), "fixture-private-key", "gpt-6-luna", stop::get,
                (p,c) -> { if (p.optInt("analyzedUnits") == 2) stop.set(true); }).optJSONObject("plan");
        eq(sends[0], 2); eq(paused.optString("analysisStatus"), "paused"); eq(paused.optInt("analyzedUnits"), 2);
        final int[] resumes = {0}; JSONObject completed = run(analyzer((request,key,cancel) -> {
            resumes[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request); eq(Json.arr(input, "targetUnits").length(), 2); eq(Json.arr(input, "pronunciationTargets").length(), 2);
            eq(Json.arr(input, "targetUnits").optJSONObject(0).optString("unitId"), "u2");
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        }), paused, new JSONArray(), null).optJSONObject("plan");
        eq(resumes[0], 1); eq(completed.optInt("analyzedUnits"), 4);
        for (int i = 0; i < 2; i++) eq(Json.arr(completed, "units").optJSONObject(i).toString(), Json.arr(paused, "units").optJSONObject(i).toString());
    }
    private static void standardAndHistory() throws Exception {
        final int[] calls = {0}; SpeechAnalyzer analyzer = analyzer((request,key,cancel) -> {
            calls[0]++; JSONObject input = SpeechChapterAnalysisTest.data(request);
            if ("history_memory".equals(input.optString("analysisStage"))) {
                yes(!input.has("pronunciationTargets"), "history does not generate speech reading");
                return SpeechChapterAnalysisTest.envelope(Json.obj("cast", new JSONArray(), "memoryDelta", StoryMemory.emptyDelta()));
            }
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        }).withDetailedActing(false);
        JSONObject result = run(analyzer, plan(241), new JSONArray(), null).optJSONObject("plan");
        eq(calls[0], 1); eq(result.optString("analysisDetail"), "standard"); eq(result.optInt("analyzedUnits"), 241);
        for (int i = 0; i < 241; i++) { JSONObject unit = Json.arr(result, "units").optJSONObject(i); yes(!unit.has("performance"), "standard keeps coarse acting contract"); yes(SpeechPronunciation.valid(unit), "standard includes reading"); }
        analyzer.summarizeHistory(new JSONArray().put(plan(2)), new JSONArray(), "fixture-private-key", "gpt-6-luna", () -> false);
        eq(calls[0], 2);
    }
    private static void whitespaceUnits() throws Exception {
        JSONObject source = SpeechPlan.create("space", Json.obj("url", "local://spaces", "paragraphs", new JSONArray().put("「話」 「次」")), null);
        eq(Json.arr(source, "units").length(), 3); eq(Json.arr(source, "units").optJSONObject(1).optString("spokenText"), " ");
        final int[] calls = {0}; JSONObject saved = run(analyzer((request,key,cancel) -> {
            calls[0]++; return SpeechChapterAnalysisTest.envelope(metadata(request));
        }), source, new JSONArray(), null).optJSONObject("plan");
        eq(calls[0], 1); eq(saved.optInt("analyzedUnits"), 3); eq(saved.optString("analysisStatus"), "complete");
        JSONObject silent = Json.arr(saved, "units").optJSONObject(1); eq(SpeechPronunciation.text(silent), " "); yes(SpeechPronunciation.valid(silent), "silent inter-quote unit completes");
    }
}
