package com.junopark.hermit.hermit.keypad;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.junopark.hermit.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editor of the virtual keypad: on/off, the stick (type, diagonals, size, the four keys), the
 * key grid (rows, keys per row, row offset, key size, spacing, every key), the quick menu button
 * and the opacity. A key can be a combination the user puts together (modifiers including Win,
 * then up to five keys in order) with a delay between the keys.
 * Every change is saved and handed to the listener at once, so a running stream follows it.
 *
 * During a stream (showLive) it is a panel over the stream instead of a dialog: in landscape in
 * the gap between the stick and the keys (or, without room there, on the side away from the
 * keys), at the top in portrait, and collapsible. The keypad is in edit mode meanwhile (drag with
 * grid snapping) and shows every change at once, sliders included. Editing does not turn the
 * keypad on: when it is off it shows only while the panel is open, and the panel says so.
 */
public class KeypadEditor {
    public interface Listener {
        void onChanged(KeypadConfig config);

        /** Drag the controls on the stream screen (null outside a stream). */
        void onEditPositions();
    }

    private final Activity activity;
    private final Listener listener;
    private Runnable onClosed;
    private final boolean canEditPositions;
    private KeypadConfig config;
    private final float density;
    private LinearLayout keyGrid;
    private AlertDialog dialog;
    // Live editing during a stream
    private final KeypadOverlay overlay;
    private LinearLayout panel;
    private boolean collapsed;
    // The keypad was off: shown for editing only, and saved as off
    private boolean shownForEditing;

    public static void show(Activity activity, Listener listener, boolean canEditPositions) {
        new KeypadEditor(activity, listener, canEditPositions, null).show();
    }

    /** The panel over a running stream, editing the keypad in place; returns the editor. */
    public static KeypadEditor showLive(Activity activity, KeypadOverlay overlay, Listener listener) {
        KeypadEditor editor = new KeypadEditor(activity, listener, false, overlay);
        editor.show();
        return editor;
    }

    private KeypadEditor(Activity activity, Listener listener, boolean canEditPositions, KeypadOverlay overlay) {
        this.activity = activity;
        this.listener = listener;
        this.canEditPositions = canEditPositions;
        this.overlay = overlay;
        this.density = activity.getResources().getDisplayMetrics().density;
        KeypadConfig current = overlay != null ? overlay.currentConfig() : null;
        this.config = current != null ? current : KeypadConfig.load(activity);
        if (overlay != null && !config.enabled) {
            // Editing on the stream shows the keypad, without turning it on
            shownForEditing = true;
            config.enabled = true;
            overlay.apply(config);
        }
    }

    // What is saved: the keypad stays off when it was shown only for editing
    private KeypadConfig toSave() {
        KeypadConfig saved = config.copy();
        if (shownForEditing) {
            saved.enabled = false;
        }
        return saved;
    }

    private boolean live() {
        return overlay != null;
    }

