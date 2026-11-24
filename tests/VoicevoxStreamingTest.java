package jp.haruyalab.novelshelf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real JNI first-chunk versus full-unit measurement. No Android speaker timing or GPT quality claim. */
public final class VoicevoxStreamingTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void run(VoicevoxEngine engine,File directory)throws Exception{
        String text="待って。今は動かないで。……よし、もう大丈夫だ。ゆっくり歩こう。";
        JSONObject unit=Json.obj("text",text,"spokenText",text,"emotion","neutral","rate",1,"pitch",1);
        JSONObject speaker=Json.obj("id","stream-role","offlineVoice","kotaro","rate",1,"pitch",1);
        JSONArray boundaries=SpeechPerformance.parts(unit),directions=new JSONArray();
        for(int i=0;i<boundaries.length();i++)directions.put(Json.obj("partId",boundaries.optJSONObject(i).optString("partId"),
                "emotion",i<2?"tension":"relief","delivery",i<2?"firm":"soft","intensity",.5,
                "rate",i<2?1.03:.95,"pitch",1,"intonation",i<2?1.12:.95,"volume",1,
                "pauseAfterMs",i<2?150:220,"ending",i<2?"cut":"fall","emphasis",.2,"confidence",.8));
        Json.put(unit,"performance",SpeechPerformance.validate(unit,directions,false));
        check(SpeechPerformance.valid(unit),"complete single-pass data is accepted for native streaming");
        JSONArray measurements=new JSONArray();
        for(int run=0;run<3;run++){
            long baselineStart=System.nanoTime();byte[] baseline=engine.synthesizeUnit(text,"kotaro",unit,speaker,()->false);
            long baselineMs=(System.nanoTime()-baselineStart)/1000000;
            ByteArrayOutputStream chunks=new ByteArrayOutputStream();final long[] first={-1};final int[] voiced={0};long began=System.nanoTime();
            byte[] streamed=engine.synthesizeUnit(text,"kotaro",unit,speaker,()->false,(pcm,ready)->{
                chunks.write(pcm,0,pcm.length);if(ready){voiced[0]++;if(first[0]<0)first[0]=(System.nanoTime()-began)/1000000;}
            });
            long whole=(System.nanoTime()-began)/1000000;
            check(voiced[0]>1,"several native chunks were produced");check(first[0]>=0&&first[0]<whole,"first safe PCM is available before final synthesis");
            check(Arrays.equals(VoicePcm.samples(streamed),chunks.toByteArray()),"callback chunks are exactly the final WAV PCM");
            check(Arrays.equals(VoicePcm.samples(baseline),VoicePcm.samples(streamed)),"streaming leaves baseline rendering unchanged");
            JSONObject row=Json.obj("run",run+1,"baselineFullUnitMs",baselineMs,"firstChunkMs",first[0],"streamFullUnitMs",whole,
                    "audioMs",VoicePcm.samples(streamed).length/48,"nativeSpeechChunks",voiced[0]);measurements.put(row);
            System.out.println("LATENCY "+row);
        }
        AtomicBoolean cancelled=new AtomicBoolean();final int[] delivered={0};boolean interrupted=false;
        try{engine.synthesizeUnit(text,"kotaro",unit,speaker,cancelled::get,(pcm,ready)->{if(ready){delivered[0]++;cancelled.set(true);}});}
        catch(InterruptedIOException expected){interrupted=true;}
        check(interrupted&&delivered[0]==1,"cancellation after first chunk stops subsequent native chunks");
        JSONObject report=Json.obj("platform","Linux x86_64","clock","System.nanoTime","textOrigin","self-authored",
                "voice","VOICEVOX:白上虎太郎","annotations","manual","androidDeviceTested",false,"actualSpeakerStartMeasured",false,
                "gptAccuracyMeasured",false,"measurements",measurements);
        Files.writeString(new File(directory,"voicevox-stream-latency.json").toPath(),report.toString(2)+"\n");
        System.out.println("VoicevoxStreamingTest: PASS ("+checks+" real JNI first-chunk/PCM/cancellation checks; Linux only)");
    }
}
