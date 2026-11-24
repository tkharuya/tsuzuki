package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;

/** Synthetic finalized-output protocol fixtures; no request, account, or novel text. */
public final class ChatGptOutputItemsTest {
    private static int checks;
    private static final String PRIVATE = "PRIVATE_FIXTURE_CONTENT_MUST_NOT_LEAK";
    private interface Operation { void run() throws Exception; }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void denied(Operation operation, String stage) throws Exception {
        try { operation.run(); throw new AssertionError("accepted invalid fixture: " + stage); }
        catch (ChatGptOutputItems.Invalid failure) {
            eq(failure.stage, stage);
            eq(failure.getMessage(), "Finalized ChatGPT output could not be verified.");
            yes(failure.getCause() == null && !failure.stage.contains(PRIVATE), "diagnostics do not retain payloads");
        }
    }
    private static JSONObject message(String id, String text) {
        JSONObject value = Json.obj("type", "message", "status", "completed", "role", "assistant", "content",
                new JSONArray().put(Json.obj("type", "output_text", "text", text, "annotations", new JSONArray())));
        if (id != null) Json.put(value, "id", id);
        return value;
    }
    private static JSONObject tool(String type, String id, String callId) {
        JSONObject value = Json.obj("type", type, "call_id", callId, "arguments", "{}", "name", "fixture");
        if (id != null) Json.put(value, "id", id);
        return value;
    }
    private static JSONObject event(String kind, Object index, Object item) {
        return Json.obj("type", "response.output_item." + kind, "output_index", index, "item", item);
    }
    private static JSONObject lifecycle(String kind, Object output) {
        return Json.obj("type", "response." + kind, "response", Json.obj("id", "resp_fixture", "output", output));
    }
    private static JSONObject response() { return Json.obj("id", "resp_fixture", "status", "completed", "usage", Json.obj("output_tokens", 6)); }
    private static JSONObject response(Object output) { JSONObject value = response(); Json.put(value, "output", output); return value; }
    private static JSONArray output(ChatGptOutputItems state, JSONObject response) throws Exception { return state.complete(response).getJSONArray("output"); }
    private static String text(JSONArray output, int index) throws Exception { return output.getJSONObject(index).getJSONArray("content").getJSONObject(0).getString("text"); }

    public static void main(String[] args) throws Exception {
        completedRecovery(); finalArrayPriority(); unfinished(); identities(); malformed(); lifecycleSnapshots(); boundaries();
        System.out.println("ChatGptOutputItems production checks passed: " + checks + " (synthetic fixtures, no live API)");
    }

    private static void completedRecovery() throws Exception {
        ChatGptOutputItems state = new ChatGptOutputItems();
        state.accept(lifecycle("created", null));
        state.accept(event("added", 0, null));
        state.accept(Json.obj("type", "response.output_item.added", "output_index", 1));
        JSONObject reasoning = Json.obj("id", "rs_fixture", "type", "reasoning", "summary", new JSONArray());
        JSONObject answer = message("msg_fixture", "{\"cast\":[],\"assignments\":[]}");
        state.accept(event("added", 5, reasoning));
        state.accept(event("added", 12, message("msg_fixture", "")));
        state.accept(Json.obj("type", "response.output_text.delta", "output_index", 12, "delta", PRIVATE));
        state.accept(event("done", 12, answer));
        state.accept(event("done", 5, reasoning));
        JSONObject missing = response();
        JSONObject completed = state.complete(missing);
        JSONArray items = completed.getJSONArray("output");
        eq(items.length(), 2); eq(items.getJSONObject(0).getString("type"), "reasoning");
        eq(text(items, 1), "{\"cast\":[],\"assignments\":[]}");
        eq(completed.getJSONObject("usage").getInt("output_tokens"), 6);
        yes(!completed.toString().contains(PRIVATE), "raw delta was discarded");
        yes(!missing.has("output"), "caller terminal envelope is not mutated");
        eq(output(state, response(JSONObject.NULL)).length(), 2);
        Json.put(answer, "content", new JSONArray());
        Json.put(items.getJSONObject(1), "content", new JSONArray());
        eq(text(output(state, response()), 1), "{\"cast\":[],\"assignments\":[]}");

        ChatGptOutputItems none = new ChatGptOutputItems();
        none.accept(Json.obj("type", "response.output_text.delta", "delta", PRIVATE));
        none.accept(Json.obj("type", "response.output_text.done", "text", PRIVATE));
        none.accept(Json.obj("type", "response.content_part.done", "part", Json.obj("type", "output_text", "text", PRIVATE)));
        eq(output(none, response()).length(), 0);
        eq(output(none, response(JSONObject.NULL)).length(), 0);

        ChatGptOutputItems refusal = new ChatGptOutputItems();
        JSONObject refused = message("msg_refused", "");
        Json.put(refused, "content", new JSONArray().put(Json.obj("type", "refusal", "refusal", "fixture refusal")));
        refusal.accept(event("done", 3, refused));
        JSONObject part = output(refusal, response()).getJSONObject(0).getJSONArray("content").getJSONObject(0);
        eq(part.getString("type"), "refusal"); eq(part.getString("refusal"), "fixture refusal");
    }

