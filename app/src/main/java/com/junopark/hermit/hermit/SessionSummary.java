package com.junopark.hermit.hermit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.junopark.hermit.HermitLog;
import com.junopark.hermit.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session summary, as in Hermit for Windows: after a stream of at least 30 seconds, the main figures
 * are shown next to the average of the previous 10 sessions, and every session is appended to
 * session-history.csv in the app's files folder.
 */
public class SessionSummary {
    public static final long MIN_DURATION_MS = 30000;
    private static final String HISTORY_FILE = "session-history.csv";
    private static final String[] COLUMNS = {
            "ended", "host", "app", "duration_s", "width", "height", "codec", "target_fps",
            "avg_fps", "bitrate_mbps", "network_loss_pct", "host_latency_avg_ms",
            "host_latency_max_ms", "rtt_ms", "rtt_variance_ms", "decode_ms"
    };
    // Compared with the previous sessions
    private static final String[] AVERAGED = {
            "avg_fps", "bitrate_mbps", "network_loss_pct", "host_latency_avg_ms", "rtt_ms", "decode_ms"
    };

    public String host, app;
    public long durationMs;
    public int width, height, targetFps, bitrateKbps;
    public String codec;
    public double avgFps, bitrateMbps, networkLossPct, decodeMs;
    public boolean hasHostLatency;
    public double hostLatencyAvgMs, hostLatencyMaxMs;
    public int rttMs, rttVarianceMs;

    private static SessionSummary pendingSummary;
    private static Map<String, Double> pendingPrevious;

    /** Returns null for sessions too short (or without video) to be meaningful. */
    public static SessionSummary from(StreamStats stats, String host, String app) {
        if (stats == null || stats.measuredMs < MIN_DURATION_MS || stats.framesRendered == 0) {
            return null;
        }
        SessionSummary s = new SessionSummary();
        s.host = host != null ? host : "";
        s.app = app != null ? app : "";
        s.durationMs = stats.measuredMs;
        s.width = stats.width;
        s.height = stats.height;
        s.codec = stats.codec;
        s.targetFps = stats.targetFps;
        s.bitrateKbps = stats.bitrateKbps;
        double secs = stats.measuredMs / 1000.0;
        s.avgFps = stats.framesRendered / secs;
        s.bitrateMbps = stats.totalBytes * 8.0 / secs / 1000000.0;
        s.networkLossPct = stats.totalFrames > 0 ? stats.framesLost * 100.0 / stats.totalFrames : 0;
        s.decodeMs = stats.framesReceived > 0 ? (double) stats.decoderTimeMs / stats.framesReceived : 0;
        s.hasHostLatency = stats.framesWithHostLatency > 0;
        if (s.hasHostLatency) {
            s.hostLatencyAvgMs = stats.hostLatencyTotal / 10.0 / stats.framesWithHostLatency;
            s.hostLatencyMaxMs = stats.hostLatencyMax / 10.0;
        }
        s.rttMs = stats.rttMs;
        s.rttVarianceMs = stats.rttVarianceMs;
        return s;
    }

    /** Appends the session to the history and, if wanted, keeps it to be shown by showIfPending(). */
    public static void record(Context context, SessionSummary summary, boolean show) {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        Map<String, Double> previous = previousAverages(file);
        try {
            summary.append(file);
        } catch (IOException e) {
            HermitLog.warning("Could not write the session history: " + e);
        }
        if (show) {
            pendingSummary = summary;
            pendingPrevious = previous;
        }
    }

