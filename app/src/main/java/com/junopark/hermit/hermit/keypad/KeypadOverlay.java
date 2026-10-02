package com.junopark.hermit.hermit.keypad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.junopark.hermit.R;

/**
 * The virtual keypad drawn over the stream: a stick (fixed or floating joystick, or a D-pad) on
 * the left and a staggered grid of keys on the right, both sending keyboard keys (or mouse
 * buttons) through KeySender, and a quick menu button. Touches outside the controls fall through
 * to the stream.
 *
 * In edit mode the controls can be dragged to new places, snapping to a grid that is drawn
 * over the stream; no keys are sent then. While editing, preview() shows new settings at once.
 */
public class KeypadOverlay {
    /** Called when the user moved a group in edit mode (the config is already updated). */
    public interface Listener {
        void onPositionsChanged(KeypadConfig config);
    }

    /** The quick menu button was tapped. */
    public interface QuickMenuListener {
        void onQuickMenu(View button);
    }

    // The quick menu button: a 44dp circle, 8dp from the key grid when it follows it, and then
    // at least 48dp from the side edges (the stream panel handle and keyboard button are there)
    private static final int MENU_SIZE_DP = 44, MENU_GAP_DP = 8, MENU_EDGE_DP = 48;

    private static final int DIR_UP = 1, DIR_DOWN = 2, DIR_LEFT = 4, DIR_RIGHT = 8;

    private final Activity activity;
    private final FrameLayout container;   // the keypad's own layer (stays below the stream panel)
    private final KeySender sender;
    private final Listener listener;
    private final float density;

    private KeypadConfig config;
    private StickView stickView;
    private KeyGridView gridView;
    private MenuButtonView menuView;
    private QuickMenuListener quickMenuListener;
    private TextView doneBar;
    private View gridLines;
    private boolean visible = true;
    private boolean editMode;
    private Runnable onEditDone;

    private final int colorFill, colorStroke, colorText, colorActive;
    private final android.os.Vibrator vibrator;