    /** Runs once the live panel has closed. */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    /** The live panel is open (the activity's back closes it). */
    public boolean isShowing() {
        return panel != null && panel.getParent() != null;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void changed() {
        if (live()) {
            // Keep the places dragged on the stream meanwhile
            KeypadConfig shown = overlay.currentConfig();
            if (shown != null) {
                config.stickX = shown.stickX;
                config.stickY = shown.stickY;
                config.gridRight = shown.gridRight;
                config.gridBottom = shown.gridBottom;
            }
            toSave().save(activity);
            overlay.preview(config);
            return;
        }
        config.save(activity);
        if (listener != null) {
            listener.onChanged(config.copy());
        }
    }

    private void show() {
        ScrollView scroll = buildContent();
        if (live()) {
            showPanel(scroll);
        } else {
            showDialog(scroll);
        }
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(live() ? 16 : 20), dp(4), dp(live() ? 16 : 20), dp(12));
        scroll.addView(root);

        if (!live()) {
            Switch enabled = new Switch(activity);
            enabled.setText(R.string.hermit_keypad_enable);
            enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            enabled.setChecked(config.enabled);
            enabled.setOnCheckedChangeListener((b, checked) -> {
                config.enabled = checked;
                changed();
            });
            root.addView(enabled, matchWrap());
        }

        // ---- Left: direction ----
        root.addView(section(R.string.hermit_keypad_section_stick));
        String[] modes = {KeypadConfig.STICK_FIXED, KeypadConfig.STICK_FLOATING, KeypadConfig.STICK_DPAD};
        String[] modeNames = {
                activity.getString(R.string.hermit_keypad_stick_fixed),
                activity.getString(R.string.hermit_keypad_stick_floating),
                activity.getString(R.string.hermit_keypad_stick_dpad)
        };
        int modeIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(config.stickMode)) modeIndex = i;
        }
        root.addView(spinnerRow(R.string.hermit_keypad_stick_mode, modeNames, modeIndex, position -> {
            config.stickMode = modes[position];
            changed();
        }));

        CheckBox eightWay = new CheckBox(activity);
        eightWay.setText(R.string.hermit_keypad_eight_way);
        eightWay.setChecked(config.eightWay);
        eightWay.setOnCheckedChangeListener((b, checked) -> {
            config.eightWay = checked;
            changed();
        });
        root.addView(eightWay, matchWrap());

        root.addView(seekRow(R.string.hermit_keypad_stick_size, 80, 260, config.stickSizeDp, "dp", v -> {
            config.stickSizeDp = v;
            changed();
        }));

        LinearLayout directions = new LinearLayout(activity);
        directions.setOrientation(LinearLayout.HORIZONTAL);
        directions.setPadding(0, dp(6), 0, 0);
        int[] dirNames = {R.string.hermit_keypad_dir_up, R.string.hermit_keypad_dir_down,
                R.string.hermit_keypad_dir_left, R.string.hermit_keypad_dir_right};
        for (int i = 0; i < 4; i++) {
            final int dir = i;
            final TextView chip = chip("");
            updateDirectionChip(chip, dirNames[dir], binding(dir));
            chip.setOnClickListener(v -> pickBinding(activity.getString(dirNames[dir]), binding(dir), picked -> {
                setBinding(dir, picked);
                updateDirectionChip(chip, dirNames[dir], picked);
                changed();
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(dp(6));
            directions.addView(chip, lp);
        }
        root.addView(directions, matchWrap());

        // ---- Right: keys ----
        root.addView(section(R.string.hermit_keypad_section_keys));
        String[] rowChoices = {"1", "2", "3", "4"};
        root.addView(spinnerRow(R.string.hermit_keypad_rows, rowChoices, config.rows - 1, position -> {
            config.rows = position + 1;
            changed();
            rebuildKeyGrid();
        }));
        String[] columnChoices = {"1", "2", "3", "4", "5", "6"};
        root.addView(spinnerRow(R.string.hermit_keypad_columns, columnChoices, config.columns - 1, position -> {
            config.columns = position + 1;
            changed();
            rebuildKeyGrid();
        }));
        root.addView(seekRow(R.string.hermit_keypad_stagger, 0, 100, Math.round(config.stagger * 100), "%", v -> {
            config.stagger = v / 100f;
            changed();
            rebuildKeyGrid();
        }));
        root.addView(seekRow(R.string.hermit_keypad_key_size, 36, 110, config.keySizeDp, "dp", v -> {
            config.keySizeDp = v;
            changed();
        }));
        root.addView(seekRow(R.string.hermit_keypad_spacing, 0, 40, config.spacingDp, "dp", v -> {
            config.spacingDp = v;
            changed();
        }));

        TextView keysHint = helper(R.string.hermit_keypad_keys_hint);
        root.addView(keysHint);
        keyGrid = new LinearLayout(activity);
        keyGrid.setOrientation(LinearLayout.VERTICAL);
        keyGrid.setPadding(0, dp(6), 0, dp(4));
        root.addView(keyGrid, matchWrap());
        rebuildKeyGrid();

        // ---- Common ----
        root.addView(section(R.string.hermit_keypad_section_common));
        root.addView(seekRow(R.string.hermit_keypad_opacity, 15, 100, Math.round(config.opacity * 100), "%", v -> {
            config.opacity = v / 100f;
            changed();
        }));
        CheckBox haptics = new CheckBox(activity);
        haptics.setText(R.string.hermit_keypad_haptics);
        haptics.setChecked(config.haptics);
        haptics.setOnCheckedChangeListener((b, checked) -> {
            config.haptics = checked;
            changed();
        });
        root.addView(haptics, matchWrap());
        CheckBox quickMenu = new CheckBox(activity);
        quickMenu.setText(R.string.hermit_keypad_quick_menu);
        quickMenu.setChecked(config.quickMenu);
        quickMenu.setOnCheckedChangeListener((b, checked) -> {
            config.quickMenu = checked;
            changed();
        });
        root.addView(quickMenu, matchWrap());
        root.addView(helper(R.string.hermit_keypad_quick_menu_hint));
        return scroll;
    }

    // ---- Live panel over the stream ---------------------------------------------------------

    private void showPanel(ScrollView scroll) {
        overlay.startEditMode(null, false);
        FrameLayout content = activity.findViewById(android.R.id.content);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.getResources().getColor(R.color.hermit_overlay_panel));
        bg.setStroke(dp(1), activity.getResources().getColor(R.color.hermit_layer2));
        panel.setBackground(bg);
        panel.setClickable(true);   // touches on the panel do not reach the stream

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(6), dp(6));
        TextView title = new TextView(activity);
        title.setText(R.string.hermit_keypad_title);
        title.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView collapse = headerButton(R.string.hermit_keypad_collapse, false);
        TextView reset = headerButton(R.string.hermit_keypad_reset, false);
        TextView done = headerButton(R.string.hermit_keypad_done, true);
        header.addView(collapse);
        header.addView(reset);
        header.addView(done);
        panel.addView(header, matchWrap());

        TextView hint = helper(R.string.hermit_keypad_live_hint);
        hint.setPadding(dp(16), 0, dp(16), dp(6));
        if (shownForEditing) {
            hint.setText(activity.getString(R.string.hermit_keypad_live_hint) + "\n" +
                    activity.getString(R.string.hermit_keypad_preview_note));
        }
        panel.addView(hint, matchWrap());
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        boolean landscape = content.getWidth() >= content.getHeight();
        FrameLayout.LayoutParams lp;
        if (landscape) {
            lp = new FrameLayout.LayoutParams(Math.min(dp(340), Math.round(content.getWidth() * 0.42f)),
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
            placeBesideKeys(lp, content.getWidth());
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(8);
        } else {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(content.getHeight() * 0.45f), Gravity.TOP);
        }
        final int expandedHeight = lp.height;
        content.addView(panel, lp);

        collapse.setOnClickListener(v -> {
            // Collapsed: only the header, so the whole keypad shows
            collapsed = !collapsed;
            scroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            hint.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapse.setText(collapsed ? R.string.hermit_keypad_expand : R.string.hermit_keypad_collapse);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
            p.height = collapsed ? ViewGroup.LayoutParams.WRAP_CONTENT : expandedHeight;
            panel.setLayoutParams(p);
        });
        reset.setOnClickListener(v -> confirmReset(() -> {
            KeypadConfig defaults = new KeypadConfig();
            defaults.enabled = true;
            config = defaults;
            toSave().save(activity);
            overlay.preview(config);
            // Rebuild the panel with the default values
            content.removeView(panel);
            collapsed = false;
            showPanel(buildContent());
        }));
        done.setOnClickListener(v -> close());
    }

    /**
     * Landscape: in the gap between the fixed stick and the key grid when the panel fits there,
     * otherwise on the side away from the key grid, narrowed to the space beside it.
     */
    private void placeBesideKeys(FrameLayout.LayoutParams lp, int width) {
        int margin = dp(8);
        Rect grid = overlay.keyGridBounds();
        Rect stick = overlay.stickBounds();
        int gapLeft = stick != null ? stick.right : 0;
        int gapRight = grid != null ? grid.left : width;
        if (stick != null && grid != null && stick.right > grid.left) {
            gapRight = gapLeft;  // the stick is right of the keys: no gap between them
        }
        int gap = gapRight - gapLeft - 2 * margin;
        if (gap >= dp(260)) {
            lp.width = Math.min(lp.width, gap);
            lp.leftMargin = gapLeft + margin + (gap - lp.width) / 2;
            return;
        }
        boolean keysOnRight = grid == null || grid.centerX() >= width / 2;
        int room = grid == null ? lp.width : keysOnRight ? grid.left - 2 * margin : width - grid.right - 2 * margin;
        lp.width = Math.max(dp(220), Math.min(lp.width, room));
        lp.leftMargin = keysOnRight ? margin : width - lp.width - margin;
    }

    // Defaults replace every key, size and position: ask first
    private void confirmReset(Runnable onReset) {
        new AlertDialog.Builder(activity)
                .setMessage(R.string.hermit_keypad_reset_confirm)
                .setPositiveButton(R.string.hermit_keypad_reset_do, (d, w) -> onReset.run())
                .setNegativeButton(R.string.hermit_keypad_cancel, null)
                .show();
    }

    /** Ends live editing: the keypad leaves edit mode and the settings are saved. */
    public void close() {
        if (!isShowing()) {
            return;
        }
        changed();
        overlay.endEditMode();
        ((ViewGroup) panel.getParent()).removeView(panel);
        // Ending edit mode saved the shown positions with the keypad on: save what was meant
        KeypadConfig saved = toSave();
        saved.save(activity);
        if (listener != null) {
            listener.onChanged(saved.copy());
        }
        if (onClosed != null) {
            onClosed.run();
        }
    }

    private TextView headerButton(int textRes, boolean primary) {
        TextView b = new TextView(activity);
        b.setText(textRes);
        b.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(2));
        bg.setColor(activity.getResources().getColor(primary ? R.color.hermit_accent_fill : R.color.hermit_layer2));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    // ---- Dialog (settings screen) -----------------------------------------------------------

    private void showDialog(ScrollView scroll) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.hermit_keypad_title)
                .setView(scroll)
                .setPositiveButton(R.string.hermit_keypad_close, null)
                .setNegativeButton(R.string.hermit_keypad_reset, null);
        if (canEditPositions) {
            builder.setNeutralButton(R.string.hermit_keypad_edit_positions, (d, w) -> {
                if (!config.enabled) {
                    config.enabled = true;
                    changed();
                }
                listener.onEditPositions();
            });
        }
        dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> confirmReset(() -> {
            // Defaults, keeping on/off; reopen to show them
            KeypadConfig defaults = new KeypadConfig();
            defaults.enabled = config.enabled;
            defaults.save(activity);
            if (listener != null) {
                listener.onChanged(defaults.copy());
            }
            dialog.dismiss();
            KeypadEditor.show(activity, listener, canEditPositions);
        })));
        dialog.show();
    }

    // ---- Stick bindings ---------------------------------------------------------------------

    private KeypadConfig.Binding binding(int dir) {
        switch (dir) {
            case 0: return config.up;
            case 1: return config.down;
            case 2: return config.left;
            default: return config.right;
        }
    }

    private void setBinding(int dir, KeypadConfig.Binding b) {
        switch (dir) {
            case 0: config.up = b; break;
            case 1: config.down = b; break;
            case 2: config.left = b; break;
            default: config.right = b; break;
        }
    }

    private void updateDirectionChip(TextView chip, int nameRes, KeypadConfig.Binding b) {
        chip.setText(activity.getString(nameRes) + "\n" + b.displayLabel(activity));
    }

    // ---- Key grid ---------------------------------------------------------------------------

    private void rebuildKeyGrid() {
        if (keyGrid == null) {
            return;
        }
        keyGrid.removeAllViews();
        int keyWidth = dp(52);
        int step = keyWidth + dp(6);
        for (int row = 0; row < config.rows; row++) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(Math.round(row * config.stagger * step), 0, 0, dp(6));
            for (int column = 0; column < config.columns; column++) {
                final int r = row, c = column;
                final TextView chip = chip(config.key(r, c).displayLabel(activity));
                chip.setOnClickListener(v -> pickBinding(
                        activity.getString(R.string.hermit_keypad_key_title, r + 1, c + 1),
                        config.key(r, c), picked -> {
                            config.setKey(r, c, picked);
                            chip.setText(picked.displayLabel(activity));
                            changed();
                        }));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(keyWidth, dp(44));
                lp.setMarginEnd(dp(6));
                line.addView(chip, lp);
            }
            keyGrid.addView(line);
        }
    }

    // ---- Key picker -------------------------------------------------------------------------

    private interface BindingCallback {
        void onPicked(KeypadConfig.Binding binding);
    }

    private void pickBinding(String title, KeypadConfig.Binding current, BindingCallback callback) {
        // The keys in pressing order (after the modifiers): one, or several for a combination
        final List<Integer> sequence = new ArrayList<>();
        sequence.add(current.code);
        for (int c : current.chain) {
            sequence.add(c);
        }
        final boolean[] combo = {current.chain.length > 0};
        final int[] delay = {current.delayMs};
        final List<TextView> chips = new ArrayList<>();
        final List<Integer> codes = new ArrayList<>();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(4), dp(16), dp(8));
        scroll.addView(root);

        // What the key sends, kept up to date
        TextView summary = new TextView(activity);
        summary.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        summary.setTypeface(summary.getTypeface(), android.graphics.Typeface.BOLD);
        summary.setPadding(0, dp(8), 0, dp(2));
        root.addView(summary, matchWrap());

        CheckBox comboBox = new CheckBox(activity);
        comboBox.setText(R.string.hermit_keypad_combo);
        comboBox.setChecked(combo[0]);
        root.addView(comboBox, matchWrap());

        LinearLayout mods = new LinearLayout(activity);
        mods.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox ctrl = modifierBox("Ctrl", (current.modifiers & KeypadConfig.MOD_CTRL) != 0);
        CheckBox alt = modifierBox("Alt", (current.modifiers & KeypadConfig.MOD_ALT) != 0);
        CheckBox shift = modifierBox("Shift", (current.modifiers & KeypadConfig.MOD_SHIFT) != 0);
        CheckBox win = modifierBox("Win", (current.modifiers & KeypadConfig.MOD_WIN) != 0);
        mods.addView(ctrl);
        mods.addView(alt);
        mods.addView(shift);
        mods.addView(win);
        root.addView(mods, matchWrap());

        root.addView(seekRow(R.string.hermit_keypad_combo_delay, 0, KeypadConfig.MAX_DELAY_MS, delay[0], " ms", v -> delay[0] = v));
        root.addView(helper(R.string.hermit_keypad_combo_delay_hint));

        EditText label = new EditText(activity);
        label.setHint(R.string.hermit_keypad_label_hint);
        label.setSingleLine(true);
        label.setInputType(InputType.TYPE_CLASS_TEXT);
        label.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        label.setText(current.label);
        root.addView(label, matchWrap());

        final BindingSource build = () -> {
            KeypadConfig.Binding b = new KeypadConfig.Binding(sequence.get(0));
            int[] chain = new int[sequence.size() - 1];
            for (int i = 1; i < sequence.size(); i++) {
                chain[i - 1] = sequence.get(i);
            }
            b.chain = chain;
            b.modifiers = (ctrl.isChecked() ? KeypadConfig.MOD_CTRL : 0) |
                    (alt.isChecked() ? KeypadConfig.MOD_ALT : 0) |
                    (shift.isChecked() ? KeypadConfig.MOD_SHIFT : 0) |
                    (win.isChecked() ? KeypadConfig.MOD_WIN : 0);
            b.delayMs = delay[0];
            b.label = label.getText().toString().trim();
            return b;
        };
        final Runnable refresh = () -> {
            for (int i = 0; i < chips.size(); i++) {
                styleChip(chips.get(i), sequence.contains(codes.get(i)));
            }
            String keys = build.get().keysLabel(activity);
            summary.setText(combo[0] ? activity.getString(R.string.hermit_keypad_combo_keys, keys,
                    sequence.size(), KeypadConfig.MAX_COMBO_KEYS) : keys);
        };
        comboBox.setOnCheckedChangeListener((b, checked) -> {
            combo[0] = checked;
            if (!checked) {
                // Back to one key: the first of the combination
                while (sequence.size() > 1) {
                    sequence.remove(sequence.size() - 1);
                }
            }
            refresh.run();
        });
        for (CheckBox box : new CheckBox[]{ctrl, alt, shift, win}) {
            box.setOnCheckedChangeListener((b, checked) -> refresh.run());
        }

        int lastGroup = -1;
        LinearLayout line = null;
        int perLine = 6;
        int inLine = 0;
        for (KeyCatalog.Key key : KeyCatalog.all()) {
            if (key.group != lastGroup) {
                padLine(line, inLine, perLine);
                lastGroup = key.group;
                TextView header = section(0);
                header.setText(KeyCatalog.groupName(activity, key.group));
                root.addView(header);
                line = null;
            }
            if (line == null || inLine == perLine) {
                line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, 0, 0, dp(4));
                root.addView(line, matchWrap());
                inLine = 0;
            }
            TextView chip = chip(KeyCatalog.label(activity, key.code));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            final int code = key.code;
            chip.setOnClickListener(v -> {
                if (combo[0]) {
                    // A combination: a tap adds the key at the end, or takes it out again
                    int at = sequence.indexOf(code);
                    if (at >= 0) {
                        if (sequence.size() > 1) {
                            sequence.remove(at);
                        }
                    } else if (sequence.size() < KeypadConfig.MAX_COMBO_KEYS) {
                        sequence.add(code);
                    }
                } else {
                    sequence.clear();
                    sequence.add(code);
                }
                refresh.run();
            });
            chips.add(chip);
            codes.add(code);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.setMarginEnd(dp(4));
            line.addView(chip, lp);
            inLine++;
        }
        padLine(line, inLine, perLine);
        refresh.run();

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.hermit_keypad_picker_title, title))
                .setView(scroll)
                .setPositiveButton(R.string.hermit_keypad_ok, (d, w) -> callback.onPicked(build.get()))
                .setNegativeButton(R.string.hermit_keypad_cancel, null)
                .show();
    }

    private interface BindingSource {
        KeypadConfig.Binding get();
    }

    // Keeps the chips of a short last line as wide as the others
    private void padLine(LinearLayout line, int inLine, int perLine) {
        if (line == null) {
            return;
        }
        for (int i = inLine; i < perLine; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.setMarginEnd(dp(4));
            line.addView(new View(activity), lp);
        }
    }

    private CheckBox modifierBox(String text, boolean checked) {
        CheckBox box = new CheckBox(activity);
        box.setText(text);
        box.setChecked(checked);
        box.setPadding(0, 0, dp(12), 0);
        return box;
    }

    // ---- Small views ------------------------------------------------------------------------

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView section(int textRes) {
        TextView t = new TextView(activity);
        if (textRes != 0) {
            t.setText(textRes);
        }
        t.setTextColor(activity.getResources().getColor(R.color.hermit_text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAllCaps(false);
        t.setPadding(0, dp(14), 0, dp(4));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView helper(int textRes) {
        TextView t = new TextView(activity);
        t.setText(textRes);
        t.setTextColor(activity.getResources().getColor(R.color.hermit_text_helper));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView chip(String text) {
        TextView chip = new TextView(activity);
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setPadding(dp(4), dp(4), dp(4), dp(4));
        chip.setMaxLines(2);
        styleChip(chip, false);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(3));
        bg.setColor(activity.getResources().getColor(selected ? R.color.hermit_accent_fill : R.color.hermit_layer2));
        bg.setStroke(dp(1), activity.getResources().getColor(selected ? R.color.hermit_accent : R.color.hermit_border_strong));
        chip.setBackground(bg);
    }

    private interface IntCallback {
        void accept(int value);
    }

    private View spinnerRow(int labelRes, String[] items, int selected, IntCallback onSelected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(activity.getResources().getColor(R.color.hermit_text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected, false);
        final int[] last = {selected};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // The first callback after setSelection() repeats the current value
                if (position != last[0]) {
                    last[0] = position;
                    onSelected.accept(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View seekRow(int labelRes, int min, int max, int value, String unit, IntCallback onChange) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);
        TextView label = new TextView(activity);
        label.setTextColor(activity.getResources().getColor(R.color.hermit_text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        String name = activity.getString(labelRes);
        label.setText(String.format(Locale.ROOT, "%s: %d%s", name, value, unit));
        box.addView(label);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;

            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText(String.format(Locale.ROOT, "%s: %d%s", name, progress + min, unit));
                // Keyboard or D-pad changes have no touch to wait for; on the stream (live) the
                // keypad follows the slider while it moves
                if (fromUser && (!tracking || live())) {
                    onChange.accept(progress + min);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                // Apply when the finger lifts, so the keypad is not rebuilt on every step
                tracking = false;
                onChange.accept(s.getProgress() + min);
            }
        });
        box.addView(seek, matchWrap());
        return box;
    }
}
