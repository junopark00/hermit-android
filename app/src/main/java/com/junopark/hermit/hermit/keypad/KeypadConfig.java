package com.junopark.hermit.hermit.keypad;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings of the virtual keypad (stick on the left, key grid on the right, a quick menu button),
 * stored as JSON in the default shared preferences. Every binding can be changed; positions are
 * fractions of the screen so they survive rotation and resolution changes.
 */
public class KeypadConfig {
    private static final String PREF = "hermit_keypad_config";

    public static final String STICK_FIXED = "fixed";
    public static final String STICK_FLOATING = "floating";
    public static final String STICK_DPAD = "dpad";

    // Modifier flags of a binding (held around the key)
    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;
    public static final int MOD_ALT = 4;
    public static final int MOD_WIN = 8;

    // A combination: at most this many keys after the modifiers, and this long between presses
    public static final int MAX_COMBO_KEYS = 5;
    public static final int MAX_DELAY_MS = 500;

    /**
     * What a control sends: a KeyCatalog code, optional modifiers and an optional label. A
     * combination adds more keys (chain), pressed in order after the first; the modifiers go down
     * first. With a delay, every key waits that long after the previous one, and the keys go up
     * in reverse order the same way (for games and programs that miss keys pressed together).
     */
    public static class Binding {
        public int code;
        public int modifiers;
        public int[] chain = new int[0];
        public int delayMs;
        public String label = "";

        public Binding(int code) {
            this.code = code;
        }

        Binding copy() {
            Binding b = new Binding(code);
            b.modifiers = modifiers;
            b.chain = chain.clone();
            b.delayMs = delayMs;
            b.label = label;
            return b;
        }

        /** Every key in pressing order: Ctrl, Alt, Shift, Win (when set), then the keys. */
        public List<Integer> steps() {
            List<Integer> steps = new ArrayList<>();
            if ((modifiers & MOD_CTRL) != 0) steps.add(KeyCatalog.VK_CTRL);
            if ((modifiers & MOD_ALT) != 0) steps.add(KeyCatalog.VK_ALT);
            if ((modifiers & MOD_SHIFT) != 0) steps.add(KeyCatalog.VK_SHIFT);
            if ((modifiers & MOD_WIN) != 0) steps.add(KeyCatalog.VK_WIN);
            steps.add(code);
            for (int c : chain) {
                steps.add(c);
            }
            return steps;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("code", code);
            o.put("mods", modifiers);
            o.put("label", label);
            if (chain.length > 0) {
                JSONArray keys = new JSONArray();
                for (int c : chain) {
                    keys.put(c);
                }
                o.put("chain", keys);
            }
            if (delayMs > 0) {
                o.put("delay", delayMs);
            }
            return o;
        }

        static Binding fromJson(JSONObject o, Binding fallback) {
            if (o == null) {
                return fallback;
            }
            int code = o.optInt("code", fallback.code);
            // Only keys this app knows (a damaged setting could hold anything)
            Binding b = new Binding(KeyCatalog.find(code) != null ? code : fallback.code);
            b.modifiers = o.optInt("mods", 0) & (MOD_SHIFT | MOD_CTRL | MOD_ALT | MOD_WIN);
            b.label = o.optString("label", "");
            JSONArray keys = o.optJSONArray("chain");
            if (keys != null) {
                List<Integer> chain = new ArrayList<>();
                for (int i = 0; i < keys.length() && chain.size() < MAX_COMBO_KEYS - 1; i++) {
                    int key = keys.optInt(i, Integer.MIN_VALUE);
                    if (KeyCatalog.find(key) != null) {
                        chain.add(key);
                    }
                }
                b.chain = new int[chain.size()];
                for (int i = 0; i < b.chain.length; i++) {
                    b.chain[i] = chain.get(i);
                }
            }
            b.delayMs = clamp(o.optInt("delay", 0), 0, MAX_DELAY_MS);
            return b;
        }

        /** What the button shows: the custom label, or the key names with the modifiers. */
        public String displayLabel(Context context) {
            if (label != null && !label.isEmpty()) {
                return label;
            }
            return keysLabel(context);
        }

        /** The keys, as "Ctrl+Alt+Del" (whatever the label). */
        public String keysLabel(Context context) {
            StringBuilder sb = new StringBuilder();
            if ((modifiers & MOD_CTRL) != 0) sb.append("Ctrl+");
            if ((modifiers & MOD_ALT) != 0) sb.append("Alt+");
            if ((modifiers & MOD_SHIFT) != 0) sb.append("Shift+");
            if ((modifiers & MOD_WIN) != 0) sb.append("Win+");
            sb.append(KeyCatalog.label(context, code));
            for (int c : chain) {
                sb.append('+').append(KeyCatalog.label(context, c));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "Binding" + Arrays.toString(steps().toArray());
        }
    }

    public boolean enabled = false;

    // Stick (left)
    public String stickMode = STICK_FIXED;
    public boolean eightWay = true;
    public int stickSizeDp = 150;
    public float stickX = 0.15f;   // centre, fraction of the width
    public float stickY = 0.70f;   // centre, fraction of the height
    public Binding up = new Binding(0x26), down = new Binding(0x28), left = new Binding(0x25), right = new Binding(0x27);

    // Key grid (right)
    public int rows = 3;
    public int columns = 4;
    public float stagger = 0.5f;   // each row moves right by this fraction of a key
    public int keySizeDp = 56;
    public int spacingDp = 10;
    public float gridRight = 0.97f;   // right edge, fraction of the width
    public float gridBottom = 0.92f;  // bottom edge, fraction of the height
    public final List<Binding> keys = new ArrayList<>();

