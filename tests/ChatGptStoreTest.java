package jp.haruyalab.novelshelf;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exercises the unmodified production ChatGptStore through its injected Sealer boundary.
 * Encryption here is an explicitly test-only SOFTWARE AES-GCM fixture, not AndroidKeyStore.
 * The existing disk-backed Context and AtomicFile contract doubles are test-only as well.
 * This suite does not verify device keystore behavior, Android AtomicFile durability,
 * OAuth identity signatures, browser login, live credentials, or a real provider call.
 */
public final class ChatGptStoreTest {
    private static final String ISSUER = "https://auth.openai.com";
    private static final String EMAIL = "shared-workspace-person@example.invalid";
    private static final String SUBJECT = "fixture-subject-shared-across-workspaces";
    private static final String ACCESS = "fixture-access-token-never-a-real-credential-";
    private static final String REFRESH = "fixture-refresh-token-never-a-real-credential-";
    private static final String ID_TOKEN = "fixture-id-token-never-a-real-credential-";
    private static final String CLIENT = "fixture-issued-client-registration-";
    private static final String REGISTRATION_HOST = "urn:uuid:31a688c4-17dc-4c58-b923-bfc568ee69a1";
    private static final String NONCE = "fixture-authentication-nonce-";
    private static final String SEALER_ERROR = "fixture-sealer-private-diagnostic-never-expose";
    private static final String[] SESSION_FIELDS = {"access_token", "refresh_token", "id_token", "token_type",
            "expires_at", "expires_in", "earliest_refresh_at", "saved_at"};
    private static final Set<String> PUBLIC_ROOT = keys("status", "busy", "error", "accounts", "activeAccountId",
            "models", "selectedModel", "firstConnection");
    private static final Set<String> PUBLIC_ACCOUNT = keys("id", "label", "email", "name", "connected", "planEnabled");
    private static final Set<String> PUBLIC_MODEL = keys("slug", "display_name");
    private static final Set<String> PRIVATE_FIELDS = keys("access_token", "refresh_token", "id_token", "client_id",
            "host_id", "issuer", "subject", "token_type", "expires_at", "expires_in", "earliest_refresh_at",
            "saved_at", "nonce", "code_verifier", "authorization", "accessToken", "refreshToken", "idToken",
            "clientId", "hostId", "revision", "credential_revision", "logout_ticket", "signing_out", "reauth_required", "scopes", "ext_agent_host_id", "auth_nonce");
    private static int checks;
    private static File suiteRoot;

