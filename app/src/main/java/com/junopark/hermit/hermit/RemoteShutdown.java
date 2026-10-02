package com.junopark.hermit.hermit;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.junopark.hermit.R;
import com.junopark.hermit.binding.PlatformBinding;
import com.junopark.hermit.nvstream.http.ComputerDetails;
import com.junopark.hermit.nvstream.http.HostHttpResponseException;
import com.junopark.hermit.nvstream.http.NvHTTP;
import com.junopark.hermit.utils.ServerHelper;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Turning the host PC off or restarting it from the PC list (Shell's GET /actions/power), following
 * Hermit for Windows.
 * The host is asked first whether this device may and which other devices are streaming; when
 * there are any, they are named in a warning so nobody is cut off by accident. Then a
 * confirmation, with an option to also close apps that have unsaved work.
 */
public class RemoteShutdown {
    private static final int WARNING_COLOR = 0xFFF1C21B;

    private final Activity activity;
    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean restart;
    private AlertDialog progress;
    private boolean cancelled;

    private RemoteShutdown(Activity activity, ComputerDetails computer, String uniqueId, boolean restart) {
        this.activity = activity;
        this.computer = computer;
        this.uniqueId = uniqueId;
        this.restart = restart;
    }

    /** restart: restart the PC instead of turning it off. */
    public static void start(Activity activity, ComputerDetails computer, String uniqueId, boolean restart) {
        new RemoteShutdown(activity, computer, uniqueId, restart).query();
    }

    private String title() {
        return activity.getString(restart ? R.string.hermit_power_restart_title : R.string.hermit_power_title, computer.name);
    }

    private NvHTTP connect() throws Exception {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                computer.httpsPort, uniqueId, computer.serverCert,
                PlatformBinding.getCryptoProvider(activity));
    }

    private String errorText(Exception e) {
        if (e instanceof HostHttpResponseException) {
            int code = ((HostHttpResponseException) e).getErrorCode();
            if (code == 401 || code == 403) {
                return activity.getString(R.string.hermit_power_no_permission);
            }
            if (code == 404 || code == 501) {
                return activity.getString(R.string.hermit_power_unsupported);
            }
            // The host answered but could not do it (for example a shutdown already under way)
            return activity.getString(R.string.hermit_power_failed, code);
        }
        if (e instanceof FileNotFoundException) {
            return activity.getString(R.string.hermit_power_unsupported);
        }
        return activity.getString(R.string.hermit_power_network);
    }

    private void showError(String message) {
        if (activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title())
                .setMessage(message)
                .setPositiveButton(R.string.hermit_power_close, null)
                .show();
    }

    private void query() {
        progress = new AlertDialog.Builder(activity)
                .setTitle(title())
                .setMessage(R.string.hermit_power_checking)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled = true)
                .setOnCancelListener(d -> cancelled = true)
                .show();

        new Thread(() -> {
            NvHTTP.PowerState state = null;
            Exception error = null;
            try {
                state = connect().hermitPowerQuery();
            } catch (Exception e) {
                error = e;
            }
            final NvHTTP.PowerState result = state;
            final Exception failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;  // the dialog went with the activity
                }
                if (progress != null && progress.isShowing()) {
                    progress.dismiss();
                }
                if (cancelled) {
                    return;
                }
                if (failure != null || result == null || !result.supported) {
                    showError(failure != null ? errorText(failure) : activity.getString(R.string.hermit_power_unsupported));
                }
                else if (!result.allowed) {
                    showError(activity.getString(R.string.hermit_power_no_permission));
                }
                else {
                    confirm(result.clients);
                }
            });
        }).start();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    private void confirm(List<String> clients) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);

        if (!clients.isEmpty()) {
            // Other devices streaming from this PC right now: a bordered warning with a 3dp
            // yellow bar on its leading edge
            LinearLayout warningBox = new LinearLayout(activity);
            warningBox.setOrientation(LinearLayout.HORIZONTAL);
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            border.setStroke(dp(1), WARNING_COLOR);
            warningBox.setBackground(border);

            View bar = new View(activity);
            bar.setBackgroundColor(WARNING_COLOR);
            warningBox.addView(bar, new LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT));

            TextView warning = new TextView(activity);
            warning.setText(activity.getString(R.string.hermit_power_others, clients.size())
                    + "\n" + TextUtils.join(", ", clients)
                    + "\n" + activity.getString(restart ? R.string.hermit_power_others_end_restart : R.string.hermit_power_others_end));
            warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            warning.setLineSpacing(dp(2), 1f);
            warning.setPadding(dp(12), dp(10), dp(12), dp(10));
            warningBox.addView(warning, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(12);
            content.addView(warningBox, params);
        }

        TextView question = new TextView(activity);
        question.setText(activity.getString(restart ? R.string.hermit_power_confirm_restart : R.string.hermit_power_confirm, computer.name));
        question.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        content.addView(question);

        CheckBox force = new CheckBox(activity);
        force.setText(R.string.hermit_power_force);
        force.setMinHeight(dp(48));
        LinearLayout.LayoutParams forceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        forceParams.topMargin = dp(8);
        content.addView(force, forceParams);

        new AlertDialog.Builder(activity)
                .setTitle(title())
                .setView(content)
                .setPositiveButton(restart
                                ? (clients.isEmpty() ? R.string.hermit_power_restart : R.string.hermit_power_restart_anyway)
                                : (clients.isEmpty() ? R.string.hermit_power_shutdown : R.string.hermit_power_shutdown_anyway),
                        (d, w) -> run(force.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void run(boolean force) {
        HermitNotice.show(activity, restart ? R.string.hermit_power_restarting : R.string.hermit_power_sending, HermitNotice.SHORT);
        new Thread(() -> {
            Exception error = null;
            boolean ok = false;
            try {
                ok = connect().hermitPowerAction(restart ? "restart" : "shutdown", force);
            } catch (Exception e) {
                error = e;
            }
            final boolean done = ok;
            final Exception failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                if (done) {
                    HermitNotice.show(activity, activity.getString(restart ? R.string.hermit_power_restart_sent : R.string.hermit_power_sent, computer.name), HermitNotice.LONG);
                }
                else {
                    showError(failure != null ? errorText(failure) : activity.getString(R.string.hermit_power_unsupported));
                }
            });
        }).start();
    }
}
