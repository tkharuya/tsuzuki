package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;

/** Exercises the production protocol with locally generated keys and synthetic HTTPS only. */
public final class ChatGptOAuthTest {
    private static final String HOST = "urn:uuid:f1018c04-2c85-4fe4-bdf0-f8f40a6b2c34";
    private static final String CLIENT = "fixture_issued_client_1", SUBJECT = "fixture_subject_1";
    private static final String ACCESS = "FIXTURE_ACCESS_NOT_A_REAL_TOKEN", REFRESH = "FIXTURE_REFRESH_NOT_A_REAL_TOKEN";
    private static final String SECRET = "PROVIDER_PRIVATE_DIAGNOSTIC_MUST_NOT_APPEAR";
    private static final long NOW = System.currentTimeMillis()/1000;
    private static final ChatGptOAuth.Cancel NEVER = () -> false;
    private static KeyPair key1, key2;
    private static int checks;
    private interface Operation { void run() throws Exception; }
    private static void yes(boolean value,String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual,Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static ChatGptOAuth.AuthException denied(Operation action,String label) throws Exception {
        try { action.run(); throw new AssertionError("accepted " + label); }
        catch (ChatGptOAuth.AuthException expected) {
            checks++;
            yes(!expected.getMessage().contains(ACCESS) && !expected.getMessage().contains(REFRESH)
                    && !expected.getMessage().contains(SECRET) && expected.getCause() == null,"safe classified failure");
            return expected;
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String unsigned(BigInteger number) {
        byte[] bytes = number.toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes,1,bytes.length);
        return b64(bytes);
    }
    private static JSONObject jwk(KeyPair key,String kid) {
        RSAPublicKey pub = (RSAPublicKey)key.getPublic();
        return Json.obj("kty","RSA","kid",kid,"alg","RS256","use","sig","key_ops",new JSONArray().put("verify"),
                "n",unsigned(pub.getModulus()),"e",unsigned(pub.getPublicExponent()));
    }
    private static JSONObject keySet(JSONObject... keys) {
        JSONArray array = new JSONArray(); for (JSONObject key : keys) array.put(key); return Json.obj("keys",array);
    }
    private static JSONObject claims(String nonce) {
        JSONObject value = Json.obj("iss",ChatGptOAuth.ISSUER,"sub",SUBJECT,"aud",CLIENT,"iat",NOW-1,"exp",NOW+3600,
                "email","fixture@example.invalid","name","試験利用者","sid","fixture_session");
        if (nonce != null) Json.put(value,"nonce",nonce); return value;
    }
    private static String jwt(JSONObject claims) throws Exception { return jwt(Json.obj("alg","RS256","kid","fixture_key_1"),claims,key1); }
    private static String jwt(JSONObject header,JSONObject claims,KeyPair key) throws Exception {
        String input = b64(bytes(header.toString())) + "." + b64(bytes(claims.toString()));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key.getPrivate()); signature.update(bytes(input));
        return input + "." + b64(signature.sign());
    }
    private static JSONObject discovery() {
        return Json.obj("issuer",ChatGptOAuth.ISSUER,"authorization_endpoint",ChatGptOAuth.AUTHORIZE,"token_endpoint",ChatGptOAuth.TOKEN,
                "jwks_uri",ChatGptOAuth.JWKS,"revocation_endpoint",ChatGptOAuth.REVOKE,
                "id_token_signing_alg_values_supported",new JSONArray().put("RS256"));
    }
    private static ChatGptOAuth.Attempt attempt() throws Exception { return ChatGptOAuth.begin(HOST,null,45123); }
    private static String callbackTarget(ChatGptOAuth.Attempt a) { return "/auth/callback?code=fixture_code&state=" + a.state + "&client_id=" + CLIENT; }
    private static ChatGptOAuth.Callback callback(ChatGptOAuth.Attempt a) throws Exception { return ChatGptOAuth.parseCallback(a,callbackTarget(a)); }
    private static JSONObject tokenResponse(String nonce) throws Exception {
        return Json.obj("access_token",ACCESS,"refresh_token",REFRESH,"id_token",jwt(claims(nonce)),
                "token_type","Bearer","expires_in",3600,"scope",ChatGptOAuth.SCOPES);
    }
    private static final class Request {
        final String method,url,body; final Map<String,String> headers;
        Request(String method,String url,Map<String,String> headers,byte[] body) {
            this.method=method; this.url=url; this.headers=new LinkedHashMap<>(headers);
            this.body=body==null ? "" : new String(body,StandardCharsets.UTF_8);
        }
        Map<String,String> form() throws Exception { return ChatGptOAuth.query(body); }
    }
    private static final class Fixture implements ChatGptOAuth.Transport,ChatGptOAuth.Clock {
        final List<Request> requests = new ArrayList<>();
        final List<JSONObject> keySets = new ArrayList<>();
        JSONObject metadata=discovery(), token, models=Json.obj("models",new JSONArray());
        String tokenBody; int tokenStatus=200, modelStatus=200, revokeStatus=200, keyFetches;
        long millis=NOW*1000; boolean failNetwork; Runnable onSend;
        final ChatGptOAuth core = new ChatGptOAuth(this,this);
        Fixture() { keySets.add(keySet(jwk(key1,"fixture_key_1"))); }
        public long millis() { return millis; }
        public ChatGptOAuth.HttpResult send(String method,String url,Map<String,String> headers,byte[] body,ChatGptOAuth.Cancel cancel) throws IOException {
            requests.add(new Request(method,url,headers,body));
            if (onSend != null) onSend.run();
            if (failNetwork) throw new IOException(SECRET+ACCESS);
            if (ChatGptOAuth.DISCOVERY.equals(url)) return new ChatGptOAuth.HttpResult(200,metadata.toString());
            if (ChatGptOAuth.JWKS.equals(url)) {
                JSONObject set=keySets.get(Math.min(keyFetches,keySets.size()-1)); keyFetches++;
                return new ChatGptOAuth.HttpResult(200,set.toString());
            }
            if (ChatGptOAuth.TOKEN.equals(url)) return new ChatGptOAuth.HttpResult(tokenStatus,tokenBody!=null ? tokenBody : token.toString(),"req_fixture");
            if (ChatGptOAuth.MODELS.equals(url)) return new ChatGptOAuth.HttpResult(modelStatus,models.toString(),"req_models");
            if (ChatGptOAuth.REVOKE.equals(url)) return new ChatGptOAuth.HttpResult(revokeStatus,revokeStatus==200 ? "" : Json.obj("detail",SECRET).toString());
            throw new AssertionError("unexpected network route");
        }
        Request last(String url) { for(int i=requests.size()-1;i>=0;i--) if(url.equals(requests.get(i).url))return requests.get(i); throw new AssertionError("missing request"); }
        JSONObject login() throws Exception {
            ChatGptOAuth.Attempt a=attempt(); token=tokenResponse(a.nonce); return core.exchange(a,callback(a),NEVER);
        }
        JSONObject verify(JSONObject c) throws Exception { return core.verifyIdToken(jwt(c),CLIENT,"fixture_nonce",true,NEVER); }
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        key1=generator.generateKeyPair(); key2=generator.generateKeyPair();
        authorization(); callbacks(); identityValidation(); keyRotation(); exchanges(); refreshes(); errors(); models(); httpsTransport(); cancellation();
        System.out.println("ChatGptOAuth production checks passed: " + checks + " (local RSA/JWKS and synthetic HTTPS; no account or live API)");
    }
    private static void authorization() throws Exception {
        ChatGptOAuth.Attempt a=attempt(),b=attempt();
        Map<String,String> q=ChatGptOAuth.query(new URI(a.authorizationUrl()).getRawQuery());
        eq(new URI(a.authorizationUrl()).getScheme(),"https"); eq(new URI(a.authorizationUrl()).getHost(),"auth.openai.com");
        eq(q.get("client_id"),ChatGptOAuth.DYNAMIC_CLIENT); eq(q.get("agent_name_hint"),"つづき");
        eq(q.get("ext_agent_host_id"),HOST); eq(q.get("scope"),ChatGptOAuth.SCOPES); eq(q.get("resource"),ChatGptOAuth.RESOURCE);
        eq(q.get("response_type"),"code"); eq(q.get("redirect_uri"),"http://127.0.0.1:45123/auth/callback");
        eq(q.get("code_challenge_method"),"S256"); eq(q.get("code_challenge"),b64(MessageDigest.getInstance("SHA-256").digest(bytes(a.verifier))));
        yes(a.state.length()>=43 && a.nonce.length()>=43 && a.verifier.length()>=43 && a.verifier.length()<=128,"random-value lengths");
        yes(!a.state.equals(b.state) && !a.nonce.equals(b.nonce) && !a.verifier.equals(b.verifier),"fresh secrets per attempt");
        yes(!q.containsKey("client_secret") && !q.containsKey("prompt") && !q.containsKey("id_token_hint"),"initial public-client request");
        JSONObject saved=Json.obj("client_id",CLIENT,"issuer",ChatGptOAuth.ISSUER,"subject",SUBJECT,"ext_agent_host_id",HOST,
                "id_token","retained_fixture_hint","email","fixture@example.invalid");
        ChatGptOAuth.Attempt returning=ChatGptOAuth.begin(HOST,saved,45124);
        q=ChatGptOAuth.query(new URI(returning.authorizationUrl()).getRawQuery());
        eq(q.get("client_id"),CLIENT); eq(q.get("id_token_hint"),"retained_fixture_hint"); eq(q.get("login_hint"),"fixture@example.invalid");
        yes(!q.containsKey("agent_name_hint") && !q.containsKey("prompt"),"reauth reuses registration without forced consent");
        eq(returning.expectedSubject,SUBJECT);
        q=ChatGptOAuth.query(new URI(ChatGptOAuth.begin(HOST,saved,45124,true).authorizationUrl()).getRawQuery());
        eq(q.get("prompt"),"consent"); eq(q.get("scope"),ChatGptOAuth.SCOPES); yes(!q.containsKey("force_reconsent"),"no unapproved consent flag");
        denied(() -> ChatGptOAuth.begin("unstable_host",null,45123),"invalid host ID");
        denied(() -> ChatGptOAuth.begin(HOST,null,0),"invalid callback port");
        JSONObject changed=Json.copy(saved); Json.put(changed,"issuer","https://example.invalid");
        denied(() -> ChatGptOAuth.begin(HOST,changed,45123),"foreign stored issuer");
        JSONObject changedHost=Json.copy(saved); Json.put(changedHost,"ext_agent_host_id","urn:uuid:f1018c04-2c85-4fe4-bdf0-f8f40a6b2c35");
        denied(() -> ChatGptOAuth.begin(HOST,changedHost,45123),"changed installation binding");
    }
    private static void callbacks() throws Exception {
        ChatGptOAuth.Attempt a=attempt();
        eq(denied(() -> ChatGptOAuth.parseCallback(a,"/favicon.ico"),"unrelated local path").code,"invalid_callback");
        eq(denied(() -> ChatGptOAuth.parseCallback(a,callbackTarget(a).replace(a.state,"wrong")),"wrong state").code,"state_mismatch");
        ChatGptOAuth.Callback accepted=callback(a); eq(accepted.code,"fixture_code"); eq(accepted.clientId,CLIENT);
        eq(denied(() -> callback(a),"callback replay").code,"callback_reused");
        String[] append={"&state=duplicate","&code=duplicate","&client_id=duplicate","&x=1&x=2","&%73tate=duplicate","&code%00=x","&x=%0a","&broken","&x=%zz","#fragment"};
        for(String suffix:append) { ChatGptOAuth.Attempt t=attempt(); denied(() -> ChatGptOAuth.parseCallback(t,callbackTarget(t)+suffix),"callback suffix "+suffix); }
        for(String prefix:new String[]{"http://127.0.0.1:45123","https://example.invalid","//example.invalid"}) {
            ChatGptOAuth.Attempt t=attempt(); denied(() -> ChatGptOAuth.parseCallback(t,prefix+callbackTarget(t)),"absolute callback target");
        }
        ChatGptOAuth.Attempt noClient=attempt();
        denied(() -> ChatGptOAuth.parseCallback(noClient,"/auth/callback?state="+noClient.state+"&code=x"),"missing first-registration client");
        ChatGptOAuth.Attempt dynamic=attempt();
        denied(() -> ChatGptOAuth.parseCallback(dynamic,callbackTarget(dynamic).replace(CLIENT,ChatGptOAuth.DYNAMIC_CLIENT)),"unissued bootstrap client");
        ChatGptOAuth.Attempt wrongIssuer=attempt();
        denied(() -> ChatGptOAuth.parseCallback(wrongIssuer,callbackTarget(wrongIssuer)+"&iss=https%3A%2F%2Fexample.invalid"),"foreign response issuer");
        ChatGptOAuth.Attempt both=attempt();
        denied(() -> ChatGptOAuth.parseCallback(both,callbackTarget(both)+"&error=access_denied"),"contradictory callback");
        ChatGptOAuth.Attempt declined=attempt();
        eq(denied(() -> ChatGptOAuth.parseCallback(declined,"/auth/callback?state="+declined.state+"&error=access_denied&error_description="+SECRET),"declined consent").code,"access_denied");
        denied(() -> callback(declined),"consumed declined callback");
        JSONObject registration=Json.obj("client_id",CLIENT,"subject",SUBJECT,"issuer",ChatGptOAuth.ISSUER);
        ChatGptOAuth.Attempt returning=ChatGptOAuth.begin(HOST,registration,45123);
        eq(ChatGptOAuth.parseCallback(returning,"/auth/callback?state="+returning.state+"&code=x").clientId,CLIENT);
        ChatGptOAuth.Attempt mismatch=ChatGptOAuth.begin(HOST,registration,45123);
        denied(() -> ChatGptOAuth.parseCallback(mismatch,callbackTarget(mismatch).replace(CLIENT,"another_issued_client")),"client substitution");
    }
    private static void identityValidation() throws Exception {
        Fixture f=new Fixture(); JSONObject valid=claims("fixture_nonce");
        eq(f.verify(valid).optString("sub"),SUBJECT); eq(f.keyFetches,1);
        f.verify(valid); eq(f.keyFetches,1);
        for(String name:new String[]{"iss","aud","sub","exp","iat","nonce"}) {
            JSONObject c=Json.copy(valid); c.remove(name); denied(() -> f.verify(c),"missing ID claim "+name);
        }
        JSONObject[] invalid={
            Json.merge(valid,Json.obj("iss","https://example.invalid")),Json.merge(valid,Json.obj("aud","foreign_client")),
            Json.merge(valid,Json.obj("sub",new JSONObject())),Json.merge(valid,Json.obj("nonce","wrong_nonce")),
            Json.merge(valid,Json.obj("exp",NOW-10)),Json.merge(valid,Json.obj("iat",NOW+30)),
            Json.merge(valid,Json.obj("exp","9999999999")),Json.merge(valid,Json.obj("iat",NOW+0.5)),
            Json.merge(valid,Json.obj("nbf",NOW+30)),Json.merge(valid,Json.obj("azp","foreign_client")),
            Json.merge(valid,Json.obj("aud",new JSONArray().put(CLIENT).put("other"))),
            Json.merge(valid,Json.obj("aud",new JSONArray().put(CLIENT).put(17),"azp",CLIENT))
        };
        for(JSONObject c:invalid) denied(() -> f.verify(c),"invalid signed claim");
        JSONObject multi=Json.merge(valid,Json.obj("aud",new JSONArray().put("other").put(CLIENT),"azp",CLIENT));
        eq(f.verify(multi).optString("sub"),SUBJECT);
        String signedWrong=jwt(Json.obj("alg","RS256","kid","fixture_key_1"),valid,key2);
        denied(() -> f.core.verifyIdToken(signedWrong,CLIENT,"fixture_nonce",true,NEVER),"forged signature");
        for(JSONObject header:new JSONObject[]{Json.obj("alg","none","kid","fixture_key_1"),Json.obj("alg","HS256","kid","fixture_key_1"),
                Json.obj("alg","RS256","kid","fixture_key_1","jku","https://example.invalid/keys"),
                Json.obj("alg","RS256","kid","fixture_key_1","x5u","https://example.invalid/cert"),
                Json.obj("alg","RS256","kid","fixture_key_1","crit",new JSONArray().put("exp"))}) {
            String value=jwt(header,valid,key1); denied(() -> f.core.verifyIdToken(value,CLIENT,"fixture_nonce",true,NEVER),"unsupported JWT header");
        }
        for(String malformed:new String[]{"not.jwt","...","e30.e30.",jwt(valid)+".extra"})
            denied(() -> f.core.verifyIdToken(malformed,CLIENT,"fixture_nonce",true,NEVER),"malformed token");
        JSONObject withoutNonce=claims(null);
        eq(f.core.verifyIdToken(jwt(withoutNonce),CLIENT,"fixture_nonce",false,NEVER).optString("sub"),SUBJECT);
    }
    private static void keyRotation() throws Exception {
        Fixture rotate=new Fixture(); rotate.keySets.add(keySet(jwk(key2,"fixture_key_2")));
        String rotated=jwt(Json.obj("alg","RS256","kid","fixture_key_2"),claims("fixture_nonce"),key2);
        eq(rotate.core.verifyIdToken(rotated,CLIENT,"fixture_nonce",true,NEVER).optString("sub"),SUBJECT); eq(rotate.keyFetches,2);
        Fixture unknown=new Fixture();
        eq(denied(() -> unknown.core.verifyIdToken(rotated,CLIENT,"fixture_nonce",true,NEVER),"unknown signing key").code,"unknown_signing_key");
        eq(unknown.keyFetches,2);
        Fixture duplicate=new Fixture(); duplicate.keySets.set(0,keySet(jwk(key1,"fixture_key_1"),jwk(key2,"fixture_key_1")));
        denied(() -> duplicate.verify(claims("fixture_nonce")),"duplicate signing-key IDs");
        for(String kind:new String[]{"EC","oct"}) {
            Fixture f=new Fixture(); JSONObject k=jwk(key1,"fixture_key_1"); Json.put(k,"kty",kind); f.keySets.set(0,keySet(k));
            denied(() -> f.verify(claims("fixture_nonce")),"non-RSA key");
        }
        Fixture wrongUse=new Fixture(); JSONObject k=jwk(key1,"fixture_key_1"); Json.put(k,"use","enc"); wrongUse.keySets.set(0,keySet(k));
        denied(() -> wrongUse.verify(claims("fixture_nonce")),"encryption-only key");
        for(String field:new String[]{"issuer","token_endpoint","authorization_endpoint","jwks_uri","revocation_endpoint"}) {
            Fixture f=new Fixture(); Json.put(f.metadata,field,"https://example.invalid/collect");
            eq(denied(() -> f.verify(claims("fixture_nonce")),"foreign discovery route").code,"invalid_discovery");
            eq(f.requests.size(),1);
        }
    }
    private static void exchanges() throws Exception {
        Fixture f=new Fixture(); ChatGptOAuth.Attempt a=attempt(); f.token=tokenResponse(a.nonce);
        ChatGptOAuth.Callback cb=callback(a); JSONObject c=f.core.exchange(a,cb,NEVER);
        eq(c.optString("client_id"),CLIENT); eq(c.optString("issuer"),ChatGptOAuth.ISSUER); eq(c.optString("subject"),SUBJECT);
        eq(c.optString("email"),"fixture@example.invalid"); eq(c.optString("name"),"試験利用者"); eq(c.optString("ext_agent_host_id"),HOST);
        eq(c.optLong("expires_at"),NOW+3600); eq(c.optLong("saved_at"),NOW); yes(ChatGptOAuth.planEnabled(c),"plan grant available");
        Request token=f.last(ChatGptOAuth.TOKEN); Map<String,String> form=token.form();
        eq(token.method,"POST"); eq(token.headers.get("Content-Type"),"application/x-www-form-urlencoded");
        eq(form.get("grant_type"),"authorization_code"); eq(form.get("client_id"),CLIENT); eq(form.get("code"),"fixture_code");
        eq(form.get("code_verifier"),a.verifier); eq(form.get("redirect_uri"),a.redirectUri); eq(form.get("resource"),ChatGptOAuth.RESOURCE);
        yes(!form.containsKey("client_secret") && !form.containsKey("scope"),"public-client exchange schema");
        denied(() -> f.core.exchange(a,cb,NEVER),"exchange replay");
        ChatGptOAuth.Attempt other=attempt(); denied(() -> f.core.exchange(other,cb,NEVER),"callback cross-attempt substitution");
        JSONObject stored=Json.copy(c); Json.put(stored,"subject","different_saved_subject");
        ChatGptOAuth.Attempt wrong=ChatGptOAuth.begin(HOST,stored,45123); Fixture mismatch=new Fixture(); mismatch.token=tokenResponse(wrong.nonce);
        eq(denied(() -> mismatch.core.exchange(wrong,callback(wrong),NEVER),"returning subject substitution").code,"identity_mismatch");
        Fixture declined=new Fixture(); ChatGptOAuth.Attempt d=attempt(); declined.token=Json.obj("id_token",jwt(claims(d.nonce)),"scope","openid profile email");
        JSONObject onlyIdentity=declined.core.exchange(d,callback(d),NEVER);
        yes(!ChatGptOAuth.planEnabled(onlyIdentity) && onlyIdentity.optString("access_token").isEmpty(),"identity-only grant retained without inference");
        for(String field:new String[]{"access_token","refresh_token"}) {
            Fixture invalid=new Fixture(); ChatGptOAuth.Attempt t=attempt(); invalid.token=tokenResponse(t.nonce); Json.put(invalid.token,field,Json.obj("secret",SECRET));
            denied(() -> invalid.core.exchange(t,callback(t),NEVER),"non-string token");
        }
        for(Object expiry:new Object[]{-1,0,86401,"3600",1.5}) {
            Fixture invalid=new Fixture(); ChatGptOAuth.Attempt t=attempt(); invalid.token=tokenResponse(t.nonce); Json.put(invalid.token,"expires_in",expiry);
            denied(() -> invalid.core.exchange(t,callback(t),NEVER),"invalid expiry");
        }
    }
    private static void refreshes() throws Exception {
        Fixture f=new Fixture(); JSONObject original=f.login(); String snapshot=original.toString();
        f.token=Json.obj("access_token",ACCESS+"_NEW","refresh_token",REFRESH+"_NEW","token_type","bearer","expires_in",3600,
                "earliest_refresh_at",(NOW+600)*1000);
        JSONObject next=f.core.refresh(original,NEVER); eq(original.toString(),snapshot);
        eq(next.optString("access_token"),ACCESS+"_NEW"); eq(next.optString("refresh_token"),REFRESH+"_NEW");
        eq(next.optString("id_token"),original.optString("id_token")); eq(next.optString("subject"),SUBJECT);
        eq(next.optJSONArray("scopes").toString(),original.optJSONArray("scopes").toString()); eq(next.optLong("earliest_refresh_at"),NOW+600);
        Request request=f.last(ChatGptOAuth.TOKEN); Map<String,String> form=request.form();
        eq(form.get("grant_type"),"refresh_token"); eq(form.get("client_id"),CLIENT); eq(form.get("refresh_token"),REFRESH); eq(form.get("resource"),ChatGptOAuth.RESOURCE);
        yes(!form.containsKey("scope") && !form.containsKey("code_verifier"),"refresh retains existing grant");
        int sent=f.requests.size(); eq(denied(() -> f.core.refresh(next,NEVER),"provider earliest-refresh time").code,"refresh_too_early"); eq(f.requests.size(),sent);
        Json.put(f.token,"earliest_refresh_at",java.time.Instant.ofEpochSecond(NOW+600).toString());
        eq(f.core.refresh(original,NEVER).optLong("earliest_refresh_at"),NOW+600);
        Json.put(f.token,"earliest_refresh_at",String.valueOf(NOW+600)); eq(f.core.refresh(original,NEVER).optLong("earliest_refresh_at"),NOW+600);
        Json.put(f.token,"id_token",jwt(claims(null))); eq(f.core.refresh(original,NEVER).optString("subject"),SUBJECT);
        Json.put(f.token,"id_token",jwt(Json.merge(claims(null),Json.obj("sub","foreign_subject"))));
        eq(denied(() -> f.core.refresh(original,NEVER),"refreshed subject change").code,"identity_mismatch");
        Json.put(f.token,"id_token",jwt(claims("wrong_refresh_nonce")));
        eq(denied(() -> f.core.refresh(original,NEVER),"refreshed nonce change").code,"nonce_mismatch");
        f.token.remove("id_token"); f.token.remove("refresh_token");
        denied(() -> f.core.refresh(original,NEVER),"missing rotated refresh token"); eq(original.toString(),snapshot);
        f.core.revoke(original,NEVER); Request revoke=f.last(ChatGptOAuth.REVOKE); Map<String,String> revokeForm=revoke.form();
        eq(revoke.method,"POST"); eq(revokeForm.get("token"),REFRESH); eq(revokeForm.get("token_type_hint"),"refresh_token"); eq(revokeForm.get("client_id"),CLIENT);
        eq(original.toString(),snapshot);
        f.revokeStatus=503; eq(denied(() -> f.core.revoke(original,NEVER),"failed remote revocation").status,503); eq(original.toString(),snapshot);
    }
    private static void errors() throws Exception {
        String[] terminal={"invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused"};
        for(String code:terminal) {
            Fixture f=new Fixture(); JSONObject current=f.login(); String snapshot=current.toString();
            f.tokenStatus=400; f.tokenBody=Json.obj("error",code,"error_description",SECRET+ACCESS).toString();
            ChatGptOAuth.AuthException e=denied(() -> f.core.refresh(current,NEVER),"terminal refresh failure");
            eq(e.code,code); eq(e.status,400); eq(e.requestId,"req_fixture"); yes(e.terminalRefresh(),"terminal refresh classified"); eq(current.toString(),snapshot);
        }
        for(int status:new int[]{400,401,403,429,503}) {
            Fixture f=new Fixture(); JSONObject current=f.login(); f.tokenStatus=status;
            f.tokenBody=Json.obj("detail",SECRET+ACCESS).toString();
            ChatGptOAuth.AuthException e=denied(() -> f.core.refresh(current,NEVER),"detail-shaped error");
            eq(e.status,status); eq(e.code,"http_error"); yes(!e.terminalRefresh(),"ambiguous error retains session");
        }
        Fixture invalidClient=new Fixture(); JSONObject current=invalidClient.login(); invalidClient.tokenStatus=401;
        invalidClient.tokenBody=Json.obj("error",Json.obj("code","invalid_client","message",SECRET)).toString();
        ChatGptOAuth.AuthException e=denied(() -> invalidClient.core.refresh(current,NEVER),"invalid client configuration");
        eq(e.code,"invalid_client"); yes(!e.terminalRefresh(),"invalid client is configuration failure");
        Fixture failure=new Fixture(); failure.failNetwork=true;
        eq(denied(() -> failure.core.models(ACCESS,NEVER),"transport exception contains private context").code,"network_error");
        Fixture malformed=new Fixture(); malformed.tokenBody="{\"ok\":true}trailing"; ChatGptOAuth.Attempt a=attempt();
        denied(() -> malformed.core.exchange(a,callback(a),NEVER),"trailing response bytes");
    }
    private static void models() throws Exception {
        Fixture f=new Fixture(); f.models=Json.obj("models",new JSONArray()
                .put(Json.obj("slug","fixture-preferred","display_name","推奨モデル","visibility","list"))
                .put(Json.obj("slug","hidden","display_name","Hidden","visibility","hidden"))
                .put(Json.obj("slug","fixture-other","display_name","別のモデル","visibility","list"))
                .put(Json.obj("slug","fixture-preferred","display_name","重複","visibility","list")));
        JSONArray result=f.core.models(ACCESS,NEVER); eq(result.length(),2); eq(result.optJSONObject(0).optString("slug"),"fixture-preferred");
        eq(result.optJSONObject(1).optString("slug"),"fixture-other"); eq(result.optJSONObject(0).length(),2);
        Request request=f.last(ChatGptOAuth.MODELS); eq(request.method,"GET"); eq(request.headers.get("Authorization"),"Bearer "+ACCESS);
        yes(!request.url.contains(ACCESS) && request.body.isEmpty(),"token only in authorization header");
        f.models=Json.obj("data",new JSONArray()); denied(() -> f.core.models(ACCESS,NEVER),"ordinary API model catalog shape");
        f.models=Json.obj("models",new JSONArray().put(Json.obj("slug","bad\nslug","display_name","x","visibility","list")));
        denied(() -> f.core.models(ACCESS,NEVER),"unsafe model identifier");
        yes(!ChatGptOAuth.planEnabled(null) && !ChatGptOAuth.planEnabled(Json.obj("access_token",ACCESS,"scopes",new JSONArray().put("resource.invoke"))),"both plan scopes required");
    }
    private static final class FakeHttps extends HttpsURLConnection {
        final byte[] response; final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        final CountDownLatch entered=new CountDownLatch(1), disconnected=new CountDownLatch(1);
        final boolean block; int status=200; long contentLength=-1; boolean disconnectCalled;
        FakeHttps(String response) throws Exception { this(bytes(response),false); }
        FakeHttps(byte[] response,boolean block) throws Exception { super(new URL(ChatGptOAuth.MODELS)); this.response=response; this.block=block; }
        @Override public int getResponseCode() throws IOException {
            entered.countDown();
            if(block) {
                try { if(!disconnected.await(5,TimeUnit.SECONDS)) throw new IOException("fixture disconnect timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(SECRET); }
                throw new IOException(SECRET);
            }
            return status;
        }
        @Override public long getContentLengthLong() { return contentLength; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public String getHeaderField(String name) { return "x-request-id".equals(name) ? "req_transport" : null; }
        @Override public void disconnect() { disconnectCalled=true; disconnected.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "FIXTURE"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
    private static void httpsTransport() throws Exception {
        FakeHttps conn=new FakeHttps("{\"models\":[]}");
        ChatGptOAuth core=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> conn),() -> NOW*1000);
        eq(core.models(ACCESS,NEVER).length(),0); yes(!conn.getInstanceFollowRedirects() && !conn.getUseCaches(),"no redirect or cache for credentials");
        eq(conn.getRequestProperty("Authorization"),"Bearer "+ACCESS); eq(conn.getConnectTimeout(),15000); eq(conn.getReadTimeout(),ChatGptOAuth.HTTP_TIMEOUT_MS);
        yes(conn.disconnectCalled,"connection always closed");
        FakeHttps redirect=new FakeHttps("{\"detail\":\""+SECRET+"\"}"); redirect.status=302;
        ChatGptOAuth redirected=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> redirect),() -> NOW*1000);
        eq(denied(() -> redirected.models(ACCESS,NEVER),"credential redirect").status,302); yes(!redirect.getInstanceFollowRedirects(),"redirect remains disabled");
        FakeHttps declared=new FakeHttps("{}"); declared.contentLength=ChatGptOAuth.MAX_JSON_BYTES+1L;
        ChatGptOAuth bigDeclared=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> declared),() -> NOW*1000);
        denied(() -> bigDeclared.models(ACCESS,NEVER),"oversized declared body");
        FakeHttps huge=new FakeHttps(new byte[ChatGptOAuth.MAX_JSON_BYTES+1],false);
        ChatGptOAuth bigStream=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> huge),() -> NOW*1000);
        denied(() -> bigStream.models(ACCESS,NEVER),"oversized streamed body");
        FakeHttps malformed=new FakeHttps(new byte[]{(byte)0xc3,(byte)0x28},false);
        ChatGptOAuth malformedUtf8=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> malformed),() -> NOW*1000);
        denied(() -> malformedUtf8.models(ACCESS,NEVER),"invalid UTF-8 response");
        FakeHttps post=new FakeHttps("{}");
        new ChatGptOAuth.HttpTransport(url -> post).send("POST",ChatGptOAuth.TOKEN,Collections.singletonMap("Content-Type","application/x-www-form-urlencoded"),bytes("grant_type=refresh_token"),NEVER);
        eq(new String(post.sent.toByteArray(),StandardCharsets.UTF_8),"grant_type=refresh_token"); yes(post.getDoOutput(),"form body written");
    }
    private static void cancellation() throws Exception {
        Fixture before=new Fixture();
        eq(denied(() -> before.core.models(ACCESS,() -> true),"cancel before request").code,"cancelled"); eq(before.requests.size(),0);
        Fixture late=new Fixture(); AtomicBoolean cancelled=new AtomicBoolean(); late.onSend=() -> cancelled.set(true);
        eq(denied(() -> late.core.models(ACCESS,cancelled::get),"cancel after response before commit").code,"cancelled");
        FakeHttps blocked=new FakeHttps(bytes("{}"),true); AtomicBoolean stop=new AtomicBoolean(); AtomicReference<Throwable> result=new AtomicReference<>();
        ChatGptOAuth core=new ChatGptOAuth(new ChatGptOAuth.HttpTransport(url -> blocked),() -> NOW*1000);
        Thread worker=new Thread(() -> { try { core.models(ACCESS,stop::get); result.set(new AssertionError("cancel ignored")); } catch(Throwable e) { result.set(e); } },"oauth-cancel-fixture");
        worker.start(); yes(blocked.entered.await(2,TimeUnit.SECONDS),"blocked network entered"); stop.set(true); worker.join(2000);
        yes(!worker.isAlive() && blocked.disconnectCalled,"cancellation promptly closes blocked network");
        yes(result.get() instanceof ChatGptOAuth.AuthException,"cancelled network has safe exception");
        eq(((ChatGptOAuth.AuthException)result.get()).code,"cancelled");
    }
}
