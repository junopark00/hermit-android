package com.junopark.hermit.hermit;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.junopark.hermit.R;

import java.util.Locale;

/**
 * The menu of the virtual keypad's quick menu button: touch mode, quality (bitrate presets),
 * screen orientation, and shortcuts to text input, the keypad editor, the performance overlay and
 * the stream panel. A card beside the button over a scrim; a tap outside it closes it (and does
 * not reach the stream). Settings apply at once through the stream panel, like its own controls.
 */
public class QuickMenu {
    /** What the stream activity does for the menu. */
    public interface Host {
        void onOpenTextInput();
        void onOpenKeypadEditor();
        void onOpenStreamPanel();
    }

    private static final int WIDTH_DP = 300, MARGIN_DP = 8;

    private final Activity activity;
    private final StreamPanel panel;
    private final Host host;
    private final float density;
    private FrameLayout scrim;

    public QuickMenu(Activity activity, StreamPanel panel, Host host) {
        this.activity = activity;
        this.panel = panel;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private int color(int res) {
        return activity.getResources().getColor(res);
    }

    public boolean isShown() {
        return scrim != null && scrim.getParent() != null;
    }

    public void hide() {
        if (isShown()) {
            ((ViewGroup) scrim.getParent()).removeView(scrim);
        }
        scrim = null;
    }

    /** Opens the menu beside the button (on the side with more room). */
    public void show(View button) {
        hide();
        FrameLayout content = activity.findViewById(android.R.id.content);
        scrim = new FrameLayout(activity);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> hide());

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);   // touches on the card do not close it
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.hermit_layer1));
        bg.setStroke(dp(1), color(R.color.hermit_border_strong));
        card.setBackground(bg);
        build(card);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(card);
        int width = Math.min(dp(WIDTH_DP), content.getWidth() - 2 * dp(MARGIN_DP));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        // Measure the card to keep it on the screen
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = content.getHeight() - 2 * dp(MARGIN_DP);
        int height = Math.min(card.getMeasuredHeight(), maxHeight);
        if (card.getMeasuredHeight() > maxHeight) {
            lp.height = maxHeight;
        }
        int[] at = new int[2], origin = new int[2];
        button.getLocationInWindow(at);
        content.getLocationInWindow(origin);
        int left = at[0] - origin[0], top = at[1] - origin[1];
        boolean roomRight = content.getWidth() - (left + button.getWidth()) >= left;
        lp.leftMargin = roomRight ? left + button.getWidth() + dp(MARGIN_DP) : left - width - dp(MARGIN_DP);
        lp.leftMargin = Math.max(dp(MARGIN_DP), Math.min(content.getWidth() - width - dp(MARGIN_DP), lp.leftMargin));
        lp.topMargin = top + button.getHeight() / 2 - height / 2;
        lp.topMargin = Math.max(dp(MARGIN_DP), Math.min(content.getHeight() - height - dp(MARGIN_DP), lp.topMargin));
        scrim.addView(scroll, lp);
        content.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void build(LinearLayout card) {
        TextView title = new TextView(activity);
        title.setText(R.string.hermit_quick_menu);
        title.setTextColor(color(R.color.hermit_text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title);

        // Touch mode (not offered without a touchscreen, like the stream panel)
        boolean touchscreen = panel.hasTouchscreen();
        if (touchscreen) {
            card.addView(section(R.string.hermit_quick_touch));
            boolean trackpad = panel.isTrackpad();
            card.addView(choices(new String[]{
                    activity.getString(R.string.hermit_quick_touch_direct),
                    activity.getString(R.string.hermit_quick_touch_trackpad)}, trackpad ? 1 : 0, i -> panel.setTrackpad(i == 1)));
        }

        // Quality
        card.addView(section(R.string.hermit_quick_quality));
        int[] presets = panel.bitratePresets();
        int chosen = panel.chosenBitrate();
        int[] names = {R.string.hermit_quick_quality_high, R.string.hermit_quick_quality_medium, R.string.hermit_quick_quality_low};
        String[] labels = new String[3];
        int selected = -1;
        for (int i = 0; i < 3; i++) {
            labels[i] = activity.getString(names[i]) + "\n" + String.format(Locale.ROOT, "%d Mbps", presets[i] / 1000);
            if (presets[i] == chosen && selected < 0) {
                selected = i;
            }
        }
        card.addView(choices(labels, selected, i -> {
            panel.applyBitrate(presets[i]);
            if (panel.bitrateNeedsReconnect()) {
                HermitNotice.show(activity, R.string.hermit_bitrate_reconnect_toast, HermitNotice.LONG);
            }
        }));
        card.addView(helper(R.string.hermit_quick_quality_note));

        // Screen orientation
        card.addView(section(R.string.hermit_quick_orientation));
        String[] orientations = {HermitPreferences.ORIENTATION_AUTO, HermitPreferences.ORIENTATION_LANDSCAPE, HermitPreferences.ORIENTATION_PORTRAIT};
        int orientation = 0;
        for (int i = 0; i < orientations.length; i++) {
            if (orientations[i].equals(panel.orientation())) {
                orientation = i;
            }
        }
        card.addView(choices(new String[]{
                activity.getString(R.string.hermit_orientation_auto),
                activity.getString(R.string.hermit_orientation_landscape),
                activity.getString(R.string.hermit_orientation_portrait)}, orientation, i -> {
            // The screen turns: the menu would sit in the wrong place
            hide();
            panel.setOrientation(orientations[i]);
        }));
        card.addView(helper(R.string.hermit_panel_orientation_hint));

        // Shortcuts
        View divider = new View(activity);
        divider.setBackgroundColor(color(R.color.hermit_border_strong));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        dlp.bottomMargin = dp(4);
        card.addView(divider, dlp);
        card.addView(action(R.string.hermit_quick_text_input, () -> host.onOpenTextInput()));
        if (touchscreen) {
            card.addView(action(R.string.hermit_quick_keypad_edit, () -> host.onOpenKeypadEditor()));
        }
        boolean overlay = panel.isOverlayShown();
        card.addView(action(overlay ? R.string.hermit_quick_overlay_hide : R.string.hermit_quick_overlay_show,
                () -> panel.setOverlayShown(!overlay)));
        card.addView(action(R.string.hermit_quick_settings, () -> host.onOpenStreamPanel()));
    }

    private interface Choice {
        void picked(int index);
    }

    // A row of equal buttons, one of them selected; a tap selects and applies at once
    private LinearLayout choices(String[] labels, int selected, Choice onPick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = new TextView(activity);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextColor(color(R.color.hermit_text_primary));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setMinHeight(dp(44));
            b.setPadding(dp(4), dp(6), dp(4), dp(6));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(3));
            b.setTag(shape);
            b.setBackground(HermitPress.background(activity, shape, dp(3)));
            style(b, i == selected);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                for (int j = 0; j < buttons.length; j++) {
                    style(buttons[j], j == index);
                }
                onPick.picked(index);
            });
            buttons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                lp.setMarginStart(dp(6));
            }
            row.addView(b, lp);
        }
        return row;
    }

    // Only the colours of the button's shape change, so a press ripple in progress carries on
    private void style(TextView b, boolean selected) {
        GradientDrawable bg = (GradientDrawable) b.getTag();
        bg.setColor(color(selected ? R.color.hermit_accent_fill : R.color.hermit_layer2));
        bg.setStroke(dp(1), color(selected ? R.color.hermit_accent : R.color.hermit_border_strong));
        b.setSelected(selected);
    }

    // A row that closes the menu and runs the shortcut
    private TextView action(int textRes, Runnable run) {
        TextView t = new TextView(activity);
        t.setText(textRes);
        t.setTextColor(color(R.color.hermit_text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(44));
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setBackground(HermitPress.background(activity, null, dp(3)));
        t.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            // A moment for the press to show before the menu goes
            v.postDelayed(() -> {
                if (isShown()) {
                    hide();
                    run.run();
                }
            }, 120);
        });
        return t;
    }

    private TextView section(int textRes) {
        TextView t = new TextView(activity);
        t.setText(textRes);
        t.setTextColor(color(R.color.hermit_text_secondary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    private TextView helper(int textRes) {
        TextView t = new TextView(activity);
        t.setText(textRes);
        t.setTextColor(color(R.color.hermit_text_helper));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }
}
