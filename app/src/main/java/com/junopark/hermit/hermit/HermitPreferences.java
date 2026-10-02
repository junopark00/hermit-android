package com.junopark.hermit.hermit;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Set;

/**
 * Settings for Hermit's own features, kept apart from the upstream PreferenceConfiguration so
 * upstream merges stay small. The keys are used by res/xml/preferences.xml.
 */
public class HermitPreferences {
    public static final String CLIPBOARD_SYNC_PREF = "checkbox_hermit_clipboard_sync";
    public static final String AUTO_RECONNECT_PREF = "checkbox_hermit_auto_reconnect";
    public static final String SESSION_SUMMARY_PREF = "checkbox_hermit_session_summary";
    public static final String OVERLAY_METRICS_PREF = "list_hermit_overlay_metrics";
    public static final String OVERLAY_TEXT_SIZE_PREF = "list_hermit_overlay_text_size";
    public static final String PINCH_ZOOM_PREF = "checkbox_hermit_pinch_zoom";
    public static final String TRACKPAD_SPEED_PREF = "seekbar_hermit_trackpad_speed";
    public static final String SCROLL_SPEED_PREF = "seekbar_hermit_scroll_speed";
    public static final String AUTO_BITRATE_PREF = "checkbox_hermit_auto_bitrate";
    public static final String PANEL_HANDLE_SIDE_PREF = "list_hermit_panel_handle_side";
    public static final String PANEL_HANDLE_Y_PREF = "hermit_panel_handle_y";
    // Screen orientation during a stream: auto follows the stream resolution's longer side
    public static final String STREAM_ORIENTATION_PREF = "list_hermit_stream_orientation";
    public static final String ORIENTATION_AUTO = "auto";
    public static final String ORIENTATION_LANDSCAPE = "landscape";
    public static final String ORIENTATION_PORTRAIT = "portrait";

    // Read by the touch contexts (upstream classes) on every touch; updated by read()
    public static volatile float trackpadScale = 1f;
    public static volatile float scrollScale = 1f;

    public boolean clipboardSync;
    public boolean autoReconnect;
    public boolean sessionSummary;
    public boolean pinchZoom;
    public int overlayMetrics;
    public float overlayTextSizeSp;

    public static HermitPreferences read(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        HermitPreferences config = new HermitPreferences();

        config.clipboardSync = prefs.getBoolean(CLIPBOARD_SYNC_PREF, true);
        config.autoReconnect = prefs.getBoolean(AUTO_RECONNECT_PREF, true);
        config.sessionSummary = prefs.getBoolean(SESSION_SUMMARY_PREF, true);
        config.pinchZoom = prefs.getBoolean(PINCH_ZOOM_PREF, true);
        trackpadScale = Math.max(50, Math.min(300, prefs.getInt(TRACKPAD_SPEED_PREF, 100))) / 100f;
        scrollScale = Math.max(25, Math.min(300, prefs.getInt(SCROLL_SPEED_PREF, 100))) / 100f;

        Set<String> metrics = prefs.getStringSet(OVERLAY_METRICS_PREF, null);
        config.overlayMetrics = metrics != null ? StatsOverlay.metricsFromKeys(metrics) : StatsOverlay.DEFAULT_METRICS;

        switch (prefs.getString(OVERLAY_TEXT_SIZE_PREF, "medium")) {
            case "small":
                config.overlayTextSizeSp = 12;
                break;
            case "large":
                config.overlayTextSizeSp = 17;
                break;
            default:
                config.overlayTextSizeSp = 14;
                break;
        }

        return config;
    }

    public static void disableSessionSummary(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(SESSION_SUMMARY_PREF, false)
                .apply();
    }
}
