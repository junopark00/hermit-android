package com.junopark.hermit.binding;

import android.content.Context;

import com.junopark.hermit.binding.audio.AndroidAudioRenderer;
import com.junopark.hermit.binding.crypto.AndroidCryptoProvider;
import com.junopark.hermit.nvstream.av.audio.AudioRenderer;
import com.junopark.hermit.nvstream.http.ClientCryptoProvider;

public class PlatformBinding {
    public static ClientCryptoProvider getCryptoProvider(Context c) {
        return new AndroidCryptoProvider(c);
    }
}