    private static void finalArrayPriority() throws Exception {
        ChatGptOutputItems state = new ChatGptOutputItems();
        state.accept(event("done", 30, message("msg_a", "older")));
        state.accept(event("done", 7, message("msg_b", "older")));
        JSONArray finalItems = new JSONArray().put(message("msg_a", "final A")).put(message("msg_b", "final B"));
        JSONObject finalResponse = response(finalItems);
        JSONArray actual = output(state, finalResponse);
        eq(text(actual, 0), "final A"); eq(text(actual, 1), "final B");
        eq(actual.getJSONObject(0).getString("id"), "msg_a");
        eq(output(state, response(new JSONArray())).length(), 0);
        // Explicit output may omit an already finalized item; never merge it back in.
        eq(output(state, response(new JSONArray().put(message("msg_b", "only B")))).length(), 1);
        Json.put(actual.getJSONObject(0), "status", "changed by caller");
        eq(finalItems.getJSONObject(0).getString("status"), "completed");
        eq(output(new ChatGptOutputItems(), response(new JSONArray().put(message(null, "minimal")))).length(), 1);
        JSONObject future = Json.obj("type", "future_output_kind", "id", "future_fixture", "extra", Json.obj("value", 42));
        eq(output(new ChatGptOutputItems(), response(new JSONArray().put(future))).getJSONObject(0).getJSONObject("extra").getInt("value"), 42);
    }

    private static void unfinished() throws Exception {
        ChatGptOutputItems pending = new ChatGptOutputItems();
        pending.accept(event("added", 10, message("msg_pending", PRIVATE)));
        denied(() -> pending.complete(response()), "output_unfinished_item");
        denied(() -> pending.complete(response(JSONObject.NULL)), "output_unfinished_item");
        denied(() -> pending.complete(response(new JSONArray())), "output_unfinished_item");
        denied(() -> pending.complete(response(new JSONArray().put(message("msg_other", "final")))), "output_unfinished_item");
        eq(text(output(pending, response(new JSONArray().put(message("msg_pending", "final")))), 0), "final");
        pending.accept(event("done", 10, message("msg_pending", "done")));
        eq(text(output(pending, response()), 0), "done");
        eq(output(pending, response(new JSONArray())).length(), 0);

        ChatGptOutputItems partial = new ChatGptOutputItems();
        partial.accept(event("done", 0, message("msg_done", "final")));
        partial.accept(event("added", 1, message("msg_unfinished", PRIVATE)));
        denied(() -> partial.complete(response()), "output_unfinished_item");
    }