    // ---- History ----------------------------------------------------------------------------

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String num(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private void append(File file) throws IOException {
        boolean exists = file.isFile() && file.length() > 0;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (!exists) {
                out.write(String.join(",", COLUMNS) + "\n");
            }
            String[] fields = {
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()),
                    csv(host), csv(app), Long.toString(durationMs / 1000),
                    Integer.toString(width), Integer.toString(height), csv(codec), Integer.toString(targetFps),
                    num(avgFps, 2), num(bitrateMbps, 2), num(networkLossPct, 3),
                    hasHostLatency ? num(hostLatencyAvgMs, 2) : "", hasHostLatency ? num(hostLatencyMaxMs, 1) : "",
                    rttMs >= 0 ? Integer.toString(rttMs) : "", rttMs >= 0 ? Integer.toString(rttVarianceMs) : "", num(decodeMs, 2)
            };
            out.write(String.join(",", fields) + "\n");
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** Averages of the last 10 recorded sessions; empty when there are none. */
    private static Map<String, Double> previousAverages(File file) {
        Map<String, Double> result = new HashMap<>();
        if (!file.isFile()) {
            return result;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            return result;
        }
        if (lines.size() < 2) {
            return result;
        }
        List<String> header = parseCsvLine(lines.get(0));
        int begin = Math.max(1, lines.size() - 10);
        for (String key : AVERAGED) {
            int column = header.indexOf(key);
            if (column < 0) {
                continue;
            }
            double sum = 0;
            int count = 0;
            for (int i = begin; i < lines.size(); i++) {
                List<String> fields = parseCsvLine(lines.get(i));
                if (column < fields.size() && !fields.get(column).isEmpty()) {
                    try {
                        sum += Double.parseDouble(fields.get(column));
                        count++;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (count > 0) {
                result.put(key, sum / count);
            }
        }
        return result;
    }

    // ---- Dialog -----------------------------------------------------------------------------

    /** Status of a value where lower is better: 0 good, 1 check, 2 poor. */
    private static int level(double value, double good, double bad) {
        return value <= good ? 0 : (value <= bad ? 1 : 2);
    }

    public static void showIfPending(Activity activity) {
        SessionSummary s = pendingSummary;
        Map<String, Double> previous = pendingPrevious;
        pendingSummary = null;
        pendingPrevious = null;
        if (s == null || activity.isFinishing()) {
            return;
        }
        new Dialog(activity, s, previous).show();
    }

    private static final class Dialog {
        private final Activity activity;
        private final SessionSummary s;
        private final Map<String, Double> previous;
        private final float density;
        private final Typeface condensed;

        Dialog(Activity activity, SessionSummary s, Map<String, Double> previous) {
            this.activity = activity;
            this.s = s;
            this.previous = previous;
            this.density = activity.getResources().getDisplayMetrics().density;
            Typeface tf = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    tf = activity.getResources().getFont(R.font.plex_sans_condensed_medium);
                } catch (RuntimeException ignored) {
                }
            }
            this.condensed = tf;
        }

        private int dp(float value) {
            return Math.round(value * density);
        }

        private TextView text(CharSequence value, int colorRes, float sp, boolean label) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextColor(activity.getResources().getColor(colorRes));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            if (label && condensed != null) {
                view.setTypeface(condensed);
            }
            view.setFontFeatureSettings("tnum");
            return view;
        }

        private String duration() {
            int total = (int) Math.round(s.durationMs / 60000.0);
            if (total < 60) {
                return activity.getString(R.string.hermit_summary_minutes, Math.max(1, total));
            }
            return activity.getString(R.string.hermit_summary_hours, total / 60, total % 60);
        }

        private String prev(String key, int digits, String unit) {
            Double value = previous != null ? previous.get(key) : null;
            return value == null ? "–" : num(value, digits) + unit;
        }

        private void fact(TableLayout table, int labelRes, String value) {
            TableRow row = new TableRow(activity);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView label = text(activity.getString(labelRes), R.color.hermit_text_helper, 13, true);
            label.setPadding(0, 0, dp(16), 0);
            row.addView(label);
            row.addView(text(value, R.color.hermit_text_primary, 14, false));
            table.addView(row);
        }

        private void metric(TableLayout table, int nameRes, String value, String prev, int level) {
            TableRow row = new TableRow(activity);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(text(activity.getString(nameRes), R.color.hermit_text_secondary, 13, false));
            TextView valueView = text(value, R.color.hermit_text_primary, 14, false);
            valueView.setGravity(Gravity.END);
            valueView.setPadding(dp(12), 0, 0, 0);
            row.addView(valueView);
            TextView prevView = text(prev, R.color.hermit_text_helper, 13, false);
            prevView.setGravity(Gravity.END);
            prevView.setPadding(dp(12), 0, 0, 0);
            row.addView(prevView);

            LinearLayout status = new LinearLayout(activity);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(12), 0, 0, 0);
            if (level >= 0) {
                int colorRes = level == 0 ? R.color.hermit_success : level == 1 ? R.color.hermit_warning : R.color.hermit_danger;
                int textRes = level == 0 ? R.string.hermit_summary_good : level == 1 ? R.string.hermit_summary_check : R.string.hermit_summary_poor;
                View square = new View(activity);
                square.setBackgroundColor(activity.getResources().getColor(colorRes));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
                lp.setMarginEnd(dp(6));
                status.addView(square, lp);
                status.addView(text(activity.getString(textRes), R.color.hermit_text_secondary, 13, false));
            }
            row.addView(status);
            table.addView(row);
        }

        private View divider() {
            View line = new View(activity);
            line.setBackgroundColor(activity.getResources().getColor(R.color.hermit_layer2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(0, dp(12), 0, dp(12));
            line.setLayoutParams(lp);
            return line;
        }

        void show() {
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(24), dp(8), dp(24), dp(8));

            TableLayout facts = new TableLayout(activity);
            fact(facts, R.string.hermit_summary_host, s.host);
            fact(facts, R.string.hermit_summary_app, s.app);
            fact(facts, R.string.hermit_summary_duration, duration());
            fact(facts, R.string.hermit_summary_video, s.width + "×" + s.height + " · " + s.codec +
                    (s.targetFps > 0 ? " · " + s.targetFps + " FPS" : ""));
            fact(facts, R.string.hermit_summary_bitrate, s.bitrateKbps > 0 ?
                    activity.getString(R.string.hermit_summary_bitrate_value, num(s.bitrateMbps, 1), num(s.bitrateKbps / 1000.0, 0)) :
                    activity.getString(R.string.hermit_summary_bitrate_avg, num(s.bitrateMbps, 1)));
            // Long values wrap instead of pushing the table past the dialog's edge
            facts.setColumnShrinkable(1, true);
            content.addView(facts);
            content.addView(divider());

            TableLayout metrics = new TableLayout(activity);
            metrics.setColumnStretchable(0, true);
            // The metric names wrap in portrait, so the status column stays on screen
            metrics.setColumnShrinkable(0, true);
            TableRow header = new TableRow(activity);
            header.setPadding(0, 0, 0, dp(4));
            int[] headers = {R.string.hermit_summary_metric, R.string.hermit_summary_this,
                    R.string.hermit_summary_previous, R.string.hermit_summary_status};
            for (int i = 0; i < headers.length; i++) {
                TextView h = text(activity.getString(headers[i]), R.color.hermit_text_helper, 12, true);
                if (i == 1 || i == 2) {
                    h.setGravity(Gravity.END);
                    h.setPadding(dp(12), 0, 0, 0);
                } else if (i == 3) {
                    h.setPadding(dp(12), 0, 0, 0);
                }
                header.addView(h);
            }
            metrics.addView(header);

            // Average FPS is not rated: the host sends frames only when the screen changes.
            metric(metrics, R.string.hermit_summary_avg_fps, num(s.avgFps, 1) + (s.targetFps > 0 ? " / " + s.targetFps : ""),
                    prev("avg_fps", 1, ""), -1);
            metric(metrics, R.string.hermit_summary_network_loss, num(s.networkLossPct, 2) + "%",
                    prev("network_loss_pct", 2, "%"), level(s.networkLossPct, 0.1, 1));
            if (s.hasHostLatency) {
                metric(metrics, R.string.hermit_summary_host_avg, num(s.hostLatencyAvgMs, 1) + " ms",
                        prev("host_latency_avg_ms", 1, " ms"), level(s.hostLatencyAvgMs, 8, 16));
                metric(metrics, R.string.hermit_summary_host_max, num(s.hostLatencyMaxMs, 1) + " ms", "–", -1);
            }
            metric(metrics, R.string.hermit_summary_rtt, s.rttMs >= 0 ? s.rttMs + " ms ±" + s.rttVarianceMs : "–",
                    prev("rtt_ms", 0, " ms"), s.rttMs >= 0 ? level(s.rttMs, 30, 80) : -1);
            metric(metrics, R.string.hermit_summary_decode, num(s.decodeMs, 1) + " ms",
                    prev("decode_ms", 1, " ms"), -1);
            content.addView(metrics);
            content.addView(divider());

            TextView fpsNote = text(activity.getString(R.string.hermit_summary_fps_note), R.color.hermit_text_helper, 12, false);
            content.addView(fpsNote);
            if (s.networkLossPct > 1) {
                TextView lossNote = text(activity.getString(R.string.hermit_summary_loss_note), R.color.hermit_warning, 13, false);
                lossNote.setPadding(0, dp(8), 0, 0);
                content.addView(lossNote);
            }

            ScrollView scroller = new ScrollView(activity);
            scroller.addView(content);

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.hermit_summary_title)
                    .setView(scroller)
                    .setPositiveButton(R.string.hermit_summary_close, null)
                    .setNeutralButton(R.string.hermit_summary_dont_show,
                            (dialog, which) -> HermitPreferences.disableSessionSummary(activity))
                    .show();
        }
    }
}
