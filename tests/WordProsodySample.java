package jp.haruyalab.novelshelf;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same cast, performance and verified readings across versions. No remote model is used. */
public final class WordProsodySample {
    static JSONObject cue(String word, int occurrence, String tone, int strength) {
        return Json.obj("word",word,"occurrence",occurrence,"tone",tone,"strength",strength);
    }

    static JSONObject line(int number, boolean wordDirections) throws Exception {
        String text = number == 0 ? "お願い、今だけはここにいて。" : "絶対に、君を置いていかない。";
        String[] readings = number == 0 ? new String[]{"オネガイ、","イマダケワココニイテ。"} : new String[]{"ゼッタイニ、","キミオオイテイカナイ。"};
        JSONObject unit = Json.obj("kind","dialogue","text",text,"spokenText",text,"emotion","neutral","pitch",1,"rate",1);
        JSONArray source = SpeechPerformance.parts(unit), parts = new JSONArray(), directions = new JSONArray();
        for (int i=0; i<source.length(); i++) {
            JSONObject part=source.getJSONObject(i);
            JSONObject saved=Json.obj("partId",part.getString("partId"),"reading",readings[i]);
            if (wordDirections) {
                JSONArray words=new JSONArray();
                if(number==0 && i==0) words.put(cue("オネガイ",1,"hesitate",3));
                if(number==0 && i==1) words.put(cue("イマダケ",1,"focus",3)).put(cue("イテ",1,"soft",2));
                if(number==1 && i==0) words.put(cue("ゼッタイニ",1,"firm",3));
                if(number==1 && i==1) words.put(cue("キミ",1,"focus",2));
                saved.put("words",words);
            }
            parts.put(saved);
            directions.put(Json.obj("partId",part.getString("partId"),"emotion",number==0?"tension":"determination",
                "delivery",number==0?"soft":"firm","intensity",.42,"rate",1,"pitch",1,"intonation",1,"volume",1,
                "pauseAfterMs",0,"ending","auto","emphasis",.1,"confidence",1));
        }
        unit.put("pronunciation",SpeechPronunciation.validate(unit,parts,true));
        unit.put("performance",SpeechPerformance.validate(unit,directions,false));
        return unit;
    }

    static byte[] render(VoicevoxEngine engine, JSONObject unit) throws Exception {
        JSONObject speaker=Json.obj("id","sample","offlineVoice","kotaro","rate",1,"pitch",1);
        return engine.synthesizeUnit(unit.getString("spokenText"),"kotaro",unit,speaker,()->false);
    }

    public static void main(String[] args) throws Exception {
        Context context = new Context(new File(args[0]),args[1]);
        boolean wordDirections=Boolean.parseBoolean(args[3]);
        Path out=Path.of(args[2]); Files.createDirectories(out);
        try (VoicevoxEngine engine=new VoicevoxEngine(context)) {
            for(int i=0;i<2;i++) Files.write(out.resolve("line-"+(i+1)+".wav"),render(engine,line(i,wordDirections)));
        }
    }
}
