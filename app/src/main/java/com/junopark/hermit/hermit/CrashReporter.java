package com.junopark.hermit.hermit;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.junopark.hermit.BuildConfig;
import com.junopark.hermit.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the details of the last crash so they can be copied or shared from the app on the next
 * start, without adb. Java exceptions are caught directly; native crashes and ANRs are taken
 * from Android's exit records (Android 11+).
 */
public final class CrashReporter {
    private static final String DIR = "hermit-crash";
    private static final String FILE = "last-crash.txt";
    private static final String PREFS = "HermitCrash";
    private static final int MAX_SHARE_CHARS = 90 * 1024;

    private static boolean showing;

    private CrashReporter() {
    }

    public static void install(Context context) {
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter trace = new StringWriter();
                throwable.printStackTrace(new PrintWriter(trace));
                write(appContext, "Uncaught exception in thread \"" + thread.getName() + "\"", trace.toString(), true);
            } catch (Throwable ignored) {
                // Never get in the way of the normal crash handling
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static File file(Context context) {
        return new File(new File(context.getFilesDir(), DIR), FILE);
    }

    private static void write(Context context, String title, String details, boolean withLog) throws IOException {
        File f = file(context);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            out.write(title + "\n");
            out.write("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()) + "\n");
            out.write("App: " + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
            out.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + "), build " + Build.DISPLAY + "\n\n");
            out.write(details);
            if (withLog) {
                out.write("\n\n--- Recent log of this process ---\n");
                out.write(ownLog());
            }
        }
    }

    /** The last lines this process wrote to logcat (apps may read their own log). */
    private static String ownLog() {
        StringBuilder sb = new StringBuilder();
        try {
            java.lang.Process logcat = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "400", "--pid=" + android.os.Process.myPid()});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && sb.length() < 200 * 1024) {
                    sb.append(line).append('\n');
                }
            }
            logcat.destroy();
        } catch (IOException | RuntimeException e) {
            sb.append("(log not available: ").append(e).append(")\n");
        }
        return sb.toString();
    }

    /** Records native crashes and ANRs since the last check (Java crashes are written directly). */
    @TargetApi(Build.VERSION_CODES.R)
    private static void collectExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastChecked = prefs.getLong("lastExitCheck", 0);
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        List<ApplicationExitInfo> exits;
        try {
            exits = am.getHistoricalProcessExitReasons(null, 0, 5);
        } catch (RuntimeException e) {
            return;
        }
        long newest = lastChecked;
        for (ApplicationExitInfo exit : exits) {
            newest = Math.max(newest, exit.getTimestamp());
            if (exit.getTimestamp() <= lastChecked || lastChecked == 0) {
                continue; // already seen (or the first run: do not report old history)
            }
            int reason = exit.getReason();
            if (reason != ApplicationExitInfo.REASON_CRASH_NATIVE && reason != ApplicationExitInfo.REASON_ANR) {
                continue;
            }
            StringBuilder details = new StringBuilder();
            details.append("Process: ").append(exit.getProcessName())
                    .append("\nExited: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date(exit.getTimestamp())))
                    .append("\nDescription: ").append(exit.getDescription()).append('\n');
            if (reason == ApplicationExitInfo.REASON_ANR) {
                // ANR traces are plain text (native tombstones are binary and left out).
                try (InputStream trace = exit.getTraceInputStream()) {
                    if (trace != null) {
                        details.append("\n--- ANR trace ---\n").append(readLimited(trace, 64 * 1024));
                    }
                } catch (IOException ignored) {
                }
            }
            try {
                write(context, reason == ApplicationExitInfo.REASON_ANR ?
                        "Application not responding (ANR)" : "Native crash", details.toString(), false);
            } catch (IOException ignored) {
            }
            break; // exits are newest first
        }
        prefs.edit().putLong("lastExitCheck", Math.max(newest, 1)).apply();
    }

    private static String readLimited(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];
        int total = 0;
        int n;
        while (total < maxBytes && (n = in.read(buffer, total, maxBytes - total)) > 0) {
            total += n;
        }
        return new String(buffer, 0, total, StandardCharsets.UTF_8);
    }

    private static String readReport(Context context) {
        File f = file(context);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            return readLimited(in, 512 * 1024);
        } catch (IOException e) {
            return null;
        }
    }

    /** Shows the last crash report, if there is one, with copy and share buttons. */
    public static void showIfPending(final Activity activity) {
        if (showing) {
            return;
        }
        collectExitReasons(activity);
        final String report = readReport(activity);
        if (report == null) {
            return;
        }
        final String shareText = report.length() > MAX_SHARE_CHARS ? report.substring(0, MAX_SHARE_CHARS) : report;
        String firstLines = report.length() > 1200 ? report.substring(0, 1200) + "…" : report;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.hermit_crash_title)
                .setMessage(activity.getString(R.string.hermit_crash_message) + "\n\n" + firstLines)
                .setPositiveButton(R.string.hermit_crash_share, (dialog, which) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Hermit crash report");
                    send.putExtra(Intent.EXTRA_TEXT, shareText);
                    activity.startActivity(Intent.createChooser(send, null));
                    clear(activity);
                })
                .setNeutralButton(R.string.hermit_crash_copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Hermit crash report", shareText));
                        HermitNotice.show(activity, R.string.hermit_crash_copied, HermitNotice.SHORT);
                    }
                    clear(activity);
                })
                .setNegativeButton(R.string.hermit_crash_close, (dialog, which) -> clear(activity))
                .setOnDismissListener(dialog -> showing = false)
                .show();
        showing = true;
    }

    private static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }
}
