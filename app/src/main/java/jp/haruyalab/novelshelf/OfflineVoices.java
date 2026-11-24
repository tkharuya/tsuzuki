package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable character IDs and bounded, local VOICEVOX AudioQuery controls. No network calls. */
public final class OfflineVoices {
    private OfflineVoices() { }
    public static final String VERSION = "voicevox-0.17.1-tsuzuki-v2";
    private static final Map<String, JSONObject> VOICES = new LinkedHashMap<>();
    static {
        add("metan", "四国めたん", "female", "明瞭で落ち着いた声。地の文や大人の女性役。", 2,
                styles(2,"ノーマル","neutral",0,"あまあま","tender",6,"ツンツン","defiant",36,"ささやき","whisper"),
                "https://zunko.jp/con_ongen_kiyaku.html");
        add("zundamon", "ずんだもん", "neutral", "高めで軽快な声。中性的な役や活発な役。", 3,
                styles(3,"ノーマル","neutral",1,"あまあま","tender",7,"ツンツン","defiant",22,"ささやき","whisper",76,"なみだめ","sadness"),
                "https://zunko.jp/con_ongen_kiyaku.html");
        add("tsumugi", "春日部つむぎ", "female", "明るく会話向きの声。元気な女性役。", 8,
                styles(8,"ノーマル","neutral"), "https://tsumugi-official.studio.site/rule");
        add("hau", "雨晴はう", "female", "柔らかく優しい声。穏やかな女性役。", 10,
                styles(10,"ノーマル","neutral"), "https://amehau.com/");
        add("takehiro", "玄野武宏", "male", "聞き取りやすい青年声。男性役や地の文。", 11,
                styles(11,"ノーマル","neutral",39,"喜び","joy",40,"ツンギレ","anger",41,"悲しみ","sadness"),
                "https://www.virvoxproject.com/voicevoxの利用規約");
        add("kotaro", "白上虎太郎", "male", "軽快な少年・青年声。感情の大きい役。", 12,
                styles(12,"ふつう","neutral",32,"わーい","joy",33,"びくびく","fear",34,"おこ","anger",35,"びえーん","sadness"),
                "https://www.virvoxproject.com/voicevoxの利用規約");
        add("ryusei", "青山龍星", "male", "低く厚みのある声。大人の男性役や落ち着いた地の文。", 13,
                styles(13,"ノーマル","neutral",81,"熱血","determination",82,"不機嫌","anger",83,"喜び","joy",84,"しっとり","calm",85,"かなしみ","sadness",86,"囁き","whisper"),
                "https://www.virvoxproject.com/voicevoxの利用規約");
        add("himari", "冥鳴ひまり", "female", "落ち着いて滑らかな声。地の文や冷静な役。", 14,
                styles(14,"ノーマル","neutral"), "https://meimeihimari.wixsite.com/himari/terms-of-use");
        add("sora", "九州そら", "female", "柔らかく息づかいのある声。静かな役やささやき。", 16,
                styles(16,"ノーマル","neutral",15,"あまあま","tender",18,"ツンツン","defiant",19,"ささやき","whisper"),
                "https://zunko.jp/con_ongen_kiyaku.html");
        add("miko", "櫻歌ミコ", "female", "高めで幼さのある声。少女役や小さなキャラクター。", 43,
                styles(43,"ノーマル","neutral"), "https://voicevox35miko.studio.site/rule");
    }
    private static void add(String id, String name, String gender, String description, int standard,
                            JSONArray styles, String terms) {
        VOICES.put(id, Json.obj("id", id, "name", name, "label", name, "gender", gender,
                "description", description, "styleId", standard, "styles", styles,
                "credit", "VOICEVOX:" + name, "termsUrl", terms, "offline", true));
    }
    private static JSONArray styles(Object... values) {
        JSONArray result = new JSONArray();
        for (int i=0;i<values.length;i+=3)
            result.put(Json.obj("id", values[i], "name", values[i+1], "emotion", values[i+2]));
        return result;
    }
    public static String defaultVoice() { return "metan"; }
    public static boolean valid(String id) { return id != null && VOICES.containsKey(id); }
    public static JSONArray catalog() {
        JSONArray result = new JSONArray();
        for (JSONObject voice : VOICES.values()) result.put(Json.copy(voice));
        return result;
    }
    public static String resolve(JSONObject speaker, JSONObject settings) {
        String id = speaker == null ? "" : speaker.optString("offlineVoice");
        if (valid(id)) return id;
        id = settings == null ? "" : settings.optString("offlineDefaultVoice");
        return valid(id) ? id : defaultVoice();
    }
    /** Called only at first migration/new identity creation; future gender/default edits never recast it. */
    public static String initialVoice(JSONObject person, String fallback) {
        String supplied = person.optString("offlineVoice");
        if (valid(supplied)) return supplied;
        String id = person.optString("id");
        if ("narrator".equals(id) || "unknown".equals(id)) return valid(fallback) ? fallback : defaultVoice();
        String gender = person.optString("gender", "neutral");
        String[] pool = "male".equals(gender) ? new String[]{"takehiro","kotaro","ryusei"}
                : "female".equals(gender) ? new String[]{"metan","tsumugi","hau","himari","sora","miko"}
                : new String[]{"zundamon","metan","himari"};
        // Including the old preset preserves distinction deterministically across the one-time migration.
        int hash = (id + "\n" + person.optString("geminiVoice")).hashCode();
        return pool[(hash & 0x7fffffff) % pool.length];
    }
    public static String name(String id) { JSONObject voice = VOICES.get(id); return voice == null ? "" : voice.optString("name"); }

