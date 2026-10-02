package com.junopark.hermit.hermit;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.junopark.hermit.R;

import java.lang.ref.WeakReference;

/**
 * Short notices in Hermit's style instead of system toasts (which many devices draw small, in the
 * system font, at a corner in landscape): a dark card with a teal bar, centred near the bottom
 * of the screen, above the soft keyboard and navigation bar. One at a time; a new one replaces
 * the last. Touches pass through it.
 *
 * The card is part of the screen, so it waits while a dialog covers the screen (an error, the
 * connecting spinner) and shows once the screen has the focus again. A notice whose screen
 * closes first (a lost connection going back to the list) moves on to the next screen; notices
 * waiting together are shown together. Only when no screen of the app comes does a system toast
 * stand in. A card is taken away when its screen goes to picture-in-picture.
 */
public final class HermitNotice {
    public static final int SHORT = Toast.LENGTH_SHORT;
    public static final int LONG = Toast.LENGTH_LONG;

    private static final int SHORT_MS = 2500, LONG_MS = 4000;
    // A notice without a screen waits this long for the next one before it becomes a system toast
    private static final int HANDOVER_MS = 1500;
    // A screen without the focus (a dialog over it) is waited for this long, then shown anyway
    private static final int FOCUS_WAIT_MS = 15000;
    private static final int MAX_WIDTH_DP = 560, BOTTOM_DP = 40, TOP_DP = 56;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean installed;
    private static Context appContext;
    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    // The notice on screen
    private static WeakReference<Activity> shownOn = new WeakReference<>(null);
    private static View shownView;
    private static CharSequence shownText;
    private static long shownUntil;

    // A notice waiting for its screen to get the focus back
    private static WeakReference<Activity> waitingOn = new WeakReference<>(null);
    private static CharSequence waitingText;
    private static long waitingDuration;
    private static ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    private static int waitSeq;  // tells a wait's timer from a later wait's

    // The screen whose text input bar takes the bottom (Android 11+); only that screen's notices
    // move to the top, so a stream that ends with the bar open leaves the other screens alone
    private static WeakReference<Activity> bottomTakenOn = new WeakReference<>(null);

    // A notice waiting for the next screen
    private static CharSequence pending;
    private static long pendingDuration;
    private static long pendingSince;
    // Kept for the screen while the display is off, at most this long
    private static final int PENDING_MAX_AGE_MS = 60000;

    private HermitNotice() {
    }

