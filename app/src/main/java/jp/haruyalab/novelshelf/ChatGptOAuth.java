package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/**
 * Public-client Sign in with ChatGPT protocol. Android storage and loopback listener are separate.
 * https://developers.openai.com/siwc/token-sharing-open-source (verified 2026-11-17).
 * No shared Codex client, copied auth cache, client secret, private backend, or inferred identity.
 */
public final class ChatGptOAuth {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    public static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    public static final String DISCOVERY = ISSUER + "/.well-known/openid-configuration";
    public static final String JWKS = ISSUER + "/.well-known/jwks.json";
    public static final String REVOKE = ISSUER + "/api/accounts/oauth/revoke";
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final String MODELS = RESOURCE + "/models";
    public static final String USAGE = "https://chatgpt.com/settings/usage";
    public static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    public static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String APP_NAME = "つづき";
    static final int MAX_JSON_BYTES = 1024 * 1024;
    static final long ATTEMPT_MS = 10 * 60 * 1000L;
    static final int HTTP_TIMEOUT_MS = 45000;
    private static final long KEY_CACHE_MS = 60 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> TERMINAL_REFRESH = new HashSet<>(Arrays.asList(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused"));

    public interface Cancel { boolean cancelled(); }
    interface Clock { long millis(); }
    interface Transport {
        HttpResult send(String method, String url, Map<String,String> headers, byte[] body, Cancel cancel) throws IOException;
    }
    interface ConnectionFactory { HttpsURLConnection open(URL url) throws IOException; }
    static final class HttpResult {
        final int status;
        final String body, requestId;
        HttpResult(int status, String body) { this(status, body, ""); }
        HttpResult(int status, String body, String requestId) {
            this.status = status; this.body = body == null ? "" : body; this.requestId = safeId(requestId);
        }
    }

    /** Safe error metadata only. Response bodies, URLs, tokens and provider text are never retained. */
    public static final class AuthException extends IOException {
        public final String code, requestId;
        public final int status;
        AuthException(String code, int status, String requestId) {
            super(messageFor(code, status));
            this.code = safeCode(code); this.status = status; this.requestId = safeId(requestId);
        }
        public boolean terminalRefresh() { return TERMINAL_REFRESH.contains(code); }
    }

    public static final class Attempt {
        public final String state, nonce, verifier, redirectUri, clientId, expectedSubject, hostId;
        public final String expectedIssuer;
        public final long createdAtMillis;
        private final String authorization;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean exchanged = new AtomicBoolean();
        private Attempt(String hostId, JSONObject saved, int port, boolean consent, long now) throws AuthException {
            if (port < 1 || port > 65535 || hostId == null || !hostId.matches("urn:uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
                throw error("invalid_configuration");
            this.hostId = hostId;
            this.createdAtMillis = now;
            this.clientId = saved == null ? DYNAMIC_CLIENT : issuedClient(saved.optString("client_id"));
            this.expectedSubject = saved == null ? "" : requiredString(saved, "subject", 512);
            this.expectedIssuer = saved == null ? ISSUER : requiredString(saved, "issuer", 256);
            if (!ISSUER.equals(expectedIssuer)) throw error("identity_mismatch");
            if (saved != null && saved.has("ext_agent_host_id")
                    && !equal(hostId, requiredString(saved,"ext_agent_host_id",512))) throw error("identity_mismatch");
            this.state = random(32); this.nonce = random(32); this.verifier = random(64);
            this.redirectUri = "http://127.0.0.1:" + port + "/auth/callback";
            LinkedHashMap<String,String> params = new LinkedHashMap<>();
            params.put("client_id", clientId);
            if (DYNAMIC_CLIENT.equals(clientId)) params.put("agent_name_hint", APP_NAME);
            params.put("ext_agent_host_id", hostId);
            params.put("response_type", "code"); params.put("redirect_uri", redirectUri);
            params.put("scope", SCOPES); params.put("resource", RESOURCE);
            params.put("state", state); params.put("nonce", nonce);
            params.put("code_challenge_method", "S256");
            try { params.put("code_challenge", encode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))); }
            catch (Exception e) { throw error("crypto_unavailable"); }
            if (saved != null) {
                String hint = saved.optString("id_token");
                if (!hint.isEmpty()) { validateSecret(hint, 65536); params.put("id_token_hint", hint); }
                String email = saved.optString("email");
                if (!email.isEmpty() && email.length() <= 320 && !control(email)) params.put("login_hint", email);
            }
            if (consent) params.put("prompt", "consent");
            authorization = AUTHORIZE + "?" + form(params);
        }
        /** May contain a retained ID-token hint. Open in the system browser; never log this URL. */
        public String authorizationUrl() { return authorization; }
        public boolean expired() { return System.currentTimeMillis() - createdAtMillis >= ATTEMPT_MS; }
    }

    public static final class Callback {
        public final String code, clientId;
        private final Attempt attempt;
        private Callback(Attempt attempt, String code, String clientId) {
            this.attempt = attempt; this.code = code; this.clientId = clientId;
        }
    }

    private final Transport transport;
    private final Clock clock;
    private JSONObject discovery, keys;
    private long discoveryAt, keysAt;
    public ChatGptOAuth() { this(new HttpTransport(), System::currentTimeMillis); }
    ChatGptOAuth(Transport transport, Clock clock) { this.transport = transport; this.clock = clock; }

    public static Attempt begin(String hostId, JSONObject saved, int loopbackPort) throws AuthException {
        return begin(hostId, saved, loopbackPort, false);
    }
    public static Attempt begin(String hostId, JSONObject saved, int loopbackPort, boolean requestPlanConsent) throws AuthException {
        return new Attempt(hostId, saved == null ? null : Json.copy(saved), loopbackPort, requestPlanConsent, System.currentTimeMillis());
    }

    /** Only a valid matching callback is consumed; unrelated local requests cannot cancel sign-in. */
    public static Callback parseCallback(Attempt attempt, String requestTarget) throws AuthException {
        if (attempt == null || attempt.expired()) throw error("login_expired");
        if (requestTarget == null || requestTarget.length() > 16384) throw error("invalid_callback");
        URI uri;
        try { uri = new URI(requestTarget); } catch (Exception e) { throw error("invalid_callback"); }
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || !"/auth/callback".equals(uri.getRawPath()) || uri.getRawQuery() == null)
            throw error("invalid_callback");
        Map<String,String> values = query(uri.getRawQuery());
        if (!equal(attempt.state, values.get("state"))) throw error("state_mismatch");
        if (values.containsKey("iss") && !equal(ISSUER,values.get("iss"))) throw error("identity_mismatch");
        if (values.containsKey("error") && values.containsKey("code")) throw error("invalid_callback");
        if (!attempt.consumed.compareAndSet(false, true)) throw error("callback_reused");
        if (values.containsKey("error")) {
            String code = safeCode(values.get("error"));
            throw error("access_denied".equals(code) ? "access_denied" : "authorization_failed");
        }
        String code = values.get("code");
        validateSecret(code, 4096);
        String client = values.get("client_id");
        if (DYNAMIC_CLIENT.equals(attempt.clientId)) client = issuedClient(client);
        else {
            if (client == null) client = attempt.clientId;
            if (!equal(client, attempt.clientId)) throw error("identity_mismatch");
        }
        return new Callback(attempt, code, client);
    }

    public JSONObject exchange(Attempt attempt, Callback callback, Cancel cancel) throws AuthException {
        check(cancel);
        if (attempt == null || callback == null || callback.attempt != attempt || attempt.expired()
                || !attempt.consumed.get() || !attempt.exchanged.compareAndSet(false, true))
            throw error("invalid_callback");
        metadata(cancel);
        JSONObject response = post(TOKEN, pairs("grant_type","authorization_code","client_id",callback.clientId,
                "code",callback.code,"code_verifier",attempt.verifier,"redirect_uri",attempt.redirectUri,
                "resource",RESOURCE), cancel);
        String idToken = requiredString(response, "id_token", 65536);
        JSONObject identity = verifyIdToken(idToken, callback.clientId, attempt.nonce, true, cancel);
        if (!attempt.expectedSubject.isEmpty() && !equal(attempt.expectedSubject, identity.optString("sub")))
            throw error("identity_mismatch");
        JSONObject candidate = credentials(response, null, identity, callback.clientId, attempt.hostId, attempt.nonce);
        check(cancel); return candidate;
    }

    /** Returns a candidate only; the caller commits it atomically using the selected account revision. */
    public JSONObject refresh(JSONObject current, Cancel cancel) throws AuthException {
        check(cancel);
        String client = issuedClient(current.optString("client_id"));
        String subject = requiredString(current, "subject", 512);
        if (!ISSUER.equals(current.optString("issuer"))) throw error("identity_mismatch");
        long earliest = integer(current.opt("earliest_refresh_at"), 0);
        if (earliest > clock.millis() / 1000) throw error("refresh_too_early");
        String refresh = requiredString(current, "refresh_token", 65536);
        metadata(cancel);
        JSONObject response = post(TOKEN, pairs("grant_type","refresh_token","client_id",client,
                "refresh_token",refresh,"resource",RESOURCE), cancel);
        JSONObject identity = Json.obj("sub",subject,"iss",ISSUER,"email",current.optString("email"),"name",current.optString("name"));
        if (response.has("id_token")) {
            String token = requiredString(response, "id_token", 65536);
            identity = verifyIdToken(token, client, current.optString("auth_nonce"), false, cancel);
            if (!equal(subject, identity.optString("sub"))) throw error("identity_mismatch");
        }
        JSONObject candidate = credentials(response, current, identity, client,
                requiredString(current,"ext_agent_host_id",512), current.optString("auth_nonce"));
        check(cancel); return candidate;
    }

    public void revoke(JSONObject current, Cancel cancel) throws AuthException {
        check(cancel);
        String refresh = current.optString("refresh_token");
        if (refresh.isEmpty()) return;
        validateSecret(refresh, 65536);
        String endpoint = metadata(cancel).optString("revocation_endpoint");
        if (!REVOKE.equals(endpoint)) throw error("invalid_discovery");
        HttpResult response = send("POST", endpoint, formHeaders(), bytes(form(pairs(
                "token",refresh,"token_type_hint","refresh_token","client_id",issuedClient(current.optString("client_id"))))), cancel);
        // An empty 200 also means success for an already-invalid refresh token (RFC 7009).
        if (response.status != 200) throw responseError(response);
        check(cancel);
    }

    public JSONArray models(String accessToken, Cancel cancel) throws AuthException {
        validateSecret(accessToken, 65536);
        Map<String,String> headers = jsonHeaders(); headers.put("Authorization", "Bearer " + accessToken);
        JSONObject response = successJson(send("GET", MODELS, headers, null, cancel));
        JSONArray source = response.optJSONArray("models");
        if (source == null || source.length() > 10000) throw error("invalid_models");
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject model = source.optJSONObject(i);
            if (model == null) throw error("invalid_models");
            if (!"list".equals(model.optString("visibility"))) continue;
            String slug = requiredString(model,"slug",128);
            if (!slug.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw error("invalid_models");
            String name = requiredString(model,"display_name",200);
            if (seen.add(slug)) result.put(Json.obj("slug",slug,"display_name",name));
        }
        check(cancel); return result;
    }

    public static boolean planEnabled(JSONObject credentials) {
        if (credentials == null || credentials.optString("access_token").isEmpty()) return false;
        JSONArray scopes = credentials.optJSONArray("scopes");
        boolean direct = false, invoke = false;
        if (scopes != null) for (int i = 0; i < scopes.length(); i++) {
            direct |= "chatgpt.tokens.use.direct".equals(scopes.optString(i));
            invoke |= "resource.invoke".equals(scopes.optString(i));
        }
        return direct && invoke;
    }

    private JSONObject credentials(JSONObject response, JSONObject previous, JSONObject identity,
            String client, String hostId, String nonce) throws AuthException {
        if (response.has("client_id") && !equal(client, response.optString("client_id"))) throw error("identity_mismatch");
        JSONArray scopes;
        if (response.has("scope")) scopes = scopes(requiredString(response,"scope",4096));
        else if (previous != null) scopes = copyArray(Json.arr(previous,"scopes"));
        else throw error("invalid_token_response");
        String access = optionalToken(response,"access_token"), refresh = optionalToken(response,"refresh_token");
        boolean granted = contains(scopes,"chatgpt.tokens.use.direct");
        if (granted && (access.isEmpty() || !contains(scopes,"resource.invoke"))) throw error("invalid_token_response");
        if (!access.isEmpty() && !"Bearer".equalsIgnoreCase(response.optString("token_type"))) throw error("invalid_token_response");
        if (contains(scopes,"offline_access") && refresh.isEmpty()) throw error("invalid_token_response");
        long now = clock.millis() / 1000;
        long expires = access.isEmpty() ? 0 : integer(response.opt("expires_in"), -1);
        if (!access.isEmpty() && (expires < 1 || expires > 86400)) throw error("invalid_token_response");
        String idToken = response.optString("id_token", previous == null ? "" : previous.optString("id_token"));
        if (idToken.isEmpty()) throw error("invalid_token_response");
        JSONObject result = Json.obj("client_id",client,"issuer",ISSUER,"subject",identity.optString("sub"),
                "email",claimText(identity,"email",320),"name",claimText(identity,"name",200),
                "ext_agent_host_id",hostId,"id_token",idToken,"access_token",access,"refresh_token",refresh,
                "token_type",access.isEmpty() ? "" : "Bearer","expires_in",expires,"expires_at",now + expires,
                "saved_at",now,"scopes",scopes,"auth_nonce",nonce);
        if (response.has("earliest_refresh_at") && !response.isNull("earliest_refresh_at")) {
            long earliest = normalizedTimestamp(response.opt("earliest_refresh_at"));
            if (earliest < 0 || earliest > now + 31 * 86400L) throw error("invalid_token_response");
            Json.put(result,"earliest_refresh_at",earliest);
        }
        return result;
    }

    private synchronized JSONObject metadata(Cancel cancel) throws AuthException {
        check(cancel);
        if (discovery != null && clock.millis() - discoveryAt < KEY_CACHE_MS) return Json.copy(discovery);
        JSONObject found = successJson(send("GET", DISCOVERY, jsonHeaders(), null, cancel));
        if (!ISSUER.equals(found.optString("issuer")) || !AUTHORIZE.equals(found.optString("authorization_endpoint"))
                || !TOKEN.equals(found.optString("token_endpoint")) || !JWKS.equals(found.optString("jwks_uri"))
                || !REVOKE.equals(found.optString("revocation_endpoint")))
            throw error("invalid_discovery");
        if (!contains(Json.arr(found,"id_token_signing_alg_values_supported"),"RS256")) throw error("invalid_discovery");
        discovery = Json.copy(found); discoveryAt = clock.millis(); return found;
    }

    private synchronized JSONObject jwks(boolean force, Cancel cancel) throws AuthException {
        metadata(cancel);
        if (!force && keys != null && clock.millis() - keysAt < KEY_CACHE_MS) return Json.copy(keys);
        JSONObject found = successJson(send("GET", JWKS, jsonHeaders(), null, cancel));
        JSONArray all = found.optJSONArray("keys");
        if (all == null || all.length() < 1 || all.length() > 100) throw error("invalid_jwks");
        keys = Json.copy(found); keysAt = clock.millis(); return found;
    }

    JSONObject verifyIdToken(String token, String client, String nonce, boolean requireNonce, Cancel cancel) throws AuthException {
        validateSecret(token,65536);
        String[] parts = token.split("\\.",-1);
        if (parts.length != 3) throw error("invalid_id_token");
        JSONObject header = json(decodeUtf8(urlBytes(parts[0],4096)));
        if (!"RS256".equals(header.optString("alg")) || header.has("jku") || header.has("x5u")
                || header.has("crit") || header.has("b64")) throw error("invalid_id_token");
        String kid = requiredString(header,"kid",256);
        JSONObject key = matchingKey(jwks(false,cancel),kid);
        if (key == null) key = matchingKey(jwks(true,cancel),kid);
        if (key == null) throw error("unknown_signing_key");
        try {
            BigInteger modulus = new BigInteger(1,urlBytes(requiredString(key,"n",2048),1024));
            BigInteger exponent = new BigInteger(1,urlBytes(requiredString(key,"e",32),16));
            if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || !exponent.testBit(0)
                    || exponent.compareTo(BigInteger.valueOf(3)) < 0 || exponent.bitLength() > 32)
                throw error("invalid_jwks");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent)));
            verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(urlBytes(parts[2],1024))) throw error("invalid_id_token");
        } catch (AuthException e) { throw e; } catch (Exception e) { throw error("invalid_id_token"); }
        JSONObject claims = json(decodeUtf8(urlBytes(parts[1],32768)));
        if (!ISSUER.equals(claims.optString("iss"))) throw error("invalid_id_token");
        Object aud = claims.opt("aud"); int audiences = 1;
        if (aud instanceof String) {
            if (!equal(client,(String)aud)) throw error("invalid_id_token");
        } else if (aud instanceof JSONArray) {
            JSONArray values = (JSONArray)aud; audiences = values.length();
            if (audiences < 1 || audiences > 20 || !contains(values,client)) throw error("invalid_id_token");
            for (int i=0;i<audiences;i++) if (!(values.opt(i) instanceof String)) throw error("invalid_id_token");
        } else throw error("invalid_id_token");
        if (claims.has("azp") && !equal(client,claims.optString("azp"))
                || audiences > 1 && !equal(client,claims.optString("azp"))) throw error("invalid_id_token");
        long now = clock.millis()/1000, exp = integer(claims.opt("exp"),-1), issued = integer(claims.opt("iat"),-1);
        if (exp < 1 || issued < 1 || exp <= now-5 || issued > now+5 || exp < issued) throw error("invalid_id_token");
        if (claims.has("nbf") && integer(claims.opt("nbf"),Long.MAX_VALUE) > now+5) throw error("invalid_id_token");
        if (requireNonce && !claims.has("nonce")) throw error("nonce_mismatch");
        if (claims.has("nonce") && (nonce == null || nonce.isEmpty() || !equal(nonce,claims.optString("nonce"))))
            throw error("nonce_mismatch");
        requiredString(claims,"sub",512);
        if (claims.has("sid")) requiredString(claims,"sid",512);
        check(cancel); return claims;
    }

    private JSONObject matchingKey(JSONObject set, String kid) throws AuthException {
        JSONObject found = null; JSONArray all = Json.arr(set,"keys");
        for (int i=0;i<all.length();i++) {
            JSONObject key = all.optJSONObject(i); if (key == null) throw error("invalid_jwks");
            if (!kid.equals(key.optString("kid"))) continue;
            if (found != null || !"RSA".equals(key.optString("kty"))
                    || key.has("alg") && !"RS256".equals(key.optString("alg"))
                    || key.has("use") && !"sig".equals(key.optString("use"))
                    || key.has("key_ops") && !contains(Json.arr(key,"key_ops"),"verify"))
                throw error("invalid_jwks");
            found = key;
        }
        return found;
    }

    private JSONObject post(String url, Map<String,String> values, Cancel cancel) throws AuthException {
        return successJson(send("POST",url,formHeaders(),bytes(form(values)),cancel));
    }
    private HttpResult send(String method, String url, Map<String,String> headers, byte[] body, Cancel cancel) throws AuthException {
        check(cancel);
        boolean allowed = "GET".equals(method) && (DISCOVERY.equals(url) || JWKS.equals(url) || MODELS.equals(url))
                || "POST".equals(method) && (TOKEN.equals(url) || REVOKE.equals(url));
        if (!allowed) throw error("invalid_configuration");
        try {
            HttpResult value = transport.send(method,url,headers,body,cancel);
            check(cancel);
            if (value == null || value.body.length() > MAX_JSON_BYTES) throw error("invalid_response");
            return value;
        } catch (AuthException e) { throw e; }
        catch (IOException e) { check(cancel); throw error("network_error"); }
        catch (RuntimeException e) { check(cancel); throw error("invalid_response"); }
    }
    private static JSONObject successJson(HttpResult response) throws AuthException {
        if (response.status != 200) throw responseError(response);
        return json(response.body);
    }
    private static AuthException responseError(HttpResult response) {
        String code = "http_error";
        try {
            JSONObject body = json(response.body); Object raw = body.opt("error");
            if (raw instanceof String) code = safeCode((String)raw);
            else if (raw instanceof JSONObject) code = safeCode(((JSONObject)raw).optString("code","http_error"));
        } catch (Exception ignored) { }
        return new AuthException(code,response.status,response.requestId);
    }
    private static JSONObject json(String text) throws AuthException {
        if (text == null || text.length() > MAX_JSON_BYTES) throw error("invalid_response");
        try {
            JSONTokener reader = new JSONTokener(text); Object value = reader.nextValue();
            if (!(value instanceof JSONObject) || reader.nextClean() != 0) throw error("invalid_response");
            return (JSONObject)value;
        } catch (AuthException e) { throw e; } catch (Exception e) { throw error("invalid_response"); }
    }
    static Map<String,String> query(String query) throws AuthException {
        LinkedHashMap<String,String> result = new LinkedHashMap<>();
        for (String part : query.split("&",-1)) {
            if (part.isEmpty()) throw error("invalid_callback");
            int split = part.indexOf('=');
            if (split < 1) throw error("invalid_callback");
            String key, value;
            try { key = URLDecoder.decode(part.substring(0,split),"UTF-8"); value = URLDecoder.decode(part.substring(split+1),"UTF-8"); }
            catch (Exception e) { throw error("invalid_callback"); }
            if (control(key) || control(value) || key.length() > 100 || value.length() > 8192 || result.containsKey(key))
                throw error("invalid_callback");
            result.put(key,value);
        }
        return result;
    }
    private static String form(Map<String,String> values) throws AuthException {
        try {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String,String> entry : values.entrySet()) {
                if (result.length() > 0) result.append('&');
                result.append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(),"UTF-8"));
            }
            return result.toString();
        } catch (Exception e) { throw error("invalid_configuration"); }
    }
    private static Map<String,String> pairs(String... values) {
        Map<String,String> out = new LinkedHashMap<>();
        for (int i=0;i+1<values.length;i+=2) out.put(values[i],values[i+1]);
        return out;
    }
    private static Map<String,String> jsonHeaders() { return pairs("Accept","application/json","Accept-Encoding","identity"); }
    private static Map<String,String> formHeaders() {
        Map<String,String> result = jsonHeaders(); result.put("Content-Type","application/x-www-form-urlencoded"); return result;
    }
    private static JSONArray scopes(String raw) throws AuthException {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String scope : raw.trim().split("\\s+")) {
            if (!scope.matches("[A-Za-z0-9._:/-]{1,200}") || distinct.size() >= 100) throw error("invalid_token_response");
            distinct.add(scope);
        }
        JSONArray result = new JSONArray(); for (String scope : distinct) result.put(scope); return result;
    }
    private static JSONArray copyArray(JSONArray source) {
        JSONArray result = new JSONArray(); for (int i=0;i<source.length();i++) result.put(source.opt(i)); return result;
    }
    private static boolean contains(JSONArray values,String value) {
        for (int i=0;i<values.length();i++) if (value.equals(values.opt(i))) return true; return false;
    }
    private static String issuedClient(String value) throws AuthException {
        if (value == null || value.equals(DYNAMIC_CLIENT) || !value.matches("[A-Za-z0-9_-]{8,200}")) throw error("invalid_client");
        return value;
    }
    private static String requiredString(JSONObject value,String field,int max) throws AuthException {
        Object raw = value.opt(field);
        if (!(raw instanceof String)) throw error("invalid_response");
        String text = (String)raw; validateSecret(text,max); return text;
    }
    private static String claimText(JSONObject value,String field,int max) throws AuthException {
        if (!value.has(field) || value.isNull(field)) return "";
        Object raw = value.opt(field);
        if (!(raw instanceof String) || ((String)raw).length() > max || control((String)raw)) throw error("invalid_id_token");
        return (String)raw;
    }
    private static String optionalToken(JSONObject value,String field) throws AuthException {
        if (!value.has(field)) return "";
        Object raw = value.opt(field);
        if (!(raw instanceof String)) throw error("invalid_token_response");
        String token = (String)raw;
        validateSecret(token,65536);
        return token;
    }
    private static void validateSecret(String value,int max) throws AuthException {
        if (value == null || value.isEmpty() || value.length() > max || control(value)) throw error("invalid_response");
    }
    private static boolean control(String value) {
        for (int i=0;i<value.length();i++) if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7f) return true; return false;
    }
    private static long integer(Object raw,long fallback) {
        if (!(raw instanceof Number)) return fallback;
        double n = ((Number)raw).doubleValue(); long value = ((Number)raw).longValue();
        return Double.isNaN(n) || Double.isInfinite(n) || n != value ? fallback : value;
    }
    private static long normalizedTimestamp(Object raw) throws AuthException {
        long value = integer(raw,-1);
        if (raw instanceof String) {
            try { value = Long.parseLong((String)raw); }
            catch (Exception e) { try { return Instant.parse((String)raw).getEpochSecond(); } catch (Exception ignored) { throw error("invalid_token_response"); } }
        }
        if (value < 0) throw error("invalid_token_response");
        return value >= 100000000000L ? value/1000 : value;
    }
    private static String random(int count) { byte[] value = new byte[count]; RANDOM.nextBytes(value); return encode(value); }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] urlBytes(String value,int max) throws AuthException {
        if (value == null || value.isEmpty() || value.length() > ((max+2)/3)*4 || !value.matches("[A-Za-z0-9_-]+"))
            throw error("invalid_id_token");
        try { byte[] bytes = Base64.getUrlDecoder().decode(value); if (bytes.length > max) throw error("invalid_id_token"); return bytes; }
        catch (IllegalArgumentException e) { throw error("invalid_id_token"); }
    }
    private static String decodeUtf8(byte[] value) throws AuthException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch (Exception e) { throw error("invalid_response"); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static boolean equal(String a,String b) {
        return a != null && b != null && MessageDigest.isEqual(bytes(a),bytes(b));
    }
    private static String safeCode(String code) { return code != null && code.matches("[a-zA-Z0-9_]{1,100}") ? code : "oauth_error"; }
    private static String safeId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,150}") ? value : ""; }
    private static AuthException error(String code) { return new AuthException(code,0,""); }
    private static void check(Cancel cancel) throws AuthException {
        if (Thread.currentThread().isInterrupted() || cancel != null && cancel.cancelled()) throw error("cancelled");
    }
    private static String messageFor(String raw,int status) {
        String code = safeCode(raw);
        if ("cancelled".equals(code)) return "ChatGPTへの接続を中断しました。";
        if ("access_denied".equals(code)) return "ChatGPTへの接続が許可されませんでした。設定から再度ログインできます。";
        if ("login_expired".equals(code) || "invalid_grant".equals(code)) return "ChatGPTの認証期限が切れました。もう一度ログインしてください。";
        if (TERMINAL_REFRESH.contains(code)) return "ChatGPTの接続が無効になりました。もう一度ログインしてください。";
        if ("subscription_sharing_usage_limit_exceeded".equals(code) || status == 429)
            return "ChatGPTの利用上限に達しました。ChatGPTの利用量設定を確認してください。";
        if ("subscription_sharing_user_not_eligible".equals(code)) return "このアカウントまたはワークスペースではChatGPTプランを利用できません。";
        if ("subscription_sharing_unsupported_capability".equals(code)) return "この設定はChatGPTプランの利用に対応していません。モデルと設定を確認してください。";
        if ("network_error".equals(code) || "timeout".equals(code) || status >= 500)
            return "ChatGPTへ接続できませんでした。保存済みのアカウントを保持しています。通信状態を確認して再度お試しください。";
        if ("refresh_too_early".equals(code)) return "ChatGPTの接続を更新できる時刻までお待ちください。";
        if ("invalid_client".equals(code)) return "ChatGPTのアプリ登録を確認できませんでした。接続設定を確認してください。";
        if ("identity_mismatch".equals(code)) return "選択したChatGPTアカウントと認証結果が一致しません。アカウントを確認してください。";
        if (status == 403) return "ChatGPTプランの利用が許可されませんでした。権限・地域・アカウントの設定を確認してください。";
        if (status == 401) return "ChatGPTの認証を確認できませんでした。アカウントと利用許可を確認してください。";
        return "ChatGPTの認証結果を安全に確認できませんでした。もう一度ログインしてください。";
    }

    /** Default HTTPS transport. Its cancellation watcher interrupts blocked connection and read calls. */
    static final class HttpTransport implements Transport {
        private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r,"TsuzukiChatGptAuth"); thread.setDaemon(true); return thread;
        });
        private final ConnectionFactory connections;
        HttpTransport() { this(url -> (HttpsURLConnection)url.openConnection()); }
        HttpTransport(ConnectionFactory connections) { this.connections = connections; }
        @Override public HttpResult send(String method,String url,Map<String,String> headers,byte[] body,Cancel cancel) throws IOException {
            check(cancel);
            HttpsURLConnection connection = connections.open(new URL(url));
            AtomicBoolean timedOut = new AtomicBoolean(), cancelled = new AtomicBoolean();
            Thread caller = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HTTP_TIMEOUT_MS);
            ScheduledFuture<?> watcher = WATCH.scheduleAtFixedRate(() -> {
                if (System.nanoTime() >= deadline) { timedOut.set(true); connection.disconnect(); }
                else if (caller.isInterrupted() || cancel != null && cancel.cancelled()) { cancelled.set(true); connection.disconnect(); }
            },50,100,TimeUnit.MILLISECONDS);
            try {
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setRequestMethod(method); connection.setConnectTimeout(15000); connection.setReadTimeout(HTTP_TIMEOUT_MS);
                for (Map.Entry<String,String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(),header.getValue());
                connection.setRequestProperty("Connection","close");
                if (body != null) {
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                    check(cancel);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                check(cancel);
                int status = connection.getResponseCode();
                if (connection.getContentLengthLong() > MAX_JSON_BYTES) throw error("invalid_response");
                String requestId = connection.getHeaderField("x-request-id");
                if (requestId == null) requestId = connection.getHeaderField("openai-request-id");
                byte[] payload;
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                try (InputStream in = stream) { payload = in == null ? new byte[0] : readBounded(in,cancel); }
                check(cancel);
                if (timedOut.get()) throw error("timeout");
                return new HttpResult(status,decodeUtf8(payload),requestId);
            } catch (IOException | RuntimeException e) {
                if (timedOut.get()) throw error("timeout");
                if (cancelled.get()) throw error("cancelled");
                check(cancel);
                if (e instanceof AuthException) throw (AuthException)e;
                throw error("network_error");
            } finally { watcher.cancel(false); connection.disconnect(); }
        }
        private static byte[] readBounded(InputStream in,Cancel cancel) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            while (true) {
                check(cancel); int count = in.read(buffer); if (count < 0) break;
                if (count > MAX_JSON_BYTES-out.size()) throw error("invalid_response");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        }
    }
}
