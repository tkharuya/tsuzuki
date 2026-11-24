package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed audition. Both playback modes receive the same actor, script, readings and directions. */
final class DialogueSample {
    private DialogueSample() { }

    static JSONObject everyday() {
        String text = "おかえり。今日は早かったね。";
        JSONObject unit = Json.obj("unitId", "conversation-preview", "text", text, "spokenText", text,
                "paragraph", 0, "speakerId", "preview", "kind", "dialogue", "emotion", "neutral",
                "intensity", .15, "rate", 1, "pitch", 1);
        JSONArray parts = SpeechPerformance.parts(unit), directions = new JSONArray(), readings = new JSONArray();
        String[] kana = {"オカエリ。", "キョーワハヤカッタネ。"};
        for (int index = 0; index < parts.length(); index++) {
            String id = parts.optJSONObject(index).optString("partId");
            JSONArray words = new JSONArray();
            if (index == 1) words.put(Json.obj("word", "ハヤカッタ", "occurrence", 1, "tone", "focus", "strength", 1));
            readings.put(Json.obj("partId", id, "reading", kana[index], "words", words));
            directions.put(Json.obj("partId", id, "emotion", "neutral", "delivery", "natural",
                    "intensity", .15, "rate", index == 1 ? 1.025 : 1, "pitch", 1, "intonation", 1,
                    "volume", 1, "pauseAfterMs", 0, "ending", "auto", "emphasis", 0, "confidence", 1));
        }
        Json.put(unit, "pronunciation", SpeechPronunciation.validate(unit, readings, true));
        Json.put(unit, "performance", SpeechPerformance.validate(unit, directions, false));
        return unit;
    }

    static JSONObject unit() {
        String text="えっ……待って！お願い、行かないで。";
        JSONObject unit=Json.obj("unitId","acting-preview","text",text,"spokenText",text,"paragraph",0,
                "speakerId","preview","kind","dialogue","emotion","neutral","intensity",.4,"rate",1,"pitch",1);
        JSONArray parts=SpeechPerformance.parts(unit), directions=new JSONArray(), readings=new JSONArray();
        String[] sounds={"エッ……","マッテ！","オネガイ、","イカナイデ。"};
        String[] emotions={"surprise","tension","tension","sadness"};
        String[] styles={"natural","urgent","soft","soft"};
        String[] words={"エッ","マッテ","オネガイ","イカナイデ"};
        String[] tones={"hesitate","firm","hesitate","soft"};
        for (int i=0;i<parts.length();i++) {
            String id=parts.optJSONObject(i).optString("partId");
            directions.put(Json.obj("partId",id,"emotion",emotions[i],"delivery",styles[i],"intensity",.82,
                    "rate",1,"pitch",1,"intonation",1,"volume",1,"pauseAfterMs",i==2?120:0,
                    "ending","auto","emphasis",.32,"confidence",1));
            readings.put(Json.obj("partId",id,"reading",sounds[i],"words",new JSONArray()
                    .put(Json.obj("word",words[i],"occurrence",1,"tone",tones[i],"strength",2))));
        }
        Json.put(unit,"pronunciation",SpeechPronunciation.validate(unit,readings,true));
        Json.put(unit,"performance",SpeechPerformance.validate(unit,directions,false));
        return unit;
    }
}
