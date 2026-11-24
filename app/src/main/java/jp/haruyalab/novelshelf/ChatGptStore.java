package jp.haruyalab.novelshelf;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted native-only account registrations. Public projections never contain OAuth credentials. */
public final class ChatGptStore {
    private static final Object LOCK = new Object();
    private static final String KEY_ALIAS = "tsuzuki.chatgpt.v1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final File file;
    private final Sealer sealer;
    interface Sealer { byte[] seal(byte[] plain) throws Exception; byte[] open(byte[] sealed) throws Exception; }

    public ChatGptStore(Context context) { this(new File(context.getFilesDir(), "chatgpt-accounts.enc"), new KeystoreSealer()); }
    ChatGptStore(File file, Sealer sealer) { this.file = file; this.sealer = sealer; }

    public String hostId() {
        synchronized (LOCK) {
            JSONObject state = read(); String id = state.optString("host_id");
            if (id.isEmpty()) { id = "urn:uuid:" + UUID.randomUUID(); Json.put(state, "host_id", id); write(state); }
            return id;
        }
    }
    public String activeId() { synchronized (LOCK) { return read().optString("active_id"); } }
    public JSONObject account(String id) {
        synchronized (LOCK) { return Json.copy(require(read(), id)); }
    }
    public JSONObject activeAccount() {
        synchronized (LOCK) { JSONObject state = read(); return Json.copy(require(state, state.optString("active_id"))); }
    }
    public void selectAccount(String id) {
        synchronized (LOCK) {
            JSONObject state = read(), next = require(state, id);
            if (!id.equals(state.optString("active_id"))) {
                invalidateSelection(find(state, state.optString("active_id")));
                invalidateSelection(next);
                Json.put(state, "active_id", id); write(state);
            }
        }
    }
    private static void invalidateSelection(JSONObject account) {
        // A logout already made the account unusable; preserve its pending cleanup revision.
        if (account != null && !account.optBoolean("signing_out")) {
            Json.put(account, "credential_revision", credentialRevision(account));
            Json.put(account, "revision", account.optLong("revision") + 1L);
        }
    }
    public static boolean connected(JSONObject account) {
        return account != null && !account.optBoolean("signing_out") && !account.optBoolean("reauth_required")
                && (!account.optString("access_token").isEmpty() || !account.optString("id_token").isEmpty());
    }
    public static boolean planEnabled(JSONObject account) {
        if (!connected(account) || account.optString("access_token").isEmpty()) return false;
        boolean invoke = false, plan = false; JSONArray scopes = Json.arr(account, "scopes");
        for (int i = 0; i < scopes.length(); i++) {
            invoke |= "resource.invoke".equals(scopes.optString(i));
            plan |= "chatgpt.tokens.use.direct".equals(scopes.optString(i));
        }
        return invoke && plan;
    }
    public static String identity(JSONObject account) {
        return SpeechStore.key(account.optString("issuer") + "\u0000" + account.optString("client_id") + "\u0000" + account.optString("subject"));
    }
    /** Credential generation is independent of selection changes and in-flight UI cancellation. */
    public static long credentialRevision(JSONObject account) {
        return account.has("credential_revision") ? account.optLong("credential_revision") : account.optLong("revision");
    }

