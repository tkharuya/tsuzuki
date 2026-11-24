package jp.haruyalab.novelshelf;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Only the unrelated disk-store identity boundary is replaced in these audio tests. */
public final class SpeechStore {
    public static String key(String text) {
        try {
            StringBuilder out=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b&255));
            return out.toString();
        } catch(Exception e){throw new IllegalStateException(e);}
    }
}
