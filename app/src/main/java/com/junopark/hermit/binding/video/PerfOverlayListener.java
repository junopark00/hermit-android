package com.junopark.hermit.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);

    // Hermit: frames lost (percent) and round trip of the last stats window, about once per
    // second on the decoder thread, for automatic bitrate
    default void onNetworkWindow(float lossPct, int rttMs) {
    }
}