    /** Follows which screen is showing (HermitApplication; also done on first use). */
    public static void install(Application app) {
        if (installed) {
            return;
        }
        installed = true;
        appContext = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumed = new WeakReference<>(activity);
                if (pending != null) {
                    CharSequence text = pending;
                    long duration = pendingDuration;
                    boolean fresh = SystemClock.uptimeMillis() - pendingSince <= PENDING_MAX_AGE_MS;
                    pending = null;
                    pendingDuration = 0;
                    if (fresh) {
                        present(activity, text, duration);
                    }
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumed.get() == activity) {
                    resumed = new WeakReference<>(null);
                }
                boolean closing = activity.isFinishing();
                boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode();
                if (waitingOn.get() == activity && (closing || pip)) {
                    // Its screen closes before it could be shown: the next screen shows it
                    CharSequence text = waitingText;
                    long duration = waitingDuration;
                    stopWaiting();
                    hold(text, duration);
                }
                if (shownOn.get() == activity && shownView != null && (closing || pip)) {
                    long left = shownUntil - SystemClock.uptimeMillis();
                    if (closing && left > 500) {
                        hold(shownText, left);
                    }
                    // (in picture-in-picture it would cover the small picture)
                    remove();
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
                // Covered by another screen, or the app went to the background: a notice still
                // waiting there, or still showing, goes where it can be seen
                if (waitingOn.get() == activity) {
                    CharSequence text = waitingText;
                    long duration = waitingDuration;
                    stopWaiting();
                    reroute(activity, text, duration);
                }
                if (shownOn.get() == activity && shownView != null) {
                    long left = shownUntil - SystemClock.uptimeMillis();
                    CharSequence text = shownText;
                    remove();
                    if (left > 500) {
                        reroute(activity, text, left);
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    /** The screen's text input bar takes the bottom (or leaves it): its notices go to the top. */
    public static void setBottomTaken(Activity screen, boolean taken) {
        if (taken) {
            bottomTakenOn = new WeakReference<>(screen);
        } else if (bottomTakenOn.get() == screen) {
            bottomTakenOn = new WeakReference<>(null);
        }
        if (shownView != null) {
            shownView.requestApplyInsets();
        }
    }

    public static void show(Context context, int textRes, int length) {
        show(context, context.getText(textRes), length);
    }

    /** Shows a notice; length is SHORT or LONG (the Toast constants). Any thread. */
    public static void show(Context context, CharSequence text, int length) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(context, text, length));
            return;
        }
        long duration = length == LONG ? LONG_MS : SHORT_MS;
        Activity own = context instanceof Activity ? (Activity) context : null;
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (!installed) {
            install((Application) context.getApplicationContext());
            // Registered just now: the calling screen is the one showing
            if (own != null && !own.isFinishing()) {
                resumed = new WeakReference<>(own);
            }
        }
        Activity current = resumed.get();
        if (current != null && !current.isFinishing() && !current.isDestroyed()) {
            present(current, text, duration);
        } else {
            hold(text, duration);
        }
    }

    // To the screen showing now, else (the app is in the background) a system toast
    private static void reroute(Activity from, CharSequence text, long duration) {
        Activity current = resumed.get();
        if (current != null && current != from && !current.isFinishing() && !current.isDestroyed()) {
            // The screen may already show a newer notice of its own: both, the older first
            if (shownOn.get() == current && shownView != null) {
                duration = Math.max(duration, shownUntil - SystemClock.uptimeMillis());
                text = join(text, shownText);
            }
            present(current, text, duration);
        } else {
            hold(text, duration);
        }
    }

    // Two notices waiting together are shown together
    private static CharSequence join(CharSequence first, CharSequence second) {
        return first == null ? second : first + "\n" + second;
    }

    // Waits for the next screen; a system toast if none comes
    private static void hold(CharSequence text, long duration) {
        if (pending == null) {
            pendingSince = SystemClock.uptimeMillis();
        }
        pending = join(pending, text);
        pendingDuration = Math.max(pendingDuration, duration);
        android.os.PowerManager power = (android.os.PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) {
            // The display is off: a toast now would never be seen; the screen shows it when the
            // app is back (within a minute)
            return;
        }
        CharSequence waiting = pending;
        main.postDelayed(() -> {
            if (pending == waiting) {
                boolean longer = pendingDuration > SHORT_MS;
                pending = null;
                pendingDuration = 0;
                Toast.makeText(appContext, waiting, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        }, HANDOVER_MS);
    }

    // On the screen now, or once a dialog over it is gone
    private static void present(Activity activity, CharSequence text, long duration) {
        if (activity.hasWindowFocus()) {
            Activity other = waitingOn.get();
            if (other != null && other != activity) {
                // Another screen's waiting notice: that screen is no longer the current one
                text = join(waitingText, text);
                duration = Math.max(duration, waitingDuration);
                stopWaiting();
            }
            showOn(activity, text, duration);
            return;
        }
        if (waitingOn.get() == activity) {
            waitingText = join(waitingText, text);
            waitingDuration = Math.max(waitingDuration, duration);
            return;
        }
        if (waitingOn.get() != null) {
            // Another screen's waiting notice: that screen is no longer the current one
            CharSequence old = waitingText;
            stopWaiting();
            text = join(old, text);
        }
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            Toast.makeText(activity, text, duration > SHORT_MS ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        waitingOn = new WeakReference<>(activity);
        waitingText = text;
        waitingDuration = duration;
        focusListener = hasFocus -> {
            if (hasFocus && waitingOn.get() == activity) {
                showWaiting(activity);
            }
        };
        content.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
        // A screen that never gets the focus back (a dialog left open) shows it after a while
        int seq = ++waitSeq;
        main.postDelayed(() -> {
            if (waitingOn.get() == activity && waitSeq == seq) {
                showWaiting(activity);
            }
        }, FOCUS_WAIT_MS);
    }

    private static void showWaiting(Activity activity) {
        CharSequence text = waitingText;
        long duration = waitingDuration;
        stopWaiting();
        if (resumed.get() == activity && !activity.isFinishing() && !activity.isDestroyed()) {
            showOn(activity, text, duration);
        } else {
            reroute(activity, text, duration);
        }
    }

    private static void stopWaiting() {
        Activity activity = waitingOn.get();
        if (activity != null && focusListener != null) {
            View content = activity.findViewById(android.R.id.content);
            if (content != null && content.getViewTreeObserver().isAlive()) {
                content.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
            }
        }
        focusListener = null;
        waitingOn = new WeakReference<>(null);
        waitingText = null;
        waitingDuration = 0;
    }

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    private static void showOn(Activity activity, CharSequence text, long duration) {
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) {
            Toast.makeText(activity, text, duration > SHORT_MS ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLayout content = (FrameLayout) root;
        remove();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.getResources().getColor(R.color.hermit_layer1));
        bg.setStroke(dp(activity, 1), activity.getResources().getColor(R.color.hermit_border_strong));
        bg.setCornerRadius(dp(activity, 4));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(dp(activity, 12));
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        View accent = new View(activity);
        accent.setBackgroundColor(activity.getResources().getColor(R.color.hermit_accent));
        card.addView(accent, new LinearLayout.LayoutParams(dp(activity, 3), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(activity.getResources().getColor(R.color.hermit_text_primary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLineSpacing(0, 1.2f);
        label.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        card.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = content.getWidth() > 0 ? content.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        label.setMaxWidth(Math.min(dp(activity, MAX_WIDTH_DP), Math.round(width * 0.9f)) - dp(activity, 3));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        place(activity, lp, content.getRootWindowInsets());
        content.addView(card, lp);
        // The keyboard coming or going while it shows moves it too
        card.setOnApplyWindowInsetsListener((v, insets) -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
            int gravity = params.gravity, top = params.topMargin, bottom = params.bottomMargin;
            place(activity, params, insets);
            if (params.gravity != gravity || params.topMargin != top || params.bottomMargin != bottom) {
                v.setLayoutParams(params);
            }
            return insets;
        });
        card.bringToFront();
        // Read out as a notice by TalkBack, like a toast
        content.announceForAccessibility(text);

        card.setAlpha(0f);
        card.setTranslationY(dp(activity, 8));
        card.animate().alpha(1f).translationY(0).setDuration(160).start();

        shownOn = new WeakReference<>(activity);
        shownView = card;
        shownText = text;
        shownUntil = SystemClock.uptimeMillis() + duration;
        // Taken off by the screen itself (PC list: setContentView() again on rotation, keyboard
        // or dark mode changes): put back for the time left (remove() clears shownView first,
        // so its own removal does not come here)
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (shownView != card) {
                    return;
                }
                main.post(() -> {
                    if (shownView != card) {
                        return;
                    }
                    Activity on = shownOn.get();
                    CharSequence left = shownText;
                    long time = shownUntil - SystemClock.uptimeMillis();
                    remove();
                    if (on == null || time <= 500) {
                        return;
                    }
                    if (resumed.get() == on && !on.isFinishing() && !on.isDestroyed()) {
                        present(on, left, time);
                    } else {
                        reroute(on, left, time);
                    }
                });
            }
        });
        card.postDelayed(() -> {
            if (shownView == card) {
                card.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                    if (shownView == card) {
                        remove();
                    }
                }).start();
            }
        }, duration);
    }

    // Near the bottom, above the soft keyboard and navigation bar; at the top (below the zoom tag
    // and a camera cutout) while the keyboard is up or the stream's text input bar takes the
    // bottom. Android 11+; before that a fullscreen window gets no keyboard insets and the bottom
    // margin is enough.
    private static void place(Context context, FrameLayout.LayoutParams lp, WindowInsets insets) {
        boolean top = context != null && bottomTakenOn.get() == context;
        int bottomInset = 0, cutout = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
            top |= insets.isVisible(WindowInsets.Type.ime());
            bottomInset = insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom;
            cutout = insets.getInsets(WindowInsets.Type.displayCutout()).top;
        }
        lp.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top ? dp(context, TOP_DP) + cutout : 0;
        lp.bottomMargin = top ? 0 : dp(context, BOTTOM_DP) + bottomInset;
    }

    private static void remove() {
        View view = shownView;
        shownView = null;
        shownText = null;
        shownOn = new WeakReference<>(null);
        if (view != null && view.getParent() instanceof ViewGroup) {
            view.animate().cancel();
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }
}
