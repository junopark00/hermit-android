package com.junopark.hermit.hermit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.junopark.hermit.R;
import com.junopark.hermit.nvstream.jni.MoonBridge;

/**
 * Automatic reconnect for the stream activity, following Hermit for Windows: after the network cut
 * an established stream off, wait 5 seconds (with a countdown and "connect now" / "cancel") and
 * start the same stream again, up to 3 times. The count starts over once a stream has run for
 * a minute. The new attempt is a fresh Game activity with the attempt number in its intent.
 */
public class Reconnector {
    public static final String EXTRA_ATTEMPT = "HermitReconnectAttempt";
    public static final int MAX_ATTEMPTS = 3;
    private static final int DELAY_SECONDS = 5;
    private static final long RESET_AFTER_MS = 60000;

    private final Activity activity;
    private final int attempt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private Runnable tick;

    public Reconnector(Activity activity) {
        this.activity = activity;
        this.attempt = activity.getIntent().getIntExtra(EXTRA_ATTEMPT, 0);
    }

    /** True if this activity is itself an automatic reconnect attempt. */
    public boolean isReconnectAttempt() {
        return attempt > 0;
    }

    /**
     * Whether a termination means the network cut the stream off. Not for a graceful end, no
     * video ever arriving (firewall / port forwarding), or a host-side start or encoder error.
     */
    public static boolean isConnectionLost(int errorCode) {
        switch (errorCode) {
            case MoonBridge.ML_ERROR_GRACEFUL_TERMINATION:
            case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
            case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
            case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
            case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                return false;
            default:
                return true;
        }
    }

    /**
     * Starts the countdown to the next attempt if one is left. streamStartedMs is the uptime when
     * this stream started (0 if it never did).
     */
    public boolean tryReconnect(long streamStartedMs) {
        long streamedMs = streamStartedMs != 0 ? SystemClock.uptimeMillis() - streamStartedMs : 0;
        int used = streamedMs > RESET_AFTER_MS ? 0 : attempt;
        if (used >= MAX_ATTEMPTS || activity.isFinishing()) {
            return false;
        }
        showCountdown(used + 1);
        return true;
    }

    private String message(int secondsLeft, int nextAttempt) {
        return activity.getString(R.string.hermit_reconnect_countdown, secondsLeft) + "\n\n" +
                activity.getString(R.string.hermit_reconnect_attempt, nextAttempt, MAX_ATTEMPTS);
    }

    private void showCountdown(final int nextAttempt) {
        final int[] secondsLeft = {DELAY_SECONDS};
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.hermit_reconnect_title)
                .setMessage(message(secondsLeft[0], nextAttempt))
                .setCancelable(false)
                .setPositiveButton(R.string.hermit_reconnect_now, (d, which) -> relaunch(nextAttempt))
                .setNegativeButton(R.string.hermit_reconnect_cancel, (d, which) -> {
                    cancel();
                    activity.finish();
                })
                .show();
        tick = new Runnable() {
            @Override
            public void run() {
                secondsLeft[0]--;
                if (secondsLeft[0] <= 0) {
                    relaunch(nextAttempt);
                    return;
                }
                if (dialog != null) {
                    dialog.setMessage(message(secondsLeft[0], nextAttempt));
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(tick, 1000);
    }

    private void relaunch(int nextAttempt) {
        cancel();
        if (activity.isFinishing()) {
            return;
        }
        Intent intent = new Intent(activity.getIntent());
        intent.putExtra(EXTRA_ATTEMPT, nextAttempt);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        // Game is singleTask: finish first so the start creates a new instance instead of
        // delivering the intent to this one.
        activity.finish();
        activity.startActivity(intent);
    }

    /** Stops a pending countdown (the activity is going away). */
    public void cancel() {
        if (tick != null) {
            handler.removeCallbacks(tick);
            tick = null;
        }
        if (dialog != null) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            dialog = null;
        }
    }
}
