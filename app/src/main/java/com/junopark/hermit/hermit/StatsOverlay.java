package com.junopark.hermit.hermit;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.TabStopSpan;

import com.junopark.hermit.R;

import java.util.Locale;
import java.util.Set;

/**
 * Builds the rows of the performance overlay ("label\tvalue" lines) from the metrics the user
 * picked in Settings, and styles them as a two-column panel. Same metrics and wording as the
 * overlay of Hermit for Windows, minus what Android's decoder path does not measure (jitter drops,
 * queue delay, render time).
 */
public final class StatsOverlay {
    public static final int METRIC_VIDEO = 1;
    public static final int METRIC_FRAME_RATES = 1 << 1;
    public static final int METRIC_BITRATE = 1 << 2;
    public static final int METRIC_NETWORK_LOSS = 1 << 3;
    public static final int METRIC_RTT = 1 << 4;
    public static final int METRIC_HOST_LATENCY = 1 << 5;
    public static final int METRIC_DECODE = 1 << 6;
    public static final int METRIC_TOTAL_LATENCY = 1 << 7;
    public static final int METRIC_DECODER = 1 << 8;
    public static final int METRIC_DEVICE = 1 << 9;   // this device's battery and heat

    // The important ones: what the picture is, whether the network keeps up, and how late it is.
    public static final int DEFAULT_METRICS = METRIC_VIDEO | METRIC_BITRATE | METRIC_NETWORK_LOSS |
            METRIC_RTT | METRIC_HOST_LATENCY | METRIC_TOTAL_LATENCY;

    // Keys stored by the MultiSelectListPreference (res/values/hermit_arrays.xml), in bit order.
    private static final String[] KEYS = {
            "video", "frame_rates", "bitrate", "network_loss", "rtt",
            "host_latency", "decode", "total_latency", "decoder", "device"
    };

    /** Per-second measurements from the decoder. */
    public static class Inputs {
        public int width, height;
        public String codec;
        public String decoder;
        public float totalFps, receivedFps, renderedFps;
        public float bitrateMbps, peakBitrateMbps;
        public float networkLossPct;
        public int rttMs, rttVarianceMs;     // -1 without an estimate
        public boolean hasHostLatency;
        public float hostLatencyMinMs, hostLatencyMaxMs, hostLatencyAvgMs;
        public float decodeMs;
        public String device = "";
    }

    private StatsOverlay() {
    }

    public static int metricsFromKeys(Set<String> keys) {
        int metrics = 0;
        for (int i = 0; i < KEYS.length; i++) {
            if (keys.contains(KEYS[i])) {
                metrics |= 1 << i;
            }
        }
        return metrics;
    }

    private static String fixed(float value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static void row(StringBuilder sb, Context context, int labelRes, String value) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(context.getString(labelRes)).append('\t').append(value);
    }

    /** Estimated one-way latency: host processing + half the round trip + decoding (display not included). */
    public static float estimatedTotalLatencyMs(Inputs in) {
        return (in.hasHostLatency ? in.hostLatencyAvgMs : 0) + Math.max(in.rttMs, 0) / 2.0f + in.decodeMs;
    }

    public static String format(Context context, Inputs in, int metrics) {
        StringBuilder sb = new StringBuilder();
        if ((metrics & METRIC_VIDEO) != 0) {
            row(sb, context, R.string.hermit_overlay_video,
                    in.width + "×" + in.height + " · " + in.codec + " · " + fixed(in.totalFps, 1) + " FPS");
        }
        if ((metrics & METRIC_FRAME_RATES) != 0) {
            row(sb, context, R.string.hermit_overlay_frame_rates,
                    fixed(in.receivedFps, 1) + " / " + fixed(in.renderedFps, 1));
        }
        if ((metrics & METRIC_BITRATE) != 0) {
            row(sb, context, R.string.hermit_overlay_bitrate,
                    context.getString(R.string.hermit_overlay_bitrate_value, fixed(in.bitrateMbps, 1), fixed(in.peakBitrateMbps, 1)));
        }
        if ((metrics & METRIC_NETWORK_LOSS) != 0) {
            row(sb, context, R.string.hermit_overlay_network_loss, fixed(in.networkLossPct, 2) + "%");
        }
        if ((metrics & METRIC_RTT) != 0) {
            row(sb, context, R.string.hermit_overlay_rtt, in.rttMs >= 0 ? in.rttMs + " ms ± " + in.rttVarianceMs : "–");
        }
        if ((metrics & METRIC_HOST_LATENCY) != 0) {
            row(sb, context, R.string.hermit_overlay_host_latency, in.hasHostLatency ?
                    fixed(in.hostLatencyAvgMs, 1) + " ms (" + fixed(in.hostLatencyMinMs, 1) + "–" + fixed(in.hostLatencyMaxMs, 1) + ")" :
                    "–");
        }
        if ((metrics & METRIC_DECODE) != 0) {
            row(sb, context, R.string.hermit_overlay_decode, fixed(in.decodeMs, 2) + " ms");
        }
        if ((metrics & METRIC_TOTAL_LATENCY) != 0) {
            row(sb, context, R.string.hermit_overlay_total_latency, "≈ " + fixed(estimatedTotalLatencyMs(in), 1) + " ms");
        }
        if ((metrics & METRIC_DECODER) != 0) {
            row(sb, context, R.string.hermit_overlay_decoder, in.decoder);
        }
        if ((metrics & METRIC_DEVICE) != 0) {
            row(sb, context, R.string.hermit_overlay_device, in.device);
        }
        return sb.toString();
    }

    /**
     * Turns "label\tvalue" lines into a two-column panel: labels in the secondary text colour,
     * values in the primary colour, aligned on one tab stop after the widest label.
     */
    public static CharSequence style(String text, TextPaint paint, int labelColor, int valueColor, float gapPx) {
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        String[] lines = text.split("\n", -1);

        float labelWidth = 0;
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                labelWidth = Math.max(labelWidth, paint.measureText(line, 0, tab));
            }
        }
        TabStopSpan tabStop = new TabStopSpan.Standard((int) Math.ceil(labelWidth + gapPx));

        int start = 0;
        for (String line : lines) {
            int end = start + line.length();
            int paragraphEnd = Math.min(end + 1, out.length());
            int tab = line.indexOf('\t');
            out.setSpan(new TabStopSpan.Standard(tabStop.getTabStop()), start, paragraphEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            if (tab > 0) {
                out.setSpan(new ForegroundColorSpan(labelColor), start, start + tab, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(valueColor), start + tab + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
        return out;
    }
}
