package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Offline regression fixtures for retained WebSocket framing, offline cache keys, and WAVs. No API call or audio playback. */
public final class AudioProtocolTest {
    private static int assertions;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static Exception rejects(Checked action,String message)throws Exception{
        try{action.run();}catch(Exception expected){assertions++;return expected;}
        throw new AssertionError("Accepted "+message);
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static byte[] frame(int opcode,boolean fin,byte[] data)throws Exception{
        ByteArrayOutputStream result=new ByteArrayOutputStream();result.write((fin?128:0)|opcode);
        if(data.length<126)result.write(data.length);
        else if(data.length<=65535){result.write(126);result.write(data.length>>>8);result.write(data.length);}
        else{result.write(127);for(int shift=56;shift>=0;shift-=8)result.write((int)((long)data.length>>>shift));}
        result.write(data);return result.toByteArray();
    }
    private static byte[] join(byte[]...values)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] v:values)out.write(v);return out.toByteArray();}
    private static RealtimeSocket memory(byte[] wire){return new RealtimeSocket(new ByteArrayInputStream(wire),new ByteArrayOutputStream());}
    private static byte[] event(JSONObject data)throws Exception{return frame(1,true,bytes(data.toString()));}

    private static void handshakes()throws Exception{
        String key="dGhlIHNhbXBsZSBub25jZQ==";
        String valid="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
        RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes(valid)),key);check(true,"RFC6455 handshake vector");
        rejects(()->RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes(valid.replace("s3pPLMBi", "badPLMBi"))),key),"wrong Sec-WebSocket-Accept");
        rejects(()->RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes(valid.replace("\r\n\r\n","\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"))),key),"unrequested compression");
        rejects(()->RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes(valid.replace("\r\n\r\n","\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"))),key),"duplicate accept header");
        Exception denied=rejects(()->RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes("HTTP/1.1 401 Unauthorized\r\n\r\nprivate response body")),key),"401 response");
        check(denied instanceof RealtimeSocket.HttpFailure&&((RealtimeSocket.HttpFailure)denied).status==401,"status retained without body");
        check(!denied.getMessage().contains("private"),"server body not exposed");
        String longHeader="HTTP/1.1 101 x\r\nLarge: "+repeat('a',33000)+"\r\n\r\n";
        rejects(()->RealtimeSocket.readHandshake(new ByteArrayInputStream(bytes(longHeader)),key),"oversized HTTP header");
        rejects(()->new RealtimeSocket().connect("bad\r\nInjected: yes","gpt-realtime-2.1-mini",1000),"authorization header injection");
        rejects(()->new RealtimeSocket().connect("fake-test-key","x?evil=true",1000),"model query injection");
    }

    private static void frames()throws Exception{
        byte[] japanese=bytes("声の確認です。"), left=Arrays.copyOfRange(japanese,0,4),right=Arrays.copyOfRange(japanese,4,japanese.length);
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        RealtimeSocket fragmented=new RealtimeSocket(new ByteArrayInputStream(join(frame(1,false,left),frame(9,true,bytes("ping")),frame(0,true,right))),sent);
        check(fragmented.readText().equals("声の確認です。"),"UTF8 split across fragmented frames");
        byte[] pong=sent.toByteArray();check((pong[0]&255)==138&&(pong[1]&128)!=0,"masked pong sent");
        check(unmask(pong).equals("ping"),"ping payload echoed in pong");
        for(int length:new int[]{0,1,125,126,300,65535,65536,70000}){
            String text=repeat('a',length);
            check(memory(frame(1,true,bytes(text))).readText().equals(text),"server length boundary "+length);
            ByteArrayOutputStream output=new ByteArrayOutputStream();new RealtimeSocket(new ByteArrayInputStream(new byte[0]),output).sendText(text);
            check(unmask(output.toByteArray()).equals(text),"masked client boundary "+length);
        }
        rejects(()->memory(new byte[]{(byte)0x81,(byte)0x80}).readText(),"masked server frame");
        rejects(()->memory(new byte[]{(byte)0xc1,0}).readText(),"unnegotiated RSV bit");
        rejects(()->memory(frame(0,true,bytes("x"))).readText(),"unexpected continuation");
        rejects(()->memory(join(frame(1,false,bytes("x")),frame(1,true,bytes("y")))).readText(),"nested fragments");
        rejects(()->memory(frame(9,false,bytes("x"))).readText(),"fragmented control message");
        rejects(()->memory(frame(9,true,new byte[126])).readText(),"oversized ping");
        rejects(()->memory(frame(2,true,bytes("binary"))).readText(),"unexpected binary message");
        rejects(()->memory(frame(1,true,new byte[]{(byte)0xc3,0x28})).readText(),"invalid UTF8");
        rejects(()->memory(new byte[]{(byte)0x81,126,0,1,65}).readText(),"non-minimal payload length");
        rejects(()->memory(new byte[]{(byte)0x81,127,(byte)0x80,0,0,0,0,0,0,0}).readText(),"negative 64-bit payload length");
        rejects(()->memory(new byte[]{(byte)0x81,127,0,0,0,0,0x01,0,0,0}).readText(),"payload size cap before allocation");
        rejects(()->memory(frame(8,true,new byte[]{3,(byte)0xed})).readText(),"reserved close status 1005");
        rejects(()->memory(frame(8,true,new byte[]{1})).readText(),"one-byte close");
        Exception closed=rejects(()->memory(frame(8,true,new byte[]{3,(byte)0xe8})).readText(),"valid close ends stream");
        check(closed instanceof EOFException,"valid close treated as stream end");
        RealtimeSocket deadline=memory(frame(1,true,bytes("late")));Field time=RealtimeSocket.class.getDeclaredField("deadlineNanos");time.setAccessible(true);time.setLong(deadline,System.nanoTime()-1);
        check(rejects(deadline::readText,"expired deadline") instanceof java.net.SocketTimeoutException,"monotonic total deadline");
    }

    private static String unmask(byte[] wire)throws Exception{
        ByteArrayInputStream in=new ByteArrayInputStream(wire);check((in.read()&128)!=0,"client frame is final");
        int b=in.read();check((b&128)!=0,"client frame is masked");long length=b&127;
        if(length==126)length=(in.read()<<8)|in.read();else if(length==127){length=0;for(int i=0;i<8;i++)length=(length<<8)|in.read();}
        byte[] mask=new byte[4];check(in.read(mask)==4,"mask present");byte[] decoded=new byte[(int)length];
        for(int i=0;i<decoded.length;i++){int value=in.read();if(value<0)throw new AssertionError("truncated masked payload");decoded[i]=(byte)(value^mask[i%4]);}
        return new String(decoded,StandardCharsets.UTF_8);
    }

    private static void cancellation()throws Exception{
        try(ServerSocket local=new ServerSocket(0);Socket client=new Socket("127.0.0.1",local.getLocalPort());Socket server=local.accept()){
            RealtimeSocket connection=new RealtimeSocket(client.getInputStream(),client.getOutputStream());
            Field transport=RealtimeSocket.class.getDeclaredField("transport");transport.setAccessible(true);transport.set(connection,client);
            CountDownLatch began=new CountDownLatch(1),ended=new CountDownLatch(1);AtomicReference<Throwable> result=new AtomicReference<>();
            Thread reader=new Thread(()->{began.countDown();try{connection.readText();}catch(Throwable e){result.set(e);}finally{ended.countDown();}});
            reader.setDaemon(true);reader.start();check(began.await(1,TimeUnit.SECONDS),"reader started");connection.close();
            check(ended.await(2,TimeUnit.SECONDS),"cancel interrupts a blocked read");check(result.get() instanceof IOException,"cancel is an IO termination");
        }
    }

    private static void apiContract()throws Exception{
        String original="「こんばんは」\nこの文章は自作の試験用テキストです。";
        JSONObject unit=Json.obj("speakerId","alice","kind","dialogue","emotion","anger","intensity",.6,"rate",1.1,"pitch",.9);
        JSONObject cast=Json.obj("id","alice","gender","female","tier","main","offlineVoice","metan","style","calm and clear","rate",1.05,"pitch",1);
        String style=SpeechAudio.instructions(unit,cast);
        check(style.equals(OfflineVoices.signature(2, OfflineVoices.parameters(unit,cast))),"unsupported anger uses pinned standard voice and bounded local query");
        check(SpeechAudio.voice(unit,Json.obj("offlineVoice","not-real"),new JSONObject()).equals("metan"),"unsupported voice cannot be sent");
        check(SpeechAudio.voice(unit,Json.obj("id","narrator","offlineVoice","metan","manual",false),Json.obj("offlineDefaultVoice","zundamon")).equals("metan"),"narrator's persisted voice always wins");
        check(SpeechAudio.voice(unit,Json.obj("id","narrator"),Json.obj("offlineDefaultVoice","zundamon")).equals("zundamon"),"default only for unassigned narrator");
        check(SpeechAudio.voice(Json.obj("emotion","joy"),cast,new JSONObject()).equals(SpeechAudio.voice(Json.obj("emotion","sadness"),cast,new JSONObject())),"emotion does not change cast voice");
        String identity=SpeechAudio.castIdentity(unit,cast,style);
        String base=SpeechAudio.cacheKey("book",original,SpeechAudio.DEFAULT_MODEL,"metan",identity,1);
        check(base.equals(SpeechAudio.cacheKey("book",original,SpeechAudio.DEFAULT_MODEL,"metan",identity,1)),"stable cache key");
        check(!base.equals(SpeechAudio.cacheKey("book2",original,SpeechAudio.DEFAULT_MODEL,"metan",identity,1)),"book separation");
        check(!base.equals(SpeechAudio.cacheKey("book",original+"。",SpeechAudio.DEFAULT_MODEL,"metan",identity,1)),"text invalidates cache");
        check(!base.equals(SpeechAudio.cacheKey("book",original,"voicevox-future-version","metan",identity,1)),"model invalidates cache");
        check(!base.equals(SpeechAudio.cacheKey("book",original,SpeechAudio.DEFAULT_MODEL,"zundamon",identity,1)),"voice invalidates cache");
        check(!base.equals(SpeechAudio.cacheKey("book",original,SpeechAudio.DEFAULT_MODEL,"metan",identity+"calm",1)),"delivery invalidates cache");
        check(!base.equals(SpeechAudio.cacheKey("book",original,SpeechAudio.DEFAULT_MODEL,"metan",identity,.8)),"synthesis speed invalidates cache");
        check(!identity.equals(SpeechAudio.castIdentity(unit,Json.copy(cast).put("id","bob"),style)),"speaker identity invalidates cache");
        check(!identity.equals(SpeechAudio.castIdentity(unit,Json.copy(cast).put("revision",2),style)),"cast revision invalidates cache");
        check(!identity.equals(SpeechAudio.castIdentity(Json.copy(unit).put("kind","thought"),cast,style)),"kind annotation invalidates cache");
        String legacy=new JSONArray().put("tsuzuki-realtime-pcm-v1").put("book").put(SpeechAudio.DEFAULT_MODEL).put("metan")
                .put(identity).put(1.0).put(SpeechAudio.SAMPLE_RATE).put(original).toString();
        byte[] oldHash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes(legacy));StringBuilder oldKey=new StringBuilder();
        for(byte value:oldHash)oldKey.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        check(!base.equals(oldKey.toString()),"old OpenAI cache namespace never reused");
        for(String field:new String[]{"style","rate","pitch"}){
            JSONObject changed=Json.copy(cast);changed.put(field,field.equals("rate")||field.equals("pitch")?1.3:field.equals("gender")?"male":field.equals("tier")?"extra":"早口で元気に");
            check(!style.equals(SpeechAudio.instructions(unit,changed)),"speaker "+field+" affects synthesis cache input");
        }
        for(String field:new String[]{"emotion","intensity","rate","pitch"}){
            JSONObject changed=Json.copy(unit);changed.put(field,field.equals("emotion")?"joy":.8);
            check(!style.equals(SpeechAudio.instructions(changed,cast)),"unit "+field+" affects synthesis cache input");
        }
        JSONObject detailed=Json.copy(unit).put("spokenText","えっ、本当なの？ありがとう。");
        JSONArray directions=new JSONArray(), boundaries=SpeechPerformance.parts(detailed);
        for(int i=0;i<boundaries.length();i++) directions.put(Json.obj("partId",boundaries.optJSONObject(i).optString("partId"),
                "emotion","neutral","delivery","natural","intensity",.4,"rate",1,"pitch",1,"intonation",1,"volume",1,
                "pauseAfterMs",100,"ending","auto","emphasis",.1,"confidence",.8));
        detailed.put("performance",SpeechPerformance.validate(detailed,directions,true));
        String detailedSignature=SpeechAudio.instructions(detailed,cast);
        check(!detailedSignature.equals(style),"reviewed detailed directions cannot reuse a coarse cache entry");
        for(String field:new String[]{"emotion","delivery","intensity","rate","pitch","intonation","volume","pauseAfterMs","ending","emphasis","confidence"}){
            JSONObject changed=Json.copy(detailed), p=changed.optJSONObject("performance").optJSONArray("parts").optJSONObject(1);
            Object next="emotion".equals(field)?"joy":"delivery".equals(field)?"soft":"ending".equals(field)?"rise":
                    "pauseAfterMs".equals(field)?Integer.valueOf(200):Double.valueOf(.9);
            p.put(field,next);
            check(SpeechPerformance.valid(changed),"cache variation is a valid reviewed part "+field);
            check(!detailedSignature.equals(SpeechAudio.instructions(changed,cast)),"detailed "+field+" invalidates synthesis cache");
        }
        JSONObject stale=Json.copy(detailed).put("spokenText","別の本文。");
        check(SpeechAudio.instructions(stale,cast).equals(style),"stale source hash cannot affect fallback audio cache");
        JSONObject unreviewed=Json.copy(detailed);unreviewed.optJSONObject("performance").put("reviewed",false);
        check(SpeechAudio.instructions(unreviewed,cast).equals(detailedSignature),"a complete one-pass plan retains the same audio identity without claiming a second model review");
        check(SpeechAudio.instructions(Json.copy(detailed).put("rate",.7).put("pitch",1.2),cast).equals(detailedSignature),
                "coarse unit rate and pitch cannot be reapplied to reviewed parts");
        check(!SpeechAudio.instructions(detailed,Json.copy(cast).put("rate",.85)).equals(detailedSignature),"character baseline invalidates detailed cache");
        check(SpeechAudio.isBlank(" \r\n\t\u3000\u00a0"),"Unicode blank units can be skipped");
        check(!SpeechAudio.isBlank("　声　"),"nonblank content retained");
        check(SpeechAudio.hasJapanese("声音。")&&SpeechAudio.hasJapanese("かな")&&SpeechAudio.hasJapanese("ｶﾅ"),"CJK and kana select Japanese");
        check(!SpeechAudio.hasJapanese("This is English."),"English selects US locale");
        JSONObject originalReading=Json.obj("spokenText","今日は生物。母は花。","text","今日は生物。母は花。",
                "speakerId","alice","kind","dialogue","emotion","neutral","rate",1,"pitch",1);
        String untouched=originalReading.toString(),originalKey=SpeechAudio.requestKey("reading",originalReading,cast,new JSONObject());
        JSONObject pronounced=withReadings(originalReading,"きょーわせーぶつ。","ははわはな。");
        check(SpeechPronunciation.valid(pronounced),"audio fixture has source-validated reading ranges");
        check(SpeechAudio.playbackText(pronounced).equals("キョーワセーブツ。ハハワハナ。"),"device TTS receives literal kana without changing ha inside words to wa");
        check(SpeechAudio.playbackText(originalReading).equals(originalReading.optString("spokenText")),"unannotated device TTS keeps original text");
        check(!SpeechAudio.requestKey("reading",pronounced,cast,new JSONObject()).equals(originalKey),"a newly assigned reading cannot reuse original audio");
        JSONObject changedReading=withReadings(originalReading,"きょーわなまもの。","ははわはな。");
        check(!SpeechAudio.requestKey("reading",pronounced,cast,new JSONObject()).equals(SpeechAudio.requestKey("reading",changedReading,cast,new JSONObject())),"homograph reading change invalidates complete and ahead audio identity");
        check(SpeechAudio.requestKey("reading",pronounced,cast,new JSONObject()).equals(SpeechAudio.requestKey("reading",pronounced,cast,Json.obj("speed",1.8))),"playback speed does not regenerate explicit readings");
        JSONObject invalidReading=Json.copy(pronounced);invalidReading.optJSONObject("pronunciation").put("sourceHash","outdated-reading");
        check(SpeechAudio.requestKey("reading",invalidReading,cast,new JSONObject()).equals(originalKey),"stale reading uses exact legacy cache identity");
        check(SpeechAudio.playbackText(invalidReading).equals(originalReading.optString("spokenText")),"stale reading does not replace device TTS text");
        check(originalReading.toString().equals(untouched),"audio preparation never mutates original text or annotations");
        JSONObject pronouncedDetailed=withReadings(detailed,"えっ、","ほんとーなの？","ありがとー。");
        check(SpeechPerformance.valid(pronouncedDetailed),"pronunciation remains independent of acting source offsets");
        check(pronouncedDetailed.optJSONObject("performance").toString().equals(detailed.optJSONObject("performance").toString()),"reading adoption preserves dense acting byte-for-byte");
        check(!SpeechAudio.instructions(pronouncedDetailed,cast).equals(detailedSignature),"detailed audio identity includes validated pronunciation");
        check(SpeechReadingInput.kana("はわぁゔカナー、ABC 123！").equals("ハワァヴカナー、ABC 123！"),"audio conversion changes only hiragana script and never infers long vowels or particles");
        JSONObject partialSource=Json.obj("spokenText","こんにちは。今日は生物。母は花。");
        JSONObject partialReading=withReadings(partialSource,"こんにちは。","今日わなまもの。","ははわはな。");
        check(SpeechAudio.playbackText(partialReading).equals("こんにちは。今日わなまもの。ハハワハナ。"),"unchanged greetings and kanji-containing manual parts retain ordinary parsing beside explicit readings");
        check(SpeechReadingInput.text(partialReading,0,partialSource.optString("spokenText").length()).equals(SpeechAudio.playbackText(partialReading)),"merged acting input applies explicit kana decisions per source part");
        check(SpeechReadingInput.text(partialReading,1,4).equals(partialSource.optString("spokenText").substring(1,4)),"unmatched inner offsets preserve original substring without guessing kana alignment");
    }

    private static JSONObject withReadings(JSONObject source,String...values)throws Exception{
        JSONObject unit=Json.copy(source);JSONArray boundaries=SpeechPerformance.parts(unit),parts=new JSONArray();
        if(boundaries.length()!=values.length)throw new AssertionError("reading fixture boundary count");
        for(int i=0;i<boundaries.length();i++){
            JSONObject part=boundaries.optJSONObject(i);parts.put(Json.obj("partId",part.optString("partId"),
                    "start",part.optInt("start"),"end",part.optInt("end"),"reading",values[i]));
        }
        unit.put("pronunciation",SpeechPronunciation.validateManual(unit,parts));return unit;
    }

    private static void pcmPipeline(File folder)throws Exception{
        File wave=new File(folder,"pipeline.wav");byte[] pcm={0,0,1,0,-1,127,0,(byte)128,0,0};
        Files.write(wave.toPath(),join(SpeechAudio.waveHeader(pcm.length),pcm));
        check(SpeechAudio.validWave(wave),"completed audio is a valid WAV");
        byte[] actual=Files.readAllBytes(wave.toPath());
        check(new String(actual,0,4,StandardCharsets.US_ASCII).equals("RIFF"),"RIFF header");
        check((actual[20]&255)==1&&(actual[22]&255)==1&&(actual[34]&255)==16,"mono PCM16 WAV");
        check(Arrays.equals(Arrays.copyOfRange(actual,44,actual.length),pcm),"PCM bytes preserved exactly");
        rejects(()->SpeechAudio.waveHeader(3),"odd PCM byte count");
        rejects(()->SpeechAudio.waveHeader(SpeechAudio.MAX_PCM_BYTES+2),"bounded unit audio");
        try(RandomAccessFile out=new RandomAccessFile(wave,"rw")){out.setLength(out.length()-1);}
        check(!SpeechAudio.validWave(wave),"truncated cache rejected");
    }

    private static void cachePruning(File folder)throws Exception{
        File cache=new File(folder,"cache");check(cache.mkdirs(),"cache folder");
        File oldest=new File(cache,repeat('a',64)+"-"+repeat('b',64)+".wav");
        File newest=new File(cache,repeat('a',64)+"-"+repeat('c',64)+".wav");
        File stale=new File(cache,"old.part"),foreign=new File(cache,"keep.txt");
        try(RandomAccessFile out=new RandomAccessFile(oldest,"rw")){out.setLength(SpeechAudio.CACHE_LIMIT_BYTES);}
        try(RandomAccessFile out=new RandomAccessFile(newest,"rw")){out.setLength(512);}
        Files.write(stale.toPath(),new byte[]{1});Files.write(foreign.toPath(),new byte[]{2});
        oldest.setLastModified(System.currentTimeMillis()-10000);stale.setLastModified(System.currentTimeMillis()-7200000);
        SpeechAudio.pruneCache(cache,newest);
        check(!oldest.exists()&&newest.exists(),"oldest cache evicted; current unit preserved");
        check(!stale.exists()&&foreign.exists(),"stale partial removed; unrelated file retained");
    }
    private static String repeat(char c,int length){char[] chars=new char[length];Arrays.fill(chars,c);return new String(chars);}
    private static void remove(File file){File[] contents=file.listFiles();if(contents!=null)for(File child:contents)remove(child);file.delete();}
    public static void main(String[] args)throws Exception{
        File folder=Files.createTempDirectory("tsuzuki-audio-fixtures-").toFile();
        try{handshakes();frames();cancellation();apiContract();pcmPipeline(folder);cachePruning(folder);
            System.out.println("AudioProtocolTest: PASS ("+assertions+" assertions, offline protocol/WAV/cache fixtures only)");}
        finally{remove(folder);}
    }
}
