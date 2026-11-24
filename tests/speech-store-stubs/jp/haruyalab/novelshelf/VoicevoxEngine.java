package jp.haruyalab.novelshelf;

import android.content.Context;
import org.json.JSONObject;

/** Test-only compilation boundary. Real native synthesis has a separate Linux JNI suite. */
public final class VoicevoxEngine {
    public interface Cancellation { boolean cancelled(); }
    public VoicevoxEngine(Context context) { }
    public byte[] synthesize(String text, int styleId, JSONObject parameters, Cancellation cancellation) throws Exception {
        throw new AssertionError("JVM logic tests must not claim to execute native synthesis");
    }
    public byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker, Cancellation cancellation) throws Exception {
        throw new AssertionError("JVM logic tests must not claim to execute native synthesis");
    }
    byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker, Cancellation cancellation, VoicePcm.Sink sink) throws Exception {
        throw new AssertionError("JVM logic tests must not claim to execute native synthesis");
    }
    byte[] synthesizeUnit(String text,String voice,JSONObject unit,JSONObject speaker,Cancellation cancel,VoicePcm.Sink sink,DialogueMode mode)
            throws Exception {
        return synthesizeUnit(text,voice,unit,speaker,cancel,sink);
    }
    public void close() { }
}
