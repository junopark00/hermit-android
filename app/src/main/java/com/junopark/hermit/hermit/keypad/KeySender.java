package com.junopark.hermit.hermit.keypad;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseIntArray;

import com.junopark.hermit.nvstream.NvConnection;
import com.junopark.hermit.nvstream.input.KeyboardPacket;
import com.junopark.hermit.nvstream.input.MouseButtonPacket;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Sends the keypad's key presses to the host. Several controls can hold the same key (or the
 * same modifier) at once, so presses are counted per key and a key goes up only when the last
 * control releases it.
 *
 * A binding's keys go down in order (modifiers first) and up in reverse order. With a delay
 * between keys, the presses and releases are spread out on the main thread. A button's release
 * that comes before the last press waits for it, so a short tap still sends the whole
 * combination; a stick direction's release drops the presses still to come instead, so the next
 * direction never overlaps it.
 */
public class KeySender {
    private final NvConnection conn;
    private final SparseIntArray held = new SparseIntArray();
    // Only this sender's delayed presses and releases run on it (releaseAll drops them all)
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** A delayed press in progress: its keys, how many went down, when the last one goes down. */
    private static final class Run {
        final List<Integer> steps;
        final long end;
        int pressed;

        Run(List<Integer> steps, long end) {
            this.steps = steps;
            this.end = end;
        }
    }

    // Bindings pressed with a delay (the Run is also the token of its pending presses)
    private final IdentityHashMap<KeypadConfig.Binding, Run> runs = new IdentityHashMap<>();

    public KeySender(NvConnection conn) {
        this.conn = conn;
    }

    public void press(KeypadConfig.Binding binding) {
        List<Integer> steps = binding.steps();
        if (binding.delayMs <= 0 || steps.size() < 2) {
            for (int code : steps) {
                down(code);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        Run run = new Run(steps, now + (long) (steps.size() - 1) * binding.delayMs);
        for (int i = 0; i < steps.size(); i++) {
            final int code = steps.get(i);
            handler.postAtTime(() -> {
                down(code);
                run.pressed++;
            }, run, now + (long) i * binding.delayMs);
        }
        runs.put(binding, run);
    }

    /** Releases a button's binding: a combination still being pressed is completed first. */
    public void release(KeypadConfig.Binding binding) {
        release(binding, true);
    }

    /**
     * @param complete true: keys still to be pressed go down first (a tap sends the whole
     *                 combination); false: they are dropped and only the pressed ones go up
     */
    public void release(KeypadConfig.Binding binding, boolean complete) {
        Run run = runs.remove(binding);
        if (run == null) {
            List<Integer> steps = binding.steps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                up(steps.get(i));
            }
            return;
        }
        long start;
        List<Integer> pressed;
        if (complete) {
            start = Math.max(SystemClock.uptimeMillis(), run.end + binding.delayMs);
            pressed = run.steps;
        } else {
            handler.removeCallbacksAndMessages(run);
            start = SystemClock.uptimeMillis();
            pressed = run.steps.subList(0, run.pressed);
        }
        for (int i = pressed.size() - 1, n = 0; i >= 0; i--, n++) {
            final int code = pressed.get(i);
            handler.postAtTime(() -> up(code), start + (long) n * binding.delayMs);
        }
    }

    /** Lifts everything still held (keypad hidden, settings changed, stream ending). */
    public void releaseAll() {
        handler.removeCallbacksAndMessages(null);
        runs.clear();
        for (int i = held.size() - 1; i >= 0; i--) {
            int code = held.keyAt(i);
            if (held.valueAt(i) > 0) {
                if (code < 0) {
                    conn.sendMouseButtonUp(mouseButton(code));
                } else {
                    held.put(code, 0);
                    conn.sendKeyboardInput(keyMap(code), KeyboardPacket.KEY_UP, modifierState(), (byte) 0);
                }
            }
        }
        held.clear();
    }

    private void down(int code) {
        int count = held.get(code) + 1;
        held.put(code, count);
        if (count == 1) {
            if (code < 0) {
                conn.sendMouseButtonDown(mouseButton(code));
            } else {
                conn.sendKeyboardInput(keyMap(code), KeyboardPacket.KEY_DOWN, modifierState(), (byte) 0);
            }
        }
    }

    private void up(int code) {
        int count = held.get(code);
        if (count <= 0) {
            return;
        }
        held.put(code, count - 1);
        if (count == 1) {
            if (code < 0) {
                conn.sendMouseButtonUp(mouseButton(code));
            } else {
                conn.sendKeyboardInput(keyMap(code), KeyboardPacket.KEY_UP, modifierState(), (byte) 0);
            }
        }
    }

    private byte modifierState() {
        byte state = 0;
        if (held.get(0xA0) > 0 || held.get(0xA1) > 0) state |= KeyboardPacket.MODIFIER_SHIFT;
        if (held.get(0xA2) > 0 || held.get(0xA3) > 0) state |= KeyboardPacket.MODIFIER_CTRL;
        if (held.get(0xA4) > 0 || held.get(0xA5) > 0) state |= KeyboardPacket.MODIFIER_ALT;
        if (held.get(0x5B) > 0) state |= KeyboardPacket.MODIFIER_META;
        return state;
    }

    // Same format as KeyboardTranslator: the host key prefix and the Windows virtual-key code
    private static short keyMap(int vk) {
        return (short) ((0x80 << 8) | (vk & 0xFF));
    }

    private static byte mouseButton(int code) {
        switch (code) {
            case KeyCatalog.MOUSE_RIGHT:
                return MouseButtonPacket.BUTTON_RIGHT;
            case KeyCatalog.MOUSE_MIDDLE:
                return MouseButtonPacket.BUTTON_MIDDLE;
            default:
                return MouseButtonPacket.BUTTON_LEFT;
        }
    }
}
