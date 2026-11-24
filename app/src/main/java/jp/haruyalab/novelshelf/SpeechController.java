package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

/** The trusted local UI can request speech actions, but can never read a stored key. */
final class SpeechController {
    interface Reply { void done(boolean ok, Object value); }
    interface Events { void send(String type, JSONObject value); }
    private final Context context;
    private final ReaderStore books;
    private final SpeechStore speech;
    private final ChatGptSession chatgpt;
    private final OfflineVoicePacks offlinePacks;
    private final OfflineVoicePacks.Listener packListener;
    private final ChatGptSession.Listener authListener;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;
    private Reply cacheReply;
    SpeechController(Context context, ReaderStore books, Events events) {
        this.context = context.getApplicationContext(); this.books = books; this.events = events; speech = new SpeechStore(context);
        chatgpt = ChatGptSession.get(context);
        offlinePacks = new OfflineVoicePacks(this.context);
        packListener = state -> { if (!closed) events.send("speechOfflinePacks", state); };
        authListener = state -> { if (!closed) events.send("speechChatgpt", state); };
        listen();
    }
    void listen() {
        SpeechService.setListener(state -> {
            if (closed) return;
            if (cacheReply != null && (state.has("removedBytes") || "error".equals(state.optString("status")))) {
                Reply reply = cacheReply; cacheReply = null;
                if (state.has("removedBytes")) reply.done(true, Json.obj("removedBytes", state.optLong("removedBytes")));
                else reply.done(false, Json.obj("code", "SPEECH", "message", state.optString("error", "音声キャッシュを削除できませんでした。")));
            }
            events.send("speechState", state);
        });
        SpeechAnalysis.setListener(state -> { if (!closed) events.send("speechAnalysis", state); });
        chatgpt.setListener(authListener);
        OfflineVoicePacks.setListener(packListener);
    }
    // Analysis belongs to its foreground service; closing a reader screen only detaches UI listeners.
    void close() { closed = true; OfflineVoicePacks.setListener(null); SpeechAnalysis.setListener(null); SpeechService.setListener(null); chatgpt.removeListener(authListener); main.removeCallbacksAndMessages(null); }
    void deleteBook(String id) {
        SpeechAnalysis.cancelBook(id);
        SpeechService.command(context, "deleteBook", Json.obj("bookId", id));
        speech.deleteBook(id);
    }
    boolean handle(String action, JSONObject p, Reply reply) {
        if (!action.startsWith("speech")) return false;
        try {
            String id = p.optString("bookId"), url = p.optString("chapterUrl");
            switch (action) {
                case "speechState": reply.done(true, bundle(id, url)); break;
                case "speechPrepare": prepare(id, url, (ok, result) -> {
                    if (!ok) reply.done(false, result); else reply.done(true, bundle(id, ((JSONObject)result).optString("chapterUrl")));
                }); break;
                case "speechControl": control(p, reply); break;
                case "speechSettings": {
                    JSONObject patch = Json.copy(p.optJSONObject("settings"));
                    JSONObject previous = speech.settings();
                    String provider = patch.optString("analysisProvider", previous.optString("analysisProvider", "chatgpt"));
                    if (!"chatgpt".equals(provider) && !"api_key".equals(provider)) throw new IllegalArgumentException("GPT解析の接続方法を確認してください。");
                    if ("chatgpt".equals(provider) && patch.has("analysisModel")) {
                        chatgpt.selectModel(p.optString("chatgptAccountId"), patch.optString("analysisModel"));
                        patch.remove("analysisModel");
                    }
                    if (p.optBoolean("deleteApiKey")) {
                        SpeechAnalysis.cancel(); speech.deleteApiKey();
                    } else if (p.has("apiKey") && !p.optString("apiKey").trim().isEmpty()) {
                        speech.setApiKey(p.optString("apiKey")); SpeechAnalysis.cancel();
                    }
                    JSONObject settings = speech.saveSettings(patch);
                    if (!previous.optString("analysisProvider", "chatgpt").equals(settings.optString("analysisProvider", "chatgpt"))
                            || !previous.optString("analysisModel").equals(settings.optString("analysisModel"))
                            || previous.optBoolean("detailedActing", true) != settings.optBoolean("detailedActing", true)
                            || previous.optBoolean("useStoryMemory", true) != settings.optBoolean("useStoryMemory", true)) SpeechAnalysis.cancel();
                    if (!previous.optString("engine").equals(settings.optString("engine")) || !previous.optString("deviceVoice").equals(settings.optString("deviceVoice"))
                            || "offline".equals(settings.optString("engine")) && DialogueMode.fromSettings(previous) != DialogueMode.fromSettings(settings))
                        SpeechService.command(context, "stop", new JSONObject());
                    reply.done(true, publicSettings()); break;
                }
                case "speechChatgptLogin": chatgpt.login(p.optString("accountId"), p.optBoolean("consent")); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptCancel": chatgpt.cancel(); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptSelect": chatgpt.select(p.optString("accountId")); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptLogout": chatgpt.logout(p.optString("accountId")); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptModels": chatgpt.refreshModels(); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptUsage": chatgpt.openUsage(); reply.done(true, chatgpt.snapshot()); break;
                case "speechChatgptWelcome": chatgpt.welcome(p.optString("accountId")); reply.done(true, chatgpt.snapshot()); break;
                case "speechOfflineVoices": reply.done(true, Json.obj("voices", OfflineVoices.catalog())); break;
                case "speechOfflinePacks": reply.done(true, offlinePacks.snapshot()); break;
                case "speechOfflineInstall": offlinePacks.start(packListener); reply.done(true, offlinePacks.snapshot()); break;
                case "speechOfflineCancel": offlinePacks.cancel(); reply.done(true, offlinePacks.snapshot()); break;
                case "speechVoices": SpeechAudio.deviceVoices(context, (voices, error) -> {
                    if (!closed) reply.done(true, Json.obj("voices", voices, "error", error == null ? "" : error));
                }); break;
                case "speechAnalyze": {
                    required(id);
                    if (!p.optBoolean("confirmed")) throw new IllegalArgumentException("本文の送信と利用枠を確認してから解析を開始してください。");
                    prepare(id, url, (ok, result) -> {
                        if (!ok) { reply.done(false, result); return; }
                        try {
                            JSONObject plan = (JSONObject)result; String currentUrl = plan.optString("chapterUrl");
                            SpeechAnalysis.start(context, id, currentUrl, p);
                            SpeechAnalysisService.protect(context, SpeechAnalysis.snapshot(context, id));
                            reply.done(true, bundle(id, currentUrl));
                        } catch (Exception e) { failure(reply, e); }
                    }); break;
                }
                case "speechAnalysisRangeOptions": {
                    required(id); reply.done(true, rangeOptions(id, url)); break;
                }
                case "speechAnalysisPreview": {
                    required(id); reply.done(true, SpeechAnalysis.previewChapter(context, id, url)); break;
                }
                case "speechMemory": {
                    required(id); reply.done(true, SpeechAnalysis.memoryState(context, id, url)); break;
                }
                case "speechMemorySave": {
                    required(id); reply.done(true, SpeechAnalysis.saveMemory(context, id, url, p.optJSONArray("changes"), p.optJSONArray("removeIds"))); break;
                }
                case "speechAnalysisRangePreview": {
                    required(id);
                    reply.done(true, SpeechAnalysis.preview(context, id, startNumber(p, id, url), rangeCount(p)));
                    break;
                }
                case "speechAnalyzeRange": {
                    required(id); requireAnalysisConfirmation(p);
                    JSONObject started = SpeechAnalysis.startConfirmedRange(context, id, startNumber(p, id, url), rangeCount(p), p.optString("expectedStartUrl"), p);
                    SpeechAnalysisService.protect(context, started);
                    reply.done(true, bundle(id, url)); break;
                }
                case "speechResumeRange": {
                    required(id); requireAnalysisConfirmation(p);
                    JSONObject started = SpeechAnalysis.resumeRange(context, id);
                    SpeechAnalysisService.protect(context, started);
                    reply.done(true, bundle(id, url)); break;
                }
                case "speechCancel": SpeechAnalysis.cancel(); reply.done(true, Json.obj("status", "pausing")); break;
                case "speechCast": {
                    required(id); JSONArray cast = speech.saveCast(id, p.optJSONArray("cast"));
                    stopBookPlayback(id); reply.done(true, cast); break;
                }
                case "speechUnit": {
                    required(id); JSONObject plan = speech.updateUnit(id, url, p.optString("unitId"), p.optJSONObject("changes"));
                    stopBookPlayback(id); reply.done(true, SpeechAnalysis.presentPlan(plan, SpeechAnalysis.snapshot(context, id), url)); break;
                }
                case "speechPronunciation": {
                    required(id);
                    prepare(id, url, (ok, result) -> {
                        if (!ok) { reply.done(false, result); return; }
                        try { reply.done(true, speech.pronunciationState(id, url, p.optString("unitId"))); }
                        catch (Exception error) { failure(reply, error); }
                    }); break;
                }
                case "speechPronunciationSave": {
                    required(id);
                    JSONObject plan = speech.savePronunciation(id, url, p.optString("unitId"), p.optJSONObject("pronunciation"), p.optBoolean("reset"), books.chapter(id, url));
                    stopBookPlayback(id);
                    reply.done(true, Json.obj("plan", SpeechAnalysis.presentPlan(plan, SpeechAnalysis.snapshot(context, id), url))); break;
                }
                case "speechClearCache": {
                    if (cacheReply != null) throw new IllegalStateException("音声キャッシュを削除中です。");
                    cacheReply = reply;
                    SpeechService.command(context, "clearCache", new JSONObject());
                    main.postDelayed(() -> { if (cacheReply == reply) { cacheReply = null; reply.done(false, Json.obj("code", "SPEECH", "message", "削除完了の確認がタイムアウトしました。再生を停止してからお試しください。")); } }, 20000);
                    break;
                }
                default: throw new IllegalArgumentException("未対応の読み上げ操作です。");
            }
        } catch (Exception e) { if (cacheReply == reply) cacheReply = null; failure(reply, e); }
        return true;
    }
    private void control(JSONObject p, Reply reply) {
        String command = p.optString("command");
        if (!command.matches("play|pause|resume|next|previous|stop|seek|speed|sleep|preview")) throw new IllegalArgumentException("読み上げ操作を確認してください。");
        if ("speed".equals(command)) speech.saveSettings(Json.obj("speed", p.optDouble("speed", 1)));
        if ("sleep".equals(command)) speech.saveSettings(Json.obj("sleepMinutes", p.optInt("minutes")));
        if ("preview".equals(command)) {
            if (p.has("offlineVoice")) {
                if (!OfflineVoices.valid(p.optString("offlineVoice"))) throw new IllegalArgumentException("試聴する声を一覧から選んでください。");
                Json.put(p, "engine", "offline");
            } else required(p.optString("bookId"));
            SpeechService.command(context, command, p); reply.done(true, SpeechService.snapshot());
        } else if ("play".equals(command)) {
            String id = p.optString("bookId"); required(id);
            prepare(id, p.optString("chapterUrl"), (ok, result) -> {
                if (!ok) { reply.done(false, result); return; }
                try {
                    JSONObject plan = (JSONObject)result; String url = plan.optString("chapterUrl"); JSONArray units = Json.arr(plan, "units");
                    if (units.length() == 0) throw new IllegalArgumentException("読み上げる本文がありません。");
                    int index;
                    if (p.has("unitIndex")) index = p.optInt("unitIndex");
                    else {
                        JSONObject position = speech.position(id); index = 0;
                        if (url.equals(position.optString("chapterUrl")) && plan.optString("inputHash").equals(position.optString("inputHash"))) index = position.optInt("unitIndex");
                        else {
                            JSONObject visual = books.position(id);
                            int paragraph = p.has("paragraph") ? p.optInt("paragraph") : url.equals(visual.optString("chapterUrl")) ? visual.optInt("paragraph") : 0;
                            for (int i = 0; i < units.length(); i++) if (units.optJSONObject(i).optInt("paragraph") >= paragraph) { index = i; break; }
                        }
                    }
                    SpeechService.command(context, "play", Json.obj("bookId", id, "chapterUrl", url, "unitIndex", Math.max(0, Math.min(units.length() - 1, index))));
                    reply.done(true, SpeechService.snapshot());
                } catch (Exception e) { failure(reply, e); }
            });
        } else {
            SpeechService.command(context, command, p); reply.done(true, SpeechService.snapshot());
        }
    }
    private void stopBookPlayback(String id) {
        // A paused MediaPlayer must not resume a voice that the user has just replaced.
        if (id.equals(SpeechService.snapshot().optString("bookId"))) SpeechService.command(context, "stop", new JSONObject());
    }
    private void prepare(String id, String url, Reply reply) {
        required(id);
        SpeechContent.load(context, id, url, new SpeechContent.Callback() {
            @Override public void loaded(JSONObject chapter) {
                if (closed) return;
                try { required(id); JSONObject plan = speech.prepare(id, chapter); recover(plan); reply.done(true, plan); }
                catch (Exception e) { failure(reply, e); }
            }
            @Override public void failed(String error) { if (!closed) reply.done(false, Json.obj("code", "SPEECH", "message", error)); }
        });
    }
    private JSONObject bundle(String id, String url) {
        JSONObject state = SpeechService.snapshot();
        if (id.isEmpty()) return Json.obj("state", state, "settings", publicSettings(), "chatgpt", chatgpt.snapshot(), "voiceCatalog", OfflineVoices.catalog(), "offlinePacks", offlinePacks.snapshot(), "cast", new JSONArray(), "plan", new JSONObject(), "position", new JSONObject(), "analysisRange", new JSONObject());
        required(id); JSONObject position = speech.position(id);
        if (url.isEmpty()) url = position.optString("chapterUrl", books.position(id).optString("chapterUrl"));
        JSONObject plan = url.isEmpty() ? new JSONObject() : speech.plan(id, url); recover(plan);
        JSONObject range = SpeechAnalysis.snapshot(context, id); plan = SpeechAnalysis.presentPlan(plan, range, url);
        return Json.obj("state", state, "settings", publicSettings(), "chatgpt", chatgpt.snapshot(), "voiceCatalog", OfflineVoices.catalog(), "offlinePacks", offlinePacks.snapshot(), "cast", speech.cast(id), "plan", plan, "position", position, "analysisRange", range);
    }
    private JSONObject rangeOptions(String id, String chosenUrl) {
        JSONObject book = books.book(id); JSONArray source = books.chapters(id), choices = new JSONArray();
        String currentUrl = chosenUrl;
        if (currentUrl.isEmpty()) currentUrl = books.position(id).optString("chapterUrl");
        if (currentUrl.isEmpty() && source.length() > 0) currentUrl = source.optJSONObject(0).optString("url");
        int currentNumber = 0, largest = 0;
        boolean contiguous = source.length() > 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject chapter = source.optJSONObject(i);
            int index = chapter == null ? -1 : chapter.optInt("index", -1);
            if (chapter == null || index < 0 || index == Integer.MAX_VALUE) { contiguous = false; continue; }
            int number = index + 1; largest = Math.max(largest, number);
            if (index != i || chapter.optString("url").isEmpty()) contiguous = false;
            if (currentUrl.equals(chapter.optString("url"))) currentNumber = number;
            choices.put(Json.obj("number", number, "title", chapter.optString("title"), "url", chapter.optString("url"), "cached", chapter.optBoolean("cached")));
        }
        int knownTotal = Math.max(largest, Math.max(book.optInt("total"), book.optInt("reportedTotal")));
        boolean complete = book.optBoolean("tocComplete") && contiguous && source.length() == knownTotal;
        return Json.obj("currentNumber", currentNumber, "currentChapterUrl", currentUrl, "knownTotal", knownTotal,
                "tocComplete", complete, "chapters", choices, "analysisRange", SpeechAnalysis.snapshot(context, id));
    }
    private int startNumber(JSONObject request, String id, String url) {
        long value = request.has("startNumber") ? positiveInteger(request, "startNumber") : rangeOptions(id, url).optInt("currentNumber");
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("開始話を目次の話数で指定してください。");
        return (int) value;
    }
    private long rangeCount(JSONObject request) { return request.has("count") ? positiveInteger(request, "count") : 10L; }
    private static long positiveInteger(JSONObject request, String field) {
        Object raw = request.opt(field);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("開始話と話数には1以上の整数を入力してください。");
        try {
            long number = new java.math.BigDecimal(raw.toString()).longValueExact();
            if (number < 1) throw new ArithmeticException();
            return number;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("開始話と話数には1以上の整数を入力してください。");
        }
    }
    private static void requireAnalysisConfirmation(JSONObject request) {
        if (!request.optBoolean("confirmed")) throw new IllegalArgumentException("解析する範囲と本文の送信・利用枠を確認してから開始してください。");
    }
    private JSONObject publicSettings() {
        JSONObject settings = speech.settings();
        Json.put(settings, "apiAnalysisModel", settings.optString("analysisModel", "gpt-6-luna"));
        if (!"api_key".equals(settings.optString("analysisProvider", "chatgpt")))
            Json.put(settings, "analysisModel", chatgpt.snapshot().optString("selectedModel"));
        return settings;
    }
    private void recover(JSONObject plan) {
        String id = plan.optString("bookId"), url = plan.optString("chapterUrl");
        if ("running".equals(plan.optString("analysisStatus")) && !SpeechAnalysis.running(id, url)) {
            Json.put(plan, "analysisStatus", "paused"); Json.put(plan, "error", ""); speech.savePlan(id, url, plan);
        }
    }
    private void required(String id) { if (id.isEmpty() || books.rawBook(id) == null) throw new IllegalArgumentException("本棚の作品を開いてください。"); }
    private void failure(Reply reply, Exception e) {
        String message = e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof ChatGptOAuth.AuthException ? e.getMessage() : "読み上げ操作を完了できませんでした。設定と保存状態を確認してください。";
        reply.done(false, Json.obj("code", "SPEECH", "message", Json.limit(message, 350)));
    }
}
