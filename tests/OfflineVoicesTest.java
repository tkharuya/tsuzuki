package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Pure production migration/prosody checks; no invented native synthesis or network calls. */
public final class OfflineVoicesTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void eq(Object actual, Object expected) { check(actual.equals(expected), actual + " != " + expected); }
    private static JSONObject person(JSONArray cast, String id) {
        for(int i=0;i<cast.length();i++) if(id.equals(cast.optJSONObject(i).optString("id"))) return cast.optJSONObject(i);
        throw new AssertionError(id);
    }
    public static void main(String[] args) throws Exception {
        JSONArray catalog=OfflineVoices.catalog(); eq(catalog.length(),10);
        String[] ids={"metan","zundamon","tsumugi","hau","takehiro","kotaro","ryusei","himari","sora","miko"};
        int[] standards={2,3,8,10,11,12,13,14,16,43};
        Set<Integer> usedStyles=new HashSet<>();
        for(int i=0;i<ids.length;i++) {
            JSONObject voice=catalog.optJSONObject(i); eq(voice.optString("id"),ids[i]);
            eq(OfflineVoices.styleId(ids[i],new JSONObject()),standards[i]);
            check(voice.optString("credit").startsWith("VOICEVOX:"),"voice credit supplied");
            check(voice.optString("termsUrl").startsWith("https://"),"voice terms supplied");
            JSONArray styles=voice.optJSONArray("styles"); Set<Integer> own=new HashSet<>();
            for(int j=0;j<styles.length();j++) {
                int style=styles.optJSONObject(j).optInt("id");
                check(usedStyles.add(style),"no style belongs to two catalog voices"); own.add(style);
            }
            for(String emotion:new String[]{"neutral","joy","anger","sadness","whisper","fear","surprise","calm","tension","unexpected"})
                check(own.contains(OfflineVoices.styleId(ids[i],Json.obj("emotion",emotion,"intensity",.8))),"emotion stays inside pinned character");
        }
        catalog.optJSONObject(0).put("id","mutated"); eq(OfflineVoices.catalog().optJSONObject(0).optString("id"),"metan");
        check(!OfflineVoices.valid("Kore")&&!OfflineVoices.valid(null),"only supported offline IDs accepted");
        eq(OfflineVoices.resolve(Json.obj("offlineVoice","kotaro"),Json.obj("offlineDefaultVoice","miko")),"kotaro");
        eq(OfflineVoices.resolve(null,Json.obj("offlineDefaultVoice","miko")),"miko");

        JSONObject old=Json.obj("id","c_old","name","手動の名前","aliases",new JSONArray().put("以前の呼び名"),
                "gender","male","tier","main","geminiVoice","Puck","manual",true,"style","穏やかにゆっくり",
                "rate",.8,"pitch",1.2,"deviceVoice","device-fixture","voice","cedar");
        String oldCopy=old.toString(); JSONArray migrated=CastRegistry.migrate(new JSONArray().put(old),"metan");
        JSONObject assigned=person(migrated,"c_old");
        check(OfflineVoices.valid(assigned.optString("offlineVoice")),"migration chooses supported voice");
        check(!assigned.has("geminiVoice"),"obsolete cloud field removed");
        for(String field:new String[]{"id","name","aliases","gender","tier","manual","style","rate","pitch","deviceVoice","voice"})
            eq(String.valueOf(assigned.opt(field)),String.valueOf(old.opt(field)));
        eq(old.toString(),oldCopy);
        eq(CastRegistry.migrate(migrated,"sora").toString(),migrated.toString());
        eq(CastRegistry.migrate(new JSONArray().put(old),"sora").optJSONObject(0).optString("offlineVoice"),assigned.optString("offlineVoice"));
        JSONObject replacement=Json.obj("id","c_old","name","解析の名前","gender","female","offlineVoice","miko");
        eq(person(CastRegistry.mergeAnalysis(migrated,new JSONArray().put(replacement),"hau"),"c_old").toString(),assigned.toString());

        JSONObject neutral=Json.obj("emotion","neutral","intensity",.8,"rate",1,"pitch",1);
        JSONObject base=OfflineVoices.parameters(neutral,new JSONObject());
        eq(base.optDouble("speedScale"),1.0); eq(base.optDouble("pitchScale"),0.0);
        JSONObject joyful=OfflineVoices.parameters(Json.copy(neutral).put("emotion","joy"),new JSONObject());
        JSONObject sad=OfflineVoices.parameters(Json.copy(neutral).put("emotion","sadness"),new JSONObject());
        check(joyful.optDouble("intonationScale")>base.optDouble("intonationScale"),"joy raises intonation");
        check(sad.optDouble("speedScale")<base.optDouble("speedScale"),"sadness slows delivery");
        check(sad.optDouble("postPhonemeLength")>base.optDouble("postPhonemeLength"),"sadness extends final pause");
        JSONObject both=OfflineVoices.parameters(Json.obj("rate",.8,"pitch",1.2),Json.obj("rate",.9,"pitch",1.1));
        check(Math.abs(both.optDouble("speedScale")-.72)<.00001,"speaker and unit speed compose");
        check(both.optDouble("pitchScale")>0,"pitch ratio maps into bounded VOICEVOX pitchScale");
        JSONObject extreme=OfflineVoices.parameters(Json.obj("rate",1000,"pitch",-5,"pauseAfterMs",9000),Json.obj("rate",1000,"pitch",1000));
        check(extreme.optDouble("speedScale")<=1.8&&Math.abs(extreme.optDouble("pitchScale"))<=.15,"extreme inputs bounded");
        check(extreme.optDouble("postPhonemeLength")<=1.66,"pause bounded");
        JSONObject ordered=new JSONObject();
        for(String key:new String[]{"pauseLengthScale","postPhonemeLength","prePhonemeLength","volumeScale","intonationScale","pitchScale","speedScale"}) ordered.put(key,base.opt(key));
        eq(OfflineVoices.signature(2,base),OfflineVoices.signature(2,ordered));
        check(!OfflineVoices.signature(2,base).equals(OfflineVoices.signature(36,base)),"style invalidates cache");
        check(OfflineVoices.parameters(neutral,Json.obj("style","ゆっくり穏やかに")).optDouble("speedScale")<1,"documented local delivery hint supported");
        eq(OfflineVoices.styleId("metan",Json.obj("emotion","anger","intensity",.1)),2);
        eq(OfflineVoices.styleId("metan",Json.obj("emotion","whisper","intensity",.1)),36);
        eq(OfflineVoices.styleId("miko",Json.obj("emotion","anger","intensity",1)),43);
        eq(OfflineVoices.styleId("metan",Json.obj("emotion","joy","intensity",1)),2);
        eq(OfflineVoices.styleId("metan",Json.obj("emotion","anger","intensity",1)),2);
        eq(OfflineVoices.styleId("zundamon",Json.obj("emotion","fear","intensity",1)),3);
        eq(OfflineVoices.styleId("sora",Json.obj("emotion","joy","intensity",1)),16);
        eq(OfflineVoices.styleId("ryusei",Json.obj("emotion","tension","intensity",1)),13);
        eq(OfflineVoices.styleId("ryusei",Json.obj("emotion","determination","intensity",1)),81);
        eq(OfflineVoices.styleId("takehiro",Json.obj("emotion","joy","intensity",1)),39);
        eq(OfflineVoices.styleId("kotaro",Json.obj("emotion","fear","intensity",1)),33);
        eq(OfflineVoices.styleId("metan",Json.obj("emotion","anger","delivery","whisper","intensity",.1)),36);
        JSONObject directed=Json.obj("emotion","sadness","intensity",1,"delivery","natural",
                "rate",1.2,"pitch",1.1,"intonation",1.3,"volume",.9,"emphasis",0,"pauseAfterMs",800);
        JSONObject directedQuery=OfflineVoices.performanceParameters(directed,Json.obj("rate",.8,"pitch",.9));
        check(Math.abs(directedQuery.optDouble("speedScale")-.96)<.000001,"detailed part composes character rate exactly once");
        check(Math.abs(directedQuery.optDouble("pitchScale")-Math.log(.99)/Math.log(2)*.12)<.000001,"detailed pitch composes character baseline once");
        eq(directedQuery.optDouble("intonationScale"),1.3);
        eq(directedQuery.optDouble("volumeScale"),.9);
        eq(directedQuery.optDouble("prePhonemeLength"),0.0);
        eq(directedQuery.optDouble("postPhonemeLength"),0.0);
        check(OfflineVoices.performanceParameters(Json.copy(directed).put("emotion","joy"),Json.obj("rate",.8,"pitch",.9))
                .toString().equals(directedQuery.toString()),"detailed numbers are not multiplied again by coarse emotion heuristics");
        JSONObject emphasis=OfflineVoices.performanceParameters(Json.copy(directed).put("emphasis",1),new JSONObject());
        check(emphasis.optDouble("intonationScale")>directedQuery.optDouble("intonationScale")
                && emphasis.optDouble("intonationScale")<=1.6,"emphasis changes whole phrase within documented bounds");
        System.out.println("OfflineVoicesTest: PASS ("+checks+" catalog, migration and local query checks; native audio not exercised)");
    }
}
