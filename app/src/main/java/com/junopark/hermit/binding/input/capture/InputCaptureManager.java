package com.junopark.hermit.binding.input.capture;

import android.app.Activity;

import com.junopark.hermit.BuildConfig;
import com.junopark.hermit.HermitLog;
import com.junopark.hermit.R;
import com.junopark.hermit.binding.input.evdev.EvdevCaptureProviderShim;
import com.junopark.hermit.binding.input.evdev.EvdevListener;

public class InputCaptureManager {
    public static InputCaptureProvider getInputCaptureProvider(Activity activity, EvdevListener rootListener) {
        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            HermitLog.info("Using Android O+ native mouse capture");
            return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.surfaceView));
        }
        // LineageOS implemented broken NVIDIA capture extensions, so avoid using them on root builds.
        // See https://github.com/LineageOS/android_frameworks_base/commit/d304f478a023430f4712dbdc3ee69d9ad02cebd3
        else if (!BuildConfig.ROOT_BUILD && ShieldCaptureProvider.isCaptureProviderSupported()) {
            HermitLog.info("Using NVIDIA mouse capture extension");
            return new ShieldCaptureProvider(activity);
        }
        else if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            HermitLog.info("Using Evdev mouse capture");
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener);
        }
        else if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported()) {
            // Android N's native capture can't capture over system UI elements
            // so we want to only use it if there's no other option.
            HermitLog.info("Using Android N+ pointer hiding");
            return new AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.surfaceView));
        }
        else {
            HermitLog.info("Mouse capture not available");
            return new NullCaptureProvider();
        }
    }
}
