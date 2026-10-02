package com.junopark.hermit.hermit;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.junopark.hermit.R;

/**
 * Pinch zoom of the stream picture on the device (like Parsec or Chrome Remote Desktop on
 * mobile). The host's picture and resolution do not change: the stream view is scaled and moved,
 * and touches are mapped back to stream coordinates.
 *
 * Two fingers become a pinch only once the distance between them changes clearly, so two-finger
 * taps and scrolling keep working at normal size. While zoomed in, dragging with two fingers
 * moves the picture. A tag at the bottom centre shows the zoom and returns to normal size when tapped.
 * Needs Android 7.0+, where a SurfaceView follows view scaling.
 */
public class ZoomController {
    /** Callbacks to the stream activity. */
    public interface Listener {
        /** Two fingers started a pinch: cancel whatever they started on the host. */
        void onZoomGestureStarted();
    }

    private static final float MAX_SCALE = 5f;

    private enum State { IDLE, CANDIDATE, ACTIVE }

    private final Activity activity;
    private final View target;
    private final Listener listener;
    private final TextView tag;
    private final float density;
    private boolean enabled = true;
    private boolean bottomTaken;  // the text input bar is at the bottom (Android 11+)
    private Runnable onTagShown;

    private float scale = 1f, translateX, translateY;
    private State state = State.IDLE;
    private float startSpan, startFocusX, startFocusY;
    private float lastSpan, lastFocusX, lastFocusY;

    // The tag's transparent inset, and its margin so the visible part stays 12dp off the edge
    private static final int TAG_INSET_DP = 11;
    private static final int TAG_MARGIN_DP = 12 - TAG_INSET_DP;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public ZoomController(Activity activity, View target, Listener listener) {
        this.activity = activity;
        this.target = target;
        this.listener = listener;
        this.density = activity.getResources().getDisplayMetrics().density;

        tag = new TextView(activity);
        tag.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // A 48dp tall touch target; the visible tag stays about 26dp (transparent inset above
        // and below it)
        tag.setBackground(new InsetDrawable(activity.getDrawable(R.drawable.hermit_tag_background),
                0, dp(TAG_INSET_DP), 0, dp(TAG_INSET_DP)));
        tag.setMinHeight(dp(48));
        tag.setMinWidth(dp(64));
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(dp(10), 0, dp(10), 0);
        tag.setAlpha(0.8f);
        tag.setVisibility(View.GONE);
        tag.setOnClickListener(v -> reset());
        // Bottom centre: the performance overlay is at the top left, connection warnings at the
        // top right and the stream panel handle at the side
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(TAG_MARGIN_DP);
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(tag, lp);
        // Above the keyboard and the navigation bar when they cover the bottom (Android 11+; before
        // that a fullscreen window gets no keyboard insets and stable bar insets, so it stays put)
        tag.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return insets;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
            // While the text input bar sits at the bottom (on the keyboard, or alone after Back hid
            // the keyboard) the tag goes to the top, below a camera cutout there
            boolean onTop = bottomTaken || insets.isVisible(WindowInsets.Type.ime());
            int gravity = (onTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
            int bottom = onTop ? 0 : dp(TAG_MARGIN_DP) + insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int top = onTop ? dp(TAG_MARGIN_DP) + insets.getInsets(WindowInsets.Type.displayCutout()).top : 0;
            if (params.gravity != gravity || params.bottomMargin != bottom || params.topMargin != top) {
                params.gravity = gravity;
                params.bottomMargin = bottom;
                params.topMargin = top;
                v.setLayoutParams(params);
            }
            return insets;
        });

        // A new layout (rotation, resize, new resolution) starts at normal size
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                reset();
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** The text input bar takes the bottom of the screen (or leaves it): the tag moves to the top. */
    public void setBottomTaken(boolean taken) {
        if (bottomTaken != taken) {
            bottomTaken = taken;
            tag.requestApplyInsets();
        }
    }

    /** Puts the tag back above layers added after it (the on-screen controller's buttons). */
    public void raiseTag() {
        if (tag.getVisibility() == View.VISIBLE) {
            tag.bringToFront();
        }
    }

    /** Called after the tag came on top of the other layers, to put the stream panel back above it. */
    public void setOnTagShown(Runnable onTagShown) {
        this.onTagShown = onTagShown;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled && isSupported();
        if (!this.enabled) {
            state = State.IDLE;
            reset();
        }
    }

    public boolean isZoomed() {
        return scale > 1.001f;
    }

    /** Back to normal size. */
    public void reset() {
        scale = 1f;
        translateX = 0;
        translateY = 0;
        apply();
    }

    /** Hides the zoom tag (picture-in-picture); the zoom itself is reset. */
    public void setTagVisible(boolean visible) {
        if (!visible) {
            reset();
        }
        tag.setVisibility(visible && isZoomed() ? View.VISIBLE : View.GONE);
    }

