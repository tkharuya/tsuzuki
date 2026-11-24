package jp.haruyalab.novelshelf;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native OAuth coordinator. Credentials and authorization URLs never cross the WebView bridge. */
public final class ChatGptSession {
    interface Listener { void update(JSONObject state); }
    private static ChatGptSession instance;
    private static final Object REFRESH_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Context context;
    private final ChatGptStore store;
    private final ChatGptOAuth oauth;
    private final ExecutorService work;
    private volatile Listener listener;
    private volatile long epoch;
    private volatile long catalogTicket;
    private volatile Pending pending;
    private boolean catalogLoading;
    private String error = "", phase = "";
    private static final class Pending {
        final long generation;
        final String expectedId;
        final ChatGptOAuth.Attempt attempt;
        final ChatGptLoopback loopback;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Pending(long generation, String expectedId, ChatGptOAuth.Attempt attempt, ChatGptLoopback loopback) {
            this.generation = generation; this.expectedId = expectedId; this.attempt = attempt; this.loopback = loopback;
        }
    }
    public static synchronized ChatGptSession get(Context context) {
        if (instance == null) instance = new ChatGptSession(context.getApplicationContext());
        return instance;
    }
    private ChatGptSession(Context context) {
        this(context, new ChatGptStore(context), new ChatGptOAuth(), Executors.newFixedThreadPool(2));
    }
    /** Test seam for transport and scheduling; production always uses the Android native boundary. */
    ChatGptSession(Context context, ChatGptStore store, ChatGptOAuth oauth, ExecutorService work) {
        this.context = context; this.store = store; this.oauth = oauth; this.work = work;
        try { store.recoverInterruptedLogouts(); }
        catch (Exception e) { error = safe(e); }
    }
    void setListener(Listener target) { listener = target; if (target != null) emit(); }
    void removeListener(Listener target) { if (listener == target) listener = null; }
    public synchronized JSONObject snapshot() {
        JSONObject state;
        try { state = store.publicState(); }
        catch (Exception e) { state = Json.obj("status", "error", "accounts", new JSONArray(), "activeAccountId", "", "models", new JSONArray(), "selectedModel", "", "firstConnection", false); error = safe(e); }
        Json.put(state, "busy", pending != null || catalogLoading);
        if (pending != null) Json.put(state, "status", phase);
        else if (!error.isEmpty() && "signed_out".equals(state.optString("status"))) Json.put(state, "status", "error");
        if (!error.isEmpty()) Json.put(state, "error", error); return state;
    }
    public synchronized boolean waiting(long generation) { return pending != null && pending.generation == generation && !pending.cancelled.get(); }
    public synchronized long currentGeneration() { return pending == null ? -1 : pending.generation; }

