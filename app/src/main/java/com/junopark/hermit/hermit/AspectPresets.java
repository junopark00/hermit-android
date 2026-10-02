package com.junopark.hermit.hermit;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.util.DisplayMetrics;
import android.view.Display;

import com.junopark.hermit.R;
import com.junopark.hermit.binding.video.MediaCodecHelper;
import com.junopark.hermit.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collection;
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
     * existing holds the caller's entries ("WIDTHxHEIGHT": standard, native and so on); a preset
     * of the same height as one of them and within 1% of its width is left out, as is a standard
     * 16:9 size. checkDecoders leaves out sizes the video decoders report they cannot decode
     * (only decoders that report 720p as supported are trusted, as in the settings screen).
     */
    public static List<Preset> forDisplay(Display display, int maxHeight, boolean checkDecoders,
                                          Collection<String> existing) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        List<Preset> presets = new ArrayList<>();
        for (Preset preset : candidates(metrics.widthPixels, metrics.heightPixels, maxHeight, existing)) {
            if (!checkDecoders || decodable(preset.width, preset.height)) {
                presets.add(preset);
            }
        }
        return presets;
    }

    /** forDisplay() without the display and the decoders: plain arithmetic, testable on its own. */
    static List<Preset> candidates(int screenWidth, int screenHeight, int maxHeight, Collection<String> existing) {
        int longSide = Math.max(screenWidth, screenHeight);
        int shortSide = Math.min(screenWidth, screenHeight);
        List<Preset> presets = new ArrayList<>();
        if (shortSide <= 0) {
            return presets;
        }
        double aspect = (double) longSide / shortSide;

        for (int height : HEIGHTS) {
            if (height <= maxHeight) {
                add(presets, new Preset(widthFor(height, aspect), height, false), existing);
            }
        }
        if (PreferenceConfiguration.isSquarishScreen(longSide, shortSide)) {
            for (int height : PORTRAIT_HEIGHTS) {
                if (height <= maxHeight) {
                    add(presets, new Preset(height, widthFor(height, aspect), true), existing);
                }
            }
        }
        return presets;
    }

    /**
     * height x aspect: the exact width when that is (all but) an even number, as at the screen's
     * own height (1080 x 2340/1080 stays 2340); otherwise a multiple of 8 when the ratio stays
     * within 1%, else the nearest even number.
     */
    static int widthFor(int height, double aspect) {
        double exact = height * aspect;
        long even = Math.round(exact / 2) * 2;
        if (Math.abs(exact - even) <= 0.01) {
            return (int) even;
        }
        int multipleOf8 = (int) Math.round(exact / 8) * 8;
        if (Math.abs((double) multipleOf8 / height - aspect) <= aspect * 0.01) {
            return multipleOf8;
        }
        return (int) even;
    }

    private static void add(List<Preset> presets, Preset preset, Collection<String> existing) {
        if (!PreferenceConfiguration.isNativeResolution(preset.width, preset.height)) {
            return; // a standard preset already (a 16:9 screen)
        }
        for (String value : existing) {
            if (nearlySame(preset, value)) {
                return; // the native size, for example
            }
        }
        for (Preset other : presets) {
            if (nearlySame(preset, other.value)) {
                return;
            }
        }
        presets.add(preset);
    }

    /** Same height as "WIDTHxHEIGHT" and a width within 1% of it (2344x1080 next to 2340x1080). */
    static boolean nearlySame(Preset preset, String value) {
        int x = value.indexOf('x');
        if (x <= 0) {
            return false;
        }
        try {
            int width = Integer.parseInt(value.substring(0, x));
            int height = Integer.parseInt(value.substring(x + 1));
            return height == preset.height && Math.abs(width - preset.width) <= width * 0.01;
        } catch (NumberFormatException e) {
            return false;
        }
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
