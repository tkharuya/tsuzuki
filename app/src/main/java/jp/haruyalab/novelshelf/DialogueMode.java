package jp.haruyalab.novelshelf;

import org.json.JSONObject;

/** Stored mode for dialogue and inner speech. A 1.11 opt-out retains standard narration. */
enum DialogueMode {
    NATURAL("natural", .84), ANIME("anime", 1.32), STANDARD("standard", 1);

    final String id;
    final double wordStrength;

    DialogueMode(String id, double wordStrength) {
        this.id = id;
        this.wordStrength = wordStrength;
    }

    static DialogueMode fromSettings(JSONObject settings) {
        for (DialogueMode mode : values())
            if (mode.id.equals(settings.optString("dialogueMode"))) return mode;
        return settings.optBoolean("animeActing", true) ? NATURAL : STANDARD;
    }

    void storeIn(JSONObject settings) {
        Json.put(settings, "dialogueMode", id);
        Json.put(settings, "animeActing", this != STANDARD);
    }
}
