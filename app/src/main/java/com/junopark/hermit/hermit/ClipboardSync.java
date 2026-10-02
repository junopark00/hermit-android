package com.junopark.hermit.hermit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.junopark.hermit.HermitLog;
import com.junopark.hermit.R;
import com.junopark.hermit.nvstream.http.HostHttpResponseException;
import com.junopark.hermit.nvstream.http.NvHTTP;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Clipboard sync between this device and the host while streaming, following Hermit for Windows:
 * what the user copied on the device is sent when they return to the stream, and what they
 * copied on the host is put on the device clipboard when they leave it.
 *
 * Android only lets the focused app read the clipboard, and the stream stops as soon as the
 * activity is no longer visible, so the host side is polled while streaming (Shell: a cheap
 * sequence number check) and the latest host content is kept ready to be applied the moment
 * focus is lost, without any network I/O at that point.
 *
 * Text works with any host that has the clipboard endpoint; images need Shell's type=info/image extensions.
 */
public class ClipboardSync {
    private static final int EXTENDED_POLL_MS = 1000;
    private static final int LEGACY_POLL_MS = 2000;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;   // encoded PNG, same as the host
    private static final int MAX_SOURCE_IMAGE_BYTES = 64 * 1024 * 1024;
    private static final long MAX_CONVERTED_PIXELS = 16L * 1000 * 1000;
    private static final String STATE_PREFS = "HermitClipboard";

    private enum Mode { UNKNOWN, EXTENDED, LEGACY, DISABLED }

    /** Host clipboard content fetched while streaming, applied to the device on focus loss. */
    private static final class HostContent {
        final String text;       // or null for an image
        final String imageName;  // file in ClipboardImageProvider's folder
        final String key;
        boolean applied;

        HostContent(String text, String imageName, String key) {
            this.text = text;
            this.imageName = imageName;
            this.key = key;
        }
    }

    private final Activity activity;
    private final Context appContext;
    private final NvHTTP http;
    private final SharedPreferences state;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean stopped;

    // Worker thread state
    private Mode mode = Mode.UNKNOWN;
    private long hostSeq = -1;
    private String legacyHostTextKey;
    private boolean reportedDenied;
    // A device clip that arrived before the host accepted clipboard calls (e.g. 403 right
    // after the stream started); sent once setup succeeds.
    private Runnable deferredPush;

    // Written by the worker, read on the UI thread
    private volatile HostContent pending;

    // UI thread state, persisted so a new stream does not resend what was already exchanged
    private String lastExchangedKey;
    private long lastSeenClipTimestamp;

