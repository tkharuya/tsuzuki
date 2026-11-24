package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Tests production segmentation with invented prose only; no network or Android mocks. */
public final class SpeechPlanTest {
    private static int checks;
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual); }
    private static void yes(boolean actual, String message) { checks++; if (!actual) throw new AssertionError(message); }
    private static JSONObject chapter(JSONArray paragraphs) { return Json.obj("url", "local://fixture/chapter-1", "title", "自作テスト", "paragraphs", paragraphs); }
    private static JSONObject chapter(String... texts) { JSONArray p = new JSONArray(); for (String s : texts) p.put(Json.obj("text", s)); return chapter(p); }
    private static void exactCoverage(JSONObject chapter, JSONObject plan) {
        JSONArray paragraphs = Json.arr(chapter, "paragraphs"), units = Json.arr(plan, "units");
        StringBuilder[] texts = new StringBuilder[paragraphs.length()]; int[] end = new int[paragraphs.length()];
        for (int i = 0; i < texts.length; i++) texts[i] = new StringBuilder();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i); int p = unit.optInt("paragraph");
            eq(unit.optInt("index"), i); yes(ids.add(unit.optString("unitId")), "unit IDs must be unique");
            eq(unit.optInt("start"), end[p]);
            String original = paragraphs.optJSONObject(p) == null ? paragraphs.optString(p) : paragraphs.optJSONObject(p).optString("text");
            eq(unit.optString("text"), original.substring(unit.optInt("start"), unit.optInt("end")));
            end[p] = unit.optInt("end"); texts[p].append(unit.optString("text"));
            String text = unit.optString("text");
            yes(text.isEmpty() || !Character.isLowSurrogate(text.charAt(0)), "must not start inside a surrogate pair");
            yes(text.isEmpty() || !Character.isHighSurrogate(text.charAt(text.length() - 1)), "must not end inside a surrogate pair");
        }
        for (int i = 0; i < paragraphs.length(); i++) {
            JSONObject p = paragraphs.optJSONObject(i); eq(texts[i].toString(), p == null ? paragraphs.optString(i) : p.optString("text"));
        }
    }
    public static void main(String[] args) {
        JSONObject text = chapter("朝の机に紙を置いた。「先に読んで」と青葉が言った。（まだ間に合う）窓を閉じる。", "", "  空白も残す。\n改行を残す。  ", "\"A test.\" He said it wasn't difficult. 😀終わり。");
        JSONObject plan = SpeechPlan.create("book-1", text, null);
        exactCoverage(text, plan);
        JSONArray units = Json.arr(plan, "units");
        eq(units.optJSONObject(0).optString("kind"), "narration");
        eq(units.optJSONObject(1).optString("kind"), "dialogue");
        eq(units.optJSONObject(1).optString("speakerId"), "unknown");
        boolean thought = false; for (int i = 0; i < units.length(); i++) thought |= "thought".equals(units.optJSONObject(i).optString("kind"));
        yes(thought, "thought quote must form a unit");
        eq(SpeechPlan.create("book-1", text, null).optString("inputHash"), plan.optString("inputHash"));
        eq(Json.arr(SpeechPlan.create("book-1", text, null), "units").toString(), units.toString());
        eq(SpeechPlan.compatible(plan, text), true);
        JSONObject edited = Json.copy(text); Json.arr(edited, "paragraphs").optJSONObject(0).remove("text");
        eq(SpeechPlan.compatible(plan, edited), false);
        JSONObject moved = Json.copy(text); Json.put(moved, "url", "local://fixture/chapter-2");
        eq(SpeechPlan.compatible(plan, moved), false);

        JSONObject ruby = chapter(new JSONArray().put(Json.obj("text", "青葉は本を読む。", "html", "<ruby>青葉<rp>（</rp><rt>あおば</rt><rp>）</rp></ruby>は本を読む。")));
        JSONObject rubyPlan = SpeechPlan.create("b", ruby, null);
        eq(Json.arr(rubyPlan, "units").optJSONObject(0).optString("spokenText"), "あおばは本を読む。");
        eq(Json.arr(rubyPlan, "units").optJSONObject(0).optString("text"), "青葉は本を読む。");
        exactCoverage(ruby, rubyPlan);
        JSONObject rubyOff = SpeechPlan.create("b", ruby, Json.obj("useRuby", false));
        eq(Json.arr(rubyOff, "units").optJSONObject(0).optString("spokenText"), "青葉は本を読む。");
        yes(!rubyOff.optString("inputHash").equals(rubyPlan.optString("inputHash")), "ruby setting must invalidate derived text");
        JSONObject changedRuby = Json.copy(ruby); Json.put(Json.arr(changedRuby, "paragraphs").optJSONObject(0), "html", "<ruby>青葉<rt>せいよう</rt></ruby>は本を読む。");
        eq(SpeechPlan.compatible(rubyPlan, changedRuby), false);
        JSONObject entities = chapter(new JSONArray().put(Json.obj("text", "A&碧<>\"😀", "html", "A&amp;<ruby>碧<rt>あお</rt></ruby>&lt;&gt;&quot;&#x1f600;")));
        eq(Json.arr(SpeechPlan.create("b", entities, null), "units").optJSONObject(0).optString("spokenText"), "A&あお<>");
        exactCoverage(entities, SpeechPlan.create("b", entities, null));
        JSONObject mismatch = chapter(new JSONArray().put(Json.obj("text", "碧はいる", "html", "<ruby>別の文<rt>かいてはいけない</rt></ruby>")));
        eq(Json.arr(SpeechPlan.create("b", mismatch, null), "units").optJSONObject(0).optString("spokenText"), "碧はいる");
        JSONObject malformed = chapter(new JSONArray().put(Json.obj("text", "碧", "html", "<ruby>碧<rt>あお</ruby>")));
        eq(Json.arr(SpeechPlan.create("b", malformed, null), "units").optJSONObject(0).optString("spokenText"), "碧");
        JSONObject trimmedRuby = chapter(new JSONArray().put(Json.obj("text", "碧です", "html", " <ruby>碧<rt>あお</rt></ruby>です  ")));
        eq(Json.arr(SpeechPlan.create("b", trimmedRuby, null), "units").optJSONObject(0).optString("spokenText"), "あおです");

        JSONArray many = new JSONArray(); for (int i = 0; i < 1207; i++) many.put(Json.obj("text", "「第" + i + "番の自作台詞です。」"));
        JSONObject longChapter = chapter(many), longPlan = SpeechPlan.create("b", longChapter, null);
        eq(Json.arr(longPlan, "units").length(), 1207); exactCoverage(longChapter, longPlan);
        StringBuilder longParagraph = new StringBuilder(); for (int i = 0; i < 80000; i++) longParagraph.append("続く😀 ");
        JSONObject enormous = chapter(longParagraph.toString()), enormousPlan = SpeechPlan.create("b", enormous, null);
        yes(Json.arr(enormousPlan, "units").length() > 600, "long paragraph must not be truncated at 600 units");
        exactCoverage(enormous, enormousPlan);
        for (int i = 0; i < Json.arr(enormousPlan, "units").length(); i++) yes(Json.arr(enormousPlan, "units").optJSONObject(i).optString("spokenText").length() <= 350, "bounded audio segment");
        JSONObject acrossParagraphs = chapter("「ここから", "二段落目まで話す。」その後に歩く。");
        exactCoverage(acrossParagraphs, SpeechPlan.create("b", acrossParagraphs, null));
        eq(Json.arr(SpeechPlan.create("b", acrossParagraphs, null), "units").optJSONObject(1).optString("kind"), "dialogue");

        StringBuilder rubyText = new StringBuilder(), rubyHtml = new StringBuilder(), rubySpoken = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            rubyText.append("碧の字。"); rubyHtml.append("<ruby>碧<rt>あお</rt></ruby>の字。"); rubySpoken.append("あおの字。");
        }
        JSONObject rubyHeavy = chapter(new JSONArray().put(Json.obj("text", rubyText.toString(), "html", rubyHtml.toString())));
        JSONObject rubyHeavyPlan = SpeechPlan.create("b", rubyHeavy, null); StringBuilder readBack = new StringBuilder();
        exactCoverage(rubyHeavy, rubyHeavyPlan);
        for (int i = 0; i < Json.arr(rubyHeavyPlan, "units").length(); i++) {
            String spoken = Json.arr(rubyHeavyPlan, "units").optJSONObject(i).optString("spokenText");
            yes(spoken.length() <= 350, "ruby reading must respect bounded speech segment"); readBack.append(spoken);
        }
        eq(readBack.toString(), rubySpoken.toString());

        JSONObject manual = Json.obj("id", "c_aoba", "name", "ユーザー名", "aliases", new JSONArray().put("青葉"), "gender", "female", "tier", "main", "voice", "shimmer", "deviceVoice", "device-test", "style", "静かに", "rate", 1.1, "pitch", .9, "manual", true);
        JSONObject ai = Json.obj("id", "c_aoba", "name", "AI名", "aliases", new JSONArray(), "gender", "male", "tier", "extra", "voice", "echo", "style", "強く", "rate", .8, "pitch", 1.4);
        JSONArray merged = SpeechPlan.mergeCast(new JSONArray().put(manual), new JSONArray().put(ai));
        eq(merged.optJSONObject(0).toString(), CastRegistry.migrate(new JSONArray().put(manual), "metan").optJSONObject(0).toString());
        eq(merged.optJSONObject(0).optBoolean("profileLocked"), true);
        eq(merged.optJSONObject(0).optBoolean("voiceLocked"), true);
        JSONObject stable = Json.copy(manual); Json.put(stable, "manual", false);
        JSONArray stableCast = SpeechPlan.mergeCast(new JSONArray().put(stable), new JSONArray().put(ai));
        eq(stableCast.optJSONObject(0).optString("voice"), "shimmer");
        eq(stableCast.optJSONObject(0).optString("deviceVoice"), "device-test");
        eq(stableCast.optJSONObject(0).optString("style"), "静かに");
        eq(stableCast.optJSONObject(0).optDouble("pitch"), .9);
        eq(SpeechPlan.mergeCast(null, null).length(), 2);
        JSONObject lockedPlan = SpeechPlan.create("b", chapter("「未特定の台詞。」"), null);
        Json.put(Json.arr(lockedPlan, "units").optJSONObject(0), "locked", true); eq(SpeechPlan.analyzedCount(lockedPlan), 1);
        System.out.println("SpeechPlan production checks passed: " + checks);
    }
}
