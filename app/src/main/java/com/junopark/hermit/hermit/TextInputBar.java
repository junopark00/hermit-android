package com.junopark.hermit.hermit;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.junopark.hermit.R;
import com.junopark.hermit.hermit.keypad.KeySender;
import com.junopark.hermit.hermit.keypad.KeypadConfig;
import com.junopark.hermit.nvstream.NvConnection;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Text input for the host, like Chrome Remote Desktop's keyboard: type in a field at the top of
 * the screen with the phone keyboard (Korean composition stays on the phone) and send the
 * finished text as Unicode, so it arrives intact whatever the host's input method is.
 * ⌫ deletes in the field while it has text, otherwise on the host (the keyboard's Backspace on
 * an empty field too); ↵ sends the text, then Enter. ⌨ switches to the phone keyboard sending
 * keys straight to the host (arrows, Esc, game keys). A row of keys and shortcuts the phone
 * keyboard lacks (Shift+Enter, Esc, Tab, arrows, Ctrl+C/V, Alt+Tab, Ctrl+Alt+Del...) sits under
 * the field; text still in the field is sent before the key.
 */
public class TextInputBar {
    /** What the stream activity does for the bar. */
    public interface Host {
        /** The bar opened (Android 11+ it sits at the bottom: other bottom layers move away). */
        void onTextInputShown();

        /** The bar closed: give the focus back to the stream. */
        void onTextInputClosed();

        /** Open the phone keyboard sending keys to the host. */
        void onRawKeyboard();
    }

    // Bytes per text packet (core library UTF8_TEXT_EVENT_MAX_COUNT is 32)
    private static final int MAX_CHUNK_BYTES = 30;
    // A key after sent text: a little later, as text and keys travel on different channels and a
    // lost and resent text packet is then less likely to arrive after the key
    private static final int KEY_AFTER_TEXT_MS = 60;

    private static final int CTRL = KeypadConfig.MOD_CTRL, ALT = KeypadConfig.MOD_ALT, SHIFT = KeypadConfig.MOD_SHIFT;

    /** A key of the shortcut row: what it shows, the Windows virtual-key code and modifiers. */
    private static final class Shortcut {
        final String label;
        final int vk, modifiers;
        final int descriptionRes;  // 0: the label says it all

        Shortcut(String label, int vk, int modifiers, int descriptionRes) {
            this.label = label;
            this.vk = vk;
            this.modifiers = modifiers;
            this.descriptionRes = descriptionRes;
        }
    }

    // Ctrl+Alt+Del is turned into the secure attention sequence by Shell (Windows ignores it as
    // ordinary input)
    private static final Shortcut[] SHORTCUTS = {
            new Shortcut("Shift+Enter", 0x0D, SHIFT, R.string.hermit_key_shift_enter),
            new Shortcut("Esc", 0x1B, 0, 0),
            new Shortcut("Tab", 0x09, 0, 0),
            new Shortcut("←", 0x25, 0, R.string.hermit_key_left),
            new Shortcut("↑", 0x26, 0, R.string.hermit_key_up),
            new Shortcut("↓", 0x28, 0, R.string.hermit_key_down),
            new Shortcut("→", 0x27, 0, R.string.hermit_key_right),
            new Shortcut("Del", 0x2E, 0, R.string.hermit_key_delete),
            new Shortcut("Ctrl+A", 'A', CTRL, R.string.hermit_key_select_all),
            new Shortcut("Ctrl+C", 'C', CTRL, R.string.hermit_key_copy),
            new Shortcut("Ctrl+X", 'X', CTRL, R.string.hermit_key_cut),
            new Shortcut("Ctrl+V", 'V', CTRL, R.string.hermit_key_paste),
            new Shortcut("Ctrl+Z", 'Z', CTRL, R.string.hermit_key_undo),
            new Shortcut("Alt+Tab", 0x09, ALT, R.string.hermit_key_alt_tab),
            new Shortcut("Win", 0x5B, 0, R.string.hermit_key_win),
            new Shortcut("Ctrl+Shift+Esc", 0x1B, CTRL | SHIFT, R.string.hermit_key_task_manager),
            new Shortcut("Ctrl+Alt+Del", 0x2E, CTRL | ALT, R.string.hermit_key_ctrl_alt_del),
    };

