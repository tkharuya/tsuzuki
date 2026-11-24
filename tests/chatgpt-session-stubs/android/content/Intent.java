package android.content;

import android.net.Uri;
import java.util.HashMap;
import java.util.Map;

/** Minimal data-only Intent fixture used by production ChatGptSession. */
public final class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    private final String action;
    private final Uri data;
    private final Class<?> component;
    private final Map<String, Long> extras = new HashMap<>();
    private int flags;
    public Intent(Context context, Class<?> component) { this.action = null; this.data = null; this.component = component; }
    public Intent(String action, Uri data) { this.action = action; this.data = data; this.component = null; }
    public Intent putExtra(String key, long value) { extras.put(key, value); return this; }
    public Intent addFlags(int value) { flags |= value; return this; }
    public String getAction() { return action; }
    public Uri getData() { return data; }
    public Class<?> fixtureComponent() { return component; }
    public int getFlags() { return flags; }
    public long getLongExtra(String key, long fallback) { Long value = extras.get(key); return value == null ? fallback : value; }
}
