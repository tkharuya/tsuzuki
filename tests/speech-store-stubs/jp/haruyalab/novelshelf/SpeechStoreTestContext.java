package jp.haruyalab.novelshelf;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** File-backed preferences double; apply is synchronous to make tests deterministic. */
public final class SpeechStoreTestContext extends Context {
    private final File root;
    public SpeechStoreTestContext(File root) { this.root = root; }
    @Override public File getFilesDir() { return directory("files"); }
    @Override public File getCacheDir() { return directory("cache"); }
    private File directory(String name) {
        File value = new File(root, name);
        if (!value.isDirectory() && !value.mkdirs()) throw new IllegalStateException("fixture directory");
        return value;
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (mode != MODE_PRIVATE) throw new IllegalArgumentException("unsupported fixture mode");
        return new Preferences(new File(directory("prefs"), name + ".properties"));
    }
    private static final class Preferences implements SharedPreferences {
        private static final Object LOCK = new Object();
        private final File path;
        Preferences(File path) { this.path = path; }
        private Properties read() {
            Properties values = new Properties();
            if (path.exists()) try (FileInputStream in = new FileInputStream(path)) { values.load(in); }
            catch (IOException e) { throw new IllegalStateException("fixture preferences read", e); }
            return values;
        }
        @Override public String getString(String key, String fallback) { synchronized (LOCK) { return read().getProperty(key, fallback); } }
        @Override public boolean contains(String key) { synchronized (LOCK) { return read().containsKey(key); } }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, String> put = new HashMap<>(); final Set<String> removed = new HashSet<>();
                @Override public Editor putString(String key, String value) { put.put(key, value); removed.remove(key); return this; }
                @Override public Editor remove(String key) { removed.add(key); put.remove(key); return this; }
                @Override public boolean commit() {
                    synchronized (LOCK) {
                        Properties values = read(); for (String key : removed) values.remove(key); values.putAll(put);
                        try (FileOutputStream out = new FileOutputStream(path)) { values.store(out, "JVM test preferences; no real API credential"); return true; }
                        catch (IOException e) { return false; }
                    }
                }
                @Override public void apply() { if (!commit()) throw new IllegalStateException("fixture preferences write"); }
            };
        }
    }
}
