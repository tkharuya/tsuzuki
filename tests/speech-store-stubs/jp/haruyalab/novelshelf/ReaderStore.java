package jp.haruyalab.novelshelf;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/** Only book existence is substituted; this fixture does not simulate or test SQLite. */
final class ReaderStore {
    private final Context context;
    ReaderStore(Context context) { this.context = context; }
    JSONObject rawBook(String id) { return marker(context, id).exists() ? Json.obj("id", id) : null; }
    void close() { }
    static void exists(Context context, String id, boolean exists) throws IOException {
        File marker = marker(context, id);
        if (exists) {
            if (!marker.getParentFile().isDirectory() && !marker.getParentFile().mkdirs()) throw new IOException("fixture books directory");
            if (!marker.exists() && !marker.createNewFile()) throw new IOException("fixture book create");
        } else if (marker.exists() && !marker.delete()) throw new IOException("fixture book remove");
    }
    private static File marker(Context context, String id) { return new File(new File(context.getFilesDir(), "fixture-books"), SpeechStore.key(id) + ".exists"); }
}