    private final Activity activity;
    private final NvConnection conn;
    private final KeySender keys;
    private final Host host;
    private final LinearLayout bar;
    private final LinearLayout row, keyRow;
    private final HorizontalScrollView keyScroller;
    // ⌫ ↵ Send ⌨: beside the field, or at the start of the key row when the bar is narrow
    private final View[] actions;
    private boolean compact;
    // The keyboard button re-showed the open bar (to bring the phone keyboard back)
    private boolean reshown;
    // Android 11+: a while after a re-show, whether a phone keyboard came
    private final Runnable reshownCheck = () -> reshown = isShown() && !imeVisible();
    // Below this width the field would be squeezed by the buttons beside it (a phone in portrait)
    private static final int COMPACT_BELOW_DP = 560;
    // Between the bar and the phone keyboard (or the screen's bottom edge)
    private static final int KEYBOARD_GAP_DP = 4;
    // The window's soft input mode while the bar is closed (restored when it closes)
    private int savedSoftInputMode = -1;
    private final EditText field;
    private final float density;

    public TextInputBar(Activity activity, NvConnection conn, KeySender keys, Host host) {
        this.activity = activity;
        this.conn = conn;
        this.keys = keys;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.hermit_layer1));
        bg.setStroke(dp(1), color(R.color.hermit_border_strong));
        bar.setBackground(bg);
        bar.setClickable(true);   // touches on the bar do not reach the stream
        bar.setVisibility(View.GONE);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        field = new EditText(activity) {
            // Keyboards that delete with deleteSurroundingText (not a Backspace key event) on an
            // empty field: delete on the host too
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                InputConnection base = super.onCreateInputConnection(outAttrs);
                if (base == null) {
                    return null;
                }
                return new InputConnectionWrapper(base, true) {
                    @Override
                    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                        if (beforeLength > 0 && length() == 0) {
                            tap(0x08);
                            return true;
                        }
                        return super.deleteSurroundingText(beforeLength, afterLength);
                    }
                };
            }
        };
        field.setHint(R.string.hermit_text_input_hint);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendText();
                return true;
            }
            // The Enter key's release too, or the field moves the focus to the next view
            return event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
        });
        // Backspace with nothing left to delete here deletes on the host
        field.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN && field.length() == 0) {
                tap(0x08);
                return true;
            }
            return false;
        });
        row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        actions = new View[]{
                button("⌫", R.string.hermit_text_input_backspace, v -> backspace()),
                button("↵", R.string.hermit_text_input_enter, v -> sendTextThenKey(new KeypadConfig.Binding(0x0D))),
                button(activity.getString(R.string.hermit_text_input_send), R.string.hermit_text_input_send, v -> sendText()),
                button("⌨", R.string.hermit_text_input_raw_keyboard, v -> {
                    hide(false);  // the keyboard stays up, now for the stream
                    host.onRawKeyboard();
                })};
        for (View action : actions) {
            row.addView(action);
        }
        row.addView(button("✕", R.string.hermit_text_input_close, v -> hide()));

        // The shortcut row, scrolled sideways on narrow screens
        keyRow = new LinearLayout(activity);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        for (Shortcut s : SHORTCUTS) {
            KeypadConfig.Binding binding = new KeypadConfig.Binding(s.vk);
            binding.modifiers = s.modifiers;
            TextView key = button(s.label, s.descriptionRes, v -> sendTextThenKey(binding));
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            key.setMinHeight(dp(40));
            key.setMinWidth(dp(44));
            key.setPadding(dp(10), dp(4), dp(10), dp(4));
            keyRow.addView(key);
        }
        alignKeyRow();
        keyScroller = new HorizontalScrollView(activity);
        keyScroller.setHorizontalScrollBarEnabled(false);
        keyScroller.addView(keyRow);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(6);
        bar.addView(keyScroller, klp);

        // Android 11+: right above the phone keyboard (the stream above stays in view, as in
        // Chrome Remote Desktop), following it through the window insets. Before that a
        // fullscreen window gets no keyboard insets: at the top, where the keyboard never covers
        // it. The side margins keep it clear of the stream panel handle at either edge.
        boolean aboveKeyboard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                aboveKeyboard ? Gravity.BOTTOM : Gravity.TOP);
        if (aboveKeyboard) {
            lp.bottomMargin = dp(KEYBOARD_GAP_DP);
        } else {
            lp.topMargin = dp(34);
        }
        lp.leftMargin = dp(48);
        lp.rightMargin = dp(48);
        FrameLayout content = activity.findViewById(android.R.id.content);
        content.addView(bar, lp);
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                v.post(this::arrange);
            }
        });
        // The phone keyboard came (perhaps slowly): the keyboard button brings it back next time
        bar.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    reshown = false;
                }
                int bottom = insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
                if (params.bottomMargin != bottom + dp(KEYBOARD_GAP_DP)) {
                    params.bottomMargin = bottom + dp(KEYBOARD_GAP_DP);
                    v.setLayoutParams(params);
                }
            }
            return v.onApplyWindowInsets(insets);
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Slides with the keyboard: the margin already has the end position (the insets
            // listener gets the end state first), the translation covers the way there
            bar.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
                @Override
                public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> animations) {
                    int now = insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom;
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
                    bar.setTranslationY(params.bottomMargin - dp(KEYBOARD_GAP_DP) - now);
                    return insets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    bar.setTranslationY(0);
                }
            });
        }
        arrange();
    }

    // Narrow (portrait): the action buttons go to the start of the key row, so the field keeps
    // its width and ✕ stays on the bar
    private void arrange() {
        View parent = (View) bar.getParent();
        if (parent == null || parent.getWidth() == 0) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int width = parent.getWidth() - lp.leftMargin - lp.rightMargin;
        boolean narrow = width < dp(COMPACT_BELOW_DP);
        if (narrow == compact) {
            return;
        }
        compact = narrow;
        for (View action : actions) {
            ((LinearLayout) action.getParent()).removeView(action);
        }
        for (int i = 0; i < actions.length; i++) {
            if (compact) {
                keyRow.addView(actions[i], i);
            } else {
                row.addView(actions[i], 1 + i);  // after the field, before ✕
            }
        }
        alignKeyRow();
    }

    // The first key lines up with the field; the others keep their gap
    private void alignKeyRow() {
        for (int i = 0; i < keyRow.getChildCount(); i++) {
            LinearLayout.LayoutParams klp = (LinearLayout.LayoutParams) keyRow.getChildAt(i).getLayoutParams();
            klp.setMarginStart(i == 0 ? 0 : dp(6));
            keyRow.getChildAt(i).setLayoutParams(klp);
        }
        for (int i = 1; i < row.getChildCount(); i++) {
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            rlp.setMarginStart(dp(6));
            row.getChildAt(i).setLayoutParams(rlp);
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private int color(int res) {
        return activity.getResources().getColor(res);
    }

    private TextView button(String text, int descriptionRes, View.OnClickListener onClick) {
        TextView b = new TextView(activity);
        b.setText(text);
        String description = descriptionRes != 0 ? activity.getString(descriptionRes) : text;
        b.setContentDescription(description);
        b.setTextColor(color(R.color.hermit_text_primary));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(48));
        b.setMinHeight(dp(48));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Long press (or hover) explains the symbol buttons
            b.setTooltipText(description);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.hermit_layer2));
        bg.setCornerRadius(dp(3));
        b.setBackground(HermitPress.background(activity, bg, dp(3)));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            onClick.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** Puts the bar back above layers added after it (the on-screen controller's buttons). */
    public void raise() {
        if (isShown()) {
            bar.bringToFront();
        }
    }

    public boolean isShown() {
        return bar.getVisibility() == View.VISIBLE;
    }

    /**
     * The keyboard button: opens the bar; with the bar open, brings the phone keyboard back when
     * Back hid it, else closes the bar.
     */
    public void onKeyboardButton() {
        // A keyboard that never comes (a hardware keyboard, or none set up): the press after a
        // re-show closes the bar
        if (isShown() && (imeVisible() || hardwareKeyboard() || reshown)) {
            hide();
            return;
        }
        boolean wasShown = isShown();
        show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Known on Android 11+: only a re-show that brought no keyboard makes the next press
            // close (a keyboard that comes later clears it, see the insets listener)
            reshown = false;
            if (wasShown) {
                field.postDelayed(reshownCheck, 600);
            }
        } else {
            reshown = wasShown;
        }
    }

    private boolean hardwareKeyboard() {
        android.content.res.Configuration c = activity.getResources().getConfiguration();
        return c.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
                c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    // Before Android 11 this cannot be known: taken as hidden (✕ and Back still close the bar)
    private boolean imeVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = field.getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        return false;
    }

    public void show() {
        field.removeCallbacks(reshownCheck);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && savedSoftInputMode < 0) {
            // The bar moves above the keyboard itself: the window must not also pan up to show
            // the focused field
            savedSoftInputMode = activity.getWindow().getAttributes().softInputMode;
            activity.getWindow().setSoftInputMode((savedSoftInputMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) |
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        boolean wasShown = isShown();
        arrange();
        // From the start of the key row (in portrait the action buttons are there)
        keyScroller.scrollTo(0, 0);
        bar.setVisibility(View.VISIBLE);
        bar.bringToFront();
        if (!wasShown) {
            host.onTextInputShown();
        }
        field.requestFocus();
        // After the input method has seen the new focus, or the request can be ignored
        field.postDelayed(() -> {
            if (!isShown()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && field.getWindowInsetsController() != null) {
                field.getWindowInsetsController().show(WindowInsets.Type.ime());
            } else {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(field, 0);
                }
            }
        }, 100);
    }

    public void hide() {
        hide(true);
    }

    private void hide(boolean hideKeyboard) {
        reshown = false;
        field.removeCallbacks(reshownCheck);
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && hideKeyboard) {
            imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        }
        boolean wasShown = isShown();
        field.clearFocus();
        bar.setVisibility(View.GONE);
        bar.setTranslationY(0);
        if (savedSoftInputMode >= 0) {
            activity.getWindow().setSoftInputMode(savedSoftInputMode);
            savedSoftInputMode = -1;
        }
        if (wasShown) {
            host.onTextInputClosed();
        }
    }

    // Deletes the last character typed here, or on the host when the field is empty
    private void backspace() {
        int length = field.length();
        if (length == 0) {
            tap(0x08);
            return;
        }
        CharSequence text = field.getText();
        int remove = Character.isLowSurrogate(text.charAt(length - 1)) && length >= 2 ? 2 : 1;
        field.getText().delete(length - remove, length);
        // The keyboard may still hold the syllable it was composing
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(field);
        }
    }

    private void tap(int vk) {
        tap(new KeypadConfig.Binding(vk));
    }

    private void tap(KeypadConfig.Binding binding) {
        keys.press(binding);
        keys.release(binding);
    }

    /** Sends the text still in the field, then the key (Enter, a shortcut). */
    private void sendTextThenKey(KeypadConfig.Binding binding) {
        if (sendText()) {
            bar.postDelayed(() -> {
                if (!activity.isFinishing()) {
                    tap(binding);
                }
            }, KEY_AFTER_TEXT_MS);
        } else {
            tap(binding);
        }
    }

    /** Sends the field's text as Unicode in packets of whole characters, then clears it. */
    private boolean sendText() {
        // The field's text includes a syllable still being composed
        String text = field.getText().toString();
        if (text.isEmpty()) {
            return false;
        }
        StringBuilder chunk = new StringBuilder();
        int chunkBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint > 0xFFFF) {
                // Emoji and other characters outside the basic plane reach the host broken (the
                // JNI layer encodes them as modified UTF-8), so they are left out
                i += Character.charCount(codePoint);
                continue;
            }
            String ch = new String(Character.toChars(codePoint));
            int bytes = ch.getBytes(StandardCharsets.UTF_8).length;
            if (chunkBytes + bytes > MAX_CHUNK_BYTES && chunk.length() > 0) {
                conn.sendUtf8Text(chunk.toString());
                chunk.setLength(0);
                chunkBytes = 0;
            }
            chunk.append(ch);
            chunkBytes += bytes;
            i += Character.charCount(codePoint);
        }
        if (chunk.length() > 0) {
            conn.sendUtf8Text(chunk.toString());
        }
        field.setText("");
        // Drop the keyboard's composing state for the text that was just sent
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(field);
        }
        return true;
    }
}
