package android.content;

/** Only the string storage interface used by SpeechStore; no Android runtime claim. */
public interface SharedPreferences {
    String getString(String key, String fallback);
    boolean contains(String key);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor remove(String key);
        boolean commit();
        void apply();
    }
}