    /** Called only with signature-validated OAuth credentials. Account identities cannot be overwritten. */
    public String saveLogin(JSONObject credentials, String expectedId) {
        synchronized (LOCK) {
            validateIdentity(credentials); String id = identity(credentials);
            if (expectedId != null && !expectedId.isEmpty() && !expectedId.equals(id))
                throw new IllegalStateException("選択したChatGPTアカウントと認証結果が一致しません。");
            JSONObject state = read(); JSONArray accounts = Json.arr(state, "accounts"); JSONObject old = find(state, id);
            if (old != null && old.optBoolean("signing_out"))
                throw new IllegalStateException("ログアウトの完了を待ってからログインしてください。");
            JSONObject next = mergeCredentials(old, credentials);
            Json.put(next, "id", id); Json.put(next, "revision", old == null ? 1L : old.optLong("revision") + 1L);
            Json.put(next, "credential_revision", old == null ? 1L : credentialRevision(old) + 1L); next.remove("logout_ticket"); next.remove("revocation_unconfirmed");
            Json.put(next, "label_number", old == null ? accounts.length() + 1 : old.optInt("label_number"));
            if (old == null) { Json.put(next, "notice_seen", false); Json.put(next, "selected_model", ""); Json.put(next, "models", new JSONArray()); }
            if (!id.equals(state.optString("active_id"))) invalidateSelection(find(state, state.optString("active_id")));
            replace(state, id, next); Json.put(state, "active_id", id); write(state); return id;
        }
    }
    /** Refresh is compare-and-swap: logout, re-login or account switching cannot resurrect stale tokens. */
    public JSONObject saveRefresh(String id, long expectedRevision, JSONObject credentials) {
        synchronized (LOCK) {
            JSONObject state = read(), old = require(state, id);
            if (!id.equals(state.optString("active_id")) || old.optLong("revision") != expectedRevision || !connected(old))
                throw new IllegalStateException("ChatGPTアカウントの状態が変わりました。解析を再開してください。");
            validateIdentity(credentials);
            if (!id.equals(identity(credentials))) throw new IllegalStateException("更新された認証情報のアカウントが一致しません。");
            JSONObject next = mergeCredentials(old, credentials); Json.put(next, "revision", expectedRevision + 1L);
            Json.put(next, "credential_revision", credentialRevision(old) + 1L);
            replace(state, id, next); write(state); return Json.copy(next);
        }
    }
    /**
     * Finishes a refresh that has already reached the server, even after selection/cancellation.
     * A rotated credential set belongs to its original registration. Never reactivate that
     * registration, cancel its pending logout, or overwrite a later login/session clear.
     */
    public JSONObject saveRotation(String id, long expectedCredentialRevision, JSONObject credentials) {
        synchronized (LOCK) {
            JSONObject state = read(), old = require(state, id);
            if (credentialRevision(old) != expectedCredentialRevision || old.optBoolean("reauth_required")
                    || (!connected(old) && !old.optBoolean("signing_out")))
                throw new IllegalStateException("ChatGPTの認証状態が変わりました。解析を再開してください。");
            validateIdentity(credentials);
            if (!id.equals(identity(credentials))) throw new IllegalStateException("更新された認証情報のアカウントが一致しません。");
            JSONObject next = mergeCredentials(old, credentials);
            Json.put(next, "signing_out", old.optBoolean("signing_out"));
            Json.put(next, "reauth_required", old.optBoolean("reauth_required"));
            Json.put(next, "revision", old.optLong("revision") + 1L);
            Json.put(next, "credential_revision", expectedCredentialRevision + 1L);
            replace(state, id, next); write(state); return Json.copy(next);
        }
    }
    public JSONObject usableAccount(String id) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            if (!id.equals(state.optString("active_id"))) throw new IllegalStateException("ChatGPTアカウントが切り替わりました。解析を再開してください。");
            if (!connected(account)) throw new IllegalStateException("ChatGPTにログインしてください。");
            if (!planEnabled(account)) throw new IllegalStateException("ChatGPTプランの利用を許可してから解析してください。");
            return Json.copy(account);
        }
    }
    public void saveModels(String id, long expectedRevision, JSONArray models) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            if (!id.equals(state.optString("active_id")) || account.optLong("revision") != expectedRevision || !planEnabled(account)) return;
            JSONArray copy = new JSONArray();
            for (int i = 0; i < models.length(); i++) {
                JSONObject item = models.optJSONObject(i);
                if (item == null || !item.optString("slug").matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw new IllegalArgumentException("モデル一覧が不正です。");
                copy.put(Json.obj("slug", item.optString("slug"), "display_name", Json.limit(item.optString("display_name", item.optString("slug")), 160)));
            }
            Json.put(account, "models", copy);
            // The user chooses the initial model. Removed choices are retained until explicitly changed.
            write(state);
        }
    }
    public void selectModel(String id, String model) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            if (!id.equals(state.optString("active_id"))) throw new IllegalStateException("ChatGPTアカウントが切り替わりました。モデルを選び直してください。");
            if (!planEnabled(account) || !containsModel(account, model)) throw new IllegalArgumentException("このChatGPTアカウントのモデル一覧から選んでください。");
            Json.put(account, "selected_model", model); write(state);
        }
    }
    public static boolean containsModel(JSONObject account, String model) {
        JSONArray list = Json.arr(account, "models");
        for (int i = 0; i < list.length(); i++) if (model.equals(list.optJSONObject(i).optString("slug"))) return true;
        return false;
    }
    public JSONObject beginLogout(String id) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            Json.put(account, "credential_revision", credentialRevision(account));
            Json.put(account, "signing_out", true); Json.put(account, "revision", account.optLong("revision") + 1L);
            Json.put(account, "logout_ticket", UUID.randomUUID().toString());
            write(state); return Json.copy(account);
        }
    }
    public void clearSession(String id, long expectedRevision) {
        clearSession(id, expectedRevision, false);
    }
    public void clearSession(String id, long expectedRevision, boolean revocationUnconfirmed) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            if (account.optLong("revision") != expectedRevision) return;
            long credentials = credentialRevision(account);
            for (String key : new String[]{"access_token", "refresh_token", "id_token", "token_type", "expires_at", "expires_in", "earliest_refresh_at", "saved_at", "auth_nonce", "logout_ticket"}) account.remove(key);
            Json.put(account, "scopes", new JSONArray()); Json.put(account, "models", new JSONArray());
            Json.put(account, "signing_out", false); Json.put(account, "reauth_required", true);
            Json.put(account, "revocation_unconfirmed", revocationUnconfirmed);
            Json.put(account, "revision", expectedRevision + 1L); Json.put(account, "credential_revision", credentials + 1L); write(state);
        }
    }
    /** Complete local cleanup if Android terminated the process during a remote sign-out attempt. */
    public boolean recoverInterruptedLogouts() {
        synchronized (LOCK) {
            JSONObject state = read(); JSONArray accounts = Json.arr(state, "accounts"); boolean recovered = false;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.optJSONObject(i);
                if (account == null || !account.optBoolean("signing_out")) continue;
                long credentials = credentialRevision(account);
                for (String key : new String[]{"access_token", "refresh_token", "id_token", "token_type", "expires_at", "expires_in", "earliest_refresh_at", "saved_at", "auth_nonce", "logout_ticket"}) account.remove(key);
                Json.put(account, "scopes", new JSONArray()); Json.put(account, "models", new JSONArray());
                Json.put(account, "signing_out", false); Json.put(account, "reauth_required", true);
                Json.put(account, "revocation_unconfirmed", true);
                Json.put(account, "revision", account.optLong("revision") + 1L);
                Json.put(account, "credential_revision", credentials + 1L); recovered = true;
            }
            if (recovered) write(state); return recovered;
        }
    }
    public void welcomeSeen(String id) {
        synchronized (LOCK) {
            JSONObject state = read(), account = require(state, id);
            if (!id.equals(state.optString("active_id")) || !planEnabled(account)) return;
            Json.put(account, "notice_seen", true); write(state);
        }
    }
    public JSONObject publicState() {
        synchronized (LOCK) {
            JSONObject state = read(); JSONArray accounts = Json.arr(state, "accounts"), visible = new JSONArray();
            String selected = state.optString("active_id"); JSONObject active = find(state, selected);
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.optJSONObject(i); String email = account.optString("email"), name = account.optString("name");
                String label = (!email.isEmpty() ? email : !name.isEmpty() ? name : "ChatGPT") + " · アカウント " + account.optInt("label_number", i + 1);
                visible.put(Json.obj("id", account.optString("id"), "label", label, "email", email, "name", name,
                        "connected", connected(account), "planEnabled", planEnabled(account)));
            }
            boolean ready = connected(active);
            String warning = active != null && active.optBoolean("revocation_unconfirmed")
                    ? "端末内のログイン情報を削除しました。サーバー側の失効は確認できていません。ChatGPTの設定で接続を確認してください。" : "";
            return Json.obj("status", ready ? "connected" : "signed_out", "busy", false, "error", warning, "accounts", visible,
                    "activeAccountId", selected, "models", ready ? Json.arr(active, "models") : new JSONArray(),
                    "selectedModel", active == null ? "" : active.optString("selected_model"),
                    "firstConnection", planEnabled(active) && !active.optBoolean("notice_seen"));
        }
    }
    private static JSONObject mergeCredentials(JSONObject old, JSONObject credentials) {
        JSONObject next = Json.copy(old);
        if (!credentials.has("earliest_refresh_at")) next.remove("earliest_refresh_at");
        for (String key : new String[]{"issuer", "subject", "client_id", "ext_agent_host_id", "auth_nonce", "email", "name", "access_token", "refresh_token", "id_token", "token_type", "expires_at", "expires_in", "earliest_refresh_at", "saved_at", "scopes"})
            if (credentials.has(key)) Json.put(next, key, credentials.opt(key));
        Json.put(next, "signing_out", false); Json.put(next, "reauth_required", false);
        if (!planEnabled(next)) Json.put(next, "models", new JSONArray());
        return next;
    }
    private static void validateIdentity(JSONObject credentials) {
        if (!"https://auth.openai.com".equals(credentials.optString("issuer")) || credentials.optString("subject").isEmpty()
                || credentials.optString("client_id").isEmpty() || "dynamic_agent_client".equals(credentials.optString("client_id"))
                || credentials.optString("access_token").isEmpty() && credentials.optString("id_token").isEmpty()) throw new IllegalArgumentException("ChatGPT認証情報が不完全です。");
    }
    private static JSONObject find(JSONObject state, String id) {
        JSONArray accounts = Json.arr(state, "accounts");
        for (int i = 0; i < accounts.length(); i++) { JSONObject account = accounts.optJSONObject(i); if (account != null && id.equals(account.optString("id"))) return account; }
        return null;
    }
    private static JSONObject require(JSONObject state, String id) {
        JSONObject account = find(state, id);
        if (account == null) throw new IllegalArgumentException("ChatGPTアカウントを選んでログインしてください。");
        return account;
    }
    private static void replace(JSONObject state, String id, JSONObject value) {
        JSONArray accounts = Json.arr(state, "accounts"), next = new JSONArray(); boolean found = false;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (id.equals(account.optString("id"))) { next.put(value); found = true; } else next.put(account);
        }
        if (!found) next.put(value); Json.put(state, "accounts", next);
    }
    private JSONObject read() {
        AtomicFile atomic = new AtomicFile(file);
        if (!file.exists() && !new File(file + ".bak").exists()) return Json.obj("schema", 1, "accounts", new JSONArray(), "active_id", "");
        try (FileInputStream in = atomic.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) { if (out.size() > MAX_BYTES - count) throw new Exception("size"); out.write(buffer, 0, count); }
            JSONObject value = new JSONObject(new String(sealer.open(out.toByteArray()), StandardCharsets.UTF_8));
            if (value.optInt("schema") != 1 || !(value.opt("accounts") instanceof JSONArray)) throw new Exception("format");
            return value;
        } catch (Exception e) { throw new IllegalStateException("ChatGPTログイン情報を復元できませんでした。端末のロック設定と保存状態を確認してください。"); }
    }
    private void write(JSONObject value) {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
        try {
            byte[] bytes = sealer.seal(value.toString().getBytes(StandardCharsets.UTF_8));
            if (bytes.length > MAX_BYTES) throw new Exception("size");
            File parent = file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("directory");
            out = atomic.startWrite(); out.write(bytes); atomic.finishWrite(out);
        } catch (Exception e) { if (out != null) atomic.failWrite(out); throw new IllegalStateException("ChatGPTログイン情報を安全に保存できませんでした。端末の空き容量とロック設定を確認してください。"); }
    }
    private static final class KeystoreSealer implements Sealer {
        private SecretKey key() throws Exception {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (!keys.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build()); generator.generateKey();
            }
            return (SecretKey) keys.getKey(KEY_ALIAS, null);
        }
        @Override public byte[] seal(byte[] plain) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain), bytes = new byte[2 + iv.length + encrypted.length];
            bytes[0] = 1; bytes[1] = (byte)iv.length; System.arraycopy(iv, 0, bytes, 2, iv.length); System.arraycopy(encrypted, 0, bytes, 2 + iv.length, encrypted.length); return bytes;
        }
        @Override public byte[] open(byte[] sealed) throws Exception {
            if (sealed.length < 30 || sealed[0] != 1 || (sealed[1] & 255) != 12) throw new Exception("format");
            byte[] iv = java.util.Arrays.copyOfRange(sealed, 2, 14);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return cipher.doFinal(sealed, 14, sealed.length - 14);
        }
    }
}
