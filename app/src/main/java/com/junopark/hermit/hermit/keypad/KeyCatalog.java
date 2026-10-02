package com.junopark.hermit.hermit.keypad;

import android.content.Context;

import com.junopark.hermit.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keys a virtual keypad button can send: Windows virtual-key codes (what the host expects) plus
 * the three mouse buttons (negative codes). Labels are the usual keycap names.
 */
public final class KeyCatalog {
    public static final int MOUSE_LEFT = -1;
    public static final int MOUSE_RIGHT = -2;
    public static final int MOUSE_MIDDLE = -3;

    public static final int VK_SHIFT = 0xA0;
    public static final int VK_CTRL = 0xA2;
    public static final int VK_ALT = 0xA4;
    public static final int VK_WIN = 0x5B;

    /** One entry of the catalogue. */
    public static final class Key {
        public final int code;
        public final String label;
        public final int group;

        Key(int code, String label, int group) {
            this.code = code;
            this.label = label;
            this.group = group;
        }
    }

    // Groups, in picker order
    public static final int GROUP_LETTERS = 0;
    public static final int GROUP_DIGITS = 1;
    public static final int GROUP_ARROWS = 2;
    public static final int GROUP_EDITING = 3;
    public static final int GROUP_MODIFIERS = 4;
    public static final int GROUP_FUNCTION = 5;
    public static final int GROUP_SYMBOLS = 6;
    public static final int GROUP_NUMPAD = 7;
    public static final int GROUP_MOUSE = 8;

    private static final List<Key> KEYS = build();

    private KeyCatalog() {
    }

    private static List<Key> build() {
        List<Key> keys = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            keys.add(new Key(c, String.valueOf(c), GROUP_LETTERS));
        }
        for (char c = '0'; c <= '9'; c++) {
            keys.add(new Key(c, String.valueOf(c), GROUP_DIGITS));
        }
        keys.add(new Key(0x26, "↑", GROUP_ARROWS));
        keys.add(new Key(0x28, "↓", GROUP_ARROWS));
        keys.add(new Key(0x25, "←", GROUP_ARROWS));
        keys.add(new Key(0x27, "→", GROUP_ARROWS));
        keys.add(new Key(0x20, "Space", GROUP_EDITING));
        keys.add(new Key(0x0D, "Enter", GROUP_EDITING));
        keys.add(new Key(0x1B, "Esc", GROUP_EDITING));
        keys.add(new Key(0x09, "Tab", GROUP_EDITING));
        keys.add(new Key(0x08, "Bksp", GROUP_EDITING));
        keys.add(new Key(0x2E, "Del", GROUP_EDITING));
        keys.add(new Key(0x2D, "Ins", GROUP_EDITING));
        keys.add(new Key(0x24, "Home", GROUP_EDITING));
        keys.add(new Key(0x23, "End", GROUP_EDITING));
        keys.add(new Key(0x21, "PgUp", GROUP_EDITING));
        keys.add(new Key(0x22, "PgDn", GROUP_EDITING));
        keys.add(new Key(0x14, "Caps", GROUP_EDITING));
        keys.add(new Key(0x2C, "PrtSc", GROUP_EDITING));
        keys.add(new Key(0x13, "Pause", GROUP_EDITING));
        keys.add(new Key(0x15, "한/영", GROUP_EDITING));   // VK_HANGUL
        keys.add(new Key(0x19, "한자", GROUP_EDITING));      // VK_HANJA
        keys.add(new Key(VK_SHIFT, "Shift", GROUP_MODIFIERS));
        keys.add(new Key(VK_CTRL, "Ctrl", GROUP_MODIFIERS));
        keys.add(new Key(VK_ALT, "Alt", GROUP_MODIFIERS));
        keys.add(new Key(0xA1, "RShift", GROUP_MODIFIERS));
        keys.add(new Key(0xA3, "RCtrl", GROUP_MODIFIERS));
        keys.add(new Key(0xA5, "RAlt", GROUP_MODIFIERS));
        keys.add(new Key(0x5B, "Win", GROUP_MODIFIERS));
        for (int i = 1; i <= 12; i++) {
            keys.add(new Key(0x6F + i, "F" + i, GROUP_FUNCTION));
        }
        keys.add(new Key(0xC0, "`", GROUP_SYMBOLS));
        keys.add(new Key(0xBD, "-", GROUP_SYMBOLS));
        keys.add(new Key(0xBB, "=", GROUP_SYMBOLS));
        keys.add(new Key(0xDB, "[", GROUP_SYMBOLS));
        keys.add(new Key(0xDD, "]", GROUP_SYMBOLS));
        keys.add(new Key(0xDC, "\\", GROUP_SYMBOLS));
        keys.add(new Key(0xBA, ";", GROUP_SYMBOLS));
        keys.add(new Key(0xDE, "'", GROUP_SYMBOLS));
        keys.add(new Key(0xBC, ",", GROUP_SYMBOLS));
        keys.add(new Key(0xBE, ".", GROUP_SYMBOLS));
        keys.add(new Key(0xBF, "/", GROUP_SYMBOLS));
        for (int i = 0; i <= 9; i++) {
            keys.add(new Key(0x60 + i, "Num" + i, GROUP_NUMPAD));
        }
        keys.add(new Key(0x6A, "Num*", GROUP_NUMPAD));
        keys.add(new Key(0x6B, "Num+", GROUP_NUMPAD));
        keys.add(new Key(0x6D, "Num-", GROUP_NUMPAD));
        keys.add(new Key(0x6E, "Num.", GROUP_NUMPAD));
        keys.add(new Key(0x6F, "Num/", GROUP_NUMPAD));
        keys.add(new Key(MOUSE_LEFT, null, GROUP_MOUSE));
        keys.add(new Key(MOUSE_RIGHT, null, GROUP_MOUSE));
        keys.add(new Key(MOUSE_MIDDLE, null, GROUP_MOUSE));
        return Collections.unmodifiableList(keys);
    }

    public static List<Key> all() {
        return KEYS;
    }

    public static Key find(int code) {
        for (Key key : KEYS) {
            if (key.code == code) {
                return key;
            }
        }
        return null;
    }

    /** Keycap label of a code (mouse buttons are translated). */
    public static String label(Context context, int code) {
        switch (code) {
            case MOUSE_LEFT:
                return context.getString(R.string.hermit_keypad_mouse_left);
            case MOUSE_RIGHT:
                return context.getString(R.string.hermit_keypad_mouse_right);
            case MOUSE_MIDDLE:
                return context.getString(R.string.hermit_keypad_mouse_middle);
            default:
                Key key = find(code);
                return key != null ? key.label : String.format("0x%02X", code);
        }
    }

    public static String groupName(Context context, int group) {
        int[] names = {
                R.string.hermit_keypad_group_letters, R.string.hermit_keypad_group_digits,
                R.string.hermit_keypad_group_arrows, R.string.hermit_keypad_group_editing,
                R.string.hermit_keypad_group_modifiers, R.string.hermit_keypad_group_function,
                R.string.hermit_keypad_group_symbols, R.string.hermit_keypad_group_numpad,
                R.string.hermit_keypad_group_mouse
        };
        return context.getString(names[group]);
    }
}