    private interface Operation { void run() throws Exception; }
    private static void yes(boolean actual, String message) { checks++; if (!actual) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static Set<String> objectKeys(JSONObject object) {
        Set<String> result = new HashSet<>(); for (Iterator<String> it = object.keys(); it.hasNext();) result.add(it.next()); return result;
    }
    private static void same(Object actual, Object expected) { eq(canonical(actual), canonical(expected)); }
    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value; List<String> names = new ArrayList<>(objectKeys(object)); Collections.sort(names);
            StringBuilder result = new StringBuilder("{");
            for (String name : names) result.append(JSONObject.quote(name)).append(':').append(canonical(object.opt(name))).append(',');
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value; StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) result.append(canonical(array.opt(i))).append(','); return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String)value);
        if (value instanceof Number) return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private static void bytesSame(byte[] actual, byte[] expected, String message) { yes(Arrays.equals(actual, expected), message); }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("operation should have been rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            checks++; String message = String.valueOf(expected.getMessage());
            for (String marker : new String[]{ACCESS, REFRESH, ID_TOKEN, CLIENT, SUBJECT, NONCE, REGISTRATION_HOST, SEALER_ERROR})
                yes(!message.contains(marker), "public error must not expose a private fixture field");
        }
    }

    public static void main(String[] args) throws Exception {
        suiteRoot = Files.createTempDirectory("tsuzuki-chatgpt-store-jvm-").toFile();
        try {
            emptyAndStableHost(); registrationIdentityAndProjection(); identityRejection();
            refreshCompareAndSwap(); logoutAndReauthorization(); accountSwitchAndAba();
            modelsRemainAnExplicitChoice(); scopesAndWelcome(); concurrentRefresh();
            rotationsSurviveSelection(); rotationsDuringLogout(); credentialGenerationsAndRecovery();
            atomicWriteFailures(); authenticatedEncryptionBoundary();
            System.out.println("ChatGptStore production persistence and account-state logic passed: " + checks);
            System.out.println("Scope: real ChatGptStore/Json/SpeechStore.key; existing disk-backed Context/AtomicFile doubles; injected SOFTWARE AES-GCM fixture Sealer.");
            System.out.println("NOT TESTED: AndroidKeyStore, actual Android AtomicFile durability, OAuth/browser flow, signed identity verification, or provider behavior.");
            System.out.println("Only synthetic accounts and credentials were used. No network request, account login, API key, or inference charge occurred.");
        } finally { remove(suiteRoot); }
    }

    private static void emptyAndStableHost() throws Exception {
        Fixture f = new Fixture("empty-host"); JSONObject empty = f.store.publicState();
        eq(empty.optString("status"), "signed_out"); eq(empty.optString("activeAccountId"), "");
        eq(empty.optBoolean("firstConnection"), false); eq(Json.arr(empty, "accounts").length(), 0);
        eq(Json.arr(empty, "models").length(), 0); eq(f.store.activeId(), "");
        yes(!f.file.exists(), "reading an empty store does not create a credential file");
        rejected(() -> f.store.activeAccount()); rejected(() -> f.store.account("missing"));
        rejected(() -> f.store.selectAccount("missing")); rejected(() -> f.store.usableAccount("missing"));
        String host = f.store.hostId(); validHost(host);
        yes(f.file.isFile(), "host is persisted before it is returned");
        eq(f.store.hostId(), host); eq(f.reopen().hostId(), host); eq(f.reopenWithFreshSealer().hostId(), host);
        eq(f.plain().optString("host_id"), host);
        String a = f.store.saveLogin(credentials("host-a", "1"), "");
        String b = f.store.saveLogin(credentials("host-b", "1"), "");
        f.store.selectAccount(a); eq(f.reopen().hostId(), host);
        JSONObject logout = f.store.beginLogout(a); f.store.clearSession(a, logout.optLong("revision"));
        f.store.selectAccount(b); eq(f.reopen().hostId(), host);
        f.store.saveLogin(credentials("host-a", "2"), a); eq(f.reopen().hostId(), host);
        Fixture other = new Fixture("another-install"); yes(!host.equals(other.store.hostId()), "separate installs have separate hosts");
        publicProjection(f.store.publicState(), host);
    }

    private static void registrationIdentityAndProjection() throws Exception {
        Fixture f = new Fixture("registration-projection"); String host = f.store.hostId();
        JSONObject inputA = credentials("personal", "1"), inputB = credentials("workspace", "1");
        Json.put(inputA, "unrecognized_raw_payload", ACCESS + "must-not-copy");
        Json.put(inputA, "host_id", "untrusted-host-field"); Json.put(inputA, "revision", 9999);
        Json.put(inputA, "models", new JSONArray().put(Json.obj("slug", "untrusted-model")));
        Json.put(inputA, "selected_model", "untrusted-model"); Json.put(inputA, "notice_seen", true);
        String a = f.store.saveLogin(inputA, null), b = f.store.saveLogin(inputB, null);
        yes(!a.equals(b), "same email and subject in different registrations remain separate");
        eq(a, ChatGptStore.identity(inputA)); eq(b, ChatGptStore.identity(inputB)); eq(f.reopen().activeId(), b);
        eq(f.store.account(a).optString("email"), f.store.account(b).optString("email"));
        eq(f.store.account(a).optString("subject"), f.store.account(b).optString("subject"));
        eq(f.store.account(a).optInt("label_number"), 1); eq(f.store.account(b).optInt("label_number"), 2);
        eq(f.store.account(a).optBoolean("notice_seen"), false); eq(f.store.account(a).optString("selected_model"), "");
        eq(Json.arr(f.store.account(a), "models").length(), 0);
        yes(!f.store.account(a).has("unrecognized_raw_payload"), "only credential fields are merged");
        yes(!f.store.account(a).has("host_id"), "server credentials cannot override the install host");
        eq(f.store.hostId(), host);
        JSONObject persistedA = f.store.account(a), persistedB = f.store.account(b);
        Json.put(inputA, "access_token", ACCESS + "caller-mutated"); Json.arr(inputA, "scopes").put("caller-mutated");
        same(f.reopen().account(a), persistedA); same(f.reopen().account(b), persistedB);

        f.store.selectAccount(a); long revision = f.store.account(a).optLong("revision");
        JSONArray incomingModels = models("fixture-one", "fixture-two");
        Json.put(incomingModels.optJSONObject(0), "access_token", ACCESS + "catalog-payload");
        Json.put(incomingModels.optJSONObject(0), "issuer", ISSUER);
        f.store.saveModels(a, revision, incomingModels); f.store.selectModel(a, "fixture-two");
        JSONObject visible = f.store.publicState(); publicProjection(visible, host);
        eq(Json.arr(visible, "accounts").length(), 2); eq(visible.optString("selectedModel"), "fixture-two");
        yes(!Json.arr(visible, "accounts").optJSONObject(0).optString("label").equals(
                Json.arr(visible, "accounts").optJSONObject(1).optString("label")), "same-email account labels stay distinguishable");
        JSONObject snapshot = f.store.account(a); Json.put(f.store.account(a), "access_token", "copy-mutated");
        Json.put(f.store.activeAccount(), "client_id", "copy-mutated");
        Json.arr(f.store.account(a), "scopes").put("copy-mutated");
        Json.put(Json.arr(visible, "models").optJSONObject(0), "slug", "copy-mutated");
        Json.put(Json.arr(visible, "accounts").optJSONObject(0), "connected", false);
        same(f.reopen().account(a), snapshot);

        JSONObject changedEmail = credentials("personal", "2"); Json.put(changedEmail, "email", "updated@example.invalid");
        eq(f.store.saveLogin(changedEmail, a), a); eq(Json.arr(f.store.publicState(), "accounts").length(), 2);
        eq(f.store.account(a).optInt("label_number"), 1); eq(f.store.account(a).optString("selected_model"), "fixture-two");
        eq(f.store.account(a).optString("email"), "updated@example.invalid");
        JSONObject differentSubject = credentials("personal", "3"); Json.put(differentSubject, "subject", SUBJECT + "-second-person");
        String c = f.store.saveLogin(differentSubject, ""); yes(!c.equals(a), "same client with a different verified subject stays separate");
        eq(Json.arr(f.store.publicState(), "accounts").length(), 3); eq(f.store.account(c).optInt("label_number"), 3);
        same(f.reopenWithFreshSealer().publicState(), f.store.publicState());
        publicProjection(f.store.publicState(), host); assertNoPlainCredentials(Files.readAllBytes(f.file.toPath()), host);
        yes(f.sealer.seals > 0 && f.sealer.opens > 0, "production storage actually crosses the injected encryption boundary");
    }

    private static void identityRejection() throws Exception {
        Fixture f = new Fixture("identity-rejection"); f.store.hostId();
        String a = f.store.saveLogin(credentials("expected", "1"), ""); JSONObject before = f.store.account(a);
        byte[] bytes = Files.readAllBytes(f.file.toPath());
        rejected(() -> f.store.saveLogin(credentials("other-workspace", "1"), a));
        JSONObject otherSubject = credentials("expected", "2"); Json.put(otherSubject, "subject", SUBJECT + "-different");
        rejected(() -> f.store.saveLogin(otherSubject, a));
        for (String key : new String[]{"issuer", "subject", "client_id"}) {
            JSONObject bad = credentials("expected", "invalid"); Json.put(bad, key, "");
            rejected(() -> f.store.saveLogin(bad, a));
        }
        JSONObject dynamic = credentials("expected", "invalid"); Json.put(dynamic, "client_id", "dynamic_agent_client");
        rejected(() -> f.store.saveLogin(dynamic, ""));
        JSONObject noTokens = credentials("expected", "invalid"); noTokens.remove("access_token"); noTokens.remove("id_token");
        rejected(() -> f.store.saveLogin(noTokens, a));
        JSONObject fakeIssuer = credentials("expected", "invalid"); Json.put(fakeIssuer, "issuer", "https://auth.openai.com.evil.invalid");
        rejected(() -> f.store.saveLogin(fakeIssuer, ""));
        same(f.store.account(a), before); eq(f.store.activeId(), a);
        bytesSame(Files.readAllBytes(f.file.toPath()), bytes, "rejected identity never rewrites durable credentials");
        JSONObject updated = credentials("expected", "2"); eq(f.store.saveLogin(updated, a), a);
        eq(f.store.account(a).optLong("revision"), before.optLong("revision") + 1L);
        tokenTuple(f.store.account(a), updated); eq(Json.arr(f.store.publicState(), "accounts").length(), 1);
    }

    private static void refreshCompareAndSwap() throws Exception {
        Fixture f = new Fixture("refresh-cas"); String a = f.store.saveLogin(credentials("refresh", "1"), "");
        JSONObject old = f.store.account(a); f.store.saveModels(a, old.optLong("revision"), models("fixture-one", "fixture-two"));
        f.store.selectModel(a, "fixture-two"); f.store.welcomeSeen(a); old = f.store.account(a);
        long revision = old.optLong("revision"); JSONObject changed = credentials("refresh", "2");
        JSONObject saved = f.store.saveRefresh(a, revision, changed);
        eq(saved.optLong("revision"), revision + 1L); tokenTuple(saved, changed);
        eq(saved.optString("selected_model"), "fixture-two"); eq(saved.optBoolean("notice_seen"), true);
        same(Json.arr(saved, "models"), Json.arr(old, "models")); eq(f.store.activeId(), a);
        JSONObject durable = f.reopen().account(a); Json.put(saved, "refresh_token", REFRESH + "caller-mutated"); same(f.store.account(a), durable);
        rejected(() -> f.store.saveRefresh(a, revision, credentials("refresh", "late")));
        long current = f.store.account(a).optLong("revision"); byte[] bytes = Files.readAllBytes(f.file.toPath());
        rejected(() -> f.store.saveRefresh(a, current, credentials("another-registration", "bad")));
        JSONObject badSubject = credentials("refresh", "bad"); Json.put(badSubject, "subject", SUBJECT + "-bad");
        rejected(() -> f.store.saveRefresh(a, current, badSubject));
        same(f.store.account(a), durable); bytesSame(Files.readAllBytes(f.file.toPath()), bytes, "rejected refresh leaves the entire token tuple intact");
        same(f.store.usableAccount(a), durable); publicProjection(f.store.publicState(), "");
    }

    private static void logoutAndReauthorization() throws Exception {
        Fixture f = new Fixture("logout-reauthorize"); String host = f.store.hostId();
        String a = f.store.saveLogin(credentials("logout", "1"), "");
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-one", "fixture-two"));
        f.store.selectModel(a, "fixture-two"); f.store.welcomeSeen(a);
        long pendingRefresh = f.store.account(a).optLong("revision");
        JSONObject logout = f.store.beginLogout(a);
        eq(logout.optLong("revision"), pendingRefresh + 1L); eq(ChatGptStore.connected(logout), false);
        eq(ChatGptStore.planEnabled(logout), false); eq(f.store.publicState().optString("status"), "signed_out");
        tokenTuple(logout, credentials("logout", "1")); rejected(() -> f.store.usableAccount(a));
        rejected(() -> f.store.saveRefresh(a, pendingRefresh, credentials("logout", "stale")));
        f.store.clearSession(a, pendingRefresh); same(f.store.account(a), logout);
        f.store.clearSession(a, logout.optLong("revision")); JSONObject cleared = f.reopen().account(a);
        for (String key : SESSION_FIELDS) yes(!cleared.has(key), "logout removes session field " + key);
        eq(Json.arr(cleared, "scopes").length(), 0); eq(Json.arr(cleared, "models").length(), 0);
        eq(cleared.optBoolean("signing_out"), false); eq(cleared.optBoolean("reauth_required"), true);
        eq(cleared.optLong("revision"), logout.optLong("revision") + 1L);
        eq(cleared.optString("client_id"), CLIENT + "logout"); eq(cleared.optString("issuer"), ISSUER);
        eq(cleared.optString("subject"), SUBJECT); eq(cleared.optString("selected_model"), "fixture-two");
        eq(cleared.optBoolean("notice_seen"), true); eq(f.store.hostId(), host); eq(f.store.activeId(), a);
        eq(Json.arr(f.store.publicState(), "accounts").length(), 1);
        f.store.clearSession(a, logout.optLong("revision")); same(f.store.account(a), cleared);
        rejected(() -> f.store.saveRefresh(a, cleared.optLong("revision"), credentials("logout", "cannot-resurrect")));

        String b = f.store.saveLogin(credentials("other-connected", "1"), "");
        f.store.selectAccount(a); eq(f.store.activeId(), a);
        eq(f.store.publicState().optString("status"), "signed_out"); eq(Json.arr(f.store.publicState(), "models").length(), 0);
        eq(ChatGptStore.connected(f.store.account(b)), true); rejected(() -> f.store.usableAccount(a));
        rejected(() -> f.store.usableAccount(b));
        eq(f.store.saveLogin(credentials("logout", "2"), a), a);
        eq(ChatGptStore.connected(f.store.activeAccount()), true); eq(f.store.publicState().optBoolean("firstConnection"), false);
        eq(f.store.account(a).optString("selected_model"), "fixture-two");
        eq(Json.arr(f.store.account(a), "models").length(), 0);
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-one"));
        eq(f.store.account(a).optString("selected_model"), "fixture-two");

        JSONObject inFlightLogout = f.store.beginLogout(a); JSONObject renewed = credentials("logout", "3");
        rejected(() -> f.store.saveLogin(renewed, a)); rejected(() -> f.store.saveLogin(renewed, ""));
        f.store.clearSession(a, inFlightLogout.optLong("revision"));
        f.store.saveLogin(renewed, a); JSONObject newLogin = f.store.account(a);
        f.store.clearSession(a, inFlightLogout.optLong("revision")); same(f.store.account(a), newLogin);
        tokenTuple(f.store.account(a), renewed); eq(ChatGptStore.connected(f.store.account(a)), true);
        rejected(() -> f.store.saveRefresh(a, inFlightLogout.optLong("revision"), credentials("logout", "too-late")));
        JSONObject logoutBeforeSwitch = f.store.beginLogout(a); f.store.selectAccount(b);
        eq(f.store.account(a).optLong("revision"), logoutBeforeSwitch.optLong("revision"));
        f.store.clearSession(a, logoutBeforeSwitch.optLong("revision"));
        eq(f.store.activeId(), b);
        for (String field : SESSION_FIELDS) yes(!f.store.account(a).has(field), "account switching must not strand logout cleanup: " + field);
        tokenTuple(f.store.account(b), credentials("other-connected", "1"));
        f.store.saveLogin(credentials("logout", "4"), a);
        JSONObject logoutBeforeOtherLogin = f.store.beginLogout(a);
        f.store.saveLogin(credentials("new-login-during-logout", "1"), "");
        f.store.clearSession(a, logoutBeforeOtherLogin.optLong("revision"));
        for (String field : SESSION_FIELDS) yes(!f.store.account(a).has(field), "a different login must not strand logout cleanup: " + field);
        eq(f.store.hostId(), host); publicProjection(f.store.publicState(), host);
    }

    private static void accountSwitchAndAba() throws Exception {
        Fixture f = new Fixture("switch-aba"); String a = f.store.saveLogin(credentials("switch-a", "1"), "");
        String b = f.store.saveLogin(credentials("switch-b", "1"), ""); f.store.selectAccount(a);
        JSONObject capturedA = f.store.account(a); long revisionA = capturedA.optLong("revision");
        f.store.saveModels(a, revisionA, models("fixture-a")); f.store.selectModel(a, "fixture-a");
        f.store.selectAccount(b); JSONObject aAfterSwitch = f.store.account(a), bAfterSwitch = f.store.account(b);
        rejected(() -> f.store.saveRefresh(a, revisionA, credentials("switch-a", "stale-while-b")));
        f.store.saveModels(a, revisionA, models("stale-models")); same(f.store.account(a), aAfterSwitch);
        rejected(() -> f.store.usableAccount(a)); rejected(() -> f.store.selectModel(a, "fixture-a"));
        same(f.store.usableAccount(b), bAfterSwitch); tokenTuple(f.store.account(b), credentials("switch-b", "1"));

        f.store.selectAccount(a); JSONObject afterAba = f.store.account(a);
        rejected(() -> f.store.saveRefresh(a, revisionA, credentials("switch-a", "stale-after-aba")));
        f.store.saveModels(a, revisionA, models("stale-after-aba")); same(f.store.account(a), afterAba);
        eq(f.store.account(a).optString("selected_model"), "fixture-a");
        yes(afterAba.optLong("revision") != revisionA, "switching away and back invalidates an in-flight registration snapshot");
        JSONObject fresh = credentials("switch-a", "fresh"); f.store.saveRefresh(a, afterAba.optLong("revision"), fresh);
        tokenTuple(f.store.account(a), fresh); tokenTuple(f.store.account(b), credentials("switch-b", "1"));
        JSONObject beforeSameSelection = f.store.account(a); f.store.selectAccount(a);
        same(f.store.account(a), beforeSameSelection); // Re-selecting the active registration is not a real account switch.

        long preLogin = f.store.account(a).optLong("revision"); JSONObject relogin = credentials("switch-a", "reauthorized");
        f.store.saveLogin(relogin, a); JSONObject afterLogin = f.store.account(a);
        rejected(() -> f.store.saveRefresh(a, preLogin, credentials("switch-a", "stale-after-reauth")));
        f.store.saveModels(a, preLogin, models("stale-reauth-models")); same(f.store.account(a), afterLogin);
        tokenTuple(afterLogin, relogin); publicProjection(f.store.publicState(), "");
        long beforeOtherLogin = afterLogin.optLong("revision");
        f.store.saveLogin(credentials("switch-new-account", "1"), ""); f.store.selectAccount(a);
        rejected(() -> f.store.saveRefresh(a, beforeOtherLogin, credentials("switch-a", "stale-after-other-login")));
        tokenTuple(f.store.account(a), relogin);
    }

    private static void modelsRemainAnExplicitChoice() throws Exception {
        Fixture f = new Fixture("model-selection"); String a = f.store.saveLogin(credentials("models", "1"), "");
        long revision = f.store.account(a).optLong("revision");
        eq(f.store.publicState().optString("selectedModel"), "");
        JSONArray catalog = models("fixture-server-first", "fixture-user-choice", "fixture-server-third");
        f.store.saveModels(a, revision, catalog); same(Json.arr(f.store.account(a), "models"), catalog);
        eq(f.store.account(a).optString("selected_model"), ""); // Even the first catalog requires the user's explicit choice.
        f.store.selectModel(a, "fixture-user-choice"); JSONObject chosen = f.store.account(a);
        rejected(() -> f.store.selectModel(a, "model-not-in-this-account")); same(f.store.account(a), chosen);
        JSONArray reordered = models("fixture-server-third", "fixture-server-first", "fixture-user-choice");
        f.store.saveModels(a, revision, reordered); same(Json.arr(f.store.account(a), "models"), reordered);
        eq(f.store.account(a).optString("selected_model"), "fixture-user-choice");
        f.store.saveModels(a, revision, models("fixture-server-first"));
        eq(f.store.account(a).optString("selected_model"), "fixture-user-choice");
        eq(ChatGptStore.containsModel(f.store.account(a), "fixture-user-choice"), false);
        eq(f.store.publicState().optString("selectedModel"), "fixture-user-choice");
        f.store.saveModels(a, revision, new JSONArray());
        eq(f.store.account(a).optString("selected_model"), "fixture-user-choice");
        f.store.saveModels(a, revision, models("fixture-new-only"));
        eq(f.store.account(a).optString("selected_model"), "fixture-user-choice");
        f.store.selectModel(a, "fixture-new-only"); eq(f.reopen().account(a).optString("selected_model"), "fixture-new-only");
        JSONObject beforeBad = f.store.account(a); byte[] bytes = Files.readAllBytes(f.file.toPath());
        for (JSONArray bad : new JSONArray[]{new JSONArray().put("not-an-object"), new JSONArray().put(new JSONObject()),
                models("space in slug"), models("/leading-slash"), models("_leading-underscore"), models("fixture?query"), models(repeat('x', 129))})
            rejected(() -> f.store.saveModels(a, revision, bad));
        same(f.store.account(a), beforeBad); bytesSame(Files.readAllBytes(f.file.toPath()), bytes, "invalid catalogs do not erase the previous selection");
        f.store.saveModels(a, revision, models("fixture/family:model-v1", repeat('x', 128)));
        f.store.selectModel(a, "fixture/family:model-v1"); eq(f.store.account(a).optString("selected_model"), "fixture/family:model-v1");
        f.store.selectModel(a, repeat('x', 128)); eq(f.store.account(a).optString("selected_model"), repeat('x', 128));
        f.store.saveModels(a, revision, models("fixture-new-only")); f.store.selectModel(a, "fixture-new-only");

        JSONArray metadata = new JSONArray().put(Json.obj("slug", "fixture-no-label", "raw", ACCESS + "catalog"))
                .put(Json.obj("slug", "fixture-long-label", "display_name", repeat('名', 200), "client_id", CLIENT + "catalog"));
        f.store.saveModels(a, revision, metadata); JSONArray sanitized = Json.arr(f.store.account(a), "models");
        eq(sanitized.optJSONObject(0).optString("display_name"), "fixture-no-label");
        eq(sanitized.optJSONObject(1).optString("display_name").length(), 160);
        for (int i = 0; i < sanitized.length(); i++) eq(objectKeys(sanitized.optJSONObject(i)), PUBLIC_MODEL);
        Json.put(metadata.optJSONObject(0), "slug", "caller-mutated");
        eq(Json.arr(f.store.account(a), "models").optJSONObject(0).optString("slug"), "fixture-no-label");
        eq(f.store.account(a).optString("selected_model"), "fixture-new-only");
        f.store.saveRefresh(a, revision, credentials("models", "2")); JSONObject refreshed = f.store.account(a);
        f.store.saveModels(a, revision, models("late-pre-refresh-catalog")); same(f.store.account(a), refreshed);
        publicProjection(f.store.publicState(), "");
    }

    private static void scopesAndWelcome() throws Exception {
        Fixture f = new Fixture("scopes-welcome"); String a = f.store.saveLogin(credentials("welcome-a", "1"), "");
        eq(ChatGptStore.planEnabled(f.store.account(a)), true); eq(f.store.publicState().optBoolean("firstConnection"), true);
        String b = f.store.saveLogin(credentials("welcome-b", "1"), "");
        eq(f.store.publicState().optBoolean("firstConnection"), true);
        f.store.welcomeSeen(a); eq(f.store.account(a).optBoolean("notice_seen"), false);
        f.store.welcomeSeen(b); eq(f.reopen().publicState().optBoolean("firstConnection"), false);
        f.store.selectAccount(a); eq(f.store.publicState().optBoolean("firstConnection"), true); f.store.welcomeSeen(a);
        f.store.saveLogin(credentials("welcome-a", "2"), a); eq(f.store.publicState().optBoolean("firstConnection"), false);
        f.store.selectAccount(b); eq(f.store.publicState().optBoolean("firstConnection"), false);
        f.store.selectAccount(a);
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-persistent-choice"));
        f.store.selectModel(a, "fixture-persistent-choice");
        JSONArray[] insufficientScopes = {new JSONArray(), new JSONArray().put("openid").put("email"),
                new JSONArray().put("resource.invoke"), new JSONArray().put("chatgpt.tokens.use.direct"),
                new JSONArray().put("chatgpt.tokens.use.direct").put("resource.invoke.similar")};
        for (int i = 0; i < insufficientScopes.length; i++) {
            JSONObject grant = credentials("welcome-a", "limited-" + i); Json.put(grant, "scopes", insufficientScopes[i]);
            long revision = f.store.account(a).optLong("revision"); f.store.saveRefresh(a, revision, grant);
            JSONObject account = f.store.account(a); same(Json.arr(account, "scopes"), insufficientScopes[i]);
            eq(ChatGptStore.connected(account), true); eq(ChatGptStore.planEnabled(account), false);
            eq(Json.arr(account, "models").length(), 0); eq(account.optString("selected_model"), "fixture-persistent-choice");
            eq(f.store.publicState().optString("status"), "connected"); eq(f.store.publicState().optBoolean("firstConnection"), false);
            rejected(() -> f.store.usableAccount(a)); rejected(() -> f.store.selectModel(a, "fixture-persistent-choice"));
            f.store.saveModels(a, revision, models("stale-authorized-catalog")); eq(Json.arr(f.store.account(a), "models").length(), 0);
            f.store.saveModels(a, account.optLong("revision"), models("not-authorized-current-catalog"));
            eq(Json.arr(f.store.account(a), "models").length(), 0);
        }
        long revision = f.store.account(a).optLong("revision"); f.store.saveRefresh(a, revision, credentials("welcome-a", "grant-restored"));
        eq(ChatGptStore.planEnabled(f.store.account(a)), true); eq(f.store.publicState().optBoolean("firstConnection"), false);
        eq(Json.arr(f.store.account(a), "models").length(), 0); eq(f.store.account(a).optString("selected_model"), "fixture-persistent-choice");
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-other"));
        eq(f.store.account(a).optString("selected_model"), "fixture-persistent-choice");

        JSONObject identityOnly = credentials("identity-only", "1");
        Json.put(identityOnly, "access_token", ""); Json.put(identityOnly, "refresh_token", "");
        Json.put(identityOnly, "scopes", new JSONArray().put("openid").put("email").put("profile"));
        String c = f.store.saveLogin(identityOnly, ""); eq(f.store.publicState().optBoolean("firstConnection"), false);
        eq(ChatGptStore.connected(f.store.account(c)), true); eq(ChatGptStore.planEnabled(f.store.account(c)), false);
        eq(f.store.publicState().optString("status"), "connected"); rejected(() -> f.store.usableAccount(c));
        eq(f.store.account(c).optString("id_token"), identityOnly.optString("id_token"));
        f.store.saveModels(c, f.store.account(c).optLong("revision"), models("identity-only-no-inference"));
        eq(Json.arr(f.store.account(c), "models").length(), 0);
        f.store.welcomeSeen(c); eq(f.store.account(c).optBoolean("notice_seen"), false);
        f.store.saveLogin(credentials("identity-only", "granted"), c);
        eq(f.store.publicState().optBoolean("firstConnection"), true); f.store.welcomeSeen(c);
        eq(f.reopen().publicState().optBoolean("firstConnection"), false);
        JSONObject logout = f.store.beginLogout(c); f.store.clearSession(c, logout.optLong("revision"));
        f.store.saveLogin(credentials("identity-only", "reconnected"), c);
        eq(f.store.publicState().optBoolean("firstConnection"), false);
        f.store.selectAccount(a); eq(f.store.publicState().optBoolean("firstConnection"), false);
        publicProjection(f.store.publicState(), "");
    }

    private static void concurrentRefresh() throws Exception {
        Fixture f = new Fixture("concurrent-refresh"); String a = f.store.saveLogin(credentials("race", "initial"), "");
        long revision = f.store.account(a).optLong("revision");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(), conflicts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            final String version = "winner-candidate-" + i;
            threads[i] = new Thread(() -> {
                try {
                    ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture start timeout");
                    try { f.reopen().saveRefresh(a, revision, credentials("race", version)); successes.incrementAndGet(); }
                    catch (IllegalStateException expected) { conflicts.incrementAndGet(); }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "chatgpt-store-refresh-fixture-" + i);
            threads[i].setDaemon(true); threads[i].start();
        }
        yes(ready.await(5, TimeUnit.SECONDS), "both refreshes reached the start barrier"); start.countDown();
        for (Thread thread : threads) { thread.join(5000); yes(!thread.isAlive(), "refresh fixture finishes"); }
        if (failure.get() != null) throw new AssertionError("concurrent refresh fixture failed", failure.get());
        eq(successes.get(), 1); eq(conflicts.get(), 1);
        JSONObject result = f.reopen().account(a); eq(result.optLong("revision"), revision + 1L);
        String winner = result.optString("access_token").endsWith("winner-candidate-0") ? "winner-candidate-0" : "winner-candidate-1";
        tokenTuple(result, credentials("race", winner));
        // CAS protects the entire access/refresh/identity-token tuple, not just one field.
        publicProjection(f.store.publicState(), "");
    }

    private static void rotationsSurviveSelection() throws Exception {
        Fixture f = new Fixture("rotation-selection"); String a = f.store.saveLogin(credentials("rotation-a", "0"), "");
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-chosen", "fixture-other"));
        f.store.selectModel(a, "fixture-chosen"); f.store.welcomeSeen(a);
        JSONObject requestSnapshot = f.store.account(a); long generation = ChatGptStore.credentialRevision(requestSnapshot);
        eq(generation, 1L);
        String b = f.store.saveLogin(credentials("rotation-b", "0"), ""); JSONObject bBefore = f.store.account(b);
        eq(ChatGptStore.credentialRevision(f.store.account(a)), generation);
        JSONObject rotated = credentials("rotation-a", "1");
        JSONObject committed = f.store.saveRotation(a, generation, rotated);
        eq(f.store.activeId(), b); tokenTuple(committed, rotated); same(f.store.account(b), bBefore);
        eq(ChatGptStore.credentialRevision(committed), generation + 1L);
        eq(committed.optString("selected_model"), "fixture-chosen"); eq(committed.optBoolean("notice_seen"), true);
        same(Json.arr(committed, "models"), Json.arr(requestSnapshot, "models"));
        eq(committed.optBoolean("signing_out"), false); eq(committed.optBoolean("reauth_required"), false);
        JSONObject durable = f.store.account(a); Json.put(committed, "access_token", ACCESS + "mutated-return"); same(f.store.account(a), durable);
        rejected(() -> f.store.saveRotation(a, generation, credentials("rotation-a", "late-duplicate")));
        rejected(() -> f.store.usableAccount(a));

        f.store.selectAccount(a); JSONObject beforeAba = f.store.account(a);
        long credentialBeforeAba = ChatGptStore.credentialRevision(beforeAba);
        f.store.selectAccount(b); f.store.selectAccount(a);
        eq(ChatGptStore.credentialRevision(f.store.account(a)), credentialBeforeAba);
        rejected(() -> f.store.saveRefresh(a, beforeAba.optLong("revision"), credentials("rotation-a", "old-selection-cas")));
        JSONObject afterAba = f.store.saveRotation(a, credentialBeforeAba, credentials("rotation-a", "2"));
        tokenTuple(afterAba, credentials("rotation-a", "2")); eq(f.store.activeId(), a);
        eq(afterAba.optString("selected_model"), "fixture-chosen");
        f.store.saveModels(a, beforeAba.optLong("revision"), models("old-catalog")); same(f.store.account(a), afterAba);
        long currentGeneration = ChatGptStore.credentialRevision(afterAba);
        rejected(() -> f.store.saveRotation(a, currentGeneration, credentials("another-identity", "wrong")));
        same(f.store.account(a), afterAba);

        byte[] bytes = Files.readAllBytes(f.file.toPath()); AtomicFile.failNextWrite(f.file, 21);
        rejected(() -> f.store.saveRotation(a, currentGeneration, credentials("rotation-a", "failed-disk-write")));
        same(f.reopen().account(a), afterAba);
        bytesSame(Files.readAllBytes(f.file.toPath()), bytes, "rotation does not commit either generation after a failed encrypted write");
        JSONObject newLogin = credentials("rotation-a", "new-login"); f.store.saveLogin(newLogin, a);
        eq(ChatGptStore.credentialRevision(f.store.account(a)), currentGeneration + 1L);
        rejected(() -> f.store.saveRotation(a, currentGeneration, credentials("rotation-a", "late-before-reauth")));
        tokenTuple(f.store.account(a), newLogin);

        long grantGeneration = ChatGptStore.credentialRevision(f.store.account(a)); f.store.selectAccount(b);
        JSONObject revokedGrant = credentials("rotation-a", "identity-only");
        Json.put(revokedGrant, "scopes", new JSONArray().put("openid"));
        Json.put(revokedGrant, "access_token", ""); Json.put(revokedGrant, "refresh_token", "");
        JSONObject withoutGrant = f.store.saveRotation(a, grantGeneration, revokedGrant);
        eq(f.store.activeId(), b); eq(ChatGptStore.connected(withoutGrant), true); eq(ChatGptStore.planEnabled(withoutGrant), false);
        eq(Json.arr(withoutGrant, "models").length(), 0); eq(withoutGrant.optString("selected_model"), "fixture-chosen");
        eq(withoutGrant.optBoolean("notice_seen"), true); tokenTuple(f.store.account(b), credentials("rotation-b", "0"));
        f.store.clearSession(a, withoutGrant.optLong("revision")); JSONObject cleared = f.store.account(a);
        rejected(() -> f.store.saveRotation(a, ChatGptStore.credentialRevision(cleared), credentials("rotation-a", "cannot-resurrect")));
        same(f.store.account(a), cleared); eq(f.store.activeId(), b); publicProjection(f.store.publicState(), "");
    }

    private static void rotationsDuringLogout() throws Exception {
        Fixture f = new Fixture("rotation-logout"); String a = f.store.saveLogin(credentials("logout-rotation", "0"), "");
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-locked-choice"));
        f.store.selectModel(a, "fixture-locked-choice"); f.store.welcomeSeen(a);
        String b = f.store.saveLogin(credentials("logout-rotation-b", "0"), ""); f.store.selectAccount(a);
        long refreshGeneration = ChatGptStore.credentialRevision(f.store.account(a));
        JSONObject logout = f.store.beginLogout(a); String ticket = logout.optString("logout_ticket");
        eq(UUID.fromString(ticket).version(), 4); eq(ChatGptStore.credentialRevision(logout), refreshGeneration);
        rejected(() -> f.store.saveLogin(credentials("logout-rotation", "overwrite-from-explicit-login"), a));
        rejected(() -> f.store.saveLogin(credentials("logout-rotation", "overwrite-from-dynamic-login"), ""));
        JSONObject response = credentials("logout-rotation", "1");
        Json.put(response, "signing_out", false); Json.put(response, "reauth_required", false);
        Json.put(response, "logout_ticket", "untrusted-ticket"); Json.put(response, "selected_model", "untrusted-model");
        Json.put(response, "notice_seen", false); Json.put(response, "credential_revision", 99999L);
        JSONObject rotatedDuringLogout = f.store.saveRotation(a, refreshGeneration, response);
        tokenTuple(rotatedDuringLogout, response); eq(rotatedDuringLogout.optBoolean("signing_out"), true);
        eq(rotatedDuringLogout.optBoolean("reauth_required"), false); eq(rotatedDuringLogout.optString("logout_ticket"), ticket);
        eq(ChatGptStore.connected(rotatedDuringLogout), false); eq(ChatGptStore.planEnabled(rotatedDuringLogout), false);
        eq(rotatedDuringLogout.optString("selected_model"), "fixture-locked-choice"); eq(rotatedDuringLogout.optBoolean("notice_seen"), true);
        eq(ChatGptStore.credentialRevision(rotatedDuringLogout), refreshGeneration + 1L);
        eq(rotatedDuringLogout.optLong("revision"), logout.optLong("revision") + 1L);
        yes(!f.store.publicState().toString().contains(ticket), "logout tickets never cross the public state boundary");
        f.store.selectAccount(b); eq(f.store.account(a).optLong("revision"), rotatedDuringLogout.optLong("revision"));
        f.store.clearSession(a, logout.optLong("revision")); same(f.store.account(a), rotatedDuringLogout);
        JSONObject freshRevokeSnapshot = f.store.account(a);
        eq(freshRevokeSnapshot.optString("refresh_token"), REFRESH + "logout-rotation-1");
        eq(freshRevokeSnapshot.optString("logout_ticket"), ticket);
        f.store.clearSession(a, freshRevokeSnapshot.optLong("revision")); JSONObject cleared = f.store.account(a);
        for (String field : SESSION_FIELDS) yes(!cleared.has(field), "clearing the latest rotated session removes " + field);
        yes(!cleared.has("logout_ticket") && !cleared.has("auth_nonce"), "completed logout removes operation ticket and authentication nonce");
        eq(ChatGptStore.credentialRevision(cleared), refreshGeneration + 2L); eq(f.store.activeId(), b);
        rejected(() -> f.store.saveRotation(a, refreshGeneration + 1L, credentials("logout-rotation", "after-clear")));
        tokenTuple(f.store.account(b), credentials("logout-rotation-b", "0"));

        f.store.saveLogin(credentials("logout-rotation", "new-login"), a);
        JSONObject firstLogout = f.store.beginLogout(a), secondLogout = f.store.beginLogout(a);
        yes(!firstLogout.optString("logout_ticket").equals(secondLogout.optString("logout_ticket")), "each logout request has its own operation identity");
        eq(ChatGptStore.credentialRevision(firstLogout), ChatGptStore.credentialRevision(secondLogout));
        JSONObject latest = f.store.saveRotation(a, ChatGptStore.credentialRevision(secondLogout), credentials("logout-rotation", "late-rotation"));
        eq(latest.optString("logout_ticket"), secondLogout.optString("logout_ticket"));
        f.store.clearSession(a, firstLogout.optLong("revision")); f.store.clearSession(a, secondLogout.optLong("revision"));
        same(f.store.account(a), latest);
        f.store.clearSession(a, latest.optLong("revision"));
        yes(!f.store.account(a).has("logout_ticket"), "only the latest successful cleanup removes the ticket");
        publicProjection(f.store.publicState(), "");
    }

    private static void credentialGenerationsAndRecovery() throws Exception {
        Fixture legacy = new Fixture("credential-generation-migration");
        String a = legacy.store.saveLogin(credentials("legacy-generation-a", "0"), "");
        String b = legacy.store.saveLogin(credentials("legacy-generation-b", "0"), ""); legacy.store.selectAccount(a);
        JSONObject state = legacy.plain(); JSONArray registrations = Json.arr(state, "accounts");
        for (int i = 0; i < registrations.length(); i++) registrations.optJSONObject(i).remove("credential_revision");
        Files.write(legacy.file.toPath(), legacy.sealer.seal(state.toString().getBytes(StandardCharsets.UTF_8)));
        long aLegacyRevision = legacy.store.account(a).optLong("revision");
        eq(ChatGptStore.credentialRevision(legacy.store.account(a)), aLegacyRevision);
        legacy.store.selectAccount(b); legacy.store.selectAccount(a);
        eq(ChatGptStore.credentialRevision(legacy.store.account(a)), aLegacyRevision);
        JSONObject migrated = legacy.store.saveRotation(a, aLegacyRevision, credentials("legacy-generation-a", "1"));
        eq(migrated.optLong("credential_revision"), aLegacyRevision + 1L);
        long beforeRefresh = ChatGptStore.credentialRevision(migrated);
        legacy.store.saveRefresh(a, migrated.optLong("revision"), credentials("legacy-generation-a", "2"));
        eq(ChatGptStore.credentialRevision(legacy.store.account(a)), beforeRefresh + 1L);
        JSONObject reset = legacy.plain(); JSONArray resetAccounts = Json.arr(reset, "accounts");
        for (int i = 0; i < resetAccounts.length(); i++) if (b.equals(resetAccounts.optJSONObject(i).optString("id"))) resetAccounts.optJSONObject(i).remove("credential_revision");
        Files.write(legacy.file.toPath(), legacy.sealer.seal(reset.toString().getBytes(StandardCharsets.UTF_8)));
        long bLegacyRevision = legacy.store.account(b).optLong("revision");
        JSONObject legacyLogout = legacy.store.beginLogout(b); eq(ChatGptStore.credentialRevision(legacyLogout), bLegacyRevision);
        legacy.store.clearSession(b, legacyLogout.optLong("revision"));
        eq(ChatGptStore.credentialRevision(legacy.store.account(b)), bLegacyRevision + 1L);

        Fixture recovery = new Fixture("interrupted-logout-recovery"); String host = recovery.store.hostId();
        String ra = recovery.store.saveLogin(credentials("recover-a", "0"), "");
        recovery.store.saveModels(ra, recovery.store.account(ra).optLong("revision"), models("fixture-user-choice"));
        recovery.store.selectModel(ra, "fixture-user-choice"); recovery.store.welcomeSeen(ra);
        String rb = recovery.store.saveLogin(credentials("recover-b", "0"), "");
        String rc = recovery.store.saveLogin(credentials("recover-c", "0"), "");
        recovery.store.beginLogout(ra); recovery.store.beginLogout(rc);
        JSONObject beforeA = recovery.store.account(ra), beforeB = recovery.store.account(rb), beforeC = recovery.store.account(rc);
        byte[] bytes = Files.readAllBytes(recovery.file.toPath()); AtomicFile.failNextFinish(recovery.file);
        rejected(() -> recovery.reopen().recoverInterruptedLogouts());
        same(recovery.store.account(ra), beforeA); same(recovery.store.account(rb), beforeB); same(recovery.store.account(rc), beforeC);
        bytesSame(Files.readAllBytes(recovery.file.toPath()), bytes, "failed startup recovery never partially clears registrations");
        eq(recovery.reopen().recoverInterruptedLogouts(), true);
        for (String id : new String[]{ra, rc}) {
            JSONObject cleared = recovery.store.account(id), before = ra.equals(id) ? beforeA : beforeC;
            for (String field : SESSION_FIELDS) yes(!cleared.has(field), "process restart clears interrupted logout field " + field);
            yes(!cleared.has("logout_ticket") && !cleared.has("auth_nonce"), "process restart discards old logout ticket and nonce");
            eq(cleared.optBoolean("signing_out"), false); eq(cleared.optBoolean("reauth_required"), true);
            eq(ChatGptStore.credentialRevision(cleared), ChatGptStore.credentialRevision(before) + 1L);
            rejected(() -> recovery.store.saveRotation(id, ChatGptStore.credentialRevision(before), credentials(ra.equals(id) ? "recover-a" : "recover-c", "too-late")));
        }
        same(recovery.store.account(rb), beforeB); eq(recovery.store.activeId(), rc); eq(recovery.store.hostId(), host);
        eq(recovery.store.account(ra).optString("selected_model"), "fixture-user-choice"); eq(recovery.store.account(ra).optBoolean("notice_seen"), true);
        byte[] recoveredBytes = Files.readAllBytes(recovery.file.toPath()); eq(recovery.reopen().recoverInterruptedLogouts(), false);
        bytesSame(Files.readAllBytes(recovery.file.toPath()), recoveredBytes, "successful startup recovery is idempotent without another write");
        publicProjection(recovery.store.publicState(), host);
    }

    private static void atomicWriteFailures() throws Exception {
        Fixture f = new Fixture("atomic-failures"); String host = f.store.hostId();
        String a = f.store.saveLogin(credentials("atomic", "1"), "");
        f.store.saveModels(a, f.store.account(a).optLong("revision"), models("fixture-kept"));
        JSONObject baseline = f.store.account(a); byte[] baselineBytes = Files.readAllBytes(f.file.toPath());
        AtomicFile.failNextStart(f.file); rejected(() -> f.store.saveLogin(credentials("uncommitted", "1"), ""));
        same(f.reopen().account(a), baseline); eq(f.store.activeId(), a); eq(Json.arr(f.store.publicState(), "accounts").length(), 1);
        bytesSame(Files.readAllBytes(f.file.toPath()), baselineBytes, "start failure leaves the previous encrypted file intact");
        AtomicFile.failNextWrite(f.file, 19);
        rejected(() -> f.store.saveRefresh(a, baseline.optLong("revision"), credentials("atomic", "uncommitted")));
        same(f.reopen().account(a), baseline);
        bytesSame(Files.readAllBytes(f.file.toPath()), baselineBytes, "partial token rotation never commits half of a credential set");
        yes(!new File(f.file + ".new").exists(), "failed partial write removes the pending ciphertext");
        AtomicFile.failNextFinish(f.file); rejected(() -> f.store.beginLogout(a)); same(f.reopen().account(a), baseline);
        bytesSame(Files.readAllBytes(f.file.toPath()), baselineBytes, "finish failure does not fake a persisted logout");

        f.sealer.failSeal = true; rejected(() -> f.store.saveModels(a, baseline.optLong("revision"), models("uncommitted")));
        f.sealer.failSeal = false; same(f.reopen().account(a), baseline);
        bytesSame(Files.readAllBytes(f.file.toPath()), baselineBytes, "encryption failure never replaces existing storage");
        JSONObject logout = f.store.beginLogout(a); byte[] logoutBytes = Files.readAllBytes(f.file.toPath());
        AtomicFile.failNextWrite(f.file, 3); rejected(() -> f.store.clearSession(a, logout.optLong("revision")));
        same(f.reopen().account(a), logout);
        bytesSame(Files.readAllBytes(f.file.toPath()), logoutBytes, "failed session clearing remains an explicit recoverable persistence error");
        f.store.clearSession(a, logout.optLong("revision"));
        for (String field : SESSION_FIELDS) yes(!f.reopen().account(a).has(field), "retried clear removes " + field);
        eq(f.reopen().hostId(), host); eq(Json.arr(f.store.publicState(), "accounts").length(), 1);

        Fixture newInstall = new Fixture("host-write-failure"); AtomicFile.failNextStart(newInstall.file);
        rejected(() -> newInstall.store.hostId()); yes(!newInstall.file.exists(), "a host is not claimed as saved after a failed initial write");
        String savedHost = newInstall.store.hostId(); validHost(savedHost); eq(newInstall.reopen().hostId(), savedHost);
    }

    private static void authenticatedEncryptionBoundary() throws Exception {
        Fixture f = new Fixture("encryption-boundary"); String host = f.store.hostId();
        String a = f.store.saveLogin(credentials("sealed", "1"), ""); JSONObject expected = f.store.account(a);
        byte[] encrypted = Files.readAllBytes(f.file.toPath());
        assertNoPlainCredentials(encrypted, host); same(f.reopenWithFreshSealer().account(a), expected);
        JSONObject plain = f.plain(); eq(plain.optInt("schema"), 1); eq(plain.optString("host_id"), host);
        tokenTuple(Json.arr(plain, "accounts").optJSONObject(0), credentials("sealed", "1"));
        byte[] fixturePlain = plain.toString().getBytes(StandardCharsets.UTF_8);
        byte[] first = f.sealer.seal(fixturePlain), second = f.sealer.seal(fixturePlain);
        yes(!Arrays.equals(first, second), "the test sealer uses a fresh IV for each software encryption");
        bytesSame(f.sealer.open(first), fixturePlain, "fixture software encryption roundtrip");
        // A separate Sealer with the same fixture key tests disk roundtrip, not AndroidKeyStore key retrieval.
        f.sealer.failOpen = true; rejected(() -> f.store.publicState()); f.sealer.failOpen = false;
        bytesSame(Files.readAllBytes(f.file.toPath()), encrypted, "decryption failure never overwrites unreadable registration data");
        ChatGptStore wrongKey = new ChatGptStore(f.file, new SoftwareAesGcmFixtureSealer()); rejected(() -> wrongKey.activeAccount());
        bytesSame(Files.readAllBytes(f.file.toPath()), encrypted, "wrong fixture key cannot reset stored accounts");
        byte[] tampered = encrypted.clone(); tampered[tampered.length - 1] ^= 1;
        Files.write(f.file.toPath(), tampered); rejected(() -> f.store.hostId()); rejected(() -> f.store.saveLogin(credentials("unreadable", "1"), ""));
        bytesSame(Files.readAllBytes(f.file.toPath()), tampered, "authentication-tag failure is surfaced instead of being treated as an empty store");
        Files.write(f.file.toPath(), encrypted); same(f.reopen().account(a), expected); eq(f.reopen().hostId(), host);
        byte[] truncated = Arrays.copyOf(encrypted, 9); Files.write(f.file.toPath(), truncated); rejected(() -> f.store.publicState());
        bytesSame(Files.readAllBytes(f.file.toPath()), truncated, "truncated ciphertext stays available for recovery");
        Files.write(f.file.toPath(), f.sealer.seal(Json.obj("schema", 999, "accounts", new JSONArray()).toString().getBytes(StandardCharsets.UTF_8)));
        rejected(() -> f.store.publicState()); Files.write(f.file.toPath(), encrypted);
        File backup = new File(f.file + ".bak"); Files.write(backup.toPath(), encrypted);
        Files.write(f.file.toPath(), truncated); same(f.reopen().account(a), expected);
        yes(!backup.exists(), "the existing AtomicFile double restores its backup contract");
        bytesSame(Files.readAllBytes(f.file.toPath()), encrypted, "backup recovery returns the complete encrypted registration set");
        publicProjection(f.store.publicState(), host);
    }

    private static JSONObject credentials(String registration, String version) {
        return Json.obj("issuer", ISSUER, "subject", SUBJECT, "client_id", CLIENT + registration,
                "ext_agent_host_id", REGISTRATION_HOST, "auth_nonce", NONCE + registration + "-" + version,
                "email", EMAIL, "name", "検査用 利用者", "access_token", ACCESS + registration + "-" + version,
                "refresh_token", REFRESH + registration + "-" + version, "id_token", ID_TOKEN + registration + "-" + version,
                "token_type", "Bearer", "expires_at", 4000000000L, "expires_in", 3600L,
                "earliest_refresh_at", 3999999900L, "saved_at", 3999996400L,
                "scopes", new JSONArray().put("openid").put("email").put("profile").put("offline_access")
                        .put("resource.invoke").put("chatgpt.tokens.use.direct"));
    }
    private static JSONArray models(String... slugs) {
        JSONArray result = new JSONArray(); for (String slug : slugs) result.put(Json.obj("slug", slug, "display_name", "検査 " + slug)); return result;
    }
    private static void tokenTuple(JSONObject actual, JSONObject expected) {
        for (String field : SESSION_FIELDS) same(actual.opt(field), expected.opt(field));
        for (String field : new String[]{"issuer", "subject", "client_id", "ext_agent_host_id", "auth_nonce"}) eq(actual.optString(field), expected.optString(field));
    }
    private static void validHost(String host) {
        yes(host.startsWith("urn:uuid:"), "install host uses the required urn:uuid prefix");
        UUID uuid = UUID.fromString(host.substring("urn:uuid:".length())); eq(uuid.version(), 4); eq(uuid.variant(), 2);
    }
    private static void publicProjection(JSONObject value, String host) {
        eq(objectKeys(value), PUBLIC_ROOT);
        JSONArray accounts = Json.arr(value, "accounts");
        for (int i = 0; i < accounts.length(); i++) eq(objectKeys(accounts.optJSONObject(i)), PUBLIC_ACCOUNT);
        JSONArray models = Json.arr(value, "models");
        for (int i = 0; i < models.length(); i++) eq(objectKeys(models.optJSONObject(i)), PUBLIC_MODEL);
        noPrivateKeys(value); String serialized = value.toString();
        for (String marker : new String[]{ACCESS, REFRESH, ID_TOKEN, CLIENT, SUBJECT, ISSUER, REGISTRATION_HOST, NONCE})
            yes(!serialized.contains(marker), "public projection must not disclose raw credentials or identity-registration internals");
        if (!host.isEmpty()) yes(!serialized.contains(host), "public projection must not disclose the install host");
    }
    private static void noPrivateKeys(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            for (String key : objectKeys(object)) { yes(!PRIVATE_FIELDS.contains(key), "private native field leaked to public state: " + key); noPrivateKeys(object.opt(key)); }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value; for (int i = 0; i < array.length(); i++) noPrivateKeys(array.opt(i));
        }
    }
    private static void assertNoPlainCredentials(byte[] ciphertext, String host) {
        String bytes = new String(ciphertext, StandardCharsets.ISO_8859_1);
        for (String marker : new String[]{ACCESS, REFRESH, ID_TOKEN, CLIENT, SUBJECT, ISSUER, REGISTRATION_HOST, NONCE, EMAIL, "\"access_token\"", "\"accounts\""})
            yes(!bytes.contains(marker), "credential storage must pass through the fixture encryption boundary");
        if (!host.isEmpty()) yes(!bytes.contains(host), "the install host is encrypted with the registration store");
    }
    private static String repeat(char character, int count) { char[] result = new char[count]; Arrays.fill(result, character); return new String(result); }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IllegalStateException("cannot remove test fixture");
    }

    private static final class Fixture {
        final File root, file; final SoftwareAesGcmFixtureSealer sealer; final ChatGptStore store;
        Fixture(String name) {
            root = new File(suiteRoot, name); SpeechStoreTestContext context = new SpeechStoreTestContext(root);
            file = new File(context.getFilesDir(), "chatgpt-accounts.enc"); sealer = new SoftwareAesGcmFixtureSealer();
            store = new ChatGptStore(file, sealer);
        }
        ChatGptStore reopen() { return new ChatGptStore(new File(new SpeechStoreTestContext(root).getFilesDir(), "chatgpt-accounts.enc"), sealer); }
        ChatGptStore reopenWithFreshSealer() { return new ChatGptStore(file, new SoftwareAesGcmFixtureSealer(sealer.keyBytes)); }
        JSONObject plain() throws Exception { return new JSONObject(new String(sealer.open(Files.readAllBytes(file.toPath())), StandardCharsets.UTF_8)); }
    }

    /**
     * TEST FIXTURE ONLY: JCA software AES-GCM with a transient, process-local test key.
     * Its envelope intentionally differs from the production AndroidKeyStore Sealer.
     * Passing these tests makes NO claim that the Android keystore was executed or verified.
     */
    private static final class SoftwareAesGcmFixtureSealer implements ChatGptStore.Sealer {
        private static final byte[] HEADER = "TEST-ONLY-AES-GCM".getBytes(StandardCharsets.US_ASCII);
        private final byte[] keyBytes;
        private final SecureRandom random = new SecureRandom();
        volatile boolean failSeal, failOpen;
        int seals, opens;
        SoftwareAesGcmFixtureSealer() { keyBytes = new byte[32]; random.nextBytes(keyBytes); }
        SoftwareAesGcmFixtureSealer(byte[] key) { keyBytes = key.clone(); }
        @Override public byte[] seal(byte[] plain) throws Exception {
            seals++; if (failSeal) throw new IllegalStateException(SEALER_ERROR + ACCESS);
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(HEADER); byte[] encrypted = cipher.doFinal(plain);
            byte[] result = new byte[HEADER.length + iv.length + encrypted.length];
            System.arraycopy(HEADER, 0, result, 0, HEADER.length); System.arraycopy(iv, 0, result, HEADER.length, iv.length);
            System.arraycopy(encrypted, 0, result, HEADER.length + iv.length, encrypted.length); return result;
        }
        @Override public byte[] open(byte[] sealed) throws Exception {
            opens++; if (failOpen) throw new IllegalStateException(SEALER_ERROR + REFRESH);
            if (sealed.length < HEADER.length + 12 + 16 || !Arrays.equals(HEADER, Arrays.copyOf(sealed, HEADER.length)))
                throw new IllegalStateException("invalid fixture envelope " + SEALER_ERROR);
            byte[] iv = Arrays.copyOfRange(sealed, HEADER.length, HEADER.length + 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(HEADER); return cipher.doFinal(sealed, HEADER.length + 12, sealed.length - HEADER.length - 12);
        }
    }
}