    private static void identities() throws Exception {
        for (JSONObject changed : new JSONObject[]{message("msg_other", PRIVATE),
                Json.obj("type", "reasoning", "id", "msg_a"),
                Json.merge(message("msg_a", PRIVATE), Json.obj("call_id", "different"))}) {
            ChatGptOutputItems state = new ChatGptOutputItems();
            state.accept(event("added", 2, message("msg_a", "")));
            denied(() -> state.accept(event("done", 2, changed)), "output_item_identity");
        }
        ChatGptOutputItems duplicates = new ChatGptOutputItems();
        duplicates.accept(event("done", 8, message("msg_a", "first")));
        duplicates.accept(event("done", 8, message("msg_a", "final")));
        eq(text(output(duplicates, response()), 0), "final");
        denied(() -> duplicates.accept(event("done", 8, message("msg_other", PRIVATE))), "output_item_identity");
        denied(() -> duplicates.accept(event("done", 9, message("msg_a", PRIVATE))), "output_duplicate_identity");
        denied(() -> duplicates.accept(event("done", 9, Json.obj("type", "reasoning", "id", "msg_a"))), "output_conflicting_id");
        denied(() -> duplicates.complete(response(new JSONArray().put(Json.obj("type", "reasoning", "id", "msg_a")))), "output_conflicting_id");

        ChatGptOutputItems added = new ChatGptOutputItems();
        added.accept(event("added", 0, message("msg_a", "")));
        denied(() -> added.accept(event("added", 0, message("msg_a", ""))), "output_duplicate_index");
        denied(() -> added.complete(response(new JSONArray().put(Json.obj("type", "reasoning", "id", "msg_a")))), "output_conflicting_id");

        for (String type : new String[]{"function_call", "custom_tool_call"}) {
            ChatGptOutputItems state = new ChatGptOutputItems();
            state.accept(event("added", 4, tool(type, null, "call_fixture")));
            state.accept(event("done", 4, tool(type, "tool_fixture", "call_fixture")));
            eq(output(state, response()).getJSONObject(0).getString("id"), "tool_fixture");
            denied(() -> state.complete(response(new JSONArray().put(tool(type, "tool_other", "call_fixture")))), "output_final_identity");
            denied(() -> state.accept(event("done", 4, tool(type, "tool_fixture", "call_other"))), "output_item_identity");
            ChatGptOutputItems full = new ChatGptOutputItems();
            full.accept(event("added", 99, tool(type, null, "call_fixture")));
            eq(output(full, response(new JSONArray().put(tool(type, "tool_final", "call_fixture")))).getJSONObject(0).getString("id"), "tool_final");
        }
        ChatGptOutputItems shell = new ChatGptOutputItems();
        shell.accept(event("added", 0, tool("shell_call", null, "call_fixture")));
        denied(() -> shell.accept(event("done", 0, tool("shell_call", "shell_id", "call_fixture"))), "output_item_identity");
        ChatGptOutputItems completedTool = new ChatGptOutputItems();
        completedTool.accept(event("done", 0, tool("function_call", null, "call_fixture")));
        eq(output(completedTool, response(new JSONArray().put(tool("function_call", "fn_final", "call_fixture")))).getJSONObject(0).getString("id"), "fn_final");
    }

