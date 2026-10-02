package com.junopark.hermit.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;

import com.junopark.hermit.hermit.HermitPreferences;
import com.junopark.hermit.nvstream.NvConnection;
import com.junopark.hermit.nvstream.input.MouseButtonPacket;

/**
 * Direct touch (the touchscreen is the host's screen), as in Chrome Remote Desktop:
 * <ul>
 * <li>tap: left click where the finger is (a second tap close by soon after makes a double click)</li>
 * <li>drag: scrolls what is under the finger, up and down (sideways when the drag starts clearly
 *     sideways), and keeps scrolling for a moment after a flick</li>
 * <li>long press, then drag: holds the left button (selecting text, moving windows) until the
 *     finger lifts</li>
 * <li>two-finger tap: right click where the first finger touched; two-finger drag scrolls</li>
 * </ul>
 * Hermit: upstream held the left button on any drag (so dragging selected text instead of
 * scrolling) and made a long press a right click.
 */
public class AbsoluteTouchContext implements TouchContext {
    /** What the fingers of one gesture share: two-finger tap, two-finger scroll. */
    public static class Gesture {
        long downTime;
        int downX, downY;
        int maxPointers;
        boolean moved;
        // Cancelled from outside (Android took the gesture, a pinch, three fingers)
        boolean aborted;
        // Where the first finger is now (it moves on after a second finger stopped its action)
        int firstX, firstY;
        /** False while the picture is zoomed in: two fingers then move the picture on this device. */
        public boolean twoFingerScroll = true;
    }

    private enum Mode { NONE, PENDING, SCROLL, DRAG }

    private static final int SCROLL_SPEED_FACTOR = 3;
    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int TWO_FINGER_TAP_TIME_THRESHOLD = 350;
    private static final int CLICK_RELEASE_DELAY = 100;
    // Velocity is measured over the last part of the drag; a pause before lifting is no flick
    private static final int FLING_SAMPLE_MS = 100;
    private static final int FLING_FRAME_MS = 16;
    private static final float FLING_TIME_CONSTANT_MS = 325f;

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Gesture gesture;
    private final Handler handler;
    private final int slop;
    private final float minFlingVelocity;  // px per ms

    private Mode mode = Mode.NONE;
    private boolean cancelled;
    private boolean fingerDown;
    private boolean clickDown;  // a tap's left button waits for its delayed release
    private boolean stoppedFling;  // this touch stopped a fling: it only stops it, no click
    private int pointers;  // fingers down, from setPointerCount()
    private float startSpan;  // second finger: distance to the first one when it landed
    private int downX, downY;
    private long downTime;
    private int lastX, lastY;
    private int lastTapX, lastTapY;
    private long lastTapTime;
    private boolean horizontal;
    private float scrollRemainder;

    // Recent positions for the flick velocity
    private final long[] sampleTime = new long[8];
    private final int[] sampleX = new int[8], sampleY = new int[8];
    private int sampleCount, sampleNext;

    private float flingVelocity;  // px per ms, along the scroll axis
    private long flingLastTime;

