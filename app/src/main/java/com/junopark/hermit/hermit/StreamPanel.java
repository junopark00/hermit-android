package com.junopark.hermit.hermit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.junopark.hermit.R;
import com.junopark.hermit.binding.video.MediaCodecHelper;
import com.junopark.hermit.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings panel over the stream, as in Hermit for Windows: opened with the back gesture or the handle
 * at the side of the stream (right or left, moved up and down with a long press; the panel opens
 * on that side). A keyboard button under the handle (moving with it) opens the text input bar. Display and input settings apply at once; resolution, frame rate,
 * bitrate, codec and HDR apply by reconnecting (the host app keeps running). Settings are the
 * same preferences as on the settings screen and are saved. Like the settings screen, it offers
 * only what this device can show (no portrait sizes on a phone locked to landscape, no 4K, 90/120
 * FPS or HDR the screen and decoders cannot do).
 */
public class StreamPanel {
    /** What the stream activity does for the panel. */
    public interface Host {
        void onPanelOpened();
        void onPanelClosed();
        void onLiveSettingsChanged();
        void onReconnect();
        void onDisconnect();
        void onQuitAppAndDisconnect();

        /** Ask the host to change the bitrate of the running stream; report with setLiveBitrateResult(). */
        void onLiveBitrate(int kbps);

        /** Open the virtual keypad editor. */
        void onOpenKeypadEditor();

        /** Show the text input bar. */
        void onOpenTextInput();

        /** Show the text input bar, or hide it when it is shown (the keyboard button). */
        void onToggleTextInput();
    }

    // Preference keys of res/xml/preferences.xml
    private static final String RESOLUTION = "list_resolution";
    private static final String FPS = "list_fps";
    private static final String BITRATE = "seekbar_bitrate_kbps";
    private static final String VIDEO_FORMAT = "video_format";
    private static final String HDR = "checkbox_enable_hdr";
    private static final String PERF_OVERLAY = "checkbox_enable_perf_overlay";
    private static final String TRACKPAD = "checkbox_touchscreen_trackpad";
    private static final String ONSCREEN_CONTROLS = "checkbox_show_onscreen_controls";
    private static final String PINCH_ZOOM = HermitPreferences.PINCH_ZOOM_PREF;
    // Values of HermitPreferences.STREAM_ORIENTATION_PREF, in spinner order
    private static final String[] ORIENTATIONS = {
            HermitPreferences.ORIENTATION_AUTO, HermitPreferences.ORIENTATION_LANDSCAPE, HermitPreferences.ORIENTATION_PORTRAIT};

    private static final String[] RESOLUTION_PRESETS = {"1280x720", "1920x1080", "2560x1440", "3840x2160"};
    // Portrait sizes, offered only on squarish screens (elsewhere the stream is landscape-locked)
    private static final String[] PORTRAIT_PRESETS = {"720x1280", "1080x1920"};
    private static final String RES_4K = "3840x2160";
    private static final int[] FPS_PRESETS = {30, 60, 90, 120};
    private static final int BITRATE_MIN = 500, BITRATE_MAX = 150000, BITRATE_STEP = 500;
    private static final int MIN_WIDTH = 320, MIN_HEIGHT = 240, MAX_WIDTH = 7680, MAX_HEIGHT = 4320;
    // The handle: a 22x56dp bar at the edge in a 48x64dp touch area (see hermit_panel_handle_*)
    private static final int HANDLE_TOUCH_WIDTH_DP = 48, HANDLE_TOUCH_HEIGHT_DP = 64, HANDLE_INNER_DP = 26;
    // Touches farther than this from the edge (the transparent part) go to what is below
    // (keypad keys, the on-screen controller, the stream)
    private static final int EDGE_TOUCH_DP = 36;
    // The keyboard button: a 22x40dp bar in a 48x48dp touch area (see hermit_panel_key_*), 4dp
    // under the handle (above it when the handle is near the bottom)
    private static final int KEY_TOUCH_DP = 48, KEY_GAP_DP = 4;

    private final Activity activity;
    private final Host host;
    private final String appName;
    private final SharedPreferences prefs;
    private final View root;
    private final ImageView handle;
    private final ImageView keyButton;
    private final View panelView;
    private final int panelWidth;  // the layout's width, before the 85% cap

    // What the stream was started with
    private final String startResolution;
    private final String startFps;
    private final int startBitrate;
    // The bitrate in effect (the start value or the last one the host applied live), and whether
    // the host changes it live: 0 unknown, 1 yes (Shell), 2 no (reconnect needed), 3 the last
    // request failed (reconnect offered)
    private int appliedBitrate;
    private int liveBitrateState;
    private boolean liveBitratePending;
    // Automatic bitrate: the controller, the bitrate it wants (0 when off) and where it is
    private final AutoBitrate autoBitrate = new AutoBitrate();
    private boolean autoRunning;
    private int autoTarget;
    private long autoLastSendMs;
    // Automatic bitrate was turned off: bring back the chosen bitrate (again every 5 s if a
    // request fails)
    private boolean restoreChosen;
    private long restoreLastMs;
    private boolean bitrateTracking;  // the bitrate slider is held
    // The last request failed on the way (a timeout can come after the host applied it), so the
    // host may stream at another bitrate than appliedBitrate: send even an equal value
    private boolean appliedUncertain;
    private boolean failureToastShown;  // one notice per closed period of the panel
    private boolean lastRequestAuto;     // the request in flight was sent by automatic bitrate
    // Keyboard, D-pad and accessibility changes to the slider (no touch tracking callbacks):
    // applied a moment after the last change
    private final Runnable keyedBitrateChange = () -> {
        if (bitrateTracking) {
            return;  // a touch drag took over; its release sends the value
        }
        if (liveBitrateState == 3) {
            liveBitrateState = 0;
        }
        requestLiveBitrate();
        stateChanged();
    };
    private final String startCodec;
    private final boolean startHdr;

    // What this device can show
    private final List<String> resolutionPresets = new ArrayList<>();
    private final List<Integer> fpsPresets = new ArrayList<>();
    private final String[] codecValues;