    private static void malformed() throws Exception {
        for (Object value : new Object[]{"wrong", 3, new JSONObject(), true})
            denied(() -> new ChatGptOutputItems().complete(response(value)), "output_array_shape");
        for (Object value : new Object[]{JSONObject.NULL, "wrong", new JSONArray(), Json.obj("type", 3),
                Json.obj("type", ""), Json.obj("type", "message", "id", 7), Json.obj("type", "function_call", "call_id", true)}) {
            denied(() -> new ChatGptOutputItems().accept(event("done", 0, value)), "output_item_shape");
            denied(() -> new ChatGptOutputItems().complete(response(new JSONArray().put(value))), "output_item_shape");
        }
        denied(() -> new ChatGptOutputItems().accept(Json.obj("type", "response.output_item.done", "output_index", 0)), "output_item_shape");
        denied(() -> new ChatGptOutputItems().complete(response(new JSONArray().put(message("duplicate", "A")).put(message("duplicate", "B")))), "output_duplicate_identity");
        denied(() -> new ChatGptOutputItems().complete(response(new JSONArray().put(message("same", "A")).put(Json.obj("id", "same", "type", "reasoning")))), "output_conflicting_id");
        denied(() -> new ChatGptOutputItems().accept(null), "output_event");
        denied(() -> new ChatGptOutputItems().complete(null), "output_response");
    }

    private static void lifecycleSnapshots() throws Exception {
        ChatGptOutputItems state = new ChatGptOutputItems();
        state.accept(lifecycle("created", new JSONArray().put(message("msg_created", "pending"))));
        denied(() -> state.complete(response()), "output_unfinished_item");
        eq(text(output(state, response(new JSONArray().put(message("msg_created", "final")))), 0), "final");
        state.accept(event("done", 0, message("msg_created", "done")));
        state.accept(lifecycle("in_progress", new JSONArray().put(message("msg_created", "snapshot finalized"))));
        eq(text(output(state, response()), 0), "snapshot finalized");
        state.accept(lifecycle("queued", JSONObject.NULL));
        eq(output(state, response()).length(), 1);
        denied(() -> state.accept(lifecycle("in_progress", new JSONArray().put(Json.obj("id", "msg_created", "type", "reasoning")))), "output_conflicting_id");
        denied(() -> new ChatGptOutputItems().accept(lifecycle("created", "wrong")), "output_array_shape");
        denied(() -> new ChatGptOutputItems().accept(lifecycle("created", new JSONArray().put(message("duplicate", "A")).put(message("duplicate", "B")))), "output_duplicate_identity");

        ChatGptOutputItems tool = new ChatGptOutputItems();
        tool.accept(event("done", 8, tool("function_call", null, "call_fixture")));
        tool.accept(lifecycle("in_progress", new JSONArray().put(tool("function_call", "tool_id", "call_fixture"))));
        eq(output(tool, response()).getJSONObject(0).getString("id"), "tool_id");
        denied(() -> tool.accept(lifecycle("in_progress", new JSONArray().put(tool("function_call", "changed", "call_fixture")))), "output_snapshot_identity");
    }

    private static void boundaries() throws Exception {
        ChatGptOutputItems sparse = new ChatGptOutputItems();
        sparse.accept(event("done", Long.MAX_VALUE, message("last", "last")));
        sparse.accept(event("done", new BigDecimal("1.0"), message("first", "first")));
        JSONArray result = output(sparse, response());
        eq(result.length(), 2); eq(text(result, 0), "first"); eq(text(result, 1), "last");
        for (Object index : new Object[]{-1, 1.5, "2", JSONObject.NULL, true, new JSONArray(),
                new BigInteger("9223372036854775808"), new BigDecimal("0.0000000001")})
            denied(() -> new ChatGptOutputItems().accept(event("done", index, message("msg_fixture", ""))), "output_index");
        denied(() -> new ChatGptOutputItems().accept(Json.obj("type", "response.output_item.done", "item", message("fixture", ""))), "output_index");
        // Empty added events are ignored even if their unused index is missing.
        new ChatGptOutputItems().accept(Json.obj("type", "response.output_item.added", "item", JSONObject.NULL));
        checks++;
        ChatGptOutputItems many = new ChatGptOutputItems();
        for (int i = 700; i >= 0; i--) many.accept(event("done", i * 10L, message("msg_" + i, "fixture " + i)));
        JSONArray all = output(many, response());
        eq(all.length(), 701); eq(text(all, 0), "fixture 0"); eq(text(all, 700), "fixture 700");
    }
}