    public KeypadOverlay(Activity activity, KeySender sender, Listener listener) {
        this.activity = activity;
        // Our own full-screen layer, added once: rebuilding the controls inside it keeps them
        // below the views added after it (the stream panel). Empty space passes touches through.
        FrameLayout content = activity.findViewById(android.R.id.content);
        this.container = new FrameLayout(activity);
        content.addView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        this.sender = sender;
        this.listener = listener;
        this.density = activity.getResources().getDisplayMetrics().density;
        colorFill = activity.getResources().getColor(R.color.hermit_layer1);
        colorStroke = activity.getResources().getColor(R.color.hermit_text_secondary);
        colorText = activity.getResources().getColor(R.color.hermit_text_primary);
        colorActive = activity.getResources().getColor(R.color.hermit_accent);
        vibrator = (android.os.Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);

        // Follow rotation and window size changes
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                layoutViews();
            }
        });
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** Applies new settings (also turns the keypad on or off). */
    public void apply(KeypadConfig newConfig) {
        sender.releaseAll();
        config = newConfig.copy();
        removeViews();
        if (!config.enabled) {
            return;
        }
        stickView = new StickView(activity);
        gridView = new KeyGridView(activity);
        container.addView(stickView, new FrameLayout.LayoutParams(1, 1));
        container.addView(gridView, new FrameLayout.LayoutParams(1, 1));
        syncMenuView();
        layoutViews();
        updateVisibility();
    }

    public void setQuickMenuListener(QuickMenuListener listener) {
        this.quickMenuListener = listener;
    }

    // The quick menu button exists while the settings ask for it
    private void syncMenuView() {
        if (config.quickMenu && menuView == null) {
            menuView = new MenuButtonView(activity);
            container.addView(menuView, new FrameLayout.LayoutParams(1, 1));
        } else if (!config.quickMenu && menuView != null) {
            container.removeView(menuView);
            menuView = null;
        }
    }

    private void removeViews() {
        if (editMode) {
            editMode = false;
            onEditDone = null;
            if (doneBar != null && doneBar.getParent() != null) {
                container.removeView(doneBar);
            }
            showGridLines(false);
        }
        if (stickView != null) {
            container.removeView(stickView);
            stickView = null;
        }
        if (gridView != null) {
            container.removeView(gridView);
            gridView = null;
        }
        if (menuView != null) {
            container.removeView(menuView);
            menuView = null;
        }
    }

    /** Hides the keypad for a while (picture-in-picture), keeping its settings. */
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            sender.releaseAll();
        }
        updateVisibility();
    }

    private void updateVisibility() {
        int v = visible ? View.VISIBLE : View.GONE;
        if (stickView != null) stickView.setVisibility(v);
        if (gridView != null) gridView.setVisibility(v);
        if (menuView != null) menuView.setVisibility(v);
        if (doneBar != null) doneBar.setVisibility(v);
    }

    public boolean isEnabled() {
        return config != null && config.enabled;
    }

    /** Drag mode for placing the stick and the key grid; onDone runs when the user taps Done. */
    public void startEditMode(Runnable onDone) {
        startEditMode(onDone, true);
    }

    /** Edit mode; without the Done bar when an editor panel ends it (endEditMode()). */
    @SuppressLint("SetTextI18n")
    public void startEditMode(Runnable onDone, boolean showDoneBar) {
        if (stickView == null || editMode) {
            return;
        }
        sender.releaseAll();
        editMode = true;
        onEditDone = onDone;
        showGridLines(true);
        if (!showDoneBar) {
            invalidateControls();
            return;
        }
        if (doneBar == null) {
            doneBar = new TextView(activity);
            doneBar.setText(activity.getString(R.string.hermit_keypad_edit_done));
            doneBar.setTextColor(colorText);
            doneBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            doneBar.setBackgroundColor(activity.getResources().getColor(R.color.hermit_accent_fill));
            doneBar.setPadding(dp(18), dp(10), dp(18), dp(10));
            doneBar.setGravity(Gravity.CENTER);
            doneBar.setOnClickListener(v -> finishEditMode());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(40);
        if (doneBar.getParent() == null) {
            container.addView(doneBar, lp);
        }
        invalidateControls();
    }

    private void invalidateControls() {
        if (stickView != null) stickView.invalidate();
        if (gridView != null) gridView.invalidate();
        if (menuView != null) menuView.invalidate();
    }

    /** Ends edit mode (the editor panel's Done); the positions go to the listener. */
    public void endEditMode() {
        if (editMode) {
            finishEditMode();
        }
    }

    public boolean isEditing() {
        return editMode;
    }

    /** The settings shown now, including positions dragged in edit mode (null when off). */
    public KeypadConfig currentConfig() {
        return config != null ? config.copy() : null;
    }

    /** Where the key grid is on the screen (null when the keypad is off). */
    public android.graphics.Rect keyGridBounds() {
        return bounds(gridView);
    }

    /** Where the fixed stick or D-pad is (null when off or floating: that one has no fixed place). */
    public android.graphics.Rect stickBounds() {
        return config != null && KeypadConfig.STICK_FLOATING.equals(config.stickMode) ? null : bounds(stickView);
    }

    private static android.graphics.Rect bounds(View view) {
        if (view == null) {
            return null;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        return new android.graphics.Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height);
    }

    /** Shows new settings while editing, staying in edit mode (live preview). */
    public void preview(KeypadConfig newConfig) {
        if (!editMode || stickView == null || !newConfig.enabled) {
            apply(newConfig);
            return;
        }
        config = newConfig.copy();
        syncMenuView();
        layoutViews();
        invalidateControls();
    }

    private void showGridLines(boolean show) {
        if (show && gridLines == null) {
            gridLines = new GridLinesView(activity);
            // Below the controls, so they get the touches first
            container.addView(gridLines, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else if (!show && gridLines != null) {
            container.removeView(gridLines);
            gridLines = null;
        }
    }

    private void finishEditMode() {
        editMode = false;
        showGridLines(false);
        if (doneBar != null && doneBar.getParent() != null) {
            container.removeView(doneBar);
        }
        invalidateControls();
        listener.onPositionsChanged(config.copy());
        if (onEditDone != null) {
            onEditDone.run();
            onEditDone = null;
        }
    }

    public void release() {
        sender.releaseAll();
    }

    // A short tick for a press, if turned on
    private void tick() {
        if (config == null || !config.haptics || vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, 120));
        } else {
            vibrator.vibrate(12);
        }
    }

    // ---- Layout -------------------------------------------------------------------------

    private int stickPx() {
        return dp(config.stickSizeDp);
    }

    // Below 1 when the grid would not fit on the screen
    private float gridScale = 1f;

    private float keySizePx() {
        return dp(config.keySizeDp) * gridScale;
    }

    private float keyStepPx() {
        return (dp(config.keySizeDp) + dp(config.spacingDp)) * gridScale;
    }

    private int gridWidthPx() {
        return Math.round((config.columns - 1) * keyStepPx() + keySizePx() +
                (config.rows - 1) * config.stagger * keyStepPx());
    }

    private int gridHeightPx() {
        return Math.round((config.rows - 1) * keyStepPx() + keySizePx());
    }

    private void layoutViews() {
        if (stickView == null || gridView == null) {
            return;
        }
        int w = container.getWidth(), h = container.getHeight();
        if (w == 0 || h == 0) {
            container.post(this::layoutViews);
            return;
        }

        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) stickView.getLayoutParams();
        if (KeypadConfig.STICK_FLOATING.equals(config.stickMode)) {
            // The whole left part of the screen; the base appears where the thumb lands
            slp.width = Math.round(w * 0.45f);
            slp.height = h - dp(40);
            slp.leftMargin = 0;
            slp.topMargin = dp(40);
        } else {
            int size = stickPx();
            slp.width = size;
            slp.height = size;
            slp.leftMargin = clamp(Math.round(config.stickX * w) - size / 2, 0, w - size);
            slp.topMargin = clamp(Math.round(config.stickY * h) - size / 2, 0, h - size);
        }
        slp.gravity = Gravity.TOP | Gravity.START;
        stickView.setLayoutParams(slp);

        FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) gridView.getLayoutParams();
        gridScale = 1f;
        float maxWidth = w * 0.9f, maxHeight = h * 0.9f;
        if (gridWidthPx() > maxWidth || gridHeightPx() > maxHeight) {
            gridScale = Math.min(maxWidth / gridWidthPx(), maxHeight / gridHeightPx());
        }
        glp.width = gridWidthPx();
        glp.height = gridHeightPx();
        glp.leftMargin = clamp(Math.round(config.gridRight * w) - glp.width, 0, Math.max(0, w - glp.width));
        glp.topMargin = clamp(Math.round(config.gridBottom * h) - glp.height, 0, Math.max(0, h - glp.height));
        glp.gravity = Gravity.TOP | Gravity.START;
        gridView.setLayoutParams(glp);

        if (menuView != null) {
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) menuView.getLayoutParams();
            int size = dp(MENU_SIZE_DP);
            mlp.width = size;
            mlp.height = size;
            if (config.menuX >= 0 && config.menuY >= 0) {
                mlp.leftMargin = clamp(Math.round(config.menuX * w) - size / 2, 0, w - size);
                mlp.topMargin = clamp(Math.round(config.menuY * h) - size / 2, 0, h - size);
            } else if (glp.topMargin >= size + dp(MENU_GAP_DP)) {
                // Above the key grid's right end
                mlp.leftMargin = clamp(glp.leftMargin + glp.width - size, dp(MENU_EDGE_DP), w - size - dp(MENU_EDGE_DP));
                mlp.topMargin = glp.topMargin - size - dp(MENU_GAP_DP);
            } else {
                // No room above: left of the grid's top row
                mlp.leftMargin = clamp(glp.leftMargin - size - dp(MENU_GAP_DP), dp(MENU_EDGE_DP), w - size - dp(MENU_EDGE_DP));
                mlp.topMargin = glp.topMargin;
            }
            mlp.gravity = Gravity.TOP | Gravity.START;
            menuView.setLayoutParams(mlp);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // Edit mode snaps to a grid of this many dp (drawn over the stream while editing)
    private static final int SNAP_DP = 16;

    /**
     * Snaps a group's left (or top) edge to the grid: either its near edge on a grid line, or its
     * far edge the same whole number of grid steps from the screen's far edge, whichever is closer.
     */
    private int snap(int pos, int size, int total) {
        int g = dp(SNAP_DP);
        int near = Math.round(pos / (float) g) * g;
        int far = total - size - Math.round((total - size - pos) / (float) g) * g;
        int best = Math.abs(near - pos) <= Math.abs(far - pos) ? near : far;
        return clamp(best, 0, Math.max(0, total - size));
    }

    private int snapPoint(float pos) {
        int g = dp(SNAP_DP);
        return Math.round(pos / g) * g;
    }

    // Faint lines every grid step (stronger every fourth) while editing
    private class GridLinesView extends View {
        private final Paint paint = new Paint();

        GridLinesView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int g = dp(SNAP_DP);
            for (int i = 0, x = 0; x < getWidth(); i++, x += g) {
                paint.setColor(i % 4 == 0 ? 0x40FFFFFF : 0x1FFFFFFF);
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int i = 0, y = 0; y < getHeight(); i++, y += g) {
                paint.setColor(i % 4 == 0 ? 0x40FFFFFF : 0x1FFFFFFF);
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
        }
    }

    // Drag in edit mode: moves a view and stores its new place in the config
    private abstract class DraggableView extends View {
        private float downRawX, downRawY;
        private int startLeft, startTop;
        private int dragPointer = -1;
        // The finger went past the touch slop: a drag, not a tap (a tap must not snap the view
        // to the grid, which would pin the quick menu button where it is)
        private boolean dragged;

        DraggableView(Context context) {
            super(context);
        }

        boolean handleEditTouch(MotionEvent e) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragPointer = e.getPointerId(0);
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragged && Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) <=
                            android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        return true;
                    }
                    dragged = true;
                    if (dragPointer >= 0) {
                        lp.leftMargin = snap(startLeft + Math.round(e.getRawX() - downRawX), lp.width, container.getWidth());
                        lp.topMargin = snap(startTop + Math.round(e.getRawY() - downRawY), lp.height, container.getHeight());
                        setLayoutParams(lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragPointer = -1;
                    if (dragged) {
                        storePosition(lp);
                    }
                    dragged = false;
                    return true;
                default:
                    return true;
            }
        }

        abstract void storePosition(FrameLayout.LayoutParams lp);

        void drawEditOutline(Canvas canvas, Paint paint) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(colorActive);
            paint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            canvas.drawRect(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1), paint);
            paint.setPathEffect(null);
        }
    }

    // ---- Stick ------------------------------------------------------------------------------

    private class StickView extends DraggableView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pointerId = -1;
        private float baseX, baseY, knobX, knobY;
        private float drawBaseX, drawBaseY;   // base as drawn (kept inside the view)
        private int directions;

        StickView(Context context) {
            super(context);
        }

        private boolean floating() {
            return KeypadConfig.STICK_FLOATING.equals(config.stickMode);
        }

        private boolean dpad() {
            return KeypadConfig.STICK_DPAD.equals(config.stickMode);
        }

        private float radius() {
            return stickPx() / 2f;
        }

        // Where the idle stick is drawn, in view coordinates
        private float restX() {
            if (!floating()) return getWidth() / 2f;
            float r = radius();
            return Math.max(r, Math.min(getWidth() - r, config.stickX * container.getWidth() - getLeft()));
        }

        private float restY() {
            if (!floating()) return getHeight() / 2f;
            float r = radius();
            return Math.max(r, Math.min(getHeight() - r, config.stickY * container.getHeight() - getTop()));
        }

        @Override
        void storePosition(FrameLayout.LayoutParams lp) {
            config.stickX = (lp.leftMargin + lp.width / 2f) / container.getWidth();
            config.stickY = (lp.topMargin + lp.height / 2f) / container.getHeight();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (editMode) {
                // The floating stick has no fixed place to drag; its idle position follows taps
                if (floating()) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        config.stickX = (float) snapPoint(e.getX() + getLeft()) / container.getWidth();
                        config.stickY = (float) snapPoint(e.getY() + getTop()) / container.getHeight();
                        invalidate();
                    }
                    return true;
                }
                return handleEditTouch(e);
            }

            int action = e.getActionMasked();
            int index = e.getActionIndex();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (action == MotionEvent.ACTION_DOWN && pointerId != -1) {
                        // A new gesture: a lost UP must not leave the stick stuck
                        pointerId = -1;
                        setDirections(0);
                    }
                    if (pointerId != -1) {
                        return true;
                    }
                    float x = e.getX(index), y = e.getY(index);
                    if (floating()) {
                        // Directions are measured from where the thumb landed (no instant press
                        // near an edge); only the drawing is kept inside the area
                        float r = radius();
                        baseX = x;
                        baseY = y;
                        drawBaseX = Math.max(r, Math.min(getWidth() - r, x));
                        drawBaseY = Math.max(r, Math.min(getHeight() - r, y));
                    } else {
                        baseX = getWidth() / 2f;
                        baseY = getHeight() / 2f;
                        drawBaseX = baseX;
                        drawBaseY = baseY;
                        // Outside the round base: let the touch reach the stream
                        if (action == MotionEvent.ACTION_DOWN && Math.hypot(x - baseX, y - baseY) > radius() * 1.1f) {
                            return false;
                        }
                    }
                    pointerId = e.getPointerId(index);
                    track(x, y);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int i = e.findPointerIndex(pointerId);
                    if (i >= 0) {
                        track(e.getX(i), e.getY(i));
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerId(index) != pointerId) {
                        return true;
                    }
                    // fall through
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pointerId = -1;
                    setDirections(0);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void track(float x, float y) {
            float r = radius();
            float dx = x - baseX, dy = y - baseY;
            float len = (float) Math.hypot(dx, dy);
            float limit = r * 0.75f;
            if (len > limit) {
                dx = dx / len * limit;
                dy = dy / len * limit;
            }
            knobX = drawBaseX + dx;
            knobY = drawBaseY + dy;

            int dirs = 0;
            if (len > r * 0.25f) {
                double angle = Math.toDegrees(Math.atan2(y - baseY, x - baseX)); // 0 = right, 90 = down
                int sectors = config.eightWay ? 8 : 4;
                int sector = (int) Math.round(angle / (360.0 / sectors));
                sector = ((sector % sectors) + sectors) % sectors;
                if (config.eightWay) {
                    int[] map = {DIR_RIGHT, DIR_RIGHT | DIR_DOWN, DIR_DOWN, DIR_DOWN | DIR_LEFT,
                            DIR_LEFT, DIR_LEFT | DIR_UP, DIR_UP, DIR_UP | DIR_RIGHT};
                    dirs = map[sector];
                } else {
                    int[] map = {DIR_RIGHT, DIR_DOWN, DIR_LEFT, DIR_UP};
                    dirs = map[sector];
                }
            }
            setDirections(dirs);
            invalidate();
        }

        private void setDirections(int dirs) {
            int changed = dirs ^ directions;
            if (changed == 0) {
                return;
            }
            int[] bits = {DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT};
            KeypadConfig.Binding[] bindings = {config.up, config.down, config.left, config.right};
            // Releases first, then presses, so a diagonal turn never sends opposite keys together
            for (int i = 0; i < 4; i++) {
                // (a combination with a delay drops its presses still to come)
            if ((changed & bits[i]) != 0 && (dirs & bits[i]) == 0) sender.release(bindings[i], false);
            }
            boolean pressed = false;
            for (int i = 0; i < 4; i++) {
                if ((changed & bits[i]) != 0 && (dirs & bits[i]) != 0) {
                    sender.press(bindings[i]);
                    pressed = true;
                }
            }
            directions = dirs;
            if (pressed) {
                tick();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            setDirections(0);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int alpha = Math.round(config.opacity * 255);
            float r = radius();
            boolean active = pointerId != -1;
            float cx = active ? drawBaseX : restX();
            float cy = active ? drawBaseY : restY();
            int idleAlpha = floating() && !active ? alpha / 2 : alpha;

            if (dpad()) {
                drawDpad(canvas, cx, cy, r, idleAlpha);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colorFill);
                paint.setAlpha(idleAlpha);
                canvas.drawCircle(cx, cy, r - dp(2), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(colorStroke);
                paint.setAlpha(idleAlpha);
                canvas.drawCircle(cx, cy, r - dp(2), paint);

                float kx = active ? knobX : cx, ky = active ? knobY : cy;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(active ? colorActive : colorStroke);
                paint.setAlpha(Math.min(255, idleAlpha + 40));
                canvas.drawCircle(kx, ky, r * 0.38f, paint);
            }

            if (editMode) {
                drawEditOutline(canvas, paint);
            }
        }

        private void drawDpad(Canvas canvas, float cx, float cy, float r, int alpha) {
            float arm = r * 0.36f;     // half width of an arm
            float reach = r - dp(2);
            RectF[] arms = {
                    new RectF(cx - arm, cy - reach, cx + arm, cy - arm),   // up
                    new RectF(cx - arm, cy + arm, cx + arm, cy + reach),   // down
                    new RectF(cx - reach, cy - arm, cx - arm, cy + arm),   // left
                    new RectF(cx + arm, cy - arm, cx + reach, cy + arm),   // right
            };
            int[] bits = {DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT};
            for (int i = 0; i < 4; i++) {
                boolean pressed = (directions & bits[i]) != 0;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(pressed ? colorActive : colorFill);
                paint.setAlpha(pressed ? Math.min(255, alpha + 60) : alpha);
                canvas.drawRoundRect(arms[i], dp(3), dp(3), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(colorStroke);
                paint.setAlpha(alpha);
                canvas.drawRoundRect(arms[i], dp(3), dp(3), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorFill);
            paint.setAlpha(alpha);
            canvas.drawRect(cx - arm, cy - arm, cx + arm, cy + arm, paint);
        }
    }

    // ---- Quick menu button --------------------------------------------------------------------

    private class MenuButtonView extends DraggableView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;

        MenuButtonView(Context context) {
            super(context);
            setContentDescription(context.getString(R.string.hermit_quick_menu));
        }

        @Override
        void storePosition(FrameLayout.LayoutParams lp) {
            config.menuX = (lp.leftMargin + lp.width / 2f) / container.getWidth();
            config.menuY = (lp.topMargin + lp.height / 2f) / container.getHeight();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (editMode) {
                return handleEditTouch(e);
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
                    pressed = false;
                    invalidate();
                    if (inside && quickMenuListener != null) {
                        tick();
                        quickMenuListener.onQuickMenu(this);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int alpha = Math.round(config.opacity * 255);
            float r = getWidth() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressed ? colorActive : colorFill);
            paint.setAlpha(pressed ? Math.min(255, alpha + 60) : alpha);
            canvas.drawCircle(r, r, r - dp(1), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(colorStroke);
            paint.setAlpha(alpha);
            canvas.drawCircle(r, r, r - dp(1), paint);
            // Three lines (a menu)
            paint.setColor(colorText);
            paint.setAlpha(Math.min(255, alpha + 90));
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float half = r * 0.36f;
            for (int i = -1; i <= 1; i++) {
                float y = r + i * r * 0.28f;
                canvas.drawLine(r - half, y, r + half, y, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
            if (editMode) {
                drawEditOutline(canvas, paint);
            }
        }
    }

    // ---- Key grid ---------------------------------------------------------------------------

    private class KeyGridView extends DraggableView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SparseIntArray pointerKey = new SparseIntArray();   // pointer id -> key index
        private final int[] pressCount = new int[KeypadConfig.MAX_ROWS * KeypadConfig.MAX_COLUMNS];

        KeyGridView(Context context) {
            super(context);
            textPaint.setColor(colorText);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        void storePosition(FrameLayout.LayoutParams lp) {
            config.gridRight = (float) (lp.leftMargin + lp.width) / container.getWidth();
            config.gridBottom = (float) (lp.topMargin + lp.height) / container.getHeight();
            // The quick menu button follows the grid unless it was placed on its own
            layoutViews();
        }

        private float keyLeft(int row, int column) {
            return column * keyStepPx() + row * config.stagger * keyStepPx();
        }

        private float keyTop(int row) {
            return row * keyStepPx();
        }

        /** Index (row * MAX_COLUMNS + column) of the key under a point, or -1. */
        private int keyAt(float x, float y) {
            float size = keySizePx();
            float slack = dp(config.spacingDp) * gridScale / 2f;
            for (int row = 0; row < config.rows; row++) {
                for (int column = 0; column < config.columns; column++) {
                    float l = keyLeft(row, column), t = keyTop(row);
                    if (x >= l - slack && x <= l + size + slack && y >= t - slack && y <= t + size + slack) {
                        return row * KeypadConfig.MAX_COLUMNS + column;
                    }
                }
            }
            return -1;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (editMode) {
                return handleEditTouch(e);
            }
            int action = e.getActionMasked();
            int index = e.getActionIndex();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (action == MotionEvent.ACTION_DOWN) {
                        // A new gesture: release anything a lost UP left pressed
                        for (int i = pointerKey.size() - 1; i >= 0; i--) {
                            releasePointer(pointerKey.keyAt(i));
                        }
                    }
                    int key = keyAt(e.getX(index), e.getY(index));
                    if (key < 0) {
                        // Between the keys: let the touch reach the stream (first finger only)
                        return action != MotionEvent.ACTION_DOWN;
                    }
                    pointerKey.put(e.getPointerId(index), key);
                    press(key);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    releasePointer(e.getPointerId(index));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    for (int i = pointerKey.size() - 1; i >= 0; i--) {
                        releasePointer(pointerKey.keyAt(i));
                    }
                    return true;
                default:
                    return true;
            }
        }

        private void press(int key) {
            if (pressCount[key]++ == 0) {
                sender.press(config.keys.get(key));
                tick();
            }
            invalidate();
        }

        private void releasePointer(int pointerId) {
            int i = pointerKey.indexOfKey(pointerId);
            if (i < 0) {
                return;
            }
            int key = pointerKey.valueAt(i);
            pointerKey.removeAt(i);
            if (pressCount[key] > 0 && --pressCount[key] == 0) {
                sender.release(config.keys.get(key));
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            for (int i = pointerKey.size() - 1; i >= 0; i--) {
                releasePointer(pointerKey.keyAt(i));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int alpha = Math.round(config.opacity * 255);
            float size = keySizePx();
            float r = size / 2f;
            for (int row = 0; row < config.rows; row++) {
                for (int column = 0; column < config.columns; column++) {
                    int key = row * KeypadConfig.MAX_COLUMNS + column;
                    float cx = keyLeft(row, column) + r, cy = keyTop(row) + r;
                    boolean pressed = pressCount[key] > 0;

                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(pressed ? colorActive : colorFill);
                    paint.setAlpha(pressed ? Math.min(255, alpha + 60) : alpha);
                    canvas.drawCircle(cx, cy, r - dp(1), paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.5f));
                    paint.setColor(colorStroke);
                    paint.setAlpha(alpha);
                    canvas.drawCircle(cx, cy, r - dp(1), paint);

                    String label = config.keys.get(key).displayLabel(getContext());
                    float textSize = size * (label.length() <= 2 ? 0.36f : label.length() <= 5 ? 0.24f : 0.18f);
                    textPaint.setTextSize(textSize);
                    textPaint.setAlpha(Math.min(255, alpha + 90));
                    canvas.drawText(label, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
                }
            }
            if (editMode) {
                drawEditOutline(canvas, paint);
            }
        }
    }
}