    public ClipboardSync(Activity activity, NvHTTP http) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.http = http;
        this.state = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        this.lastExchangedKey = state.getString("lastExchangedKey", null);
        this.lastSeenClipTimestamp = state.getLong("lastSeenClipTimestamp", -1);
    }

    /** Called once the stream has started (the host only accepts clipboard calls then). */
    public void start() {
        if (stopped) {
            return; // the stream already ended before connectionStarted ran
        }
        thread = new HandlerThread("Hermit clipboard");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::init);
    }

    public void stop() {
        stopped = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    // ---- UI thread -----------------------------------------------------------------------

    /** The stream window got focus: send what the user copied on the device since last time. */
    public void onFocusGained() {
        if (stopped || handler == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }

        // The description can be read without Android's "pasted from clipboard" notice, so use
        // its timestamp to skip clips we have already handled.
        ClipDescription description = cm.getPrimaryClipDescription();
        if (description == null) {
            return;
        }
        long timestamp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? description.getTimestamp() : -1;
        if (timestamp > 0 && timestamp == lastSeenClipTimestamp) {
            return;
        }

        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (SecurityException e) {
            return; // not focused after all
        }
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }

        // A clip is remembered as handled only once it was sent (or needs no sending), so a
        // failed send is tried again on the next return to the stream.
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        if (uri != null && ClipboardImageProvider.isOurs(appContext, uri)) {
            rememberSeen(timestamp);
            return; // the host image we put there ourselves
        }
        if (uri != null && clip.getDescription().hasMimeType("image/*")) {
            final String key = "image:" + uri + "@" + timestamp;
            if (key.equals(lastExchangedKey)) {
                rememberSeen(timestamp);
                return;
            }
            final String mime = appContext.getContentResolver().getType(uri);
            handler.post(() -> pushImage(uri, mime, key, timestamp));
            return;
        }

        CharSequence text = item.getText();
        if (text == null && uri == null) {
            text = item.coerceToText(appContext);
        }
        if (text == null || text.length() == 0) {
            rememberSeen(timestamp);
            return;
        }
        final String value = text.toString();
        final String key = textKey(value);
        if (key.equals(lastExchangedKey)) {
            rememberSeen(timestamp);
            return; // our own copy of host content, or text already sent
        }
        handler.post(() -> pushText(value, key, timestamp));
    }

    /** Worker thread: a device clip reached the host. */
    private void markSent(String key, long timestamp) {
        activity.runOnUiThread(() -> {
            rememberExchanged(key);
            rememberSeen(timestamp);
        });
    }

    /** The stream window lost focus: put the latest host content on the device clipboard. */
    public void onFocusLost() {
        // Also after stop(): content fetched before the stream ended still belongs to the user.
        HostContent content = pending;
        if (content == null || content.applied) {
            return;
        }
        content.applied = true;
        if (content.key.equals(lastExchangedKey)) {
            return;
        }

        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        ClipData clip;
        if (content.text != null) {
            clip = ClipData.newPlainText("Hermit", content.text);
        } else {
            clip = ClipData.newUri(appContext.getContentResolver(), "Hermit",
                    ClipboardImageProvider.uriFor(appContext, content.imageName));
        }
        try {
            cm.setPrimaryClip(clip);
        } catch (RuntimeException e) {
            HermitLog.warning("Clipboard: could not set the device clipboard: " + e);
            return;
        }
        rememberExchanged(content.key);
        ClipDescription description = cm.getPrimaryClipDescription();
        if (description != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rememberSeen(description.getTimestamp());
        }
    }

    private void rememberExchanged(String key) {
        lastExchangedKey = key;
        state.edit().putString("lastExchangedKey", key).apply();
    }

    private void rememberSeen(long timestamp) {
        lastSeenClipTimestamp = timestamp;
        state.edit().putLong("lastSeenClipTimestamp", timestamp).apply();
    }

    // ---- Worker thread -------------------------------------------------------------------

    private void init() {
        if (stopped) {
            return;
        }
        try {
            String info = new String(http.hermitGetClipboard("info", 4096), StandardCharsets.UTF_8);
            long seq = parseLong(info, "seq");
            if (seq >= 0) {
                // Shell: remember the current host state but do not copy it; only changes made
                // during the stream come back to the device.
                mode = Mode.EXTENDED;
                hostSeq = seq;
                HermitLog.info("Clipboard sync: host supports text and images");
                schedulePoll(EXTENDED_POLL_MS);
                runDeferredPush();
                return;
            }
            mode = Mode.LEGACY;
        } catch (HostHttpResponseException e) {
            if (!handleHttpError(e, "setup")) {
                return;
            }
            if (e.getErrorCode() != 400) {
                // e.g. 403 before the host registered the stream: try again later
                schedulePoll(LEGACY_POLL_MS);
                return;
            }
            mode = Mode.LEGACY;
        } catch (IOException e) {
            HermitLog.warning("Clipboard sync setup failed: " + e);
            schedulePoll(LEGACY_POLL_MS);
            return;
        }

        HermitLog.info("Clipboard sync: host supports text only");
        try {
            legacyHostTextKey = textKey(new String(http.hermitGetClipboard("text", MAX_TEXT_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            HermitLog.warning("Clipboard: could not read the host text: " + e);
        }
        schedulePoll(LEGACY_POLL_MS);
        runDeferredPush();
    }

    private void runDeferredPush() {
        Runnable push = deferredPush;
        deferredPush = null;
        if (push != null) {
            push.run();
        }
    }

    private void schedulePoll(int delayMs) {
        if (!stopped) {
            handler.postDelayed(this::poll, delayMs);
        }
    }

    private void poll() {
        if (stopped) {
            return;
        }
        if (mode == Mode.UNKNOWN) {
            init();
            return;
        }
        if (mode == Mode.DISABLED) {
            return;
        }
        try {
            if (mode == Mode.EXTENDED) {
                pollExtended();
            } else {
                pollLegacy();
            }
        } catch (HostHttpResponseException e) {
            if (!handleHttpError(e, "check")) {
                return;
            }
        } catch (IOException e) {
            // Network hiccup: keep polling
        }
        schedulePoll(mode == Mode.EXTENDED ? EXTENDED_POLL_MS : LEGACY_POLL_MS);
    }

    private void pollExtended() throws IOException {
        String info = new String(http.hermitGetClipboard("info", 4096), StandardCharsets.UTF_8);
        long seq = parseLong(info, "seq");
        if (seq < 0 || seq == hostSeq) {
            return;
        }
        String type = parseField(info, "type");
        hostSeq = seq;

        if ("text".equals(type)) {
            byte[] data = http.hermitGetClipboard("text", MAX_TEXT_BYTES);
            if (data.length > 0) {
                String text = new String(data, StandardCharsets.UTF_8);
                pending = new HostContent(text, null, textKey(text));
            }
        } else if ("image".equals(type)) {
            byte[] png = http.hermitGetClipboard("image", MAX_IMAGE_BYTES);
            if (png.length > 0) {
                String name = "host-" + System.currentTimeMillis() + "-" + seq + ".png";
                File dir = ClipboardImageProvider.directory(appContext);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("Cannot create " + dir);
                }
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                    out.write(png);
                }
                pending = new HostContent(null, name, "hostimage:" + seq + ":" + png.length);
                pruneImages(dir);
            }
        } else {
            // Files (or nothing) on the host: nothing this device can paste, and older text
            // must not be applied instead.
            pending = null;
        }
    }

    /**
     * Keeps the newest few host images: the device clipboard may still point at an older one
     * (applied when the user last left a stream) until something else is copied.
     */
    private static void pruneImages(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 3) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 3; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private void pollLegacy() throws IOException {
        byte[] data = http.hermitGetClipboard("text", MAX_TEXT_BYTES);
        if (data.length == 0) {
            return;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String key = textKey(text);
        if (!key.equals(legacyHostTextKey)) {
            legacyHostTextKey = key;
            pending = new HostContent(text, null, key);
        }
    }

    private void pushText(String text, String key, long timestamp) {
        if (stopped) {
            return;
        }
        if (!ensureReady()) {
            if (mode == Mode.UNKNOWN) {
                deferredPush = () -> pushText(text, key, timestamp);
            }
            return;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_TEXT_BYTES) {
            toast(R.string.hermit_clipboard_too_large);
            return;
        }
        try {
            String reply = http.hermitSetClipboard("text", data, "text/plain; charset=utf-8");
            recordHostSeq(reply);
            legacyHostTextKey = key;
            markSent(key, timestamp);
            toast(R.string.hermit_clipboard_sent_text);
        } catch (HostHttpResponseException e) {
            handleHttpError(e, "send");
        } catch (IOException e) {
            HermitLog.warning("Clipboard text not sent: " + e);
        }
    }

    private void pushImage(Uri uri, String mime, String key, long timestamp) {
        if (stopped) {
            return;
        }
        if (!ensureReady()) {
            if (mode == Mode.UNKNOWN) {
                deferredPush = () -> pushImage(uri, mime, key, timestamp);
            }
            return;
        }
        if (mode != Mode.EXTENDED) {
            HermitLog.info("Clipboard images need a Shell host; only text is synced");
            return;
        }
        byte[] png;
        try {
            png = readAsPng(appContext.getContentResolver(), uri, mime);
        } catch (IOException | SecurityException e) {
            HermitLog.warning("Clipboard image could not be read: " + e);
            return;
        } catch (OutOfMemoryError e) {
            HermitLog.warning("Clipboard image too large to convert: " + e);
            toast(R.string.hermit_clipboard_too_large);
            return;
        }
        if (png == null || png.length > MAX_IMAGE_BYTES) {
            toast(R.string.hermit_clipboard_too_large);
            return;
        }
        try {
            String reply = http.hermitSetClipboard("image", png, "image/png");
            recordHostSeq(reply);
            markSent(key, timestamp);
            toast(R.string.hermit_clipboard_sent_image);
        } catch (HostHttpResponseException e) {
            handleHttpError(e, "send image");
        } catch (IOException e) {
            HermitLog.warning("Clipboard image not sent: " + e);
        }
    }

    private boolean ensureReady() {
        if (mode == Mode.UNKNOWN) {
            init();
        }
        return mode == Mode.EXTENDED || mode == Mode.LEGACY;
    }

    /** Returns false if clipboard sync is off for the rest of the stream. */
    private boolean handleHttpError(HostHttpResponseException e, String operation) {
        switch (e.getErrorCode()) {
            case 401:
                // The host did not give this device the clipboard permission.
                mode = Mode.DISABLED;
                if (!reportedDenied) {
                    reportedDenied = true;
                    toast(R.string.hermit_clipboard_denied);
                }
                return false;
            case 404:
                // The host has no clipboard endpoint.
                mode = Mode.DISABLED;
                HermitLog.info("Clipboard sync: host has no clipboard endpoint");
                return false;
            case 413:
                // Only for what the user sends; oversize host content is skipped quietly
                // (a legacy host would otherwise report it on every poll).
                if (operation.startsWith("send")) {
                    toast(R.string.hermit_clipboard_too_large);
                }
                return true;
            default:
                HermitLog.warning("Clipboard " + operation + " failed: HTTP " + e.getErrorCode());
                return true;
        }
    }

    private void recordHostSeq(String reply) {
        long seq = parseLong(reply, "seq");
        if (seq >= 0) {
            hostSeq = seq; // our own write: do not fetch it back
        }
    }

    private void toast(int messageRes) {
        if (stopped) {
            return;
        }
        activity.runOnUiThread(() -> HermitNotice.show(appContext, messageRes, HermitNotice.SHORT));
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static byte[] readAsPng(ContentResolver resolver, Uri uri, String mime) throws IOException {
        byte[] data;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > MAX_SOURCE_IMAGE_BYTES) {
                    return null;
                }
            }
            data = buffer.toByteArray();
        }
        if ("image/png".equals(mime)) {
            return data;
        }
        // The host clipboard takes PNG; convert anything else (JPEG screenshots, WebP, ...).
        // Photos from the camera can be 50 MP or more: downsample to at most 16 MP so the
        // conversion fits in memory.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while ((long) (bounds.outWidth / options.inSampleSize) * (bounds.outHeight / options.inSampleSize) > MAX_CONVERTED_PIXELS) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) {
            throw new IOException("Unsupported image format: " + mime);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        bitmap.recycle();
        return png.toByteArray();
    }

    private static String textKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("text:");
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "text:" + text.hashCode() + ":" + text.length();
        }
    }

    private static String parseField(String body, String name) {
        for (String line : body.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(name)) {
                return line.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static long parseLong(String body, String name) {
        String value = parseField(body, name);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
