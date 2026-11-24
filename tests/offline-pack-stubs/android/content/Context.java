package android.content;
import java.io.File;
import android.content.pm.ApplicationInfo;
public class Context {
 private final File directory;
 private final ApplicationInfo info = new ApplicationInfo();
 public Context(File directory, String nativePath) { this.directory=directory; info.nativeLibraryDir=nativePath; }
 public Context getApplicationContext() { return this; }
 public File getNoBackupFilesDir() { return directory; }
 public ApplicationInfo getApplicationInfo() { return info; }
 public void startForegroundService(Intent ignored) { }
}
