package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Retains finalized Responses items without treating text deltas as completed output.
 * The caller alone decides whether the response has reached a successful terminal event.
 * Identity and missing-output recovery follow openai-python's streaming responses state.
 */
final class ChatGptOutputItems {
    private final Map<Long, Item> added = new HashMap<>();
    private final TreeMap<Long, Item> done = new TreeMap<>();
    private final Map<Identity, Long> donePositions = new HashMap<>();
    private final Map<String, Identity> doneIds = new HashMap<>();
    private boolean lifecycleSeen;

    static final class Invalid extends Exception {
        final String stage;
        private Invalid(String stage) {
            super("Finalized ChatGPT output could not be verified.");
            this.stage = stage;
        }
    }

    void accept(JSONObject event) throws Invalid {
        if (event == null) throw new Invalid("output_event");
        String type = event.optString("type");
        if ("response.output_item.added".equals(type)) {
            // Some providers emit empty added events. Their indexes may leave gaps.
            if (!event.has("item") || event.isNull("item")) return;
            long index = index(event);
            Item item = item(event.opt("item"));
            if (added.containsKey(index)) throw new Invalid("output_duplicate_index");
            Item completed = done.get(index);
            if (completed != null && !compatible(item, completed)) throw new Invalid("output_item_identity");
            added.put(index, item);
        } else if ("response.output_item.done".equals(type)) {
            long index = index(event);
            Item item = item(event.opt("item"));
            Item pending = added.get(index), previous = done.get(index);
            if (pending != null && !compatible(pending, item)
                    || previous != null && !compatible(previous, item))
                throw new Invalid("output_item_identity");
            Long position = donePositions.get(item.identity);
            if (position != null && position.longValue() != index) throw new Invalid("output_duplicate_identity");
            checkId(doneIds, item);
            if (previous != null && previous.id != null) doneIds.remove(previous.id);
            done.put(index, item);
            donePositions.put(item.identity, index);
            if (item.id != null) doneIds.put(item.id, item.identity);
        } else if ("response.created".equals(type) || "response.queued".equals(type)
                || "response.in_progress".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            if (response == null) return; // Envelope validation belongs to the SSE reader.
            Catalog snapshot = output(response);
            if (!lifecycleSeen && "response.created".equals(type) && added.isEmpty() && done.isEmpty()) {
                // A created response can already contain output items. They are still pending.
                for (int i = 0; i < snapshot.items.length; i++) added.put((long) i, snapshot.items[i]);
            } else {
                for (Item replacement : snapshot.items) {
                    checkId(doneIds, replacement);
                    Long position = donePositions.get(replacement.identity);
                    if (position == null) continue;
                    Item previous = done.get(position);
                    if (!compatible(previous, replacement)) throw new Invalid("output_snapshot_identity");
                    if (previous.id != null) doneIds.remove(previous.id);
                    done.put(position, replacement);
                    if (replacement.id != null) doneIds.put(replacement.id, replacement.identity);
                }
            }
            lifecycleSeen = true;
        }
        // Raw deltas, content-part/text done events and unknown metadata events are not output items.
    }

    JSONObject complete(JSONObject response) throws Invalid {
        if (response == null) throw new Invalid("output_response");
        boolean omitted = !response.has("output") || response.isNull("output");
        Catalog finalOutput = output(response);
        for (Item completed : done.values()) {
            Item finalItem = finalOutput.byIdentity.get(completed.identity);
            if (finalItem != null && !compatible(completed, finalItem)) throw new Invalid("output_final_identity");
            checkId(finalOutput.byId, completed);
        }
        for (Map.Entry<Long, Item> entry : added.entrySet()) {
            Item pending = entry.getValue();
            checkId(finalOutput.byId, pending);
            if (done.containsKey(entry.getKey())) continue;
            Item finalItem = finalOutput.byIdentity.get(pending.identity);
            if (finalItem == null || !compatible(pending, finalItem)) throw new Invalid("output_unfinished_item");
        }
        JSONObject result = copy(response);
        if (omitted) {
            JSONArray recovered = new JSONArray();
            // Sparse indexes are sorted, never used to allocate a potentially huge array.
            for (Item completed : done.values()) recovered.put(copy(completed.value));
            Json.put(result, "output", recovered);
        }
        // An explicit final array, including [], always remains authoritative.
        return result;
    }

    private static long index(JSONObject event) throws Invalid {
        Object value = event.opt("output_index");
        if (!(value instanceof Number)) throw new Invalid("output_index");
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < 0) throw new Invalid("output_index");
            return result;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new Invalid("output_index");
        }
    }

    private static Item item(Object value) throws Invalid {
        if (!(value instanceof JSONObject)) throw new Invalid("output_item_shape");
        JSONObject object = (JSONObject) value;
        Object type = object.opt("type");
        if (!(type instanceof String) || ((String) type).isEmpty()) throw new Invalid("output_item_shape");
        return new Item(copy(object), (String) type, optionalString(object, "id"), optionalString(object, "call_id"));
    }

    private static String optionalString(JSONObject object, String key) throws Invalid {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw new Invalid("output_item_shape");
        return (String) value;
    }

    private static Catalog output(JSONObject response) throws Invalid {
        Object value = response.opt("output");
        if (value == null || value == JSONObject.NULL) return new Catalog(new Item[0]);
        if (!(value instanceof JSONArray)) throw new Invalid("output_array_shape");
        JSONArray array = (JSONArray) value;
        Item[] items = new Item[array.length()];
        for (int i = 0; i < array.length(); i++) items[i] = item(array.opt(i));
        return new Catalog(items);
    }

    private static JSONObject copy(JSONObject source) throws Invalid {
        try { return new JSONObject(source.toString()); }
        catch (Exception invalid) { throw new Invalid("output_item_shape"); }
    }

    private static boolean compatible(Item previous, Item replacement) {
        return previous.identity.equals(replacement.identity)
                && (equal(previous.id, replacement.id) || previous.id == null
                && ("function_call".equals(previous.type) || "custom_tool_call".equals(previous.type)));
    }

    private static void checkId(Map<String, Identity> ids, Item item) throws Invalid {
        if (item.id == null) return;
        Identity existing = ids.get(item.id);
        if (existing != null && !existing.equals(item.identity)) throw new Invalid("output_conflicting_id");
    }

    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    private static final class Item {
        final JSONObject value;
        final String type, id;
        final Identity identity;
        Item(JSONObject value, String type, String id, String callId) {
            this.value = value; this.type = type; this.id = id;
            boolean byCall = "function_call".equals(type) || "custom_tool_call".equals(type)
                    || "shell_call".equals(type) || "shell_call_output".equals(type);
            identity = new Identity(type, byCall ? null : id, callId);
        }
    }

    private static final class Catalog {
        final Item[] items;
        final Map<Identity, Item> byIdentity = new HashMap<>();
        final Map<String, Identity> byId = new HashMap<>();
        Catalog(Item[] items) throws Invalid {
            this.items = items;
            for (Item item : items) {
                if (byIdentity.put(item.identity, item) != null) throw new Invalid("output_duplicate_identity");
                checkId(byId, item);
                if (item.id != null) byId.put(item.id, item.identity);
            }
        }
    }

    private static final class Identity {
        final String type, id, callId;
        Identity(String type, String id, String callId) { this.type = type; this.id = id; this.callId = callId; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity item = (Identity) other;
            return type.equals(item.type) && equal(id, item.id) && equal(callId, item.callId);
        }
        @Override public int hashCode() {
            return 31 * (31 * type.hashCode() + (id == null ? 0 : id.hashCode())) + (callId == null ? 0 : callId.hashCode());
        }
    }
}
