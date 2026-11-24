package jp.haruyalab.novelshelf;

import android.content.Context;
import org.json.JSONObject;

/** Authentication boundary; these strings are not credentials and never reach a network. */
final class ChatGptSession {
    static final String TOKEN = "FIXTURE_RANGE_TOKEN_NOT_A_CREDENTIAL";
    static volatile String selectedAccount = "fixture-account", selectedModel = "fixture-model";
    private static final ChatGptSession INSTANCE = new ChatGptSession();
    static ChatGptSession get(Context context) { return INSTANCE; }
    JSONObject snapshot() { return Json.obj("selectedModel", selectedModel); }
    JSONObject accountForAnalysis() { return Json.obj("id", selectedAccount, "selected_model", selectedModel); }
    String accessToken(String accountId, ChatGptOAuth.Cancel cancel) throws Exception {
        if (!selectedAccount.equals(accountId) || cancel.cancelled()) throw new IllegalStateException("fixture account changed");
        return TOKEN;
    }
}
