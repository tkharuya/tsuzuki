package jp.haruyalab.novelshelf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Runs production ChatGptSession, ChatGptStore and ChatGptOAuth, with controllable fixture
 * HTTPS responses and an injected two-thread executor. Latches determine race order.
 * Android/Handler/browser/service/loopback boundaries are narrow test doubles. OAuth code,
 * signed fixture ID-token verification, storage logic, and session coordination are real.
 * SOFTWARE AES-GCM is a test fixture only; this suite does NOT verify AndroidKeyStore,
 * a real Android lifecycle, production loopback sockets, live accounts, or paid inference.
 */
public final class ChatGptSessionTest {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final String CLIENT = "fixture_session_client_";
    private static final String SUBJECT = "fixture_session_subject";
    private static final String ACCESS = "FIXTURE_SESSION_ACCESS_";
    private static final String REFRESH = "FIXTURE_SESSION_REFRESH_";
    private static final String ID_TOKEN = "FIXTURE_SESSION_IDTOKEN_";
    private static final String SECRET = "FIXTURE_PROVIDER_PRIVATE_DIAGNOSTIC";
    private static final String KID = "fixture-session-rsa";
    private static final ChatGptOAuth.Cancel NEVER = () -> false;
    private static File suiteRoot;
    private static KeyPair signingKey;
    private interface Operation { void run() throws Exception; }
    private interface Response { ChatGptOAuth.HttpResult reply(Request request) throws Exception; }

