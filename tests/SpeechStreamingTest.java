package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real generation/cache/stream playback logic; deterministic native, Android-handler and audio-device boundaries. */
public final class SpeechStreamingTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void until(BooleanSupplier condition,String label)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){Handler.drain();Thread.sleep(5);}
        Handler.drain();check(condition.getAsBoolean(),label);
    }
    private static JSONObject unit(){return Json.obj("text","最初の句。続きの句。","spokenText","最初の句。続きの句。","speakerId","narrator","emotion","neutral","rate",1,"pitch",1);}
    private static JSONObject speaker(){return Json.obj("id","narrator","offlineVoice","metan","rate",1,"pitch",1);}
    private static final class Callback implements SpeechAudio.StreamCallback {
        volatile SpeechPcmSource stream;volatile SpeechAudio.Result result;volatile String failure;
        @Override public void streaming(SpeechPcmSource value){stream=value;}
        @Override public void ready(SpeechAudio.Result value){result=value;}
        @Override public void failed(String value){failure=value;}
    }
    private static void earlyCacheAndCancel(File root)throws Exception{
        File dir=new File(root,"cache");dir.mkdirs();SpeechAudio audio=new SpeechAudio(new Context(root,""),dir);
        try{
            VoicevoxEngine.reset(true);Callback cb=new Callback();SpeechAudio.Job job=audio.generate("book",unit(),speaker(),new JSONObject(),cb);
            until(()->cb.stream!=null,"first audio is delivered before native finishes");
            check(cb.result==null,"whole-unit completion is still held");check(job.firstAudioMs()>=0,"measured first PCM time exists");
            byte[] first=new byte[8192];int read=cb.stream.read(0,first,first.length);
            check(read>1920,"first native audio plus initial silence is readable");check(!cb.stream.isComplete(),"growing source is not falsely complete");
            check(dir.listFiles((d,n)->n.endsWith(".wav")).length==0,"partial audio is not a valid cache entry");
            VoicevoxEngine.release.countDown();until(()->cb.result!=null,"full generation commits WAV");
            check(cb.result.firstAudioMs<=cb.result.generationElapsedMs,"first PCM precedes final cache commit");
            check(cb.stream.isComplete(),"EOF follows cache commit");check(SpeechAudio.validWave(cb.result.file),"committed cache passes production validation");
            byte[] stored=VoicePcm.samples(Files.readAllBytes(cb.result.file.toPath()));byte[] live=new byte[stored.length];
            int offset=0;while(offset<live.length){byte[] block=new byte[Math.min(8192,live.length-offset)];int n=cb.stream.read(offset,block,block.length);check(n>0,"complete source remains readable after rename");System.arraycopy(block,0,live,offset,n);offset+=n;}
            check(Arrays.equals(stored,live),"stream and completed WAV preserve every PCM sample");cb.stream.close();
            int calls=VoicevoxEngine.calls.get();Callback cached=new Callback();audio.generate("book",unit(),speaker(),Json.obj("speed",1.8),cached);
            until(()->cached.result!=null,"cache fast path succeeds");check(cached.result.cached&&cached.stream==null,"cached WAV does not start an unnecessary stream");
            check(VoicevoxEngine.calls.get()==calls,"speed-only change performs no synthesis");
            check(cached.result.generationElapsedMs==-1&&cached.result.firstAudioMs==-1,"cache path does not invent generation timings");

            VoicevoxEngine.reset(true);Callback cancelled=new Callback();SpeechAudio.Job pending=audio.generate("cancel",unit(),speaker(),new JSONObject(),cancelled);
            until(()->cancelled.stream!=null,"cancellation reaches an in-progress stream");pending.cancel();VoicevoxEngine.release.countDown();
            CountDownLatch barrier=new CountDownLatch(1);audio.afterPendingOnWorker(barrier::countDown);check(barrier.await(5,TimeUnit.SECONDS),"cancelled native work reaches barrier");Handler.drain();
            check(cancelled.result==null&&cancelled.failure==null,"cancelled job publishes no stale completion or failure");
            String prefix=SpeechStore.key("cancel");check(dir.listFiles((d,n)->n.startsWith(prefix)).length==0,"cancelled partial file is removed");
            boolean closed=false;try{cancelled.stream.read(0,new byte[2],2);}catch(Exception expected){closed=true;}check(closed,"cancelled source closes its reader");

            VoicevoxEngine.reset(true);Callback speculative=new Callback();audio.generate("future",unit(),speaker(),new JSONObject(),speculative);
            until(()->speculative.stream!=null,"speculative native inference is blocked");
            SpeechAudio.Result hit=audio.cached(unit(),speaker(),new JSONObject(),"book");check(hit!=null&&hit.cached,"cache lookup bypasses occupied synthesis worker");
            VoicevoxEngine.release.countDown();until(()->speculative.result!=null,"speculative generation completes");speculative.stream.close();
        }finally{VoicevoxEngine.release.countDown();audio.close();}
    }

    private static JSONObject readingUnit(String reading)throws Exception{
        JSONObject unit=Json.obj("text","今日の生物は冷蔵庫に入れる。","spokenText","今日の生物は冷蔵庫に入れる。",
                "speakerId","narrator","emotion","neutral","rate",1,"pitch",1);
        if(reading!=null){JSONObject part=SpeechPerformance.parts(unit).optJSONObject(0);
            unit.put("pronunciation",SpeechPronunciation.validateManual(unit,new JSONArray().put(Json.obj(
                    "partId",part.optString("partId"),"start",part.optInt("start"),"end",part.optInt("end"),"reading",reading))));}
        return unit;
    }
    private static void pronunciationCache(File root)throws Exception{
        File dir=new File(root,"reading-cache");dir.mkdirs();SpeechAudio audio=new SpeechAudio(new Context(root,""),dir);
        try{
            VoicevoxEngine.reset(false);JSONObject original=readingUnit(null);Callback first=new Callback();
            audio.generate("reading",original,speaker(),new JSONObject(),first);until(()->first.result!=null,"original reading is cached");first.stream.close();
            JSONObject corrected=readingUnit("きょーのなまものわれーぞーこにいれる。");String originalBody=corrected.optString("spokenText");
            check(audio.cached(corrected,speaker(),new JSONObject(),"reading")==null,"reading edit cannot hit an older completed WAV");
            Callback next=new Callback();audio.generate("reading",corrected,speaker(),new JSONObject(),next);
            until(()->next.result!=null,"corrected reading produces a separate completed WAV");next.stream.close();
            check(!next.result.cached&&VoicevoxEngine.calls.get()==2,"reading correction invokes generation exactly once");
            check(!first.result.cacheKey.equals(next.result.cacheKey),"corrected and original audio cache entries remain distinct");
            JSONObject homograph=Json.copy(corrected);
            Json.put(homograph,"text","同じ音声用の読みを持つ別表記");
            check(!SpeechAudio.requestKey("reading",homograph,speaker(),new JSONObject()).equals(next.result.cacheKey),"written lexical accent input participates in the audio cache key");
            SpeechAudio.Result speedOnly=audio.cached(corrected,speaker(),Json.obj("speed",1.7),"reading");
            check(speedOnly!=null&&speedOnly.file.equals(next.result.file),"playback speed change reuses corrected audio");
            JSONObject changed=readingUnit("きょーのせーぶつわれーぞーこにいれる。");
            check(audio.cached(changed,speaker(),new JSONObject(),"reading")==null,"a second homograph correction misses the prior reading cache");
            JSONObject invalid=Json.copy(corrected);invalid.optJSONObject("pronunciation").put("sourceHash","stale");
            SpeechAudio.Result fallback=audio.cached(invalid,speaker(),new JSONObject(),"reading");
            check(fallback!=null&&fallback.file.equals(first.result.file),"invalid reading falls back to the unchanged legacy cache");
            check(SpeechAudio.playbackText(corrected).equals("キョーノナマモノワレーゾーコニイレル。"),"the device path shares explicit kana pronunciation");
            check(corrected.optString("spokenText").equals(originalBody),"reading playback leaves original spoken text intact");
        }finally{audio.close();}
    }

    private static final class FakeOutput implements PcmPlayback.Output {
        volatile boolean playing,closed;volatile float speed;volatile long written,played;final AtomicBoolean consume;
        FakeOutput(AtomicBoolean consume){this.consume=consume;}
        public int write(byte[] pcm,int at,int len){if(closed)throw new IllegalStateException();int n=Math.min(len,2048);written+=n;return n;}
        public long playedFrames(){if(playing&&consume.get())played=written/2;return played;}
        public void play(){playing=true;}public void pause(){playing=false;}public void speed(float v){speed=v;}public void close(){closed=true;playing=false;}
    }
    private static void playbackControls(File root)throws Exception{
        File file=new File(root,"growing.wav");AtomicBoolean consume=new AtomicBoolean(false);AtomicReference<FakeOutput> device=new AtomicReference<>();AtomicInteger opens=new AtomicInteger();
        AtomicInteger playing=new AtomicInteger(),buffering=new AtomicInteger(),completed=new AtomicInteger(),failed=new AtomicInteger();
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")){
            out.write(VoicePcm.header(0));SpeechPcmSource source=new SpeechPcmSource(file);
            PcmPlayback playback=new PcmPlayback(source,()->{FakeOutput d=new FakeOutput(consume);device.set(d);opens.incrementAndGet();return d;},new PcmPlayback.Listener(){
                public void playing(){playing.incrementAndGet();}public void buffering(){buffering.incrementAndGet();}public void completed(){completed.incrementAndGet();}public void failed(){failed.incrementAndGet();}
            });
            playback.start(1,false);until(()->buffering.get()>0,"empty live source reports buffering without completion");
            out.seek(44);out.write(new byte[48000]);source.publish(48000);
            until(()->playing.get()>0&&device.get().written>0,"first available audio begins before stream EOF");
            check(completed.get()==0&&!source.isComplete(),"playback does not require full source generation");
            playback.pause();check(!device.get().playing,"pause stops device output immediately");
            long before=device.get().written;Thread.sleep(25);check(device.get().written==before,"paused pump does not consume more PCM");
            check(playback.seek(100)&&playback.positionMs()==100,"a paused seek is accepted and visible before resume");
            playback.speed(1.6f);playback.resume(1.6f);until(()->device.get().playing&&device.get().speed==1.6f,"resume applies speed without regenerating audio");
            int opened=opens.get();playback.seek(200);until(()->opens.get()>opened,"seek recreates output at a generated PCM offset");
            check(playback.positionMs()>=200,"seek position is retained");
            consume.set(true);out.seek(48044);out.write(new byte[24000]);source.publish(72000);source.finish();
            until(()->completed.get()==1,"completion waits for full generated PCM to drain");
            check(playback.finished()&&playback.positionMs()==1500,"completed position reflects full PCM duration");check(failed.get()==0,"play/pause/resume/seek did not report device errors");
            check(!playback.seek(200),"a completed stream rejects seek so its WAV can be reopened");
            playback.close();until(()->device.get().closed,"completed output releases device");
        }
        File stopped=new File(root,"stopped.wav");try(RandomAccessFile out=new RandomAccessFile(stopped,"rw")){
            out.write(VoicePcm.header(0));SpeechPcmSource source=new SpeechPcmSource(stopped);AtomicInteger callbacks=new AtomicInteger();
            PcmPlayback p=new PcmPlayback(source,()->new FakeOutput(new AtomicBoolean(true)),new PcmPlayback.Listener(){public void playing(){callbacks.incrementAndGet();}public void buffering(){}public void completed(){callbacks.incrementAndGet();}public void failed(){callbacks.incrementAndGet();}});
            p.start(1,true);p.close();Thread.sleep(40);check(callbacks.get()==0,"closing a paused waiting stream emits no stale completion");
        }
    }
    private static void shortOutputAndBounds(File root)throws Exception{
        File f=new File(root,"short.wav");AtomicBoolean end=new AtomicBoolean();AtomicInteger done=new AtomicInteger(),errors=new AtomicInteger();
        try(RandomAccessFile out=new RandomAccessFile(f,"rw")){
            out.write(VoicePcm.header(480));out.write(new byte[480]);SpeechPcmSource source=new SpeechPcmSource(f);source.publish(480);source.finish();
            PcmPlayback p=new PcmPlayback(source,()->new PcmPlayback.Output(){
                long written;public int write(byte[] b,int at,int n){written+=n;return n;}public long playedFrames(){return end.get()?written/2:0;}
                public void play(){}public void pause(){}public void speed(float s){}public void close(){}public void finishInput(){end.set(true);}
            },new PcmPlayback.Listener(){public void playing(){}public void buffering(){}public void completed(){done.incrementAndGet();}public void failed(){errors.incrementAndGet();}});
            p.start(1,false);until(()->done.get()==1,"a short stream below device priming threshold drains at EOF");
            check(end.get()&&p.positionMs()==10,"device-only priming never changes logical duration");
            check(f.length()==524&&errors.get()==0,"priming adds no samples to the source or cache");p.close();
        }
        File b=new File(root,"bounds.wav");try(RandomAccessFile out=new RandomAccessFile(b,"rw")){
            out.write(VoicePcm.header(0));SpeechPcmSource source=new SpeechPcmSource(b);
            boolean excessive=false,odd=false,backwards=false;
            try{source.publish(VoicePcm.MAX_PCM_BYTES+2L);}catch(Exception expected){excessive=true;}
            try{source.publish(3);}catch(Exception expected){odd=true;}
            source.publish(4);try{source.publish(2);}catch(Exception expected){backwards=true;}
            check(excessive&&odd&&backwards,"growing source rejects excessive, odd and regressing lengths");source.close();
        }
    }
    private static void actingModeCache() throws Exception {
        JSONObject u=unit(), s=speaker(), anime=Json.obj("dialogueMode","anime"), natural=Json.obj("dialogueMode","natural"), standard=Json.obj("dialogueMode","standard");
        Json.put(u,"kind","dialogue");
        String naturalKey=SpeechAudio.requestKey("book",u,s,natural), animeKey=SpeechAudio.requestKey("book",u,s,anime);
        String standardKey=SpeechAudio.requestKey("book",u,s,standard);
        check(naturalKey.equals(SpeechAudio.requestKey("book",u,s,new JSONObject())),"new playback defaults to conversational dialogue");
        check(naturalKey.equals(SpeechAudio.requestKey("book",u,s,Json.obj("animeActing",true))),"old acting selection migrates to natural");
        check(standardKey.equals(SpeechAudio.requestKey("book",u,s,Json.obj("animeActing",false))),"old opt-out retains standard");
        check(!naturalKey.equals(animeKey) && !standardKey.equals(animeKey) && !standardKey.equals(naturalKey),"all three dialogue caches are distinct");
        Json.put(u,"locked",true);
        check(!animeKey.equals(SpeechAudio.requestKey("book",u,s,anime)) && !naturalKey.equals(SpeechAudio.requestKey("book",u,s,natural)),"both acting modes honor manual locks");
        Json.put(u,"kind","narration");
        check(SpeechAudio.requestKey("book",u,s,anime).equals(SpeechAudio.requestKey("book",u,s,natural)) && SpeechAudio.requestKey("book",u,s,natural).equals(SpeechAudio.requestKey("book",u,s,standard)),"narrator cache is shared across all modes");
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("tsuzuki-stream-test-").toFile();
        try{actingModeCache();earlyCacheAndCancel(root);pronunciationCache(root);playbackControls(root);shortOutputAndBounds(root);System.out.println("SpeechStreamingTest: PASS ("+checks+" generation/pronunciation/cache/stream/control checks; synthetic native and Android audio boundaries)");}
        finally{for(File f:root.listFiles()){if(f.isDirectory())for(File child:f.listFiles())child.delete();f.delete();}root.delete();}
    }
}
