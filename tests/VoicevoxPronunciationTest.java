package jp.haruyalab.novelshelf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hiroshiba.voicevoxcore.AccentPhrase;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.Mora;
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer;

/** Manual readings over self-authored text; real JNI mora/PCM, never a GPT accuracy assessment. */
public final class VoicevoxPronunciationTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);System.out.println("PASS reading "+label);}
    private static JSONObject unit(String text){return Json.obj("text",text,"spokenText",text,"emotion","neutral","rate",1,"pitch",1);}
    private static JSONObject reading(JSONObject source,String...readings)throws Exception{
        JSONObject unit=Json.copy(source);JSONArray boundaries=SpeechPerformance.parts(unit),parts=new JSONArray();
        if(boundaries.length()!=readings.length)throw new AssertionError("reading fixture boundary count");
        for(int i=0;i<boundaries.length();i++){
            JSONObject part=boundaries.optJSONObject(i);parts.put(Json.obj("partId",part.optString("partId"),
                    "start",part.optInt("start"),"end",part.optInt("end"),"reading",readings[i]));
        }
        unit.put("pronunciation",SpeechPronunciation.validateManual(unit,parts));return unit;
    }
    private static JSONObject acting(JSONObject source,boolean merge)throws Exception{
        JSONObject unit=Json.copy(source);JSONArray boundaries=SpeechPerformance.parts(unit),parts=new JSONArray();
        for(int i=0;i<boundaries.length();i++)parts.put(Json.obj("partId",boundaries.optJSONObject(i).optString("partId"),
                "emotion",merge?"neutral":i==0?"tension":"relief","delivery",merge?"natural":i==0?"firm":"soft",
                "intensity",.4,"rate",1,"pitch",1,"intonation",1,"volume",1,
                "pauseAfterMs",merge&&i+1<boundaries.length()?0:170,"ending",merge?"auto":i==0?"cut":"fall",
                "emphasis",.1,"confidence",.9));
        unit.put("performance",SpeechPerformance.validate(unit,parts,false));return unit;
    }
    private static JSONObject moras(Synthesizer synth,String input)throws Exception{
        AudioQuery query=synth.createAudioQuery(input,12);StringBuilder text=new StringBuilder(),phones=new StringBuilder();
        for(AccentPhrase phrase:query.accentPhrases)for(Mora mora:phrase.moras){
            text.append(mora.text);if(mora.consonant!=null)phones.append(mora.consonant).append(' ');phones.append(mora.vowel).append(' ');
        }
        return Json.obj("input",input,"moraText",text.toString(),"phonemes",phones.toString().trim());
    }
    static void run(VoicevoxEngine engine,File directory)throws Exception{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("fixture directory");
        String original="今日は生物を学ぶ。母は花を運ぶ。";
        String[] kana={"きょーわせーぶつおまなぶ。","ははわはなおはこぶ。"};
        JSONObject speaker=Json.obj("id","reading-role","offlineVoice","kotaro","rate",1,"pitch",1);
        JSONObject source=unit(original),pronounced=reading(source,kana);String unchanged=pronounced.toString();
        check(SpeechPronunciation.valid(pronounced),"explicit manual readings match source and all phrase ranges");
        String nativeText=SpeechReadingInput.text(pronounced);
        check(nativeText.equals("キョーワセーブツオマナブ。ハハワハナオハコブ。"),"audio input preserves explicit particle wa and lexical ha");
        byte[] legacy=engine.synthesizeUnit(original,"kotaro",source,speaker,()->false);
        byte[] coarse=engine.synthesizeUnit(original,"kotaro",pronounced,speaker,()->false);
        byte[] kanaOnly=engine.synthesizeUnit(nativeText,"kotaro",unit(nativeText),speaker,()->false);
        check(Arrays.equals(VoicePcm.samples(coarse),VoicePcm.samples(legacy)),"matching written pronunciation retains the original native accent and PCM");
        check(!Arrays.equals(VoicePcm.samples(coarse),VoicePcm.samples(kanaOnly)),"the fixture distinguishes written accent from all-kana PCM");
        check(VoicePcm.samples(coarse).length>24000,"pronounced output contains real nonempty native audio");
        check(pronounced.toString().equals(unchanged),"native playback never rewrites visible text or stored reading");
        JSONArray renders=new JSONArray();
        for(boolean merge:new boolean[]{false,true}){
            JSONObject directed=acting(pronounced,merge),reference=acting(unit(original),merge);
            String originalDirections=directed.optJSONObject("performance").toString();
            JSONArray groups=VoicevoxEngine.renderingParts(directed);
            check(groups.length()==(merge?1:2),"expected "+(merge?"merged":"separate")+" acting groups are retained");
            StringBuilder reconstructed=new StringBuilder();
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                reconstructed.append(SpeechReadingInput.text(directed,group.optInt("start"),group.optInt("end")));
            }
            check(reconstructed.toString().equals(nativeText),"original offsets retrieve all expanded readings across "+(merge?"merged":"separate")+" groups");
            ByteArrayOutputStream chunks=new ByteArrayOutputStream();final int[] speechChunks={0};
            byte[] actual=engine.synthesizeUnit(original,"kotaro",directed,speaker,()->false,(pcm,ready)->{
                chunks.write(pcm,0,pcm.length);if(ready)speechChunks[0]++;
            });
            byte[] referenceWave=engine.synthesizeUnit(original,"kotaro",reference,speaker,()->false);
            check(Arrays.equals(VoicePcm.samples(actual),VoicePcm.samples(referenceWave)),"dense acting retains matching written pronunciation without changing controls");
            check(Arrays.equals(VoicePcm.samples(actual),chunks.toByteArray())&&speechChunks[0]==groups.length(),"streamed reading PCM exactly matches completed WAV and native group count");
            check(SpeechPerformance.valid(directed)&&directed.optJSONObject("performance").toString().equals(originalDirections),"reading leaves every acting offset and direction intact");
            String file=merge?"pronunciation-merged-kotaro.wav":"pronunciation-directed-kotaro.wav";
            Files.write(new File(directory,file).toPath(),actual);
            renders.put(Json.obj("mode",merge?"merged":"separate","nativeGroups",groups.length(),"audioMs",VoicePcm.samples(actual).length/48,"file",file));
        }
        JSONObject food=reading(unit("今日の生物は冷蔵庫に入れる。"),"きょーのなまものわれーぞーこにいれる。");
        String foodText=SpeechReadingInput.text(food);
        byte[] foodWave=engine.synthesizeUnit(food.optString("spokenText"),"kotaro",food,speaker,()->false);
        byte[] foodReference=engine.synthesizeUnit(foodText,"kotaro",unit(foodText),speaker,()->false);
        check(Arrays.equals(VoicePcm.samples(foodWave),VoicePcm.samples(foodReference)),"the alternate namamono reading reaches real synthesis");
        JSONObject stale=Json.copy(pronounced);stale.optJSONObject("pronunciation").put("sourceHash","old-source");
        check(!SpeechPronunciation.valid(stale),"stale pronunciation is rejected before playback");
        check(Arrays.equals(VoicePcm.samples(legacy),VoicePcm.samples(engine.synthesizeUnit(original,"kotaro",stale,speaker,()->false))),"stale reading returns to unchanged legacy synthesis");
        boolean mismatch=false;try{engine.synthesizeUnit(original+"別文","kotaro",pronounced,speaker,()->false);}catch(IOException error){mismatch="VOICEVOX_TEXT_MISMATCH".equals(error.getMessage());}
        check(mismatch,"valid pronunciation cannot be attached to a different supplied transcript");

        Field field=VoicevoxEngine.class.getDeclaredField("synthesizer");field.setAccessible(true);Synthesizer synth=(Synthesizer)field.get(engine);
        JSONObject hiragana=moras(synth,SpeechPronunciation.text(pronounced)),literal=moras(synth,nativeText),alternate=moras(synth,foodText);
        System.out.println("PRONUNCIATION_MORAS "+Json.obj("literal",literal,"alternate",alternate));
        check(literal.optString("moraText").contains("ハハワハナオハコブ"),"actual native moras retain ha in haha/hana and wa for the particle");
        check(literal.optString("phonemes").contains("s e e b u ts u"),"actual native phonemes select the specified long-vowel seibutsu reading");
        check(literal.optString("phonemes").startsWith("ky o o w a"),"actual native phonemes retain kyo long vowel followed by particle wa");
        check(alternate.optString("moraText").contains("ナマモノ"),"actual native moras select namamono for the food context");
        check(!hiragana.optString("moraText").equals(literal.optString("moraText")),"native fixture demonstrates why explicit kana bypasses hiragana particle re-analysis");
        JSONObject partial=reading(unit("こんにちは。今日は生物。母は花。"),"こんにちは。","今日わなまもの。","ははわはな。");
        String partialText=SpeechReadingInput.text(partial);JSONObject partialMoras=moras(synth,partialText);
        check(partialText.equals("こんにちは。今日わなまもの。ハハワハナ。"),"mixed manual edits preserve untouched and kanji-containing source parts");
        check(partialMoras.optString("moraText").contains("コンニチワ")&&partialMoras.optString("moraText").contains("ハハワハナ"),"actual native greeting wa and explicitly directed lexical ha coexist");
        Files.write(new File(directory,"pronunciation-kotaro.wav").toPath(),coarse);
        Files.write(new File(directory,"pronunciation-namamono-kotaro.wav").toPath(),foodWave);
        JSONObject report=Json.obj("platform","Linux x86_64","textOrigin","self-authored","annotations","manual",
                "voice","VOICEVOX:白上虎太郎","androidDeviceTested",false,"gptAccuracyMeasured",false,
                "original",original,"savedReading",SpeechPronunciation.text(pronounced),"literalKana",literal,
                "hiraganaBeforeAudioConversion",hiragana,"alternateHomograph",alternate,"partialManualReading",partialMoras,"actingRenders",renders);
        Files.writeString(new File(directory,"voicevox-pronunciation.json").toPath(),report.toString(2)+"\n");
        System.out.println("VoicevoxPronunciationTest: PASS ("+checks+" real JNI reading/mora/PCM checks; manual readings, Linux only)");
    }
}