    public float opacity = 0.55f;
    public boolean haptics = true;   // a short vibration on every press

    // Quick menu button (touch mode, quality, keypad editor...); its centre, fractions of the
    // screen, or below 0 to sit above the key grid's right end
    public boolean quickMenu = true;
    public float menuX = -1f;
    public float menuY = -1f;

    public static final int MAX_ROWS = 4;
    public static final int MAX_COLUMNS = 6;

    // Default keys, row by row (top row first)
    private static final int[] DEFAULT_KEYS = {
            'Q', 'W', 'E', 'R', 'T', 'Y',
            'A', 'S', 'D', 'F', 'G', 'H',
            'Z', 'X', 'C', 'V', 'B', 'N',
            0x20, KeyCatalog.VK_SHIFT, KeyCatalog.VK_CTRL, KeyCatalog.VK_ALT, 0x0D, 0x1B,
    };

    public KeypadConfig() {
        ensureKeys();
    }

    /** Keys are stored for the full MAX_ROWS x MAX_COLUMNS grid so resizing keeps bindings. */
    private void ensureKeys() {
        while (keys.size() < MAX_ROWS * MAX_COLUMNS) {
            keys.add(new Binding(DEFAULT_KEYS[keys.size()]));
        }
    }

    public Binding key(int row, int column) {
        return keys.get(row * MAX_COLUMNS + column);
    }

    public void setKey(int row, int column, Binding binding) {
        keys.set(row * MAX_COLUMNS + column, binding);
    }

    public KeypadConfig copy() {
        KeypadConfig c = new KeypadConfig();
        try {
            c.readJson(toJson());
        } catch (JSONException ignored) {
        }
        return c;
    }

    // ---- Storage --------------------------------------------------------------------------

    public static KeypadConfig load(Context context) {
        KeypadConfig config = new KeypadConfig();
        String json = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, null);
        if (json != null) {
            try {
                config.readJson(new JSONObject(json));
            } catch (JSONException ignored) {
                // Damaged settings: start from the defaults
            }
        }
        return config;
    }

    public void save(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putString(PREF, toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("stickMode", stickMode);
        o.put("eightWay", eightWay);
        o.put("stickSizeDp", stickSizeDp);
        o.put("stickX", stickX);
        o.put("stickY", stickY);
        o.put("up", up.toJson());
        o.put("down", down.toJson());
        o.put("left", left.toJson());
        o.put("right", right.toJson());
        o.put("rows", rows);
        o.put("columns", columns);
        o.put("stagger", stagger);
        o.put("keySizeDp", keySizeDp);
        o.put("spacingDp", spacingDp);
        o.put("gridRight", gridRight);
        o.put("gridBottom", gridBottom);
        o.put("opacity", opacity);
        o.put("haptics", haptics);
        o.put("quickMenu", quickMenu);
        o.put("menuX", menuX);
        o.put("menuY", menuY);
        JSONArray array = new JSONArray();
        for (Binding b : keys) {
            array.put(b.toJson());
        }
        o.put("keys", array);
        return o;
    }

    void readJson(JSONObject o) {
        enabled = o.optBoolean("enabled", enabled);
        String mode = o.optString("stickMode", stickMode);
        stickMode = STICK_FLOATING.equals(mode) || STICK_DPAD.equals(mode) ? mode : STICK_FIXED;
        eightWay = o.optBoolean("eightWay", eightWay);
        stickSizeDp = clamp(o.optInt("stickSizeDp", stickSizeDp), 80, 260);
        stickX = clamp((float) o.optDouble("stickX", stickX), 0f, 1f);
        stickY = clamp((float) o.optDouble("stickY", stickY), 0f, 1f);
        up = Binding.fromJson(o.optJSONObject("up"), up);
        down = Binding.fromJson(o.optJSONObject("down"), down);
        left = Binding.fromJson(o.optJSONObject("left"), left);
        right = Binding.fromJson(o.optJSONObject("right"), right);
        rows = clamp(o.optInt("rows", rows), 1, MAX_ROWS);
        columns = clamp(o.optInt("columns", columns), 1, MAX_COLUMNS);
        stagger = clamp((float) o.optDouble("stagger", stagger), 0f, 1f);
        keySizeDp = clamp(o.optInt("keySizeDp", keySizeDp), 36, 110);
        spacingDp = clamp(o.optInt("spacingDp", spacingDp), 0, 40);
        gridRight = clamp((float) o.optDouble("gridRight", gridRight), 0f, 1f);
        gridBottom = clamp((float) o.optDouble("gridBottom", gridBottom), 0f, 1f);
        opacity = clamp((float) o.optDouble("opacity", opacity), 0.15f, 1f);
        haptics = o.optBoolean("haptics", haptics);
        quickMenu = o.optBoolean("quickMenu", quickMenu);
        menuX = clamp((float) o.optDouble("menuX", menuX), -1f, 1f);
        menuY = clamp((float) o.optDouble("menuY", menuY), -1f, 1f);
        if (menuX < 0 || menuY < 0) {
            menuX = menuY = -1f;
        }
        JSONArray array = o.optJSONArray("keys");
        if (array != null) {
            keys.clear();
            for (int i = 0; i < array.length() && i < MAX_ROWS * MAX_COLUMNS; i++) {
                keys.add(Binding.fromJson(array.optJSONObject(i), new Binding(DEFAULT_KEYS[i])));
            }
        }
        ensureKeys();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
