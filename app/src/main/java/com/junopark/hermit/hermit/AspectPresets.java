package com.junopark.hermit.hermit;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.util.DisplayMetrics;
import android.view.Display;

import com.junopark.hermit.R;
import com.junopark.hermit.binding.video.MediaCodecHelper;
import com.junopark.hermit.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolution presets in the aspect ratio of this device's screen (most phones are not 16:9, tablets
 * are often 16:10, 4:3 or 3:2), offered by the settings screen and the stream panel next to the
 * standard 16:9 sizes. For the standard heights the width is height x (long side / short side of
 * the real display), rounded to an even number, or to a multiple of 8 when that keeps the ratio
 * within 1%. The stream is landscape, so these are landscape sizes; squarish screens, which also
 * stream in portrait, get portrait copies of the 720 and 1080 sizes as well (like the portrait
 * presets). Values are plain "WIDTHxHEIGHT" strings, parsed like any other resolution.
 */
public final class AspectPresets {
    private static final int[] HEIGHTS = {720, 1080, 1440, 2160};
    // Portrait copies on squarish screens, for the short sides of the portrait presets
    private static final int[] PORTRAIT_HEIGHTS = {720, 1080};

    private AspectPresets() {
    }

    /** One preset: its value ("2400x1080") and whether it is a portrait copy. */
    public static final class Preset {
        public final int width, height;
        public final boolean portrait;
        public final String value;

        Preset(int width, int height, boolean portrait) {
            this.width = width;
            this.height = height;
            this.portrait = portrait;
            this.value = width + "x" + height;
        }

        /** "2400×1080 (screen aspect)", or "(screen aspect, portrait)" for a portrait copy. */
        public String label(Context context) {
            return context.getString(portrait ? R.string.hermit_resolution_screen_aspect_portrait
                    : R.string.hermit_resolution_screen_aspect, width, height);
        }
    }

    /**
     * The presets for this display: heights up to maxHeight (720, 1080, 1440 or 2160, as the
     * caller's capability filters allow), landscape first by height, then any portrait copies.
     * checkDecoders leaves out sizes the video decoders report they cannot decode (only decoders
     * that report 720p as supported are trusted, as in the settings screen). Sizes equal to a
     * standard preset are left out here; the caller skips any other entry it already has.
     */
    public static List<Preset> forDisplay(Display display, int maxHeight, boolean checkDecoders) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        List<Preset> presets = new ArrayList<>();
        if (shortSide <= 0) {
            return presets;
        }
        double aspect = (double) longSide / shortSide;

        for (int height : HEIGHTS) {
            if (height <= maxHeight) {
                add(presets, new Preset(widthFor(height, aspect), height, false), checkDecoders);
            }
        }
        if (PreferenceConfiguration.isSquarishScreen(longSide, shortSide)) {
            for (int height : PORTRAIT_HEIGHTS) {
                if (height <= maxHeight) {
                    add(presets, new Preset(height, widthFor(height, aspect), true), checkDecoders);
                }
            }
        }
        return presets;
    }

    /** height x aspect, even; a multiple of 8 when the ratio stays within 1%. */
    static int widthFor(int height, double aspect) {
        double exact = height * aspect;
        int multipleOf8 = (int) Math.round(exact / 8) * 8;
        if (Math.abs((double) multipleOf8 / height - aspect) <= aspect * 0.01) {
            return multipleOf8;
        }
        return (int) Math.round(exact / 2) * 2;
    }

    private static void add(List<Preset> presets, Preset preset, boolean checkDecoders) {
        if (!PreferenceConfiguration.isNativeResolution(preset.width, preset.height)) {
            return; // a standard preset already (a 16:9 screen)
        }
        for (Preset other : presets) {
            if (other.value.equals(preset.value)) {
                return;
            }
        }
        if (checkDecoders && !decodable(preset.width, preset.height)) {
            return;
        }
        presets.add(preset);
    }

    /** False only when a trusted decoder exists and no trusted decoder can decode the size. */
    private static boolean decodable(int width, int height) {
        boolean trusted = false;
        for (String mimeType : new String[]{"video/avc", "video/hevc"}) {
            try {
                MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1);
                if (decoder == null) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities caps = decoder.getCapabilitiesForType(mimeType).getVideoCapabilities();
                if (!caps.getSupportedWidths().contains(1280)) {
                    continue; // reports nonsense: ignored, as in the settings screen
                }
                trusted = true;
                if (caps.isSizeSupported(width, height)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // A broken decoder: no say either way
            }
        }
        return !trusted;
    }
}
