package jp.haruyalab.novelshelf;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hiroshiba.voicevoxcore.AccentPhrase;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.Mora;
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer;

/** Real native query and PCM, deterministic directions rather than a remote model. */
public final class VoicevoxWordProsodyTest {
    private static int checks;
    private static void check(boolean ok,String label) {
        checks++;
        if(!ok) throw new AssertionError(label);
    }
    private static List<Mora> moras(AudioQuery query) {
        List<Mora> list=new ArrayList<>();
        for(AccentPhrase phrase:query.accentPhrases) list.addAll(phrase.moras);
        return list;
    }
    private static JSONArray part(String reading, JSONArray words) {
        return new JSONArray().put(Json.obj("reading",reading,"start",0,"end",reading.length(),"words",words));
    }
    private static void unchanged(Mora a,Mora b,String label) {
        check(a.pitch==b.pitch && a.vowelLength==b.vowelLength,label);
    }
    static void run(VoicevoxEngine engine) throws Exception {
        Field field=VoicevoxEngine.class.getDeclaredField("synthesizer"); field.setAccessible(true);
        Synthesizer synth=(Synthesizer)field.get(engine);
        String text="キミダケ、キミダケ。";
        AudioQuery original=synth.createAudioQuery(text,12);
        List<Mora> before=moras(original);
        check(before.size()==8,"two occurrences, each four moras");
        for(String tone:new String[]{"focus","soft","hesitate","firm"}) {
            AudioQuery directed=synth.createAudioQuery(text,12);
            new WordContour(directed).apply(part(text,new JSONArray().put(WordProsodySample.cue("キミダケ",2,tone,3))),0,text.length());
            List<Mora> after=moras(directed);
            check(directed.accentPhrases.size()==original.accentPhrases.size(),"native phrase count is preserved");
            double firstShift=0,lastShift=0;
            int changed=0;
            for(int i=0;i<before.size();i++) {
                Mora a=before.get(i),b=after.get(i);
                if(i<4 || a.pitch==0) unchanged(a,b,"untargeted or devoiced mora is untouched");
                else {
                    if(changed==0) firstShift=b.pitch-a.pitch;
                    lastShift=b.pitch-a.pitch;
                    check(a.pitch!=b.pitch && a.vowelLength!=b.vowelLength,"selected voiced mora changes pitch and duration");
                    changed++;
                }
                check(a.text.equals(b.text) && a.vowel.equals(b.vowel),"the affected word keeps its phonemes");
            }
            check(changed>=2,"at least two selected voiced moras are shaped");
            if(tone.equals("focus")) check(firstShift>0 && lastShift>0,"highlight rises");
            if(tone.equals("soft")) check(firstShift<0 && lastShift<firstShift,"softens toward the end");
            if(tone.equals("hesitate")) check(lastShift>firstShift,"hesitation recovers from initial low tone");
            if(tone.equals("firm")) check(firstShift>0 && lastShift<0,"firm stress falls");
        }
        AudioQuery unsafe=synth.createAudioQuery(text,12);
        JSONArray cues=new JSONArray().put(JSONObject.NULL).put(WordProsodySample.cue("キミ",3,"focus",3))
            .put(WordProsodySample.cue("キミ",1,"unknown",2));
        new WordContour(unsafe).apply(part(text,cues),0,text.length());
        List<Mora> after=moras(unsafe);
        for(int i=0;i<after.size();i++) unchanged(before.get(i),after.get(i),"unusable stored cues skip without affecting audio");
        JSONObject edited=WordProsodySample.line(0,true);
        JSONArray editedParts=edited.getJSONObject("pronunciation").getJSONArray("parts");
        check(SpeechPronunciation.validateManualEdit(edited,editedParts).getJSONArray("parts").length()==2,"manual controls survive exact source bounds");
        editedParts.getJSONObject(0).getJSONArray("words").put(WordProsodySample.cue("ナイ",2,"soft",2));
        boolean rejected=false;
        try {SpeechPronunciation.validateManualEdit(edited,editedParts);} catch(IllegalArgumentException e) {rejected=e.getMessage().contains("句 1");}
        check(rejected,"manual invalid anchor shows the exact part and cannot silently save");
        check(WordProsody.checked("キョウ",new JSONArray().put(WordProsodySample.cue("キ",1,"firm",3))).length()==0,"a cue cannot split a contracted sound");
        String[] spellings={"キョー","きょお","ｷｮｰ"};
        for(String spelling:spellings) check(WordProsody.key(spelling).equals("キョオ"),"equivalent kana and long vowels align");
        check(!WordProsody.pauses("だよ？").equals(WordProsody.pauses("だよ。")),"explicit question and statement remain distinct");
        check(WordProsody.pauses("だよ?").equals(WordProsody.pauses("だよ？")),"matching question punctuation is reusable");
        for(int i=0;i<2;i++) {
            JSONObject flat=WordProsodySample.line(i,false),expressive=WordProsodySample.line(i,true);
            check(SpeechPronunciation.valid(expressive) && WordProsody.scored(expressive),"word cues validate and persist");
            check(!SpeechPronunciation.signature(flat).equals(SpeechPronunciation.signature(expressive)),"changing word directions changes the pronunciation audio cache");
            check(VoicevoxEngine.renderingParts(flat).length()==VoicevoxEngine.renderingParts(expressive).length(),"word cues add no synthesis segments");
            check(!Arrays.equals(VoicePcm.samples(WordProsodySample.render(engine,flat)),VoicePcm.samples(WordProsodySample.render(engine,expressive))),"word control alone changes actual rendered PCM with matching written-accent policy");
        }
        System.out.println("VoicevoxWordProsodyTest: PASS ("+checks+" real native word direction, mora and PCM checks)");
    }
}
