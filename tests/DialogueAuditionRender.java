package jp.haruyalab.novelshelf;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Renders the real production path and records the sample offsets of streamed phrases. */
public final class DialogueAuditionRender {
    static void render(VoicevoxEngine engine, Path directory, String name, JSONObject unit, String voice, DialogueMode mode) throws Exception {
        String stored=unit.toString();
        JSONArray chunks=new JSONArray();
        ByteArrayOutputStream stream=new ByteArrayOutputStream();
        VoicePcm.Sink sink=(pcm,spoken)->{
            if(spoken) chunks.put(Json.obj("start",stream.size()/2,"frames",pcm.length/2));
            stream.write(pcm,0,pcm.length);
        };
        JSONObject speaker=Json.obj("id","audition","offlineVoice",voice,"rate",1,"pitch",1);
        byte[] wav=engine.synthesizeUnit(unit.optString("spokenText"),voice,unit,speaker,()->false,sink,mode);
        if(!Arrays.equals(VoicePcm.samples(wav),stream.toByteArray())||!unit.toString().equals(stored)) throw new AssertionError("stream or source mismatch");
        Files.write(directory.resolve(name+".wav"),wav);
        Files.writeString(directory.resolve(name+".json"),Json.obj("voice",voice,"chunks",chunks,"text",unit.optString("text")).toString(2));
    }

    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),out=Path.of(args[1]); Files.createDirectories(out);
        DialogueMode mode = "true".equals(args[2]) ? DialogueMode.ANIME : "false".equals(args[2])
                ? DialogueMode.STANDARD : DialogueMode.valueOf(args[2].toUpperCase(java.util.Locale.ROOT));
        Context c=new Context(root.resolve("private").toFile(),root.resolve("native").toString());
        try(VoicevoxEngine engine=new VoicevoxEngine(c)){
            JSONObject scene=DialogueSample.unit();
            render(engine,out,"audition",scene,"kotaro",mode);
            render(engine,out,"default-voice",scene,"metan",mode);
            render(engine,out,"previous-plea",WordProsodySample.line(0,true),"kotaro",mode);
            render(engine,out,"previous-promise",WordProsodySample.line(1,true),"kotaro",mode);
            JSONObject everyday = DialogueSample.everyday();
            render(engine,out,"everyday",everyday,"kotaro",mode);
            JSONObject reading = everyday.getJSONObject("pronunciation").getJSONArray("parts").getJSONObject(1);
            Json.put(reading,"words",new JSONArray());
            if (!SpeechPronunciation.valid(everyday)) throw new AssertionError("unaltered reading required");
            render(engine,out,"everyday-no-focus",everyday,"kotaro",mode);
            JSONObject plain=Json.obj("text","大丈夫？まだ間に合う！","spokenText","大丈夫？まだ間に合う！","kind","dialogue","emotion","neutral","intensity",0);
            render(engine,out,"unanalysed",plain,"tsumugi",mode);
            Json.put(plain,"kind","narration");
            render(engine,out,"narration",plain,"tsumugi",mode);
        }
    }
}
