package com.junopark.hermit.hermit;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.junopark.hermit.R;
import com.junopark.hermit.nvstream.http.ComputerDetails;
import com.junopark.hermit.nvstream.http.NvHTTP;

/**
 * Opens the host's web UI pairing page in the browser while the PIN dialog is up, with the PIN and
 * this device's name prefilled (Shell: /pin reads them from the URL fragment).
 *
 * The PIN and the name travel in the fragment only, so they never reach the host's request log.
 * The web UI port follows the Sunshine convention (HTTPS = base - 5, HTTP = base, web UI = base + 1,
 * 47990 by default); it is derived from the ports the PC list already knows.
 */
public final class ShellPairingPage {
    private static final int WEB_UI_PORT_OFFSET_FROM_HTTP = 1;
    private static final int WEB_UI_PORT_OFFSET_FROM_HTTPS = 6;

    private ShellPairingPage() {
    }

    /** Whether the host has a web UI pairing page: every open-source host (Shell, Apollo, Sunshine), not NVIDIA GameStream. */
    public static boolean isAvailable(ComputerDetails computer) {
        return computer != null && !computer.nvidiaServer && computer.activeAddress != null;
    }

    /** The web UI port: HTTP port + 1, or HTTPS port + 6 when only that is known, else 47990. */
    public static int webUiPort(ComputerDetails computer) {
        if (computer.activeAddress != null && computer.activeAddress.port > 0) {
            return computer.activeAddress.port + WEB_UI_PORT_OFFSET_FROM_HTTP;
        }
        if (computer.httpsPort > 0) {
            return computer.httpsPort + WEB_UI_PORT_OFFSET_FROM_HTTPS;
        }
        return NvHTTP.DEFAULT_HTTP_PORT + WEB_UI_PORT_OFFSET_FROM_HTTP;
    }

    /** The name the pairing page shows for this device: the Android device name, else the model. */
    public static String deviceName(Context context) {
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
            } catch (SecurityException e) {
                // Some devices restrict it; the model is good enough
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        return name.trim();
    }

    /** https://host:port/pin#pin=PIN&name=NAME (IPv6 hosts in brackets; the fragment percent-encoded). */
    public static Uri url(ComputerDetails computer, String pin, String deviceName) {
        String host = computer.activeAddress.address;
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return Uri.parse("https://" + host + ":" + webUiPort(computer) + "/pin#pin=" + Uri.encode(pin)
                + "&name=" + Uri.encode(deviceName));
    }

    /** Opens the page in the default browser; a notice when there is none. */
    public static void open(Activity activity, ComputerDetails computer, String pin) {
        Uri uri = url(computer, pin, deviceName(activity));
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException e) {
            // SecurityException: a default browser that is not exported (see HelpLauncher)
            HermitNotice.show(activity, activity.getString(R.string.hermit_pair_no_browser), HermitNotice.LONG);
        }
    }
}