    private static void yes(boolean value, String message) { CHECKS.incrementAndGet(); if (!value) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) {
        CHECKS.incrementAndGet(); if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
    private static void await(CountDownLatch latch, String label) throws Exception { yes(latch.await(8, TimeUnit.SECONDS), label); }
    private static void eventually(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Looper.runFixtureEvent(25);
        yes(condition.getAsBoolean(), label); Looper.drainFixtureQueue();
    }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("operation should have been rejected"); }
        catch (IllegalArgumentException | IllegalStateException | ChatGptOAuth.AuthException expected) {
            CHECKS.incrementAndGet(); safeMessage(String.valueOf(expected.getMessage()));
        }
    }
    private static void safeMessage(String message) {
        for (String privateValue : new String[]{ACCESS, REFRESH, ID_TOKEN, CLIENT, SUBJECT, SECRET})
            yes(!message.contains(privateValue), "public error leaked a private fixture value");
    }

    public static void main(String[] args) throws Exception {
        suiteRoot = Files.createTempDirectory("tsuzuki-chatgpt-session-jvm-").toFile();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); signingKey = generator.generateKeyPair();
        try {
            cancelledRefreshPersistsRotation(); logoutRevokesTheRotatedToken(); selectionAndAbaPreserveRotation();
            reauthorizationWinsOverOldRefresh(); oldCatalogCannotReplaceNewCatalog(); loginGenerationAndSelection();
            identityOnlyAndScopeRevocation(); terminalAndTemporaryRefreshErrors(); concurrentRefreshIsSerialized();
            logoutWarningsBelongToTheirRegistration(); interruptedLogoutRecovery();
            eq(ChatGptLoopback.fixtureListenerCount(), 0);
            System.out.println("ChatGptSession production coordination checks passed: " + CHECKS.get());
            System.out.println("Scope: real Session/Store/OAuth and local RSA fixture verification; latch-controlled transport, existing file AtomicFile double, software AES-GCM fixture.");
            System.out.println("TEST BOUNDARIES: queued Handler/Looper, recorded Context/Intent, queued callback listener, service class literal, and analysis cancellation signal.");
            System.out.println("NOT TESTED: actual Android lifecycle/foreground service, AndroidKeyStore, real loopback sockets, system browser, live account/API behavior, or inference billing.");
            System.out.println("No external or local network request occurred. All accounts, tokens, signatures and provider responses are synthetic fixtures.");
        } finally { Looper.drainFixtureQueue(); remove(suiteRoot); }
    }

    private static void cancelledRefreshPersistsRotation() throws Exception {
        try (Fixture f = new Fixture("cancel-rotation")) {
            String a = f.register("a", true); long generation = ChatGptStore.credentialRevision(f.store.account(a));
            Step refresh = f.api.refresh(Step.blocked(tokenResponse("a", "R1")));
            AtomicBoolean cancel = new AtomicBoolean(); AccessCall call = f.access(a, cancel::get);
            refresh.arrived(); eq(refresh.request.form.get("refresh_token"), REFRESH + "a-R0");
            cancel.set(true); f.session.cancel();
            eq(refresh.request.cancel.cancelled(), false); // The in-flight rotation outlives inference/UI cancellation.
            refresh.release(); call.failed("cancelled"); f.idle();
            eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-R1");
            eq(f.store.account(a).optString("access_token"), ACCESS + "a-R1");
            eq(ChatGptStore.credentialRevision(f.store.account(a)), generation + 1L);
            eq(f.session.accessToken(a, NEVER), ACCESS + "a-R1"); eq(f.api.count("refresh_token"), 1);
            eq(f.session.snapshot().optString("error"), "");
            publicState(f.session.snapshot(), f.store.hostId());
        }
    }

    private static void logoutRevokesTheRotatedToken() throws Exception {
        try (Fixture f = new Fixture("logout-rotation")) {
            String a = f.register("a", true);
            Step refresh = f.api.refresh(Step.blocked(tokenResponse("a", "R1")));
            Step revoke = f.api.revoke(Step.blocked(new ChatGptOAuth.HttpResult(200, "")));
            AccessCall call = f.access(a, NEVER); refresh.arrived();
            f.session.logout(a); eq(f.store.account(a).optBoolean("signing_out"), true);
            eq(f.api.countUrl(ChatGptOAuth.REVOKE), 0); refresh.release(); call.failed("cancelled");
            revoke.arrived(); eq(revoke.request.form.get("token"), REFRESH + "a-R1");
            eq(revoke.request.form.get("client_id"), CLIENT + "a");
            eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-R1");
            eq(f.store.account(a).optBoolean("signing_out"), true);
            revoke.release(); f.idle(); assertCleared(f.store.account(a));
            eq(f.session.snapshot().optString("status"), "signed_out"); eq(f.session.snapshot().optString("error"), "");
            eq(f.api.count("refresh_token"), 1); eq(f.api.countUrl(ChatGptOAuth.REVOKE), 1);
            rejected(() -> f.session.accessToken(a, NEVER));
        }
    }

    private static void selectionAndAbaPreserveRotation() throws Exception {
        for (boolean aba : new boolean[]{false, true}) try (Fixture f = new Fixture(aba ? "rotation-aba" : "rotation-inactive")) {
            String a = f.register("a", true), b = f.register("b", false); f.store.selectAccount(a);
            JSONObject bBefore = f.store.account(b); long credentialGeneration = ChatGptStore.credentialRevision(f.store.account(a));
            Step refresh = f.api.refresh(Step.blocked(tokenResponse("a", "R1")));
            AccessCall call = f.access(a, NEVER); refresh.arrived();
            f.session.select(b); if (aba) f.session.select(a);
            eq(ChatGptStore.credentialRevision(f.store.account(a)), credentialGeneration);
            refresh.release(); call.failed("cancelled"); f.idle();
            eq(f.store.activeId(), aba ? a : b); eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-R1");
            eq(f.store.account(b).optString("access_token"), bBefore.optString("access_token"));
            eq(f.store.account(b).optString("refresh_token"), bBefore.optString("refresh_token"));
            eq(ChatGptStore.credentialRevision(f.store.account(a)), credentialGeneration + 1L);
            eq(f.api.count("refresh_token"), 1); eq(f.session.snapshot().optString("error"), "");
            eq(f.session.accountForAnalysis().optString("id"), aba ? a : b);
        }
    }

    private static void reauthorizationWinsOverOldRefresh() throws Exception {
        try (Fixture f = new Fixture("reauth-versus-refresh")) {
            String a = f.register("a", true);
            Step refresh = f.api.refresh(Step.blocked(tokenResponse("a", "old-R1")));
            AccessCall call = f.access(a, NEVER); refresh.arrived();
            f.session.login(a, false); Login login = f.latestLogin("a");
            f.api.login(Step.immediate(loginResponse(login, "NEW", true)));
            login.deliver();
            eventually(() -> (ACCESS + "a-NEW").equals(f.store.account(a).optString("access_token")), "reauthorization commits before the old refresh returns");
            long newGeneration = ChatGptStore.credentialRevision(f.store.account(a));
            refresh.release(); call.failed(null); f.idle();
            eq(f.store.account(a).optString("access_token"), ACCESS + "a-NEW");
            eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-NEW");
            eq(ChatGptStore.credentialRevision(f.store.account(a)), newGeneration);
            eq(f.session.accessToken(a, NEVER), ACCESS + "a-NEW");
            eq(f.api.count("authorization_code"), 1); eq(f.api.count("refresh_token"), 1);
            eq(f.store.activeId(), a); eq(f.session.snapshot().optString("error"), "");
        }
    }

    private static void oldCatalogCannotReplaceNewCatalog() throws Exception {
        try (Fixture f = new Fixture("catalog-order")) {
            String a = f.register("a", false);
            Step old = f.api.models(Step.blocked(modelResponse()));
            Step fresh = f.api.models(Step.immediate(modelResponse("fixture-new", "fixture-model")));
            f.session.refreshModels(); old.arrived(); f.session.refreshModels(); fresh.arrived();
            eventually(() -> "fixture-new".equals(Json.arr(f.store.account(a), "models").optJSONObject(0).optString("slug")), "new catalog is committed while old response is delayed");
            eq(f.store.account(a).optString("selected_model"), "fixture-model");
            old.release(); f.idle();
            eq(Json.arr(f.store.account(a), "models").optJSONObject(0).optString("slug"), "fixture-new");
            eq(f.session.snapshot().optString("error"), ""); eq(f.session.snapshot().optBoolean("busy"), false);

            Step beforeSwitch = f.api.models(Step.blocked(modelResponse("stale-before-switch")));
            f.session.refreshModels(); beforeSwitch.arrived(); String b = f.register("b", false); f.session.select(b);
            beforeSwitch.release(); f.idle();
            eq(f.store.activeId(), b); eq(Json.arr(f.store.account(a), "models").optJSONObject(0).optString("slug"), "fixture-new");
            yes(!f.session.snapshot().toString().contains("stale-before-switch"), "old account models never enter the active account state");
        }
    }

    private static void loginGenerationAndSelection() throws Exception {
        try (Fixture f = new Fixture("login-generations")) {
            String a = f.register("a", false), b = f.register("b", false); f.store.selectAccount(a);
            f.session.login(a, false); Login first = f.latestLogin("a"); long g1 = f.session.currentGeneration();
            eq(f.session.waiting(g1), true); rejected(() -> f.session.accountForAnalysis());
            rejected(() -> f.session.selectModel(a, "fixture-model")); eq(f.session.currentGeneration(), g1);
            f.session.login(a, true); Login second = f.latestLogin("a"); long g2 = f.session.currentGeneration();
            yes(g2 > g1, "each login has a fresh generation"); eq(second.query.get("prompt"), "consent");
            eq(ChatGptLoopback.fixtureListening(first.port), false);
            int cancellationSignals = SpeechAnalysis.cancellations.get();
            f.session.cancel(g1); f.session.serviceStopped(g1); Looper.drainFixtureQueue();
            eq(f.session.waiting(g2), true); eq(SpeechAnalysis.cancellations.get(), cancellationSignals);
            eq(f.session.snapshot().optString("status"), "waiting"); eq(f.context.stoppedServices.get(), 0);
            f.session.cancel(g2); f.idle(); eq(f.session.currentGeneration(), -1L);
            yes(f.context.stoppedServices.get() > 0, "ending the matching attempt requests service shutdown");
            eq(ChatGptLoopback.fixtureListening(second.port), false);
            int afterCancel = SpeechAnalysis.cancellations.get(); f.session.cancel(g2); f.session.serviceStopped(g2);
            eq(SpeechAnalysis.cancellations.get(), afterCancel);

            f.session.login(a, false); Login exchanging = f.latestLogin("a");
            Step response = f.api.login(Step.blocked(loginResponse(exchanging, "MUST-NOT-COMMIT", true)));
            exchanging.deliver(); response.arrived(); eq(f.session.snapshot().optString("status"), "exchanging");
            f.session.select(b); response.release(); f.idle();
            eq(f.store.activeId(), b); eq(f.store.account(a).optString("access_token"), ACCESS + "a-R0");
            eq(f.store.account(b).optString("access_token"), ACCESS + "b-R0"); eq(f.session.snapshot().optString("error"), "");
            eq(ChatGptLoopback.fixtureListening(exchanging.port), false);
            f.session.login(b, false); long current = f.session.currentGeneration();
            f.session.serviceStopped(current); f.idle();
            eq(f.session.currentGeneration(), -1L); yes(!f.session.snapshot().optString("error").isEmpty(), "current service shutdown reports the interrupted login");
            publicState(f.session.snapshot(), f.store.hostId());
        }
        try (Fixture f = new Fixture("browser-unavailable")) {
            f.context.failActivity = true; rejected(() -> f.session.login("", false)); f.idle();
            eq(f.session.currentGeneration(), -1L); eq(ChatGptLoopback.fixtureListenerCount(), 0);
            yes(!f.session.snapshot().optString("error").contains(SECRET), "browser exception payload is not exposed");
        }
    }

    private static void identityOnlyAndScopeRevocation() throws Exception {
        try (Fixture f = new Fixture("identity-only")) {
            f.session.login("", false); Login login = f.latestLogin("identity");
            f.api.login(Step.immediate(loginResponse(login, "ID-ONLY", false))); login.deliver(); f.idle();
            JSONObject account = f.store.activeAccount();
            eq(ChatGptStore.connected(account), true); eq(ChatGptStore.planEnabled(account), false);
            eq(f.session.snapshot().optString("status"), "connected"); eq(f.session.snapshot().optBoolean("firstConnection"), false);
            eq(f.api.countUrl(ChatGptOAuth.MODELS), 0); rejected(() -> f.session.accountForAnalysis());
            rejected(() -> f.session.refreshModels()); rejected(() -> f.session.accessToken(account.optString("id"), NEVER));
            eq(f.api.count("refresh_token"), 0); publicState(f.session.snapshot(), f.store.hostId());
        }
        try (Fixture f = new Fixture("scope-revoked-on-refresh")) {
            String a = f.register("a", true);
            f.api.refresh(Step.immediate(Json.obj("scope", "openid profile email")));
            AccessCall call = f.access(a, NEVER); call.failed(null); f.idle();
            JSONObject account = f.store.account(a); eq(ChatGptStore.connected(account), true); eq(ChatGptStore.planEnabled(account), false);
            eq(account.optString("access_token"), ""); eq(account.optString("refresh_token"), "");
            eq(Json.arr(account, "models").length(), 0); eq(account.optString("selected_model"), "fixture-model");
            rejected(() -> f.session.accountForAnalysis()); eq(f.api.count("refresh_token"), 1);
        }
    }

    private static void terminalAndTemporaryRefreshErrors() throws Exception {
        try (Fixture f = new Fixture("terminal-refresh")) {
            String a = f.register("a", true);
            f.api.refresh(Step.immediate(new ChatGptOAuth.HttpResult(400, Json.obj("error", "invalid_grant", "error_description", SECRET).toString())));
            AccessCall call = f.access(a, NEVER); call.failed("invalid_grant"); f.idle();
            assertCleared(f.store.account(a)); safeMessage(f.session.snapshot().optString("error"));
            yes(!f.session.snapshot().optString("error").isEmpty(), "terminal refresh directs the user to reauthorize");
            eq(f.api.count("refresh_token"), 1);
        }
        try (Fixture f = new Fixture("temporary-refresh")) {
            String a = f.register("a", true); byte[] saved = Files.readAllBytes(f.file.toPath());
            f.api.refresh(Step.immediate(new ChatGptOAuth.HttpResult(503, Json.obj("error", "temporarily_unavailable", "error_description", SECRET).toString())));
            AccessCall call = f.access(a, NEVER); call.failed("temporarily_unavailable"); f.idle();
            eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-R0");
            yes(Arrays.equals(Files.readAllBytes(f.file.toPath()), saved), "temporary upstream error does not destroy renewable credentials");
            eq(f.api.count("refresh_token"), 1); eq(ChatGptStore.connected(f.store.account(a)), true);
        }
    }

    private static void concurrentRefreshIsSerialized() throws Exception {
        try (Fixture f = new Fixture("serialized-refresh")) {
            String a = f.register("a", true); Step refresh = f.api.refresh(Step.blocked(tokenResponse("a", "R1")));
            AccessCall first = f.access(a, NEVER); refresh.arrived(); AccessCall second = f.access(a, NEVER);
            refresh.release(); eq(first.succeeded(), ACCESS + "a-R1"); eq(second.succeeded(), ACCESS + "a-R1"); f.idle();
            eq(f.api.count("refresh_token"), 1); eq(f.store.account(a).optString("refresh_token"), REFRESH + "a-R1");
        }
    }

    private static void logoutWarningsBelongToTheirRegistration() throws Exception {
        try (Fixture f = new Fixture("logout-warning-isolation")) {
            String a = f.register("a", false), b = f.register("b", false); f.store.selectAccount(a);
            Step revoke = f.api.revoke(Step.blocked(new ChatGptOAuth.HttpResult(500, Json.obj("error", "server_error", "detail", SECRET).toString())));
            f.session.logout(a); revoke.arrived(); f.session.select(b); revoke.release(); f.idle();
            assertCleared(f.store.account(a)); eq(f.store.activeId(), b); eq(f.session.snapshot().optString("error"), "");
            f.session.select(a); f.idle();
            yes(f.session.snapshot().optString("error").contains("サーバー側"), "failed remote revocation remains visible on its own registration");
            safeMessage(f.session.snapshot().optString("error"));
            f.session.select(b); f.idle(); eq(f.session.snapshot().optString("error"), "");
            eq(f.store.account(b).optString("refresh_token"), REFRESH + "b-R0");
            f.session.login(a, false); Login login = f.latestLogin("a");
            f.api.login(Step.immediate(loginResponse(login, "RECONNECTED", true))); login.deliver(); f.idle();
            eq(f.store.activeId(), a); eq(f.session.snapshot().optString("error"), "");
        }
    }

    private static void interruptedLogoutRecovery() throws Exception {
        try (Fixture f = new Fixture("process-restart-fixture")) {
            String a = f.register("a", false), b = f.register("b", false); f.store.beginLogout(a);
            // Construct a fresh production coordinator over durable state. This is not an Android process simulation.
            f.session.removeListener(f.listener);
            f.session = new ChatGptSession(f.context, f.store, new ChatGptOAuth(f.api, System::currentTimeMillis), f.work);
            f.session.setListener(f.listener); f.idle();
            assertCleared(f.store.account(a)); eq(f.store.activeId(), b); eq(f.session.snapshot().optString("error"), "");
            eq(f.api.countUrl(ChatGptOAuth.REVOKE), 0);
            f.session.select(a); f.idle();
            yes(f.session.snapshot().optString("error").contains("サーバー側"), "interrupted logout warning is stored for the affected registration");
            f.session.select(b); f.idle(); eq(f.session.snapshot().optString("error"), "");
        }
    }

    private static JSONObject tokenResponse(String tag, String version) {
        return Json.obj("access_token", ACCESS + tag + "-" + version, "refresh_token", REFRESH + tag + "-" + version,
                "token_type", "Bearer", "expires_in", 3600, "scope", ChatGptOAuth.SCOPES);
    }
    private static JSONObject loginResponse(Login login, String version, boolean plan) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        JSONObject claims = Json.obj("iss", ChatGptOAuth.ISSUER, "aud", CLIENT + login.tag, "sub", SUBJECT,
                "iat", now, "exp", now + 3600, "nonce", login.query.get("nonce"),
                "email", "session-fixture@example.invalid", "name", "検査利用者");
        JSONObject result = plan ? tokenResponse(login.tag, version) : Json.obj("scope", "openid profile email");
        Json.put(result, "id_token", jwt(claims)); return result;
    }
    private static JSONObject modelResponse(String... slugs) {
        JSONArray models = new JSONArray(); for (String slug : slugs) models.put(Json.obj("slug", slug, "display_name", "検査 " + slug, "visibility", "list"));
        return Json.obj("models", models);
    }
    private static JSONArray storedModels() { return new JSONArray().put(Json.obj("slug", "fixture-model", "display_name", "検査モデル")).put(Json.obj("slug", "fixture-other", "display_name", "別の検査モデル")); }
    private static void assertCleared(JSONObject account) {
        for (String key : new String[]{"access_token", "refresh_token", "id_token", "token_type", "expires_at", "earliest_refresh_at", "auth_nonce", "logout_ticket"})
            yes(!account.has(key), "session cleanup removes " + key);
        eq(ChatGptStore.connected(account), false); eq(account.optBoolean("signing_out"), false); eq(account.optBoolean("reauth_required"), true);
    }
    private static void publicState(JSONObject state, String host) {
        String text = state.toString();
        for (String value : new String[]{ACCESS, REFRESH, ID_TOKEN, CLIENT, SUBJECT, SECRET, host, "\"access_token\"", "\"refresh_token\"",
                "\"id_token\"", "\"client_id\"", "\"host_id\"", "\"issuer\"", "\"subject\"", "\"auth_nonce\"", "\"logout_ticket\"", "\"credential_revision\""})
            yes(!text.contains(value), "public session state discloses a private fixture field");
    }
    private static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray(); return b64(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    private static String jwt(JSONObject claims) throws Exception {
        String input = b64(Json.obj("alg", "RS256", "kid", KID).toString().getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(signingKey.getPrivate()); signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signature.sign());
    }
    private static JSONObject jwks() {
        RSAPublicKey key = (RSAPublicKey)signingKey.getPublic();
        return Json.obj("keys", new JSONArray().put(Json.obj("kty", "RSA", "kid", KID, "alg", "RS256", "use", "sig",
                "n", unsigned(key.getModulus()), "e", unsigned(key.getPublicExponent()))));
    }
    private static JSONObject discovery() {
        return Json.obj("issuer", ChatGptOAuth.ISSUER, "authorization_endpoint", ChatGptOAuth.AUTHORIZE,
                "token_endpoint", ChatGptOAuth.TOKEN, "jwks_uri", ChatGptOAuth.JWKS, "revocation_endpoint", ChatGptOAuth.REVOKE,
                "id_token_signing_alg_values_supported", new JSONArray().put("RS256"));
    }
    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IOException("fixture cleanup failed");
    }

    private static final class Request {
        final String method, url; final Map<String, String> headers, form; final ChatGptOAuth.Cancel cancel;
        Request(String method, String url, Map<String, String> headers, byte[] body, ChatGptOAuth.Cancel cancel) throws Exception {
            this.method = method; this.url = url; this.headers = headers; this.cancel = cancel;
            this.form = body == null ? java.util.Collections.emptyMap() : ChatGptOAuth.query(new String(body, StandardCharsets.UTF_8));
        }
    }
    private static final class Step {
        final CountDownLatch entered = new CountDownLatch(1), finish;
        final Response response;
        volatile Request request;
        Step(boolean blocked, Response response) { finish = new CountDownLatch(blocked ? 1 : 0); this.response = response; }
        static Step immediate(JSONObject json) { return immediate(new ChatGptOAuth.HttpResult(200, json.toString())); }
        static Step immediate(ChatGptOAuth.HttpResult result) { return new Step(false, request -> result); }
        static Step blocked(JSONObject json) { return blocked(new ChatGptOAuth.HttpResult(200, json.toString())); }
        static Step blocked(ChatGptOAuth.HttpResult result) { return new Step(true, request -> result); }
        void arrived() throws Exception { await(entered, "fixture request reached its controlled response gate"); }
        void release() { finish.countDown(); }
        ChatGptOAuth.HttpResult send(Request request) throws Exception {
            this.request = request; entered.countDown();
            if (!finish.await(8, TimeUnit.SECONDS)) throw new AssertionError("fixture response gate timeout");
            return response.reply(request); // Deliberately allows a cancelled response to arrive late.
        }
    }
    private static final class Api implements ChatGptOAuth.Transport {
        final ConcurrentLinkedQueue<Step> refresh = new ConcurrentLinkedQueue<>(), login = new ConcurrentLinkedQueue<>(), models = new ConcurrentLinkedQueue<>(), revoke = new ConcurrentLinkedQueue<>();
        final List<Step> allSteps = new CopyOnWriteArrayList<>();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Step add(ConcurrentLinkedQueue<Step> queue, Step step) { allSteps.add(step); queue.add(step); return step; }
        Step refresh(Step step) { return add(refresh, step); }
        Step login(Step step) { return add(login, step); }
        Step models(Step step) { return add(models, step); }
        Step revoke(Step step) { return add(revoke, step); }
        @Override public ChatGptOAuth.HttpResult send(String method, String url, Map<String, String> headers, byte[] body, ChatGptOAuth.Cancel cancel) throws IOException {
            try {
                Request request = new Request(method, url, headers, body, cancel); requests.add(request);
                if (ChatGptOAuth.DISCOVERY.equals(url)) { eq(method, "GET"); return new ChatGptOAuth.HttpResult(200, discovery().toString()); }
                if (ChatGptOAuth.JWKS.equals(url)) { eq(method, "GET"); return new ChatGptOAuth.HttpResult(200, jwks().toString()); }
                Step next;
                if (ChatGptOAuth.TOKEN.equals(url)) {
                    eq(method, "POST"); eq(request.form.get("resource"), ChatGptOAuth.RESOURCE);
                    yes(!request.form.containsKey("scope") && !request.form.containsKey("client_secret"), "token exchange uses the registered public-client contract");
                    next = "refresh_token".equals(request.form.get("grant_type")) ? refresh.poll() : login.poll();
                    if (next == null) throw new AssertionError("unplanned token request");
                } else if (ChatGptOAuth.MODELS.equals(url)) {
                    eq(method, "GET"); yes(headers.get("Authorization").startsWith("Bearer " + ACCESS), "catalog uses only a synthetic account bearer");
                    next = models.poll(); if (next == null) next = Step.immediate(modelResponse("fixture-model", "fixture-other"));
                } else if (ChatGptOAuth.REVOKE.equals(url)) {
                    eq(method, "POST"); eq(request.form.get("token_type_hint"), "refresh_token");
                    next = revoke.poll(); if (next == null) next = Step.immediate(new ChatGptOAuth.HttpResult(200, ""));
                } else throw new AssertionError("unapproved fixture network route");
                return next.send(request);
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IOException("fixture transport failed");
            }
        }
        int count(String grant) { int n = 0; for (Request request : requests) if (grant.equals(request.form.get("grant_type"))) n++; return n; }
        int countUrl(String url) { int n = 0; for (Request request : requests) if (url.equals(request.url)) n++; return n; }
        void releaseAll() { for (Step step : allSteps) step.release(); }
        void assertHealthy() { if (unexpected.get() != null) throw new AssertionError("unexpected fixture transport failure", unexpected.get()); }
    }
    private static final class Login {
        final String tag; final Map<String, String> query; final int port;
        Login(Intent browser, String tag) throws Exception {
            this.tag = tag; eq(browser.getAction(), Intent.ACTION_VIEW); yes((browser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0, "system-browser request starts a task");
            URI authorization = new URI(browser.getData().toString());
            eq(authorization.getScheme(), "https"); eq(authorization.getHost(), "auth.openai.com");
            query = ChatGptOAuth.query(authorization.getRawQuery()); port = new URI(query.get("redirect_uri")).getPort();
        }
        void deliver() { yes(ChatGptLoopback.deliverFixtureCallback(port, "/auth/callback?state=" + query.get("state") + "&code=fixture_code&client_id=" + CLIENT + tag), "callback reaches its pending fixture listener"); }
    }
    private static final class AccessCall {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result; volatile Throwable failure;
        final Thread thread;
        AccessCall(ChatGptSession session, String id, ChatGptOAuth.Cancel cancel) {
            thread = new Thread(() -> {
                try { result = session.accessToken(id, cancel); }
                catch (Throwable error) { failure = error; }
                finally { done.countDown(); }
            }, "session-access-fixture"); thread.setDaemon(true); thread.start();
        }
        void failed(String code) throws Exception {
            await(done, "access token operation completes"); yes(result == null, "cancelled or invalidated operation must not return a token");
            yes(failure instanceof Exception, "operation reports a classified failure");
            if (code != null) { yes(failure instanceof ChatGptOAuth.AuthException, "failure is a safe OAuth classification"); eq(((ChatGptOAuth.AuthException)failure).code, code); }
            safeMessage(String.valueOf(failure.getMessage()));
        }
        String succeeded() throws Exception { await(done, "access token operation completes"); if (failure != null) throw new AssertionError("access operation failed", failure); return result; }
    }
    private static final class Work extends AbstractExecutorService {
        final ExecutorService delegate = Executors.newFixedThreadPool(2, job -> { Thread thread = new Thread(job, "session-worker-fixture"); thread.setDaemon(true); return thread; });
        final Object monitor = new Object(); int pending;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        @Override public void execute(Runnable command) {
            synchronized (monitor) { pending++; }
            delegate.execute(() -> {
                try { command.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { synchronized (monitor) { pending--; monitor.notifyAll(); } }
            });
        }
        void idle() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            synchronized (monitor) {
                while (pending != 0 && System.nanoTime() < deadline) TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime()));
                yes(pending == 0, "all session worker operations finish");
            }
            if (failure.get() != null) throw new AssertionError("uncaught production Session worker failure", failure.get());
        }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(time, unit); }
    }
    private static final class RecordingContext extends Context {
        final SpeechStoreTestContext files;
        final List<Intent> activities = new CopyOnWriteArrayList<>(), serviceStarts = new CopyOnWriteArrayList<>();
        final AtomicInteger stoppedServices = new AtomicInteger();
        volatile boolean failActivity;
        RecordingContext(File root) { files = new SpeechStoreTestContext(root); }
        @Override public File getFilesDir() { return files.getFilesDir(); }
        @Override public File getCacheDir() { return files.getCacheDir(); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return files.getSharedPreferences(name, mode); }
        @Override public ComponentName startForegroundService(Intent intent) { serviceStarts.add(intent); return null; }
        @Override public boolean stopService(Intent intent) { stoppedServices.incrementAndGet(); return true; }
        @Override public void startActivity(Intent intent) { if (failActivity) throw new IllegalStateException(SECRET); activities.add(intent); }
    }
    private static final class Fixture implements AutoCloseable {
        final File root, file; final RecordingContext context; final ChatGptStore store; final Api api = new Api(); final Work work = new Work();
        final List<AccessCall> calls = new ArrayList<>(); final ChatGptSession.Listener listener;
        ChatGptSession session;
        Fixture(String name) throws Exception {
            root = Files.createTempDirectory(suiteRoot.toPath(), name + "-").toFile(); context = new RecordingContext(root);
            file = new File(context.getFilesDir(), "chatgpt-accounts.enc"); store = new ChatGptStore(file, new SoftwareSealer());
            String host = store.hostId(); listener = state -> publicState(state, host);
            session = new ChatGptSession(context, store, new ChatGptOAuth(api, System::currentTimeMillis), work);
            session.setListener(listener); Looper.drainFixtureQueue();
        }
        String register(String tag, boolean expired) {
            long now = System.currentTimeMillis() / 1000;
            JSONArray scopes = new JSONArray(); for (String scope : ChatGptOAuth.SCOPES.split(" ")) scopes.put(scope);
            JSONObject credentials = Json.obj("issuer", ChatGptOAuth.ISSUER, "subject", SUBJECT, "client_id", CLIENT + tag,
                    "ext_agent_host_id", store.hostId(), "auth_nonce", "fixture_nonce_" + tag, "email", "session-fixture@example.invalid", "name", "検査利用者",
                    "access_token", ACCESS + tag + "-R0", "refresh_token", REFRESH + tag + "-R0", "id_token", ID_TOKEN + tag,
                    "token_type", "Bearer", "expires_at", expired ? now - 10 : now + 3600, "expires_in", 3600, "saved_at", now, "scopes", scopes);
            String id = store.saveLogin(credentials, ""); store.saveModels(id, store.account(id).optLong("revision"), storedModels()); store.selectModel(id, "fixture-model"); return id;
        }
        AccessCall access(String id, ChatGptOAuth.Cancel cancel) { AccessCall call = new AccessCall(session, id, cancel); calls.add(call); return call; }
        Login latestLogin(String tag) throws Exception {
            Intent intent = context.activities.get(context.activities.size() - 1);
            eq(context.serviceStarts.get(context.serviceStarts.size() - 1).getLongExtra("generation", -1), session.currentGeneration());
            return new Login(intent, tag);
        }
        void idle() throws Exception { work.idle(); Looper.drainFixtureQueue(); api.assertHealthy(); }
        @Override public void close() throws Exception {
            session.removeListener(listener); session.cancel(); api.releaseAll();
            for (AccessCall call : calls) { if (!call.done.await(8, TimeUnit.SECONDS)) { call.thread.interrupt(); throw new AssertionError("access fixture failed to stop"); } }
            try { idle(); }
            finally { work.shutdownNow(); yes(work.awaitTermination(8, TimeUnit.SECONDS), "fixture executor stops"); Looper.drainFixtureQueue(); remove(root); }
        }
    }
    /** TEST-ONLY software encryption boundary; no Android key provider is loaded. */
    private static final class SoftwareSealer implements ChatGptStore.Sealer {
        final byte[] key = new byte[32]; final SecureRandom random = new SecureRandom();
        SoftwareSealer() { random.nextBytes(key); }
        @Override public byte[] seal(byte[] plain) throws Exception {
            byte[] iv = new byte[12]; random.nextBytes(iv); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain), result = new byte[12 + encrypted.length];
            System.arraycopy(iv, 0, result, 0, 12); System.arraycopy(encrypted, 0, result, 12, encrypted.length); return result;
        }
        @Override public byte[] open(byte[] sealed) throws Exception {
            if (sealed.length < 28) throw new IOException("invalid software fixture envelope");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        }
    }
}
