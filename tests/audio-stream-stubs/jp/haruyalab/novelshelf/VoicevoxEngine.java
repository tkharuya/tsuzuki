package jp.haruyalab.novelshelf;
import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
/** Controllable native boundary. Real JNI timing is verified separately. */
public final class VoicevoxEngine {
    interface Cancellation { boolean cancelled(); }
    static final AtomicInteger calls=new AtomicInteger();
    static volatile CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1);
    public VoicevoxEngine(Context context){}
    static void reset(boolean block){first=new CountDownLatch(1);release=new CountDownLatch(block?1:0);calls.set(0);}
    byte[] synthesizeUnit(String text,String voice,JSONObject unit,JSONObject speaker,Cancellation cancel)throws IOException{return synthesizeUnit(text,voice,unit,speaker,cancel,null);}
    byte[] synthesizeUnit(String text,String voice,JSONObject unit,JSONObject speaker,Cancellation cancel,VoicePcm.Sink sink)throws IOException{
        calls.incrementAndGet();VoicePcm pcm=new VoicePcm(sink);
        pcm.silence(40);pcm.appendWave(tone(4800,(short)1200));first.countDown();
        try{while(!release.await(20,TimeUnit.MILLISECONDS))if(cancel.cancelled())throw new InterruptedIOException("VOICEVOX_CANCELLED");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("VOICEVOX_CANCELLED");}
        if(cancel.cancelled())throw new InterruptedIOException("VOICEVOX_CANCELLED");
        pcm.silence(120);pcm.appendWave(tone(7200,(short)2300));pcm.silence(80);return pcm.wave();
    }
    static byte[] tone(int bytes,short value)throws IOException{
        byte[] result=java.util.Arrays.copyOf(VoicePcm.header(bytes),44+bytes);
        for(int i=44;i<result.length;i+=2){result[i]=(byte)value;result[i+1]=(byte)(value>>>8);}return result;
    }
    byte[] synthesizeUnit(String text,String voice,JSONObject unit,JSONObject speaker,Cancellation cancel,VoicePcm.Sink sink,DialogueMode mode)
            throws IOException {
        return synthesizeUnit(text,voice,unit,speaker,cancel,sink);
    }
    public void close(){}
}
