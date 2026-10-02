package com.junopark.hermit.hermit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

import com.junopark.hermit.R;

/**
 * Touch feedback for Hermit's own buttons drawn from shapes (quick menu, text input bar): a teal
 * ripple over the shape, so a press shows at once, as on the system's buttons.
 */
public final class HermitPress {
    private HermitPress() {
    }

    /** The background with a ripple; content may be null (a plain row), radius in pixels. */
    public static Drawable background(Context context, Drawable content, float radius) {
        int accent = context.getResources().getColor(R.color.hermit_accent);
        // About 35% of the accent, over the button
        int ripple = (accent & 0x00FFFFFF) | 0x5A000000;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }
}
