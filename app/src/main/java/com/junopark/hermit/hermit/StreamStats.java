package com.junopark.hermit.hermit;

/**
 * Whole-session totals from the video decoder, taken when the stream stops. Filled by
 * MediaCodecDecoderRenderer.getHermitStreamStats() and turned into a SessionSummary.
 */
public class StreamStats {
    public long measuredMs;          // from the first frame to the snapshot
    public int width, height;
    public String codec = "";
    public int targetFps;
    public int bitrateKbps;          // requested bitrate (the one in effect at the end)

    public long totalFrames;         // received + lost
    public long framesReceived;
    public long framesRendered;
    public long framesLost;
    public long decoderTimeMs;       // summed per frame
    public long totalBytes;          // video payload received

    public long hostLatencyTotal;    // in 0.1 ms units, summed over framesWithHostLatency
    public long framesWithHostLatency;
    public int hostLatencyMax;       // in 0.1 ms units

    public int rttMs, rttVarianceMs; // last estimate while streaming, -1 if none
}