    private static float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    /**
     * Looks at a touch event first. Returns true if it belongs to a pinch or a zoomed-in pan
     * and must not reach the host.
     */
    public boolean onTouchEvent(MotionEvent e) {
        if (!enabled) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state = State.IDLE;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (state == State.ACTIVE) {
                    if (e.getPointerCount() >= 2) {
                        // A finger came back (and may now be one of the first two): measure from
                        // the new pair, or the picture jumps
                        lastSpan = span(e);
                        lastFocusX = (e.getX(0) + e.getX(1)) / 2;
                        lastFocusY = (e.getY(0) + e.getY(1)) / 2;
                    }
                    return true;
                }
                if (e.getPointerCount() == 2) {
                    state = State.CANDIDATE;
                    startSpan = lastSpan = span(e);
                    startFocusX = lastFocusX = (e.getX(0) + e.getX(1)) / 2;
                    startFocusY = lastFocusY = (e.getY(0) + e.getY(1)) / 2;
                } else {
                    state = State.IDLE;  // three fingers: the keyboard gesture
                }
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() < 2 || state == State.IDLE) {
                    return state == State.ACTIVE;
                }
                float span = span(e);
                float focusX = (e.getX(0) + e.getX(1)) / 2;
                float focusY = (e.getY(0) + e.getY(1)) / 2;
                if (state == State.CANDIDATE) {
                    boolean pinch = Math.abs(span - startSpan) > Math.max(dp(28), startSpan * 0.1f);
                    // Zoomed in, two fingers moving together move the picture (instead of scrolling)
                    boolean pan = isZoomed() && Math.hypot(focusX - startFocusX, focusY - startFocusY) > dp(18);
                    if (!pinch && !pan) {
                        return false;
                    }
                    state = State.ACTIVE;
                    listener.onZoomGestureStarted();
                    lastSpan = span;
                    lastFocusX = focusX;
                    lastFocusY = focusY;
                    return true;
                }
                // Active: zoom around the fingers' midpoint and follow its movement
                float newScale = lastSpan > 0 ? Math.max(1f, Math.min(MAX_SCALE, scale * span / lastSpan)) : scale;
                float fx = lastFocusX - target.getLeft();
                float fy = lastFocusY - target.getTop();
                translateX = fx - (fx - translateX) * (newScale / scale) + (focusX - lastFocusX);
                translateY = fy - (fy - translateY) * (newScale / scale) + (focusY - lastFocusY);
                scale = newScale;
                lastSpan = span;
                lastFocusX = focusX;
                lastFocusY = focusY;
                apply();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (state == State.ACTIVE) {
                    // Continue with the remaining two fingers, if any
                    if (e.getPointerCount() > 2) {
                        int up = e.getActionIndex();
                        int a = up == 0 ? 1 : 0, b = up <= 1 ? 2 : 1;
                        lastSpan = (float) Math.hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b));
                        lastFocusX = (e.getX(a) + e.getX(b)) / 2;
                        lastFocusY = (e.getY(a) + e.getY(b)) / 2;
                    }
                    return true;
                }
                state = State.IDLE;
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = state == State.ACTIVE;
                state = State.IDLE;
                if (scale < 1.05f) {
                    reset();  // snap back to exactly normal size
                }
                return consumed;
            }
            default:
                return state == State.ACTIVE;
        }
    }

    /** A copy of a touch event (parent coordinates) in the stream view's own coordinates. */
    public MotionEvent toStreamCoordinates(MotionEvent e) {
        Matrix m = new Matrix();
        m.postTranslate(-(target.getLeft() + translateX), -(target.getTop() + translateY));
        m.postScale(1f / scale, 1f / scale);
        MotionEvent copy = MotionEvent.obtain(e);
        copy.transform(m);
        return copy;
    }

    private void apply() {
        int w = target.getWidth(), h = target.getHeight();
        // Keep the picture covering its normal area: no empty edges
        translateX = Math.max(w - w * scale, Math.min(0, translateX));
        translateY = Math.max(h - h * scale, Math.min(0, translateY));
        target.setPivotX(0);
        target.setPivotY(0);
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setTranslationX(translateX);
        target.setTranslationY(translateY);

        if (isZoomed()) {
            tag.setText(activity.getString(R.string.hermit_zoom_tag, Math.round(scale * 100)));
            if (tag.getVisibility() != View.VISIBLE) {
                tag.setVisibility(View.VISIBLE);
                // Above the on-screen controller and the keypad, so a tap on it never presses a
                // button on the host (the panel goes back on top)
                tag.bringToFront();
                if (onTagShown != null) {
                    onTagShown.run();
                }
            }
        } else {
            tag.setVisibility(View.GONE);
        }
    }
}
