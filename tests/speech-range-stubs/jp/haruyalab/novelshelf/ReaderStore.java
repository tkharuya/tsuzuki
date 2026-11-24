package jp.haruyalab.novelshelf;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** SQLite boundary fixture. Production queue, link validation, analyzer and SpeechStore remain real. */
final class ReaderStore {
    private static final Map<String, JSONObject> books = new HashMap<>(), positions = new HashMap<>(), bodies = new HashMap<>(), remote = new HashMap<>();
    private static final Map<String, JSONArray> catalogs = new HashMap<>();
    static int positionWrites, readWrites;
    ReaderStore(Context context) { }
    void close() { }
    static synchronized void install(JSONObject book, JSONArray catalog, JSONArray chapters, boolean cache) {
        String id = book.optString("id"); books.put(id, Json.copy(book)); catalogs.put(id, copy(catalog));
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.optJSONObject(i); remote.put(id + chapter.optString("url"), Json.copy(chapter));
            if (cache) bodies.put(id + chapter.optString("url"), Json.copy(chapter));
        }
        positions.put(id, Json.obj("chapterUrl", catalog.optJSONObject(0).optString("url"), "paragraph", 7));
    }
    static synchronized void removeBook(String id) { books.remove(id); catalogs.remove(id); positions.remove(id); }
    static synchronized JSONObject remote(String id, String url) { JSONObject value = remote.get(id + url); return value == null ? null : Json.copy(value); }
    synchronized JSONObject rawBook(String id) { synchronized (ReaderStore.class) { JSONObject value = books.get(id); return value == null ? null : Json.copy(value); } }
    synchronized JSONArray chapters(String id) { synchronized (ReaderStore.class) { return copy(catalogs.get(id)); } }
    synchronized JSONObject chapterMeta(String id, String url) {
        JSONArray catalog = chapters(id);
        for (int i = 0; i < catalog.length(); i++) if (url.equals(catalog.optJSONObject(i).optString("url"))) return Json.copy(catalog.optJSONObject(i));
        return null;
    }
    synchronized JSONObject chapter(String id, String url) {
        synchronized (ReaderStore.class) {
            if (!books.containsKey(id)) return null;
            JSONObject metadata = chapterMeta(id, url), value = bodies.get(id + url); if (metadata == null || value == null) return null;
            JSONObject out = Json.copy(value); Json.put(out, "bookId", id); Json.put(out, "index", metadata.optInt("index")); return out;
        }
    }
    synchronized void putChapter(String id, JSONObject chapter) {
        synchronized (ReaderStore.class) {
            if (!books.containsKey(id)) throw new IllegalStateException("fixture book was deleted");
            if (chapterMeta(id, chapter.optString("url")) == null) mergeChapters(id, new JSONArray().put(chapter), chapterCount(id));
            bodies.put(id + chapter.optString("url"), Json.copy(chapter));
        }
    }
    synchronized int chapterCount(String id) { return chapters(id).length(); }
    synchronized void mergeChapters(String id, JSONArray incoming, int offset) {
        synchronized (ReaderStore.class) {
            JSONArray all = chapters(id);
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject item = incoming.optJSONObject(i); boolean found = false;
                for (int j = 0; j < all.length(); j++) if (item.optString("url").equals(all.optJSONObject(j).optString("url"))) { Json.put(all.optJSONObject(j), "index", item.optInt("index", offset + i)); found = true; }
                if (!found) all.put(Json.copy(item));
            }
            catalogs.put(id, all);
        }
    }
    synchronized void putBook(JSONObject patch) { synchronized (ReaderStore.class) { String id = patch.optString("id"); books.put(id, Json.merge(books.get(id), patch)); } }
    synchronized JSONObject job(String id) { return null; }
    synchronized void setJob(String id, String state, String error) { }
    synchronized JSONObject position(String id) { synchronized (ReaderStore.class) { return Json.copy(positions.get(id)); } }
    synchronized void savePosition(String id, JSONObject value) { synchronized (ReaderStore.class) { positionWrites++; positions.put(id, Json.copy(value)); } }
    synchronized void setRead(String id, String url, boolean read) { synchronized (ReaderStore.class) { readWrites++; } }
    private static JSONArray copy(JSONArray value) { try { return value == null ? new JSONArray() : new JSONArray(value.toString()); } catch (Exception e) { throw new IllegalStateException(e); } }
}
