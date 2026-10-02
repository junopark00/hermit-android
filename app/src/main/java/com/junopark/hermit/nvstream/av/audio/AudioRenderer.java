package com.junopark.hermit.nvstream.av.audio;

import com.junopark.hermit.nvstream.jni.MoonBridge;

public interface AudioRenderer {
    int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame);

    void start();

    void stop();
    
    void playDecodedAudio(short[] audioData);
    
    void cleanup();
}
