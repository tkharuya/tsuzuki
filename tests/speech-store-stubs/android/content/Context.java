package android.content;

import java.io.File;

/** Minimal JVM double for the four Context calls made by production SpeechStore. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public Context getApplicationContext() { return this; }
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract File getFilesDir();
    public abstract File getCacheDir();
}
