package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Official-page fetching boundary. Validation/next-link processing below uses real ChapterContent. */
public final class SpeechContent {
    public interface Callback { void loaded(JSONObject chapter); void failed(String message); }
    static final List<String> loads = new ArrayList<>();
    static volatile String blockedUrl = "", failUrl = "";
    static volatile Runnable held;
    static void load(Context context, String id, String url, Callback callback) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (loads) { loads.add(url); }
            Runnable complete = () -> {
                ReaderStore books = new ReaderStore(context);
                try {
                    if (books.rawBook(id) == null) throw new IllegalStateException("作品が削除されています。");
                    if (url.equals(failUrl)) throw new IllegalStateException("ログイン・公開状態を確認してください。");
                    JSONObject body = books.chapter(id, url);
                    if (body == null) {
                        body = ReaderStore.remote(id, url);
                        if (body == null) throw new IllegalStateException("本文がありません。");
                        body = ChapterContent.clean(books, id, url, body); books.putChapter(id, body);
                    }
                    callback.loaded(ChapterContent.withLinks(books, id, body));
                } catch (Exception e) { callback.failed(e.getMessage()); }
            };
            if (url.equals(blockedUrl)) held = complete; else complete.run();
        });
    }
    static void release() { Runnable value = held; held = null; blockedUrl = ""; if (value != null) value.run(); }
}
