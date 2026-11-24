package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the same normal WebView extractor and validation as the visual reader. */
public final class SpeechContent {
    public interface Callback { void loaded(JSONObject chapter); void failed(String error); }
    private SpeechContent() {}
    public static void load(Context context, String bookId, String requestedUrl, Callback callback) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> loadOnMain(app, bookId, requestedUrl, callback));
    }
    private static void loadOnMain(Context app, String id, String raw, Callback callback) {
        ReaderStore db = new ReaderStore(app);
        try {
            JSONObject b = db.rawBook(id);
            if (b == null) throw new IllegalArgumentException("本棚に作品がありません。");
            String chosen = raw == null ? "" : raw;
            if (chosen.isEmpty()) {
                chosen = db.position(id).optString("chapterUrl");
                if (chosen.isEmpty()) { JSONArray a = db.chapters(id); if (a.length() > 0) chosen = a.optJSONObject(0).optString("url"); }
            }
            final String url = chosen;
            JSONObject cached = db.chapter(id, url);
            if (cached != null) { JSONObject result = ChapterContent.withLinks(db, id, cached); db.close(); callback.loaded(result); return; }
            if ("local".equals(b.optString("source"))) throw new IllegalArgumentException("保存済みの本文がありません。");
            String valid = Urls.input(url);
            if (!valid.equals(url) || !Urls.sameBook(url, b.optString("url"))) throw new IllegalArgumentException("この作品の章を選んでください。");
            final WebFetcher fetcher = new WebFetcher(app);
            fetcher.fetch(url, "chapter", true, (data, error) -> {
                JSONObject result = null; String failure = null;
                try {
                    if (error != null) failure = error.getMessage();
                    else if (db.rawBook(id) == null) failure = "作品が削除されています。";
                    else {
                        JSONObject clean = ChapterContent.clean(db, id, url, data);
                        db.putChapter(id, clean); result = ChapterContent.withLinks(db, id, clean);
                    }
                } catch (Exception e) { failure = e instanceof IllegalArgumentException ? e.getMessage() : "本文を保存できませんでした。空き容量を確認してください。"; }
                finally { fetcher.destroy(); db.close(); }
                if (failure == null && result != null) callback.loaded(result); else callback.failed(failure == null ? "本文を取得できませんでした。" : failure);
            });
        } catch (Exception e) { db.close(); callback.failed(e instanceof IllegalArgumentException ? e.getMessage() : "本文の読み込みに失敗しました。章を開き直してください。"); }
    }
    public static void completed(Context context, String bookId, String chapterUrl) {
        ReaderStore db = new ReaderStore(context.getApplicationContext());
        try { if (db.chapterMeta(bookId, chapterUrl) != null) db.setRead(bookId, chapterUrl, true); }
        finally { db.close(); }
    }
    public static String next(Context context, String bookId, String chapterUrl) {
        ReaderStore db = new ReaderStore(context.getApplicationContext());
        try {
            JSONObject chapter = db.chapter(bookId, chapterUrl);
            if (chapter == null) return "";
            String next = ChapterContent.withLinks(db, bookId, chapter).optString("nextUrl");
            return next.equals(chapterUrl) ? "" : next;
        } finally { db.close(); }
    }
}