    /** An emotion selects only styles belonging to the pinned voice. Missing styles use bounded prosody. */
    public static int styleId(String voice, JSONObject unit) {
        JSONObject entry = VOICES.get(valid(voice) ? voice : defaultVoice());
        if (unit == null) unit = new JSONObject();
        // A whisper is a delivery, not an emotion. Keep the old emotion spelling for saved 1.7 plans.
        String emotion = "whisper".equals(unit.optString("delivery")) ? "whisper" : unit.optString("emotion", "neutral");
        double intensity = finite(unit.optDouble("intensity", .4), 0, 1, .4);
        if (intensity >= .35 || "whisper".equals(emotion)) {
            JSONArray styles = Json.arr(entry, "styles");
            for (int i=0;i<styles.length();i++) {
                JSONObject style = styles.optJSONObject(i);
                if (emotion.equals(style.optString("emotion"))) return style.optInt("id");
            }
        }
        return entry.optInt("styleId");
    }

    /**
     * A reviewed detailed part already contains the model's numeric direction. Do not reapply the
     * coarse unit's emotion/rate/pitch. Character rate/pitch remain relative baselines, applied once.
     * Delivery and emphasis make small, bounded changes; they never invent a word to emphasize.
     */
    public static JSONObject performanceParameters(JSONObject part, JSONObject speaker) {
        if (part == null) part = new JSONObject();
        if (speaker == null) speaker = new JSONObject();
        double rate = finite(part.optDouble("rate", 1), .7, 1.4, 1)
                * finite(speaker.optDouble("rate", 1), .5, 1.5, 1);
        double pitch = finite(part.optDouble("pitch", 1), .8, 1.2, 1)
                * finite(speaker.optDouble("pitch", 1), .5, 2, 1);
        double intonation = finite(part.optDouble("intonation", 1), .6, 1.6, 1);
        double volume = finite(part.optDouble("volume", 1), .75, 1.2, 1);
        double pause = 1;
        switch (part.optString("delivery", "natural")) {
            case "soft": volume *= .96; intonation *= .97; break;
            case "whisper": volume *= .94; break;
            case "firm": volume *= 1.02; intonation *= 1.03; break;
            case "urgent": pause = .9; break;
            case "hesitant": pause = 1.12; break;
            default: break;
        }
        double emphasis = finite(part.optDouble("emphasis", 0), 0, 1, 0);
        intonation *= 1 + .08 * emphasis;
        volume *= 1 + .025 * emphasis;
        return Json.obj("speedScale", finite(rate, .5, 1.8, 1),
                "pitchScale", finite(Math.log(pitch)/Math.log(2)*.12, -.15, .15, 0),
                "intonationScale", finite(intonation, .6, 1.6, 1),
                "volumeScale", finite(volume, .75, 1.2, 1),
                // Padding is added once by the PCM assembler; part pauses are exact milliseconds.
                "prePhonemeLength", 0, "postPhonemeLength", 0, "pauseLengthScale", pause);
    }
    public static JSONObject parameters(JSONObject unit, JSONObject speaker) {
        if (unit == null) unit = new JSONObject();
        if (speaker == null) speaker = new JSONObject();
        double intensity = finite(unit.optDouble("intensity", .4), 0, 1, .4);
        double rate = finite(unit.optDouble("rate", 1), .5, 1.5, 1)
                * finite(speaker.optDouble("rate", 1), .5, 1.5, 1);
        double pitch = finite(unit.optDouble("pitch", 1), .5, 2, 1)
                * finite(speaker.optDouble("pitch", 1), .5, 2, 1);
        double intonation = 1, volume = 1, pre = .08, post = .16, pause = 1;
        // Local engines have no free-form acting prompt. Preserve the stored description,
        // applying only a small documented vocabulary; all other words remain descriptive.
        String description = speaker.optString("style").toLowerCase(java.util.Locale.ROOT);
        if (description.contains("ゆっくり") || description.contains("slow")) rate *= .9;
        if (description.contains("早口") || description.contains("fast")) rate *= 1.08;
        if (description.contains("落ち着") || description.contains("穏やか") || description.contains("calm")) intonation *= .9;
        if (description.contains("元気") || description.contains("energetic")) intonation *= 1.1;
        switch (unit.optString("emotion", "neutral")) {
            case "joy": intonation += .30*intensity; rate *= 1+.06*intensity; break;
            case "sadness": intonation -= .25*intensity; rate *= 1-.10*intensity; post += .12*intensity; break;
            case "anger": intonation += .25*intensity; volume += .08*intensity; break;
            case "fear": intonation += .18*intensity; pause += .20*intensity; break;
            case "surprise": intonation += .35*intensity; pre += .04*intensity; break;
            case "calm": intonation -= .20*intensity; rate *= 1-.06*intensity; break;
            case "tension": intonation += .18*intensity; pause += .15*intensity; break;
            case "whisper": intonation -= .28*intensity; volume -= .12*intensity; rate *= 1-.05*intensity; break;
            default: break;
        }
        // Pause fields are local/manual extensions; existing GPT schemas need no reanalysis.
        if (unit.has("pauseAfterMs")) post += finite(unit.optDouble("pauseAfterMs"), 0, 1500, 0)/1000;
        return Json.obj("speedScale", finite(rate, .5, 1.8, 1),
                "pitchScale", finite(Math.log(pitch)/Math.log(2)*.12, -.15, .15, 0),
                "intonationScale", finite(intonation, .6, 1.5, 1),
                "volumeScale", finite(volume, .75, 1.15, 1),
                "prePhonemeLength", pre, "postPhonemeLength", post,
                "pauseLengthScale", finite(pause, .8, 1.4, 1));
    }
    /** Canonical order is intentional: JSONObject iteration order is not a cache protocol. */
    public static String signature(int styleId, JSONObject parameters) {
        JSONArray values = new JSONArray().put(VERSION).put(styleId);
        for (String key : new String[]{"speedScale","pitchScale","intonationScale","volumeScale",
                "prePhonemeLength","postPhonemeLength","pauseLengthScale"}) {
            double value=parameters.optDouble(key);
            if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("音声パラメーターが不正です。");
            values.put(Double.valueOf(value)); // Android's primitive-double overload declares JSONException.
        }
        return values.toString();
    }
    private static double finite(double value, double min, double max, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }
}
