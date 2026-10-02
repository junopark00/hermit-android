package com.junopark.hermit.hermit;

/**
 * Automatic bitrate for streams to a Shell host (live bitrate changes), same rules as Hermit for
 * Windows (app/streaming/autobitrate.h). Fed once per second with the last stats window:
 * - congestion (more than 2% of frames lost, or the round trip clearly above its usual value)
 *   lowers the bitrate to 80%, at most every 2 s, down to a fifth of the chosen bitrate
 *   (at least 2 Mbps);
 * - 10 calm windows (no loss, usual round trip), at least 10 s after the last change, raise it
 *   by a tenth of the chosen bitrate (at least 1 Mbps), up to the chosen bitrate;
 * - a window with too few frames to judge loss (lossPct < 0, a still picture) counts only its
 *   round trip; it neither adds to nor clears the calm windows.
 */
public class AutoBitrate {
    private int max;
    private int current;
    private long lastChangeMs;
    private int calmWindows;
    private float baseRtt = -1;

    /** startKbps: where the host streams now (0: at the ceiling). */
    public void reset(int maxKbps, int startKbps) {
        max = maxKbps;
        current = startKbps > 0 ? Math.max(Math.min(minKbps(), maxKbps), Math.min(startKbps, maxKbps)) : maxKbps;
        lastChangeMs = 0;
        calmWindows = 0;
        baseRtt = -1;
    }

    public void reset(int maxKbps) {
        reset(maxKbps, 0);
    }

    public int current() {
        return current;
    }

    private int minKbps() {
        return Math.max(2000, max / 5);
    }

    /** Returns the new bitrate in kbps, or 0 to keep the current one. */
    public int update(float lossPct, int rttMs, long nowMs, int maxKbps) {
        if (maxKbps != max) {
            // The user picked another bitrate: the new ceiling and the new start
            reset(maxKbps);
            lastChangeMs = nowMs;
            return current;
        }

        if (rttMs > 0) {
            baseRtt = baseRtt < 0 ? rttMs : Math.min(rttMs, baseRtt + 0.5f);
        }
        boolean rttHigh = baseRtt > 0 && rttMs > baseRtt + 40 && rttMs > baseRtt * 1.5f;

        if (lossPct > 2.0f || rttHigh) {
            calmWindows = 0;
            if (current > minKbps() && nowMs - lastChangeMs >= 2000) {
                current = Math.max(minKbps(), (int) (current * 0.8f) / 500 * 500);
                lastChangeMs = nowMs;
                return current;
            }
            return 0;
        }

        if (lossPct < 0) {
            return 0;  // too few frames to tell: neither calm nor trouble
        }

        if (lossPct < 0.3f && !(baseRtt > 0 && rttMs > baseRtt + 20)) {
            calmWindows++;
            if (current < max && calmWindows >= 10 && nowMs - lastChangeMs >= 10000) {
                current = Math.min(max, current + Math.max(1000, max / 10));
                lastChangeMs = nowMs;
                calmWindows = 0;
                return current;
            }
        } else {
            calmWindows = 0;
        }
        return 0;
    }
}