    public synchronized void login(String accountId, boolean consent) throws Exception {
        String expectedId = accountId == null ? "" : accountId;
        JSONObject saved = expectedId.isEmpty() ? null : store.account(expectedId);
        if (saved != null && saved.optBoolean("signing_out")) throw new IllegalStateException("ログアウトの完了を待ってからログインしてください。");
        invalidate(); SpeechAnalysis.cancel(); error = "";
        ChatGptLoopback loopback = new ChatGptLoopback();
        Pending next;
        try { next = new Pending(epoch, expectedId, ChatGptOAuth.begin(store.hostId(), saved, loopback.port(), consent), loopback); }
        catch (Exception e) { loopback.close(); throw e; }
        pending = next; phase = "waiting";
        try {
            context.startForegroundService(new Intent(context, ChatGptLoginService.class).putExtra("generation", next.generation));
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(next.attempt.authorizationUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            invalidate(); error = "ログイン用のブラウザーを開けませんでした。端末のブラウザーと通知設定を確認してください。"; emit();
            throw new IllegalStateException(error);
        }
        emit(); work.execute(() -> completeLogin(next));
    }
    private void completeLogin(Pending job) {
        boolean loadModels = false;
        ChatGptOAuth.Cancel cancel = () -> job.cancelled.get() || pending != job || epoch != job.generation;
        try {
            ChatGptOAuth.Callback callback = job.loopback.await(job.attempt, cancel);
            synchronized (this) { if (cancel.cancelled()) return; phase = "exchanging"; emit(); }
            JSONObject credentials = oauth.exchange(job.attempt, callback, cancel);
            synchronized (this) {
                if (cancel.cancelled()) return;
                store.saveLogin(credentials, job.expectedId);
                pending = null; phase = ""; error = "";
                loadModels = ChatGptStore.planEnabled(credentials);
                stopServiceIfIdle(); emit();
            }
        } catch (Exception e) {
            synchronized (this) {
                if (pending == job && epoch == job.generation) {
                    pending = null; phase = "";
                    error = job.cancelled.get() ? "" : safe(e); stopServiceIfIdle(); emit();
                }
            }
        } finally { job.loopback.close(); }
        if (loadModels) {
            synchronized (this) { if (epoch == job.generation && pending == null) refreshModels(); }
        }
    }
    public synchronized void cancel() { invalidate(); SpeechAnalysis.cancel(); error = ""; emit(); }
    /** A delayed notification from an earlier browser sign-in cannot cancel a newer operation. */
    public synchronized void cancel(long generation) { if (waiting(generation)) cancel(); }
    public synchronized void serviceStopped(long generation) {
        if (!waiting(generation)) return;
        invalidate(); error = "ログイン待機が終了しました。アプリを開いてもう一度ログインしてください。"; emit();
    }
    public synchronized void select(String accountId) {
        store.account(accountId); // Validate before cancelling a valid selection.
        invalidate(); SpeechAnalysis.cancel(); store.selectAccount(accountId); error = ""; emit();
        if (ChatGptStore.planEnabled(store.account(accountId))) refreshModels();
    }
    public synchronized void selectModel(String accountId, String model) {
        if (pending != null) throw new IllegalStateException("ログイン完了を待ってからモデルを保存してください。");
        store.selectModel(accountId, model); invalidate(); SpeechAnalysis.cancel(); error = ""; emit();
    }
    public synchronized void welcome(String accountId) { store.welcomeSeen(accountId); emit(); }
    public synchronized void logout(String accountId) {
        store.account(accountId); invalidate(); SpeechAnalysis.cancel(); error = "";
        JSONObject old = store.beginLogout(accountId); String ticket = old.optString("logout_ticket"); emit();
        work.execute(() -> {
            // Wait for an already-started rotation, then revoke the newest persisted token.
            synchronized (REFRESH_LOCK) {
                JSONObject latest;
                try {
                    latest = store.account(accountId);
                    if (!latest.optBoolean("signing_out") || !ticket.equals(latest.optString("logout_ticket"))) return;
                } catch (Exception e) { synchronized (ChatGptSession.this) { accountError(accountId, safe(e)); emit(); } return; }
                boolean revoked = false;
                try { oauth.revoke(latest, () -> Thread.currentThread().isInterrupted()); revoked = true; }
                catch (Exception ignored) { }
                finally {
                    synchronized (ChatGptSession.this) {
                        try {
                            store.clearSession(accountId, latest.optLong("revision"), !revoked);
                            if (!revoked) accountError(accountId, "端末内のログイン情報を削除しました。サーバー側の失効を確認できなかったため、ChatGPTの設定で接続を確認してください。");
                        } catch (Exception e) { accountError(accountId, safe(e)); }
                        emit();
                    }
                }
            }
        });
    }
    public synchronized JSONObject accountForAnalysis() {
        if (pending != null) throw new IllegalStateException("ChatGPTのログイン完了を待ってから解析してください。");
        JSONObject account = store.usableAccount(store.activeId());
        String model = account.optString("selected_model");
        if (model.isEmpty() || !ChatGptStore.containsModel(account, model))
            throw new IllegalStateException("このChatGPTアカウントのモデル一覧を更新して、解析モデルを選んでください。");
        return account;
    }
    /** Called before each batch. Refresh rotation is serialized, persisted atomically and account-bound. */
    public String accessToken(String accountId, ChatGptOAuth.Cancel external) throws Exception {
        long generation = epoch;
        ChatGptOAuth.Cancel cancel = () -> external != null && external.cancelled() || epoch != generation || Thread.currentThread().isInterrupted();
        synchronized (REFRESH_LOCK) {
            check(cancel);
            JSONObject current = store.usableAccount(accountId); long now = System.currentTimeMillis() / 1000;
            long expiry = current.optLong("expires_at"), earliest = current.optLong("earliest_refresh_at");
            if (expiry > now + 60 || earliest > now && expiry > now + 5) { check(cancel); return current.optString("access_token"); }
            if (earliest > now) throw new IllegalStateException("ChatGPTの認証更新がまだ許可されていません。少し待って再開してください。");
            if (current.optString("refresh_token").isEmpty()) throw new IllegalStateException("ChatGPTに再ログインしてから解析を再開してください。");
            try {
                // Once a refresh is sent, its replacement token must be persisted even if the user
                // cancels inference or selects another account. Otherwise rotation would lose R1.
                JSONObject refreshed = oauth.refresh(current, () -> Thread.currentThread().isInterrupted());
                synchronized (this) {
                    JSONObject committed = store.saveRotation(accountId, ChatGptStore.credentialRevision(current), refreshed); emit();
                    check(cancel); store.usableAccount(accountId);
                    if (!ChatGptStore.planEnabled(committed)) throw new IllegalStateException("ChatGPTプランの利用許可が解除されました。再ログインして利用を許可してください。");
                    return committed.optString("access_token");
                }
            } catch (ChatGptOAuth.AuthException e) {
                synchronized (this) {
                    if (e.terminalRefresh()) {
                        JSONObject latest = store.account(accountId);
                        if (!latest.optBoolean("signing_out") && ChatGptStore.credentialRevision(latest) == ChatGptStore.credentialRevision(current))
                            store.clearSession(accountId, latest.optLong("revision"));
                        if (!cancel.cancelled()) error = e.getMessage(); emit();
                    }
                }
                throw e;
            }
        }
    }
    public synchronized void refreshModels() {
        if (pending != null) throw new IllegalStateException("ログイン完了を待ってからモデルを更新してください。");
        JSONObject selected = store.usableAccount(store.activeId()); String id = selected.optString("id");
        final long generation = epoch, ticket = ++catalogTicket;
        catalogLoading = true; error = ""; emit();
        work.execute(() -> {
            ChatGptOAuth.Cancel cancel = () -> epoch != generation || catalogTicket != ticket || Thread.currentThread().isInterrupted();
            try {
                String token = accessToken(id, cancel); JSONObject before = store.usableAccount(id);
                JSONArray models = oauth.models(token, cancel);
                synchronized (ChatGptSession.this) {
                    check(cancel); store.saveModels(id, before.optLong("revision"), models);
                    if (models.length() == 0) error = "このアカウントで利用できるモデルがありません。ChatGPTのプランと利用権限を確認してください。";
                }
            } catch (Exception e) {
                synchronized (ChatGptSession.this) { if (!cancel.cancelled()) error = safe(e); }
            } finally {
                synchronized (ChatGptSession.this) { if (generation == epoch && ticket == catalogTicket) { catalogLoading = false; emit(); } }
            }
        });
    }
    public void openUsage() {
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptOAuth.USAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { throw new IllegalStateException("ブラウザーを開けませんでした。端末にブラウザーを設定してください。"); }
    }
    /** Must be called under this lock. Invalidates every in-flight operation before a new identity is selected. */
    private void invalidate() {
        epoch++; catalogTicket++; catalogLoading = false;
        Pending old = pending; pending = null; phase = "";
        if (old != null) { old.cancelled.set(true); old.loopback.close(); stopServiceIfIdle(); }
    }
    private void stopServiceIfIdle() {
        MAIN.post(() -> { synchronized (ChatGptSession.this) { if (pending == null) context.stopService(new Intent(context, ChatGptLoginService.class)); } });
    }
    private void emit() {
        MAIN.post(() -> { Listener target = listener; if (target != null) target.update(snapshot()); });
    }
    private void accountError(String accountId, String message) {
        try { if (accountId.equals(store.activeId())) error = message; }
        catch (Exception e) { error = safe(e); }
    }
    private static void check(ChatGptOAuth.Cancel cancel) throws ChatGptOAuth.AuthException {
        if (cancel != null && cancel.cancelled()) throw new ChatGptOAuth.AuthException("cancelled", 0, "");
    }
    private static String safe(Exception e) {
        if (e instanceof ChatGptOAuth.AuthException || e instanceof IllegalStateException || e instanceof IllegalArgumentException)
            return Json.limit(e.getMessage() == null ? "ChatGPTとの接続を完了できませんでした。" : e.getMessage(), 350);
        return "ChatGPTとの接続を完了できませんでした。通信状態を確認してやり直してください。";
    }
}