    private final Spinner resolutionSpinner, fpsSpinner, codecSpinner, overlaySizeSpinner, handleSideSpinner, orientationSpinner;
    private final EditText resolutionCustom, bitrateValue;
    private final SeekBar bitrateSlider;
    private final TextView bitrateHint;
    private final Switch autoBitrateSwitch, hdrSwitch, overlaySwitch, clipboardSwitch, trackpadSwitch, oscSwitch, keypadSwitch, zoomSwitch;
    private final SeekBar trackpadSpeed, scrollSpeed;
    private final TextView trackpadSpeedLabel, scrollSpeedLabel, overlaySizeLabel;
    private final LinearLayout metricsList;
    private final Button applyButton, revertButton;
    private boolean updating;
    private boolean handleHidden;  // setHandleVisible(false): stays hidden when the panel closes

    public StreamPanel(Activity activity, PreferenceConfiguration config, String subtitle, String appName, Host host) {
        this.activity = activity;
        this.host = host;
        this.appName = appName;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        this.codecValues = activity.getResources().getStringArray(R.array.video_format_values);

        startResolution = config.width + "x" + config.height;
        startFps = Integer.toString(config.fps);
        startBitrate = config.bitrate;
        appliedBitrate = startBitrate;
        startCodec = prefs.getString(VIDEO_FORMAT, "auto");
        startHdr = prefs.getBoolean(HDR, false);

        FrameLayout content = activity.findViewById(android.R.id.content);
        root = LayoutInflater.from(activity).inflate(R.layout.hermit_stream_panel, content, false);
        content.addView(root);

        // The handle at the side of the stream: tap opens the panel, a long press moves it
        handle = new EdgeView(activity);
        handle.setContentDescription(activity.getString(R.string.hermit_panel_handle));
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setAlpha(0.6f);
        content.addView(handle, new FrameLayout.LayoutParams(dp(HANDLE_TOUCH_WIDTH_DP), dp(HANDLE_TOUCH_HEIGHT_DP)));
        setUpHandleTouch();

        // Typing on the host is frequent on a phone: the text input one tap away, beside the handle
        keyButton = new EdgeView(activity);
        keyButton.setContentDescription(activity.getString(R.string.hermit_keyboard_button));
        keyButton.setScaleType(ImageView.ScaleType.CENTER);
        keyButton.setImageResource(R.drawable.hermit_keyboard);
        keyButton.setAlpha(0.6f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyButton.setTooltipText(activity.getString(R.string.hermit_keyboard_button));
        }
        keyButton.setOnClickListener(v -> host.onToggleTextInput());
        content.addView(keyButton, new FrameLayout.LayoutParams(dp(HANDLE_TOUCH_WIDTH_DP), dp(KEY_TOUCH_DP)));
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) {
                placeHandle();
            }
        });

        // Narrow screens: at most 85% of the width
        View panel = root.findViewById(R.id.hermitPanel);
        panelView = panel;
        panelWidth = panel.getLayoutParams().width;
        int maxWidth = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85f);
        if (panel.getLayoutParams().width > maxWidth) {
            panel.getLayoutParams().width = maxWidth;
        }

        ((TextView) root.findViewById(R.id.hermitPanelSubtitle)).setText(subtitle);
        root.findViewById(R.id.hermitPanelScrim).setOnClickListener(v -> close());
        root.findViewById(R.id.hermitPanelClose).setOnClickListener(v -> close());

        resolutionSpinner = root.findViewById(R.id.hermitPanelResolution);
        resolutionCustom = root.findViewById(R.id.hermitPanelResolutionCustom);
        fpsSpinner = root.findViewById(R.id.hermitPanelFps);
        bitrateSlider = root.findViewById(R.id.hermitPanelBitrateSlider);
        bitrateValue = root.findViewById(R.id.hermitPanelBitrateValue);
        bitrateHint = root.findViewById(R.id.hermitPanelBitrateHint);
        autoBitrateSwitch = root.findViewById(R.id.hermitPanelAutoBitrate);
        codecSpinner = root.findViewById(R.id.hermitPanelCodec);
        hdrSwitch = root.findViewById(R.id.hermitPanelHdr);
        applyButton = root.findViewById(R.id.hermitPanelApply);
        revertButton = root.findViewById(R.id.hermitPanelRevert);
        overlaySwitch = root.findViewById(R.id.hermitPanelOverlay);
        metricsList = root.findViewById(R.id.hermitPanelMetrics);
        overlaySizeSpinner = root.findViewById(R.id.hermitPanelOverlaySize);
        handleSideSpinner = root.findViewById(R.id.hermitPanelHandleSide);
        orientationSpinner = root.findViewById(R.id.hermitPanelOrientation);
        clipboardSwitch = root.findViewById(R.id.hermitPanelClipboard);
        trackpadSwitch = root.findViewById(R.id.hermitPanelTrackpad);
        oscSwitch = root.findViewById(R.id.hermitPanelOsc);
        keypadSwitch = root.findViewById(R.id.hermitPanelKeypad);
        zoomSwitch = root.findViewById(R.id.hermitPanelZoom);
        trackpadSpeed = root.findViewById(R.id.hermitPanelTrackpadSpeed);
        scrollSpeed = root.findViewById(R.id.hermitPanelScrollSpeed);
        trackpadSpeedLabel = root.findViewById(R.id.hermitPanelTrackpadSpeedLabel);
        scrollSpeedLabel = root.findViewById(R.id.hermitPanelScrollSpeedLabel);
        overlaySizeLabel = root.findViewById(R.id.hermitPanelOverlaySizeLabel);
        root.findViewById(R.id.hermitPanelTextInput).setOnClickListener(v -> {
            close();
            host.onOpenTextInput();
        });
        zoomSwitch.setVisibility(ZoomController.isSupported() ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.hermitPanelKeypadEdit).setOnClickListener(v -> host.onOpenKeypadEditor());

        findDeviceOptions(config);
        setUpStreamSettings();
        setUpLiveSettings();

        applyButton.setOnClickListener(v -> {
            bitrateSlider.removeCallbacks(keyedBitrateChange);  // the reconnect uses the value
            if (!commitTypedValues()) {
                return;  // the error is on the field; nothing to reconnect with yet
            }
            close();
            host.onReconnect();
        });
        revertButton.setOnClickListener(v -> {
            bitrateValue.clearFocus();
            resolutionCustom.setText("");
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(RESOLUTION, startResolution)
                    .putString(FPS, startFps)
                    .putString(VIDEO_FORMAT, startCodec)
                    .putBoolean(HDR, startHdr);
            // With automatic bitrate (or while bringing back the choice after it) the applied value
            // is not the user's choice: keep the choice
            if (!autoRunning && !restoreChosen) {
                editor.putInt(BITRATE, appliedBitrate);
            }
            editor.apply();
            refresh();
            if (!autoRunning && !restoreChosen && appliedUncertain) {
                // The last request failed on the way and may have reached the host: send the
                // reverted value so the host certainly streams at it
                if (liveBitrateState == 3) {
                    liveBitrateState = 0;
                }
                requestLiveBitrate();
                stateChanged();
            }
        });
        root.findViewById(R.id.hermitPanelDisconnect).setOnClickListener(v -> {
            close();
            host.onDisconnect();
        });
        root.findViewById(R.id.hermitPanelQuitApp).setOnClickListener(v -> confirmQuitApp());

        placeHandle();
        // Show the handle clearly for a few seconds after the stream starts, then fade it
        handle.postDelayed(() -> {
            handle.animate().alpha(0.35f).setDuration(400).start();
            keyButton.animate().alpha(0.35f).setDuration(400).start();
        }, 4000);
    }

    // ---- Handle -----------------------------------------------------------------------------

    private boolean handleOnLeft() {
        return "left".equals(prefs.getString(HermitPreferences.PANEL_HANDLE_SIDE_PREF, "right"));
    }

    /** Puts the handle on its side and height, and the panel on the same side. */
    private void placeHandle() {
        View parent = (View) handle.getParent();
        if (parent == null) {
            return;
        }
        if (parent.getHeight() == 0) {
            parent.post(this::placeHandle);
            return;
        }
        boolean left = handleOnLeft();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
        lp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        float y = Math.max(0f, Math.min(1f, prefs.getFloat(HermitPreferences.PANEL_HANDLE_Y_PREF, 0.35f)));
        lp.topMargin = Math.round(y * Math.max(0, parent.getHeight() - lp.height));
        handle.setLayoutParams(lp);
        handle.setBackgroundResource(left ? R.drawable.hermit_panel_handle_left : R.drawable.hermit_panel_handle_right);
        handle.setImageResource(left ? R.drawable.hermit_chevron_right : R.drawable.hermit_chevron_left);
        // The chevron in the middle of the visible bar, not of the wider touch area
        handle.setPadding(left ? 0 : dp(HANDLE_INNER_DP), 0, left ? dp(HANDLE_INNER_DP) : 0, 0);
        keyButton.setBackgroundResource(left ? R.drawable.hermit_panel_key_left : R.drawable.hermit_panel_key_right);
        keyButton.setPadding(left ? 0 : dp(HANDLE_INNER_DP), 0, left ? dp(HANDLE_INNER_DP) : 0, 0);
        placeKeyButton();

        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panelView.getLayoutParams();
        plp.gravity = left ? Gravity.START : Gravity.END;
        // At most 85% of the width, so a tap beside it can close it (again after rotation)
        plp.width = Math.min(panelWidth, Math.round(parent.getWidth() * 0.85f));
        panelView.setLayoutParams(plp);
        // The panel's border on the side facing the stream
        panelView.setBackgroundResource(left ? R.drawable.hermit_panel_background_left : R.drawable.hermit_panel_background);
    }

    /** Puts the keyboard button on the handle's side, under it (or above it near the bottom). */
    private void placeKeyButton() {
        View parent = (View) handle.getParent();
        FrameLayout.LayoutParams hlp = (FrameLayout.LayoutParams) handle.getLayoutParams();
        FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) keyButton.getLayoutParams();
        klp.gravity = hlp.gravity;
        int below = hlp.topMargin + hlp.height + dp(KEY_GAP_DP);
        klp.topMargin = below + klp.height <= parent.getHeight() ? below :
                Math.max(0, hlp.topMargin - dp(KEY_GAP_DP) - klp.height);
        keyButton.setLayoutParams(klp);
    }

    // Quitting loses unsaved work in the host app: ask first
    private void confirmQuitApp() {
        boolean named = appName != null && !appName.isEmpty();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.hermit_quit_confirm_title)
                .setMessage(named ? activity.getString(R.string.hermit_quit_confirm_message, appName) :
                        activity.getString(R.string.hermit_quit_confirm_message_any))
                .setPositiveButton(R.string.hermit_action_quit_app, (d, w) -> {
                    close();
                    host.onQuitAppAndDisconnect();
                })
                .setNegativeButton(R.string.hermit_action_cancel, null)
                .show();
    }

    // A tap opens the panel; a long press, then dragging, moves the handle up or down
    private void setUpHandleTouch() {
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private int startTop;
            private boolean dragging, moved;
            private final Runnable startDrag = () -> {
                dragging = true;
                handle.setAlpha(1f);
                handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
                View parent = (View) handle.getParent();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startTop = lp.topMargin;
                        dragging = false;
                        moved = false;
                        handle.animate().cancel();
                        handle.setAlpha(0.85f);
                        handle.postDelayed(startDrag, ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging) {
                            int max = Math.max(0, parent.getHeight() - lp.height);
                            lp.topMargin = Math.max(0, Math.min(max, startTop + Math.round(e.getRawY() - downY)));
                            handle.setLayoutParams(lp);
                            placeKeyButton();
                        } else if (Math.abs(e.getRawY() - downY) > slop) {
                            moved = true;
                            handle.removeCallbacks(startDrag);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handle.removeCallbacks(startDrag);
                        if (dragging) {
                            int max = Math.max(1, parent.getHeight() - lp.height);
                            prefs.edit().putFloat(HermitPreferences.PANEL_HANDLE_Y_PREF, (float) lp.topMargin / max).apply();
                        } else if (!moved) {
                            open();
                        }
                        dragging = false;
                        handle.setAlpha(0.35f);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handle.removeCallbacks(startDrag);
                        dragging = false;
                        handle.setAlpha(0.35f);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /** A control at the screen edge whose transparent part, towards the stream, lets touches through. */
    private class EdgeView extends ImageView {
        EdgeView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN &&
                    (handleOnLeft() ? e.getX() > dp(EDGE_TOUCH_DP) : e.getX() < getWidth() - dp(EDGE_TOUCH_DP))) {
                return false;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    public boolean isOpen() {
        return root.getVisibility() == View.VISIBLE;
    }

    public void open() {
        if (isOpen()) {
            return;
        }
        bitrateTracking = false;
        failureToastShown = false;  // a new closed period after this one gets its own notice
        refresh();
        root.setVisibility(View.VISIBLE);
        handle.setVisibility(View.GONE);
        keyButton.setVisibility(View.GONE);
        host.onPanelOpened();
    }

    public void close() {
        if (!isOpen()) {
            return;
        }
        closing = true;
        commitTypedValues();
        closing = false;
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        root.setVisibility(View.GONE);
        handle.setVisibility(handleHidden ? View.GONE : View.VISIBLE);
        keyButton.setVisibility(handleHidden ? View.GONE : View.VISIBLE);
        host.onPanelClosed();
    }

    /** Puts the panel and its tab back on top (after views were added above them). */
    public void bringToFront() {
        root.bringToFront();
        handle.bringToFront();
        keyButton.bringToFront();
    }

    /** Hides the tab and the keyboard button (picture-in-picture, keypad editor). */
    public void setHandleVisible(boolean visible) {
        handleHidden = !visible;
        handle.setVisibility(visible && !isOpen() ? View.VISIBLE : View.GONE);
        keyButton.setVisibility(visible && !isOpen() ? View.VISIBLE : View.GONE);
    }

    // ---- Picture and quality (reconnect) ----------------------------------------------------

    /** The resolutions, frame rates and HDR this device can show (as the settings screen filters them). */
    private void findDeviceOptions(PreferenceConfiguration config) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int nativeWidth = Math.max(metrics.widthPixels, metrics.heightPixels) & ~1;
        int nativeHeight = Math.min(metrics.widthPixels, metrics.heightPixels) & ~1;
        String nativeSize = nativeWidth + "x" + nativeHeight;

        // 4K only when the screen or a decoder can show it
        boolean can4k = nativeWidth >= 3840 || decoderSupportsWidth("video/avc", 3840) || decoderSupportsWidth("video/hevc", 3840);
        for (String preset : RESOLUTION_PRESETS) {
            if (can4k || !preset.equals(RES_4K)) {
                resolutionPresets.add(preset);
            }
        }
        // The native landscape size; portrait sizes only where the stream is not landscape-locked
        if (!resolutionPresets.contains(nativeSize)) {
            int i = 0;
            while (i < resolutionPresets.size() && widthOf(resolutionPresets.get(i)) <= nativeWidth) {
                i++;
            }
            resolutionPresets.add(i, nativeSize);
        }
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            resolutionPresets.addAll(Arrays.asList(PORTRAIT_PRESETS));
        }
        resolutionCustom.setHint(activity.getString(R.string.hermit_panel_resolution_hint, nativeSize));

        // 90 and 120 FPS only on screens that refresh that fast, unless unlocked in Settings
        float maxFps = display.getRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode mode : display.getSupportedModes()) {
                maxFps = Math.max(maxFps, mode.getRefreshRate());
            }
        }
        for (int preset : FPS_PRESETS) {
            if (config.unlockFps || (preset != 120 || maxFps >= 118) && (preset != 90 || maxFps >= 88)) {
                fpsPresets.add(preset);
            }
        }

        // HDR only on Android 7+ with an HDR10 screen
        boolean hdr10 = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && display.getHdrCapabilities() != null &&
                !PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
            for (int type : display.getHdrCapabilities().getSupportedHdrTypes()) {
                if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                    hdr10 = true;
                    break;
                }
            }
        }
        hdrSwitch.setVisibility(hdr10 ? View.VISIBLE : View.GONE);
    }

    private static int widthOf(String size) {
        return Integer.parseInt(size.substring(0, size.indexOf('x')));
    }

    private static boolean decoderSupportsWidth(String mimeType, int width) {
        try {
            MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1);
            return decoder != null && decoder.getCapabilitiesForType(mimeType).getVideoCapabilities()
                    .getSupportedWidths().contains(width);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void setUpStreamSettings() {
        resolutionSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            String value = (String) resolutionSpinner.getItemAtPosition(position);
            prefs.edit().putString(RESOLUTION, value).apply();
            stateChanged();
        }));
        resolutionCustom.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyCustomResolution();
            }
            return false;
        });

        fpsSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            String label = (String) fpsSpinner.getItemAtPosition(position);
            prefs.edit().putString(FPS, label.replace(" FPS", "")).apply();
            stateChanged();
        }));

        bitrateSlider.setMax(BITRATE_MAX);
        bitrateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updating) {
                    return;
                }
                int value = Math.max(BITRATE_MIN, Math.round(progress / (float) BITRATE_STEP) * BITRATE_STEP);
                prefs.edit().putInt(BITRATE, value).apply();
                bitrateValue.setText(formatMbps(value));
                stateChanged();
                if (!bitrateTracking) {
                    bitrateSlider.removeCallbacks(keyedBitrateChange);
                    bitrateSlider.postDelayed(keyedBitrateChange, 300);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                bitrateTracking = true;
                bitrateSlider.removeCallbacks(keyedBitrateChange);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                bitrateTracking = false;
                if (liveBitrateState == 3) {
                    liveBitrateState = 0;
                }
                requestLiveBitrate();
                stateChanged();  // also when nothing was sent (back at the applied value)
            }
        });
        bitrateValue.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyTypedBitrate();
            }
            return false;
        });
        bitrateValue.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyTypedBitrate();
            }
        });

        // The same names and order as the settings screen
        codecSpinner.setAdapter(adapter(Arrays.asList(activity.getResources().getStringArray(R.array.video_format_names))));
        codecSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            prefs.edit().putString(VIDEO_FORMAT, codecValues[position]).apply();
            stateChanged();
        }));

        hdrSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                prefs.edit().putBoolean(HDR, checked).apply();
                stateChanged();
            }
        });

        autoBitrateSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                prefs.edit().putBoolean(HermitPreferences.AUTO_BITRATE_PREF, checked).apply();
                if (!checked) {
                    stopAutoBitrate();
                }
                stateChanged();
            }
        });
    }

    // ---- Automatic bitrate ------------------------------------------------------------------

    /**
     * Network figures of the last stats window (about once per second). With automatic bitrate
     * on, adjusts the bitrate of the running stream (AutoBitrate) between a fifth of the chosen
     * bitrate and the chosen bitrate, through the host's live bitrate change (Shell).
     */
    public void onNetworkWindow(float lossPct, int rttMs) {
        if (!prefs.getBoolean(HermitPreferences.AUTO_BITRATE_PREF, false)) {
            stopAutoBitrate();
            return;
        }
        if (liveBitrateState == 2 || activity.isFinishing()) {
            return;
        }
        int max = prefs.getInt(BITRATE, startBitrate);
        if (!autoRunning) {
            if (liveBitratePending) {
                return;  // start from where the host is once the request in flight is done
            }
            autoRunning = true;
            restoreChosen = false;
            // From where the host streams now (an earlier request may have failed)
            autoBitrate.reset(max, appliedBitrate);
            autoTarget = autoBitrate.current();
        }
        long now = android.os.SystemClock.uptimeMillis();
        int kbps = autoBitrate.update(lossPct, rttMs, now, max);
        if (kbps > 0) {
            autoTarget = kbps;
            sendAutoTarget();
        } else if ((autoTarget != appliedBitrate || appliedUncertain) && now - autoLastSendMs >= 5000) {
            // A request failed on the way: again every 5 seconds until the host has it
            sendAutoTarget();
        }
        if (isOpen()) {
            stateChanged();
        }
    }

    private void sendAutoTarget() {
        if (autoTarget <= 0 || (autoTarget == appliedBitrate && !appliedUncertain) || liveBitratePending || liveBitrateState == 2) {
            return;
        }
        liveBitratePending = true;
        lastRequestAuto = true;
        autoLastSendMs = android.os.SystemClock.uptimeMillis();
        host.onLiveBitrate(autoTarget);
    }

    // Turned off: back to the chosen bitrate. Called when switched off and on every network
    // window while off, so a failed restore is sent again.
    private void stopAutoBitrate() {
        if (autoRunning) {
            autoRunning = false;
            autoTarget = 0;
            restoreChosen = true;
            restoreLastMs = 0;
        }
        if (!restoreChosen || bitrateTracking) {
            return;  // nothing to bring back, or the pick is still moving under the slider
        }
        if (liveBitrateState == 2 || (prefs.getInt(BITRATE, startBitrate) == appliedBitrate && !appliedUncertain)) {
            restoreChosen = false;
            return;
        }
        // 5 s after the last failure.
        // The failed state stays, so "Apply and reconnect" remains offered meanwhile.
        long now = android.os.SystemClock.uptimeMillis();
        if (!liveBitratePending && now - restoreLastMs >= 5000) {
            restoreLastMs = now;
            requestLiveBitrate();
        }
    }

    private boolean closing;

    private boolean applyCustomResolution() {
        String text = resolutionCustom.getText().toString().trim().toLowerCase(Locale.ROOT).replace('×', 'x');
        String[] parts = text.split("\\s*x\\s*");
        int width = -1, height = -1;
        if (parts.length == 2) {
            try {
                // Even sizes only; odd sizes do not work well with the encoders
                width = Integer.parseInt(parts[0]) & ~1;
                height = Integer.parseInt(parts[1]) & ~1;
            } catch (NumberFormatException ignored) {
            }
        }
        if (width < MIN_WIDTH || height < MIN_HEIGHT || width > MAX_WIDTH || height > MAX_HEIGHT) {
            showInputError(resolutionCustom, R.string.hermit_panel_resolution_error);
            if (closing) {
                // Said once as a toast; not again on every later close
                resolutionCustom.setError(null);
                resolutionCustom.setText("");
            }
            return false;
        }
        resolutionCustom.setError(null);
        prefs.edit().putString(RESOLUTION, width + "x" + height).apply();
        resolutionCustom.setText("");
        refresh();
        return true;
    }

    private boolean applyTypedBitrate() {
        String text = bitrateValue.getText().toString().trim().replace(',', '.');
        int value = -1;
        try {
            value = Math.round(Float.parseFloat(text) * 1000);
        } catch (NumberFormatException ignored) {
        }
        if (value < BITRATE_MIN || value > BITRATE_MAX) {
            showInputError(bitrateValue, R.string.hermit_panel_bitrate_error);
            if (!bitrateValue.hasFocus() || closing) {
                // Left the field: back to the value in effect
                bitrateValue.setError(null);
                refreshBitrate();
            }
            return false;
        }
        bitrateValue.setError(null);
        prefs.edit().putInt(BITRATE, value).apply();
        refreshBitrate();
        stateChanged();
        requestLiveBitrate();
        return true;
    }

    // In the field while the panel stays open, else (the panel closing) as a toast
    private void showInputError(EditText field, int messageRes) {
        if (!closing && isOpen() && field.hasFocus()) {
            field.setError(activity.getString(messageRes));
        } else {
            HermitNotice.show(activity, messageRes, HermitNotice.LONG);
        }
    }

    private void requestLiveBitrate() {
        int bitrate = prefs.getInt(BITRATE, startBitrate);
        // With automatic bitrate the chosen value is the ceiling; AutoBitrate picks it up itself
        if (autoRunning || liveBitrateState == 2 || (bitrate == appliedBitrate && !appliedUncertain) ||
                liveBitratePending || activity.isFinishing()) {
            return;
        }
        liveBitratePending = true;
        lastRequestAuto = false;
        stateChanged();
        host.onLiveBitrate(bitrate);
    }

    /** result: encoding kbps (applied), 0 transient failure, -1 the host cannot change it live. */
    public void setLiveBitrateResult(int requestedKbps, int result) {
        liveBitratePending = false;
        appliedUncertain = result == 0;
        if (result <= 0 && !lastRequestAuto && !isOpen() && !failureToastShown && !activity.isFinishing()) {
            // A change of the user's (sent as the panel closed, or the restore of their choice)
            // failed: say so once; automatic bitrate's own failures show only in the panel
            failureToastShown = true;
            HermitNotice.show(activity, result < 0 ? R.string.hermit_bitrate_reconnect_toast : R.string.hermit_bitrate_failed_toast,
                    HermitNotice.LONG);
        }
        if (result == 0 && restoreChosen) {
            // The next restore attempt 5 s after this failure (a timeout can take longer than 5 s)
            restoreLastMs = android.os.SystemClock.uptimeMillis();
        }
        if (result > 0) {
            liveBitrateState = 1;
            appliedBitrate = requestedKbps;
        }
        else {
            liveBitrateState = result < 0 ? 2 : 3;
            if (result < 0) {
                // The host cannot change it live: automatic bitrate has nothing to drive, and the
                // chosen bitrate cannot be brought back without reconnecting
                autoRunning = false;
                autoTarget = 0;
                restoreChosen = false;
            }
        }
        stateChanged();
        if (autoRunning) {
            // (Automatic bitrate starts only with no request in flight and sends every request
            // while it runs, so this result is one of its own.)
            // AutoBitrate moved on while the request ran
            if (result > 0) {
                sendAutoTarget();
            }
            return;
        }
        // The value may have moved again while the request ran (not after a failure: no retry loop)
        if (result > 0 && prefs.getInt(BITRATE, startBitrate) != requestedKbps) {
            requestLiveBitrate();
        }
    }

    /** False when a typed value is not valid (and was not applied). */
    private boolean commitTypedValues() {
        boolean ok = true;
        if (bitrateValue.hasFocus()) {
            ok = applyTypedBitrate();
        }
        if (resolutionCustom.getText().length() > 0) {
            ok &= applyCustomResolution();
        }
        return ok;
    }

    private boolean reconnectNeededIgnoringBitrate() {
        return !prefs.getString(RESOLUTION, startResolution).equals(startResolution) ||
                !prefs.getString(FPS, startFps).equals(startFps) ||
                !prefs.getString(VIDEO_FORMAT, startCodec).equals(startCodec) ||
                prefs.getBoolean(HDR, startHdr) != startHdr;
    }

    private boolean reconnectNeeded() {
        boolean bitrateDiffers = prefs.getInt(BITRATE, startBitrate) != appliedBitrate;
        return reconnectNeededIgnoringBitrate() ||
                // The bitrate counts only when the host cannot change it live (Shell can), or the
                // last live change failed (the host may also be elsewhere after a timeout)
                (liveBitrateState == 2 && bitrateDiffers) ||
                (liveBitrateState == 3 && !autoRunning && (bitrateDiffers || appliedUncertain));
    }

    private void stateChanged() {
        if (updating) {
            return;
        }
        boolean needed = reconnectNeeded();
        applyButton.setEnabled(needed);
        // While the chosen bitrate is being brought back, Revert keeps it, so a bitrate
        // difference alone is nothing Revert could undo (Apply reconnects with the choice)
        revertButton.setEnabled(restoreChosen ? reconnectNeededIgnoringBitrate() : needed);
        String status = liveBitratePending ? activity.getString(R.string.hermit_panel_bitrate_applying) :
                liveBitrateState == 1 ? activity.getString(R.string.hermit_panel_bitrate_live) :
                liveBitrateState == 2 ? activity.getString(R.string.hermit_panel_bitrate_reconnect) :
                liveBitrateState == 3 ? activity.getString(R.string.hermit_panel_bitrate_failed) : null;
        if (autoRunning && liveBitrateState != 2) {
            // The live status line is replaced by where the automatic adjustment is, and whether
            // its last change failed (it tries again every 5 seconds)
            status = activity.getString(R.string.hermit_panel_auto_bitrate_status,
                    formatMbps(appliedBitrate), formatMbps(prefs.getInt(BITRATE, startBitrate)));
            if (liveBitrateState == 3 && !liveBitratePending) {
                status += "\n" + activity.getString(R.string.hermit_panel_auto_bitrate_failed);
            }
        }
        String recommendation = PreferenceConfiguration.getBitrateRecommendationText(activity);
        bitrateHint.setText(status != null ? status + "\n" + recommendation : recommendation);
        autoBitrateSwitch.setEnabled(liveBitrateState != 2);
    }

    // ---- Display and input (at once) --------------------------------------------------------

    private void setUpLiveSettings() {
        Resources res = activity.getResources();
        String[] names = res.getStringArray(R.array.hermit_overlay_metric_names);
        String[] values = res.getStringArray(R.array.hermit_overlay_metric_values);
        for (int i = 0; i < names.length; i++) {
            CheckBox box = new CheckBox(activity);
            box.setText(names[i]);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.setTag(values[i]);
            box.setOnCheckedChangeListener((b, checked) -> {
                if (!updating) {
                    saveMetrics();
                }
            });
            metricsList.addView(box);
        }

        overlaySwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                prefs.edit().putBoolean(PERF_OVERLAY, checked).apply();
                updateDependents();
                host.onLiveSettingsChanged();
            }
        });

        overlaySizeSpinner.setAdapter(adapter(Arrays.asList(res.getStringArray(R.array.hermit_overlay_size_names))));
        final String[] sizeValues = res.getStringArray(R.array.hermit_overlay_size_values);
        overlaySizeSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            prefs.edit().putString(HermitPreferences.OVERLAY_TEXT_SIZE_PREF, sizeValues[position]).apply();
            host.onLiveSettingsChanged();
        }));

        orientationSpinner.setAdapter(adapter(Arrays.asList(res.getString(R.string.hermit_orientation_auto),
                res.getString(R.string.hermit_orientation_landscape), res.getString(R.string.hermit_orientation_portrait))));
        // Only a real change: the spinner reports its first selection again after refresh()
        orientationSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            if (!ORIENTATIONS[position].equals(orientation())) {
                setOrientation(ORIENTATIONS[position]);
            }
        }));

        handleSideSpinner.setAdapter(adapter(Arrays.asList(res.getStringArray(R.array.hermit_panel_handle_side_names))));
        final String[] sideValues = res.getStringArray(R.array.hermit_panel_handle_side_values);
        handleSideSpinner.setOnItemSelectedListener(new SimpleSelection(position -> {
            prefs.edit().putString(HermitPreferences.PANEL_HANDLE_SIDE_PREF, sideValues[position]).apply();
            placeHandle();
        }));

        clipboardSwitch.setOnCheckedChangeListener(liveSwitch(HermitPreferences.CLIPBOARD_SYNC_PREF));
        trackpadSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                prefs.edit().putBoolean(TRACKPAD, checked).apply();
                updateDependents();
                host.onLiveSettingsChanged();
            }
        });
        oscSwitch.setOnCheckedChangeListener(liveSwitch(ONSCREEN_CONTROLS));
        zoomSwitch.setOnCheckedChangeListener(liveSwitch(PINCH_ZOOM));
        setUpPercentSeek(trackpadSpeed, trackpadSpeedLabel, R.string.title_hermit_trackpad_speed,
                HermitPreferences.TRACKPAD_SPEED_PREF, 50, 300, 10);
        // Steps of 5 like the settings screen, so 100% and 300% can be picked
        setUpPercentSeek(scrollSpeed, scrollSpeedLabel, R.string.title_hermit_scroll_speed,
                HermitPreferences.SCROLL_SPEED_PREF, 25, 300, 5);
        keypadSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                com.junopark.hermit.hermit.keypad.KeypadConfig keypad = com.junopark.hermit.hermit.keypad.KeypadConfig.load(activity);
                keypad.enabled = checked;
                keypad.save(activity);
                host.onLiveSettingsChanged();
            }
        });
    }

    // Controls that do nothing in the current mode: the overlay's rows and text size while it is
    // off (hidden), the trackpad speed in touch mode (dimmed)
    private void updateDependents() {
        boolean overlay = overlaySwitch.isChecked();
        metricsList.setVisibility(overlay ? View.VISIBLE : View.GONE);
        overlaySizeLabel.setVisibility(overlay ? View.VISIBLE : View.GONE);
        overlaySizeSpinner.setVisibility(overlay ? View.VISIBLE : View.GONE);

        boolean trackpad = trackpadSwitch.isChecked();
        setUsable(trackpad, trackpadSpeedLabel, trackpadSpeed);
    }

    private static void setUsable(boolean usable, View... views) {
        for (View view : views) {
            view.setEnabled(usable);
            view.setAlpha(usable ? 1f : 0.4f);
        }
    }

    // A percentage slider saved as an int preference and applied when released
    private void setUpPercentSeek(SeekBar seek, TextView label, int titleRes, String key, int min, int max, int step) {
        seek.setMax((max - min) / step);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText(activity.getString(titleRes) + ": " + (min + progress * step) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(key, min + s.getProgress() * step).apply();
                host.onLiveSettingsChanged();
            }
        });
        seek.setTag(step);
    }

    private void refreshPercentSeek(SeekBar seek, TextView label, int titleRes, String key, int min) {
        int value = prefs.getInt(key, 100);
        int step = seek.getTag() instanceof Integer ? (Integer) seek.getTag() : 10;
        seek.setProgress(Math.max(0, Math.round((value - min) / (float) step)));
        label.setText(activity.getString(titleRes) + ": " + value + "%");
    }

    // ---- Quick menu (the keypad's button) ----------------------------------------------------

    public boolean isTrackpad() {
        return prefs.getBoolean(TRACKPAD, false);
    }

    public void setTrackpad(boolean trackpad) {
        prefs.edit().putBoolean(TRACKPAD, trackpad).apply();
        host.onLiveSettingsChanged();
    }

    public boolean isOverlayShown() {
        return prefs.getBoolean(PERF_OVERLAY, false);
    }

    public void setOverlayShown(boolean shown) {
        prefs.edit().putBoolean(PERF_OVERLAY, shown).apply();
        host.onLiveSettingsChanged();
    }

    /** HermitPreferences.ORIENTATION_AUTO, _LANDSCAPE or _PORTRAIT. */
    public String orientation() {
        return prefs.getString(HermitPreferences.STREAM_ORIENTATION_PREF, HermitPreferences.ORIENTATION_AUTO);
    }

    public void setOrientation(String orientation) {
        prefs.edit().putString(HermitPreferences.STREAM_ORIENTATION_PREF, orientation).apply();
        if (HermitPreferences.ORIENTATION_PORTRAIT.equals(orientation) && prefs.getBoolean(ONSCREEN_CONTROLS, false)) {
            // Game keeps landscape for the on-screen controller: say why nothing turns
            HermitNotice.show(activity, R.string.hermit_orientation_osc_note, HermitNotice.LONG);
        }
        host.onLiveSettingsChanged();
    }

    /**
     * The quick menu's High, Medium and Low bitrates for the running stream: Medium is the
     * recommended bitrate (as the settings show it), High half as much again, Low half of it.
     */
    public int[] bitratePresets() {
        String[] size = startResolution.split("x");
        int medium = PreferenceConfiguration.getRecommendedBitrate(Integer.parseInt(size[0]),
                Integer.parseInt(size[1]), Integer.parseInt(startFps), false);
        return new int[]{presetBitrate(medium * 1.5), presetBitrate(medium), presetBitrate(medium * 0.5)};
    }

    private static int presetBitrate(double kbps) {
        return Math.max(BITRATE_MIN, Math.min(BITRATE_MAX, (int) Math.round(kbps / 1000.0) * 1000));
    }

    /** The chosen bitrate (the maximum when automatic bitrate is on). */
    public int chosenBitrate() {
        return prefs.getInt(BITRATE, startBitrate);
    }

    /** Chooses a bitrate and applies it to the running stream when the host can (Shell). */
    public void applyBitrate(int kbps) {
        prefs.edit().putInt(BITRATE, kbps).apply();
        if (liveBitrateState == 3) {
            liveBitrateState = 0;
        }
        failureToastShown = false;  // a failure of this choice is said once
        requestLiveBitrate();
        stateChanged();
    }

    /** The host cannot change the bitrate live: a new one needs a reconnect. */
    public boolean bitrateNeedsReconnect() {
        return liveBitrateState == 2;
    }

    private android.widget.CompoundButton.OnCheckedChangeListener liveSwitch(final String key) {
        return (b, checked) -> {
            if (!updating) {
                prefs.edit().putBoolean(key, checked).apply();
                host.onLiveSettingsChanged();
            }
        };
    }

    private void saveMetrics() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < metricsList.getChildCount(); i++) {
            CheckBox box = (CheckBox) metricsList.getChildAt(i);
            if (box.isChecked()) {
                keys.add((String) box.getTag());
            }
        }
        prefs.edit().putStringSet(HermitPreferences.OVERLAY_METRICS_PREF, keys).apply();
        host.onLiveSettingsChanged();
    }

    // ---- Shared -----------------------------------------------------------------------------

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static String formatMbps(int kbps) {
        return String.format(Locale.ROOT, "%.1f", kbps / 1000f);
    }

    private void refreshBitrate() {
        int bitrate = prefs.getInt(BITRATE, startBitrate);
        updating = true;
        bitrateSlider.setProgress(bitrate);
        updating = false;
        if (!bitrateValue.hasFocus()) {
            bitrateValue.setText(formatMbps(bitrate));
        }
    }

    /** Loads every control from the saved settings. */
    private void refresh() {
        updating = true;

        String resolution = prefs.getString(RESOLUTION, startResolution);
        List<String> resolutions = new ArrayList<>(resolutionPresets);
        if (!resolutions.contains(resolution)) {
            resolutions.add(0, resolution);
        }
        resolutionSpinner.setAdapter(adapter(resolutions));
        resolutionSpinner.setSelection(resolutions.indexOf(resolution), false);

        int fps;
        try {
            fps = Integer.parseInt(prefs.getString(FPS, startFps));
        } catch (NumberFormatException e) {
            fps = 60;
        }
        List<String> fpsLabels = new ArrayList<>();
        boolean found = false;
        for (int preset : fpsPresets) {
            if (!found && fps < preset) {
                fpsLabels.add(fps + " FPS");
                found = true;
            }
            if (preset == fps) {
                found = true;
            }
            fpsLabels.add(preset + " FPS");
        }
        if (!found) {
            fpsLabels.add(fps + " FPS");
        }
        fpsSpinner.setAdapter(adapter(fpsLabels));
        fpsSpinner.setSelection(fpsLabels.indexOf(fps + " FPS"), false);

        codecSpinner.setSelection(Math.max(0, Arrays.asList(codecValues).indexOf(prefs.getString(VIDEO_FORMAT, "auto"))), false);
        hdrSwitch.setChecked(prefs.getBoolean(HDR, false));
        autoBitrateSwitch.setChecked(prefs.getBoolean(HermitPreferences.AUTO_BITRATE_PREF, false));

        overlaySwitch.setChecked(prefs.getBoolean(PERF_OVERLAY, false));
        Set<String> metrics = prefs.getStringSet(HermitPreferences.OVERLAY_METRICS_PREF, null);
        if (metrics == null) {
            metrics = new HashSet<>(Arrays.asList(activity.getResources().getStringArray(R.array.hermit_overlay_metric_defaults)));
        }
        for (int i = 0; i < metricsList.getChildCount(); i++) {
            CheckBox box = (CheckBox) metricsList.getChildAt(i);
            box.setChecked(metrics.contains((String) box.getTag()));
        }
        String[] sizeValues = activity.getResources().getStringArray(R.array.hermit_overlay_size_values);
        overlaySizeSpinner.setSelection(Math.max(0, Arrays.asList(sizeValues).indexOf(
                prefs.getString(HermitPreferences.OVERLAY_TEXT_SIZE_PREF, "medium"))), false);

        handleSideSpinner.setSelection(handleOnLeft() ? 1 : 0, false);
        orientationSpinner.setSelection(Math.max(0, Arrays.asList(ORIENTATIONS).indexOf(orientation())), false);
        clipboardSwitch.setChecked(prefs.getBoolean(HermitPreferences.CLIPBOARD_SYNC_PREF, true));
        trackpadSwitch.setChecked(prefs.getBoolean(TRACKPAD, false));
        oscSwitch.setChecked(prefs.getBoolean(ONSCREEN_CONTROLS, false));
        zoomSwitch.setChecked(prefs.getBoolean(PINCH_ZOOM, true));
        refreshPercentSeek(trackpadSpeed, trackpadSpeedLabel, R.string.title_hermit_trackpad_speed, HermitPreferences.TRACKPAD_SPEED_PREF, 50);
        refreshPercentSeek(scrollSpeed, scrollSpeedLabel, R.string.title_hermit_scroll_speed, HermitPreferences.SCROLL_SPEED_PREF, 25);
        keypadSwitch.setChecked(com.junopark.hermit.hermit.keypad.KeypadConfig.load(activity).enabled);
        updateDependents();

        updating = false;
        refreshBitrate();
        stateChanged();
    }

    /** Spinner selection callback that ignores programmatic changes made while refreshing. */
    private class SimpleSelection implements AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer onSelected;

        SimpleSelection(java.util.function.IntConsumer onSelected) {
            this.onSelected = onSelected;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!updating) {
                onSelected.accept(position);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
