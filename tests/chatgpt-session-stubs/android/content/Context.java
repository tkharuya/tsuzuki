package android.content;

import java.io.File;

/** JVM-only boundary. No real activity, Android service, or application lifecycle is started. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public Context getApplicationContext() { return this; }
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract File getFilesDir();
    public abstract File getCacheDir();
    public ComponentName startForegroundService(Intent intent) { throw new UnsupportedOperationException("fixture records service requests only"); }
    public boolean stopService(Intent intent) { throw new UnsupportedOperationException("fixture records service requests only"); }
    public void startActivity(Intent intent) { throw new UnsupportedOperationException("fixture records browser intents only"); }
}
