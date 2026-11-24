package jp.haruyalab.novelshelf;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises stored directions, source-local fallbacks, manual pauses and same-character timbres. */
public final class DialogueActingTest {
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++; if(!condition)throw new AssertionError(message);
    }
    static void run(VoicevoxEngine engine) throws Exception {
        JSONObject scene=DialogueSample.unit(),speaker=Json.obj("id","actor","offlineVoice","kotaro","rate",1,"pitch",1);
        String text=scene.getString("spokenText"),saved=scene.toString();
        JSONArray script=VoicevoxEngine.renderingParts(scene,true,true);
        String[] beats={"STARTLED","CALL","TENDER","SAD"};
        check(script.length()==beats.length,"the script changes intention at four source phrases");
        for(int n=0;n<beats.length;n++) check(script.getJSONObject(n).getString("beat").equals(beats[n]),"part "+n+" playing direction");
        check(script.getJSONObject(0).getInt("pauseAfterMs")==430,"a startled ellipsis leaves room before the call");
        check(VoicevoxEngine.renderingParts(scene).getJSONObject(0).getInt("pauseAfterMs")==0,"standard retains the original pause");
        check(DialogueActing.CALL.style("kotaro",script.getJSONObject(1))==12,"exclamation alone does not assign an angry voice");
        check(DialogueActing.STARTLED.style("kotaro",script.getJSONObject(0))==33,"startled Kotaro uses his own fear style");
        check(DialogueActing.TENDER.style("metan",script.getJSONObject(2))==0,"Metan can act softly in her own model");
        check(DialogueActing.TENDER.style("tsumugi",script.getJSONObject(2))==8,"single-style character never changes identity");
        check(DialogueActing.select(Json.obj("emotion","joy"),"やった！",false)==DialogueActing.BRIGHT,"scene evidence takes precedence over an exclamation");
        check(DialogueActing.select(new JSONObject(),"そうか。",true)==DialogueActing.INNER,"a neutral inner thought retains normal voicing");
        check(DialogueActing.select(Json.obj("delivery","whisper"),"静かに。",false)==DialogueActing.HUSHED,"explicit whisper is distinct");
        check(DialogueActing.CALL.pause("待って！",15,true)==15 && DialogueActing.STARTLED.pause("えっ……",0,true)==0,"manual pauses survive");
        check(DialogueActing.STARTLED.pause("えっ……",600,false)==600,"a longer directed pause is preserved");
        check(scene.toString().equals(saved),"planning never changes saved input");
        JSONObject neutral=Json.obj("kind","narration","text","森が眠っている。","spokenText","森が眠っている。");
        byte[] standard=engine.synthesizeUnit(neutral.getString("text"),"kotaro",neutral,speaker,()->false,null,false);
        byte[] anime=engine.synthesizeUnit(neutral.getString("text"),"kotaro",neutral,speaker,()->false,null,true);
        check(Arrays.equals(standard,anime),"narrator PCM is bit-identical across modes");
        JSONObject everyday=Json.obj("text","今日はいい天気。","spokenText","今日はいい天気。","kind","dialogue");
        JSONObject quiet=VoicevoxEngine.renderingParts(everyday,false,true).getJSONObject(0);
        check(quiet.getString("beat").equals("TALK"),"ordinary unanalysed speech does not acquire an invented emotion");
        JSONArray conversational = VoicevoxEngine.renderingParts(scene,true,DialogueMode.NATURAL);
        check(conversational.getJSONObject(0).getInt("pauseAfterMs")==160,"brief hesitation in conversational mode");
        check(DialogueActing.STARTLED.conversationPause("えっ……",630,true)==630,"manual conversational pause is exact");
        check(DialogueActing.STARTLED.conversationStyle("kotaro",script.getJSONObject(0),false)==12,"surprise keeps this actor's normal register");
        check(DialogueActing.SAD.conversationStyle("kotaro",script.getJSONObject(3),false)==12,"plea stays in the same register");
        check(DialogueActing.SAD.conversationStyle("kotaro",script.getJSONObject(3),true)==35,"manual sadness preserves selected character direction");
        check(DialogueActing.HUSHED.conversationStyle("metan",Json.obj("delivery","whisper"),false)==36,"requested whisper retained");
        JSONObject casual=DialogueSample.everyday();
        check(VoicevoxEngine.renderingParts(casual,true,DialogueMode.NATURAL).length()==1,"ordinary dialogue flows through one native phrase group");
        check(VoicevoxEngine.renderingParts(casual,true,DialogueMode.ANIME).length()==2,"former mode still honors its different part rates");
        Json.put(casual,"locked",true);
        check(VoicevoxEngine.renderingParts(casual,true,DialogueMode.NATURAL).length()==2,"manual part rates never merged by similarity");
        JSONObject extreme=Json.obj("rate",1.4,"pitch",.8);
        JSONObject toned=DialogueActing.conversationControls(extreme,false);
        check(toned.optDouble("rate")<1.25 && toned.optDouble("pitch")>.85 && extreme.optDouble("rate")==1.4,"automatic changes are softened without mutating stored controls");
        check(DialogueActing.conversationControls(extreme,true)==extreme,"manual numeric controls kept");
        byte[] gentleNarrator=engine.synthesizeUnit(neutral.getString("text"),"kotaro",neutral,speaker,()->false,null,DialogueMode.NATURAL);
        check(Arrays.equals(standard,gentleNarrator),"natural mode never acts the narrator");
        System.out.println("DialogueActingTest: PASS ("+checks+" direction, natural fallback, style, pause and real unchanged-narration checks)");
    }
}