    private final Runnable startDrag = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.PENDING || cancelled) {
                return;
            }
            mode = Mode.DRAG;
            updatePosition(downX, downY);
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            targetView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    private final Runnable leftButtonUp = new Runnable() {
        @Override
        public void run() {
            clickDown = false;
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
    };

    private final Runnable rightButtonUp = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        }
    };

    private final Runnable flingFrame = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.max(1, now - flingLastTime);
            flingLastTime = now;
            scroll(flingVelocity * elapsed);
            flingVelocity *= (float) Math.exp(-elapsed / FLING_TIME_CONSTANT_MS);
            if (Math.abs(flingVelocity) >= minFlingVelocity / 4) {
                handler.postDelayed(this, FLING_FRAME_MS);
            } else {
                flingVelocity = 0;
            }
        }
    };

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, Gesture gesture)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.gesture = gesture;
        this.handler = new Handler(Looper.getMainLooper());
        ViewConfiguration vc = ViewConfiguration.get(view.getContext());
        this.slop = vc.getScaledTouchSlop();
        this.minFlingVelocity = vc.getScaledMinimumFlingVelocity() / 1000f;
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true;
        }

        // A touch whose end never came (taken by another gesture) must not leave anything held
        handler.removeCallbacks(startDrag);
        if (mode == Mode.DRAG) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
        // Touching a list that still scrolls from a flick only stops it (as on the phone)
        stoppedFling = flingVelocity != 0;
        stopFling();
        lastX = downX = eventX;
        lastY = downY = eventY;
        downTime = eventTime;
        cancelled = false;
        fingerDown = true;
        scrollRemainder = 0;
        sampleCount = 0;
        addSample(eventX, eventY, eventTime);

        if (actionIndex == 0) {
            if (pointers > 1) {
                // A finger landing again while another one is down (lifted and put back): it
                // joins that gesture and starts nothing on its own
                cancelled = true;
                mode = Mode.NONE;
                return true;
            }
            // The last tap's button goes up before the cursor moves, or the host sees a drag
            releaseClick();
            gesture.downTime = eventTime;
            gesture.downX = gesture.firstX = eventX;
            gesture.downY = gesture.firstY = eventY;
            gesture.maxPointers = 1;
            gesture.moved = false;
            gesture.aborted = false;
            handler.postDelayed(startDrag, ViewConfiguration.getLongPressTimeout());
        } else {
            startSpan = (float) Math.hypot(eventX - gesture.firstX, eventY - gesture.firstY);
        }
        mode = Mode.PENDING;

        return true;
    }

    private boolean beyondSlop(int x, int y) {
        return Math.hypot(x - downX, y - downY) > slop;
    }

    private void updatePosition(int eventX, int eventY) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), targetView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), targetView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)targetView.getWidth(), (short)targetView.getHeight());
    }

    // Scrolls by a finger movement along the scroll axis: the content follows the finger
    private void scroll(float delta) {
        scrollRemainder += delta * SCROLL_SPEED_FACTOR * HermitPreferences.scrollScale;
        int amount = (int) scrollRemainder;
        if (amount == 0) {
            return;
        }
        amount = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amount));
        scrollRemainder -= amount;
        if (horizontal) {
            // A finger moving right shows what is on the left
            conn.sendMouseHighResHScroll((short) -amount);
        } else {
            conn.sendMouseHighResScroll((short) amount);
        }
    }

    private void addSample(int x, int y, long time) {
        sampleTime[sampleNext] = time;
        sampleX[sampleNext] = x;
        sampleY[sampleNext] = y;
        sampleNext = (sampleNext + 1) % sampleTime.length;
        sampleCount = Math.min(sampleCount + 1, sampleTime.length);
    }

    // px per ms along the scroll axis over the last FLING_SAMPLE_MS, 0 if the finger paused
    private float releaseVelocity(long upTime) {
        int newest = (sampleNext + sampleTime.length - 1) % sampleTime.length;
        if (sampleCount < 2 || upTime - sampleTime[newest] > FLING_SAMPLE_MS / 2) {
            return 0;
        }
        int oldest = newest;
        for (int i = 1; i < sampleCount; i++) {
            int index = (newest - i + sampleTime.length) % sampleTime.length;
            if (upTime - sampleTime[index] > FLING_SAMPLE_MS) {
                break;
            }
            oldest = index;
        }
        long dt = sampleTime[newest] - sampleTime[oldest];
        if (dt < 10) {
            return 0;
        }
        int distance = horizontal ? sampleX[newest] - sampleX[oldest] : sampleY[newest] - sampleY[oldest];
        return (float) distance / dt;
    }

    private void stopFling() {
        handler.removeCallbacks(flingFrame);
        flingVelocity = 0;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        handler.removeCallbacks(startDrag);
        fingerDown = false;
        if (cancelled) {
            mode = Mode.NONE;
            return;
        }

        if (mode == Mode.PENDING && actionIndex == 0 && !stoppedFling) {
            click(eventTime);
        } else if (mode == Mode.DRAG) {
            updatePosition(eventX, eventY);
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        } else if (mode == Mode.SCROLL && actionIndex == 0) {
            float velocity = releaseVelocity(eventTime);
            if (Math.abs(velocity) >= minFlingVelocity) {
                flingVelocity = velocity;
                flingLastTime = SystemClock.uptimeMillis();
                handler.postDelayed(flingFrame, FLING_FRAME_MS);
            }
        }
        mode = Mode.NONE;
    }

    // A tap: a left click where the finger touched, or at the last tap for a double click
    private void click(long eventTime) {
        boolean doubleTap = eventTime - lastTapTime <= DOUBLE_TAP_TIME_THRESHOLD + CLICK_RELEASE_DELAY &&
                Math.hypot(downX - lastTapX, downY - lastTapY) <= slop * 3;
        if (!doubleTap) {
            // Don't reposition for a second tap close by. This makes double-clicking easier.
            updatePosition(downX, downY);
            lastTapX = downX;
            lastTapY = downY;
        }
        lastTapTime = eventTime;
        releaseClick();
        clickDown = true;
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        // Release the left mouse button in 100ms to allow for apps that use polling
        // to detect mouse button presses.
        handler.postDelayed(leftButtonUp, CLICK_RELEASE_DELAY);
    }

    // The last tap's left button, if its delayed release has not come yet
    private void releaseClick() {
        if (clickDown) {
            handler.removeCallbacks(leftButtonUp);
            clickDown = false;
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (actionIndex == 0 && !fingerDown && pointers == 1 && gesture.maxPointers > 1) {
            // The first finger lifted and the other one now comes here: follow it from where it
            // is, so a two-finger swipe whose first finger lifted early is not a right click
            fingerDown = true;
            downX = eventX;
            downY = eventY;
        }
        if (fingerDown && beyondSlop(eventX, eventY)) {
            gesture.moved = true;
        }
        if (actionIndex == 0) {
            gesture.firstX = eventX;
            gesture.firstY = eventY;
        }
        if (cancelled || mode == Mode.NONE) {
            return true;
        }

        if (mode == Mode.PENDING) {
            if (!beyondSlop(eventX, eventY)) {
                return true;
            }
            if (actionIndex == 0) {
                // A drag before the long press: scroll what is under the finger
                handler.removeCallbacks(startDrag);
                horizontal = Math.abs(eventX - downX) > 2 * Math.abs(eventY - downY);
                updatePosition(downX, downY);
            } else {
                // The second finger of a two-finger scroll (the first one stopped at its touch).
                // Not while zoomed in, and only for fingers moving together: fingers moving
                // apart or closer are a pinch (zoom), which would otherwise scroll the host
                // until it is recognised
                float span = (float) Math.hypot(eventX - gesture.firstX, eventY - gesture.firstY);
                double moved = Math.hypot(eventX - downX, eventY - downY);
                if (!gesture.twoFingerScroll || Math.abs(span - startSpan) > moved / 2) {
                    return true;
                }
                horizontal = false;
                // The wheel goes to the window under the cursor: between the two fingers
                updatePosition((gesture.firstX + eventX) / 2, (gesture.firstY + eventY) / 2);
            }
            mode = Mode.SCROLL;
            lastX = downX;
            lastY = downY;
        }

        if (mode == Mode.SCROLL) {
            if (actionIndex == 0 || gesture.twoFingerScroll) {
                scroll(horizontal ? eventX - lastX : eventY - lastY);
            }
            addSample(eventX, eventY, eventTime);
        } else if (mode == Mode.DRAG) {
            updatePosition(eventX, eventY);
        }

        lastX = eventX;
        lastY = eventY;

        return true;
    }

    // Ends what this finger started on the host
    private void release() {
        cancelled = true;
        handler.removeCallbacks(startDrag);
        stopFling();
        if (mode == Mode.DRAG) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
        mode = Mode.NONE;
    }

    @Override
    public void cancelTouch() {
        gesture.aborted = true;
        release();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        int before = pointers;
        pointers = pointerCount;
        if (actionIndex != 0) {
            if (pointerCount < 2 && before >= 2) {
                // Fewer than two fingers: this index's finger lifted, or it is now the first one
                // (contexts go by index); a finger landing here later starts afresh
                fingerDown = false;
                cancelled = true;
                mode = Mode.NONE;
            }
            return;
        }
        if (pointerCount > gesture.maxPointers) {
            gesture.maxPointers = pointerCount;
        }
        if (pointerCount > 1) {
            // A second finger: the first one no longer clicks, scrolls or drags
            release();
        } else if (pointerCount == 0) {
            // All fingers up: two fingers that touched briefly without moving are a right click
            if (gesture.maxPointers == 2 && !gesture.moved && !gesture.aborted &&
                    SystemClock.uptimeMillis() - gesture.downTime <= TWO_FINGER_TAP_TIME_THRESHOLD) {
                updatePosition(gesture.downX, gesture.downY);
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                handler.removeCallbacks(rightButtonUp);
                handler.postDelayed(rightButtonUp, CLICK_RELEASE_DELAY);
            }
            gesture.maxPointers = 0;
        }
    }
}
