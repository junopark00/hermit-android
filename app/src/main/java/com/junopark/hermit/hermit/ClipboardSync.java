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
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;

import com.junopark.hermit.HermitLog;
import com.junopark.hermit.R;
import com.junopark.hermit.nvstream.http.HostHttpResponseException;
import com.junopark.hermit.nvstream.http.NvHTTP;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clipboard sync between this device and the host while streaming, following Hermit for Windows:
 * what the user copied on the device is sent when they return to the stream, and what they
 * copied on the host is put on the device clipboard when they leave it.
 *
 * Android only lets the focused app read the clipboard, and the stream stops as soon as the
 * activity is no longer visible, so the host side is polled while streaming (Shell: a cheap
 * sequence number check) and the latest host content is kept ready to be applied the moment
 * focus is lost, without any network I/O at that point. When both clipboards changed, the most
 * recent change wins (see ClipboardChangeOrder).
 *
 * Text works with any host that has the clipboard endpoint; images need Shell's type=info/image extensions.
 * Files are not synced in either direction (a notice says so once per stream).
 *
 * The host grants reading (GET) and setting (POST) its clipboard separately, so a refusal (401)
 * stops only that direction.
 */
public class ClipboardSync {
    private static final int EXTENDED_POLL_MS = 1000;
    private static final int LEGACY_POLL_MS = 2000;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;   // encoded PNG, same as the host
    private static final int MAX_SOURCE_IMAGE_BYTES = 64 * 1024 * 1024;
    private static final long MAX_CONVERTED_PIXELS = 16L * 1000 * 1000;
    private static final long MAX_HOST_IMAGE_PIXELS = 8192L * 8192;  // Shell answers 413 above it
    // Failed fetches of one host clipboard item before it is given up on
    private static final int MAX_HOST_FETCH_FAILURES = 3;
    // The same for a host image (up to 32 MB each time): fetched once more at most, as in Hermit
    // for Windows
    private static final int MAX_HOST_IMAGE_FETCH_FAILURES = 2;
    // Busy answers (503) in a row for one host clipboard item before each further one counts as
    // a failed fetch
    private static final int MAX_HOST_BUSY_ANSWERS = 10;
    // First line of Shell's 422 body when it could not convert its clipboard image to PNG
    private static final String IMAGE_NOT_CONVERTIBLE = "image-not-convertible";
    // HTTP 500s for one device image before this host is taken to refuse it
    private static final int MAX_IMAGE_SERVER_ERRORS = 2;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    // The host clipboard key of a text-only host while its text is too large to fetch
    private static final String LEGACY_TOO_LARGE = "toolarge";
    private static final String STATE_PREFS = "HermitClipboard";
    // Label of the clips Hermit puts on the device clipboard: readable from the clip description
    // without Android's paste notice, so Hermit's own clip is not taken for a device copy
    private static final String OWN_CLIP_LABEL = "Hermit host clipboard";

    private enum Mode { UNKNOWN, EXTENDED, LEGACY, DISABLED }

    /** Host clipboard content fetched while streaming, applied to the device on focus loss. */
    private static final class HostContent {
        final String text;       // or null for an image
        final String imageName;  // file in ClipboardImageProvider's folder
        final String key;
        final long changeTime;   // when the host change was first found (ClipboardChangeOrder)
        boolean applied;

        HostContent(String text, String imageName, String key, long changeTime) {
            this.text = text;
            this.imageName = imageName;
            this.key = key;
            this.changeTime = changeTime;
        }
    }

    private final Activity activity;
    private final Context appContext;
    private final NvHTTP http;
    private final SharedPreferences state;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean stopped;

    // Worker thread state (mode and writeDenied are also read on the UI thread, to leave the
    // device clipboard alone once nothing can be sent)
    private volatile Mode mode = Mode.UNKNOWN;
    // The host clipboard's content (key: "seq:" + its sequence number, or for text-only hosts the
    // textKey of its text) and when it changed; its hostTime() is also read on the UI thread
    private final ClipboardChangeOrder order = new ClipboardChangeOrder();
    // The host refused reading (GET) or setting (POST) its clipboard for this device (401):
    // polling stops, or sending does, for the rest of the stream
    private boolean readDenied;
    private volatile boolean writeDenied;
    // The host did not answer type=info (401 before reading was allowed), so Extended is assumed
    // for sending until an image is refused as an unknown type
    private boolean modeAssumed;
    private boolean reportedHostTooLarge;
    // The host clipboard item (seq) whose fetch failed, and how often in a row: given up on after
    // a few tries, or at once on a timeout, instead of fetched again every second
    private long failedHostSeq = -1;
    private int hostFetchFailures;
    private boolean reportedHostFetchFailed;
    // The host clipboard item (seq) the host answered busy (503) for, and how often in a row
    private long busyHostSeq = -1;
    private int hostBusyAnswers;
    private boolean reportedHostImageNotConvertible;
    // The clip whose failure was last reported: one notice per clip, not one per return
    private String failureReportedKey;
    // The device image the host last answered 500 for, and how often
    private String serverErrorKey;
    private int serverErrors;
    // The device image whose send last failed with a network error: sent once more at most
    private String networkErrorKey;
    // A device clip that arrived before the host accepted clipboard calls (e.g. 403 right
    // after the stream started); sent once setup succeeds.
    private Runnable deferredPush;
    // Whether setup was tried already: only the try at stream start records the host's content
    // as there before the stream (ClipboardChangeOrder.hostSetUp)
    private boolean setupTried;
    // Wall-clock time the last setup try that failed was started: a later setup dates the host's
    // content then
    private long lastFailedSetup;

    // Written by the worker, read on the UI thread
    private volatile HostContent pending;
    private final AtomicBoolean filesNoticeShown = new AtomicBoolean();

    // UI thread state, persisted so a new stream does not resend what was already exchanged
    private String lastExchangedKey;
    private long lastSeenClipTimestamp;
    // A device clip this device cannot send at all (too large, unreadable, ...): not tried again
    // on each return, nor on a later stream (the timestamp covers it where Android has one)
    private String refusedKey;
    // A device clip this host refused (no images, over its limit): skipped for the rest of this
    // stream only, so a later stream to another host still tries it
    private String hostRefusedKey;
    private long hostRefusedClipTimestamp = -1;
    // The device clip queued for sending and not yet sent or given up on (a slow image upload,
    // or waiting for the host to accept clipboard calls): not queued again on another return
    private String inFlightKey;
    // The timestamp of the latest device copy seen (0: none, or Android 7 and earlier, which has
    // no clip timestamps); also read on the worker thread
    private volatile long latestDeviceCopy;
    // Wall-clock time (the base of clip timestamps) around the last setPrimaryClip() of host
    // content: a clip stamped in it is ours even if its description could not be read afterwards
    private long ownClipFrom = -1;
    private long ownClipTo = -1;

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
            // A send can wait minutes for the host's reply: abort it rather than leave the worker
            // (and the image) behind. This NvHTTP is ours alone; not on the UI thread, as the
            // cancel closes sockets.
            new Thread(http::cancelPendingRequests, "Hermit clipboard stop").start();
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
        if (isOwnClip(description, timestamp)) {
            // The host content we put there: nothing to send, and not a device copy (onFocusLost
            // cannot always record its timestamp: Android 10+ denies the read once focus is gone)
            if (timestamp > 0 && timestamp != lastSeenClipTimestamp) {
                rememberSeen(timestamp);
            }
            return;
        }
        if (timestamp > latestDeviceCopy) {
            // Not the host content we put there: the user copied something on the device, whether
            // it can be sent or not. Host content found before it is not put over it.
            latestDeviceCopy = timestamp;
        }
        if (writeDenied || mode == Mode.DISABLED) {
            // Nothing can be sent for the rest of the stream: do not read the clipboard (Android
            // 12+ shows a "pasted from clipboard" notice for every read) on each return
            return;
        }
        if (timestamp > 0 && (timestamp == lastSeenClipTimestamp || timestamp == hostRefusedClipTimestamp)) {
            return;
        }
        if (!order.deviceMayReplaceHost(timestamp)) {
            // The host clipboard changed after this copy: the host's content wins (and is put on
            // the device clipboard when the stream loses focus). Not read, so no paste notice.
            HermitLog.info("Clipboard: device clip not sent: newer content was copied on the host");
            rememberSeen(timestamp);
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
            if (key.equals(lastExchangedKey) || key.equals(refusedKey)) {
                rememberSeen(timestamp);
                return;
            }
            if (key.equals(hostRefusedKey)) {
                hostRefusedClipTimestamp = timestamp;
                return;
            }
            if (key.equals(inFlightKey)) {
                return; // still being sent
            }
            String type;
            try {
                type = appContext.getContentResolver().getType(uri);
            } catch (RuntimeException e) {
                // A faulty content provider; the image is converted whatever its type
                HermitLog.warning("Clipboard image type could not be read: " + e);
                type = null;
            }
            final String mime = type;
            inFlightKey = key;
            handler.post(() -> pushImage(uri, mime, key, timestamp));
            return;
        }

        CharSequence text = item.getText();
        if (text == null && uri == null) {
            try {
                text = item.coerceToText(appContext);
            } catch (RuntimeException e) {
                HermitLog.warning("Clipboard text could not be read: " + e);
                text = null;
            }
        }
        if (text == null || text.length() == 0) {
            if (uri != null) {
                showFilesNotice(); // a file (or other non-image content) copied on the device
            }
            rememberSeen(timestamp);
            return;
        }
        final String value = text.toString();
        final String key = textKey(value);
        if (key.equals(lastExchangedKey) || key.equals(refusedKey)) {
            rememberSeen(timestamp);
            return; // our own copy of host content, or text already sent (or refused)
        }
        if (key.equals(hostRefusedKey)) {
            hostRefusedClipTimestamp = timestamp;
            return;
        }
        if (key.equals(inFlightKey)) {
            return; // still being sent
        }
        inFlightKey = key;
        handler.post(() -> pushText(value, key, timestamp));
    }

    /** Worker thread: a device clip reached the host. */
    private void markSent(String key, long timestamp) {
        activity.runOnUiThread(() -> {
            rememberExchanged(key);
            rememberSeen(timestamp);
            clearInFlight(key);
        });
    }

    /**
     * Worker thread: a device clip cannot be sent at all (too large, unreadable), or an image
     * failed with a network error twice: it is not sent again on the next return to the stream,
     * nor on a later stream, until something else is copied.
     */
    private void markRefused(String key, long timestamp) {
        activity.runOnUiThread(() -> {
            refusedKey = key;
            rememberSeen(timestamp);
            clearInFlight(key);
        });
    }

    /**
     * Worker thread: this host refused a device clip (no images, over its limit): it is not sent
     * again during this stream, but a later stream (maybe to another host) tries it.
     */
    private void markRefusedByHost(String key, long timestamp) {
        activity.runOnUiThread(() -> {
            hostRefusedKey = key;
            hostRefusedClipTimestamp = timestamp;
            clearInFlight(key);
        });
    }

    /**
     * Worker thread: the host clipboard changed after a device clip was copied: the clip is not
     * sent, now or on a later return (the host's newer content wins).
     */
    private void markSuperseded(String key, long timestamp) {
        activity.runOnUiThread(() -> {
            rememberSeen(timestamp);
            clearInFlight(key);
        });
    }

    /** Worker thread: a device clip was not sent this time; it is tried again on the next return. */
    private void markNotSent(String key) {
        activity.runOnUiThread(() -> clearInFlight(key));
    }

    private void clearInFlight(String key) {
        if (key.equals(inFlightKey)) {
            inFlightKey = null; // a newer clip queued since stays in flight
        }
    }

    /** Whether the device clip is host content Hermit put there (by its label, or its timestamp). */
    private boolean isOwnClip(ClipDescription description, long timestamp) {
        return TextUtils.equals(OWN_CLIP_LABEL, description.getLabel())
                || (timestamp > 0 && timestamp >= ownClipFrom && timestamp <= ownClipTo);
    }

    /** Any thread: files are not synced; said once per stream. */
    private void showFilesNotice() {
        if (!filesNoticeShown.getAndSet(true)) {
            toast(R.string.hermit_clipboard_files_not_synced, HermitNotice.LONG);
        }
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
        if (!ClipboardChangeOrder.hostMayReplaceDevice(content.changeTime, latestDeviceCopy)) {
            HermitLog.info("Clipboard: host content not applied: newer content was copied on the device");
            return;
        }

        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        ClipData clip;
        if (content.text != null) {
            clip = ClipData.newPlainText(OWN_CLIP_LABEL, content.text);
        } else {
            clip = ClipData.newUri(appContext.getContentResolver(), OWN_CLIP_LABEL,
                    ClipboardImageProvider.uriFor(appContext, content.imageName));
        }
        // Android stamps the clip with System.currentTimeMillis() during this (synchronous) call
        long before = System.currentTimeMillis();
        try {
            cm.setPrimaryClip(clip);
        } catch (RuntimeException e) {
            HermitLog.warning("Clipboard: could not set the device clipboard: " + e);
            return;
        }
        ownClipFrom = before;
        ownClipTo = System.currentTimeMillis();
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
        // A setup that failed at stream start and succeeds now cannot tell whether the host's
        // clipboard changed in between: what it finds is a host change made when the last try
        // failed, so a device clip copied before that (waiting in deferredPush) does not
        // overwrite it, a device copy made after it still wins, and the next poll fetches the
        // host content while no such copy came. When the host did not change after all, its
        // content wins over the older clip (copy it again to send it), which is better than
        // overwriting a copy made on the host.
        boolean atStart = !setupTried;
        setupTried = true;
        if (!atStart) {
            HermitLog.info("Clipboard sync: setting up again after it failed at stream start");
        }
        long attempt = System.currentTimeMillis();
        try {
            String info = new String(http.hermitGetClipboard("info", 4096), StandardCharsets.UTF_8);
            long seq = parseLong(info, "seq");
            if (seq >= 0) {
                // Shell: remember the current host state but do not copy it (at stream start);
                // only changes made during the stream come back to the device.
                mode = Mode.EXTENDED;
                order.hostSetUp(seqKey(seq), atStart, lastFailedSetup);
                HermitLog.info("Clipboard sync: host supports text and images");
                schedulePoll(EXTENDED_POLL_MS);
                runDeferredPush();
                return;
            }
            mode = Mode.LEGACY;
        } catch (HostHttpResponseException e) {
            if (e.getErrorCode() == 401) {
                // Reading is refused, which hides what the host supports; sending may still be
                // allowed. Assume Shell (a text send works with any host) and fall back to text
                // only if the host refuses an image as an unknown type.
                handleReadError(e);
                mode = Mode.EXTENDED;
                modeAssumed = true;
                runDeferredPush();
                return;
            }
            if (!handleReadError(e)) {
                return;
            }
            if (e.getErrorCode() != 400) {
                // e.g. 403 before the host registered the stream: try again later
                lastFailedSetup = attempt;
                schedulePoll(LEGACY_POLL_MS);
                return;
            }
            mode = Mode.LEGACY;
        } catch (IOException e) {
            HermitLog.warning("Clipboard sync setup failed: " + e);
            lastFailedSetup = attempt;
            schedulePoll(LEGACY_POLL_MS);
            return;
        }

        HermitLog.info("Clipboard sync: host supports text only");
        try {
            byte[] text = http.hermitGetClipboard("text", MAX_TEXT_BYTES);
            order.hostSetUp(textKey(new String(text, StandardCharsets.UTF_8)), atStart, lastFailedSetup);
        } catch (HostHttpResponseException e) {
            if (e.getErrorCode() == 413) {
                // At stream start: there before the stream, nothing to say
                order.hostSetUp(LEGACY_TOO_LARGE, atStart, lastFailedSetup);
            } else {
                handleReadError(e);
            }
        } catch (IOException e) {
            HermitLog.warning("Clipboard: could not read the host text: " + e);
        }
        if (mode == Mode.LEGACY && !readDenied) {
            schedulePoll(LEGACY_POLL_MS);
        }
        runDeferredPush();
    }

    private void runDeferredPush() {
        Runnable push = deferredPush;
        deferredPush = null;
        if (push != null) {
            push.run();
        }
    }

    // One poll chain: init() also runs from a send (ensureReady) while a poll is waiting, and
    // its retry must not start a second chain
    private final Runnable pollTask = this::poll;

    private void schedulePoll(int delayMs) {
        if (!stopped) {
            handler.removeCallbacks(pollTask);
            handler.postDelayed(pollTask, delayMs);
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
        if (mode == Mode.DISABLED || readDenied) {
            return;
        }
        try {
            if (mode == Mode.EXTENDED) {
                pollExtended();
            } else {
                pollLegacy();
            }
        } catch (HostHttpResponseException e) {
            if (!handleReadError(e)) {
                return;
            }
        } catch (IOException e) {
            // Network hiccup: keep polling
        }
        schedulePoll(mode == Mode.EXTENDED ? EXTENDED_POLL_MS : LEGACY_POLL_MS);
    }

    private void pollExtended() throws IOException {
        // A host change counts from when this request was issued, not from when it returned
        // (which a slow reply would make later than a device copy made meanwhile)
        long requested = System.currentTimeMillis();
        String info = new String(http.hermitGetClipboard("info", 4096), StandardCharsets.UTF_8);
        long seq = parseLong(info, "seq");
        if (seq < 0) {
            return;
        }
        switch (order.hostSeen(seqKey(seq), requested, latestDeviceCopy)) {
            case UNCHANGED:
                return;
            case SUPERSEDED:
                HermitLog.info("Clipboard: host content not fetched again: newer content was copied on the device");
                return;
            default:
                break;
        }
        String type = parseField(info, "type");

        // The host clipboard changed: whatever was fetched before is no longer what the user
        // copied there last, so it is replaced, or dropped when nothing usable comes back.
        // A fetch that failed on the way is tried again on the next poll (hostRetry) unless the
        // user copied something on the device since: after a network hiccup a few times at most,
        // an image once more (hostFetchFailed), while the host clipboard is busy (503) for a
        // while longer (hostBusy).
        boolean image = "image".equals(type);
        HostContent content = null;
        File imageFile = null;
        try {
            if ("text".equals(type)) {
                byte[] data = http.hermitGetClipboard("text", MAX_TEXT_BYTES);
                if (data.length > 0) {
                    String text = new String(data, StandardCharsets.UTF_8);
                    content = new HostContent(text, null, textKey(text), order.hostTime());
                }
            } else if (image) {
                byte[] png = http.hermitGetClipboard("image", MAX_IMAGE_BYTES);
                if (png.length > 0) {
                    String name = "host-" + System.currentTimeMillis() + "-" + seq + ".png";
                    File dir = ClipboardImageProvider.directory(appContext);
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IOException("Cannot create " + dir);
                    }
                    imageFile = new File(dir, name);
                    try (FileOutputStream out = new FileOutputStream(imageFile)) {
                        out.write(png);
                    }
                    content = new HostContent(null, name, "hostimage:" + seq + ":" + png.length,
                            order.hostTime());
                    pruneImages(dir);
                }
            } else if ("files".equals(type)) {
                // Nothing this device can paste, and older text must not be applied instead
                showFilesNotice();
            }
        } catch (HostHttpResponseException e) {
            pending = null;
            switch (e.getErrorCode()) {
                case 413:
                    reportHostTooLarge();
                    return;
                case 422:
                    if (IMAGE_NOT_CONVERTIBLE.equals(e.getErrorMessage())) {
                        // Fails the same way however often it is fetched: drop it
                        HermitLog.warning("Clipboard: the host could not convert its clipboard image");
                        reportHostImageNotConvertible();
                        return;
                    }
                    break;
                case 503:
                    hostBusy(seq, image, e);
                    return;
                default:
                    break;
            }
            hostBusyAnswers = 0;
            if (e.getErrorCode() != 401 && e.getErrorCode() != 404) {
                hostFetchFailed(seq, image, e); // 401 and 404 stop polling instead
            }
            throw e;
        } catch (IOException e) {
            pending = null;
            hostBusyAnswers = 0;
            if (imageFile != null) {
                // Partly written (storage full, ...): nothing points at it
                //noinspection ResultOfMethodCallIgnored
                imageFile.delete();
            }
            hostFetchFailed(seq, image, e);
            throw e;
        }
        pending = content;
    }

    /**
     * Fetching host clipboard item seq failed. A timeout (the host taking too long to encode a
     * large image) or a third failure in a row (for an image, a second) gives up on it until the
     * host clipboard changes again, instead of fetching it (and the host encoding it) every
     * second for the rest of the stream. Otherwise it is fetched again on the next poll.
     */
    private void hostFetchFailed(long seq, boolean image, IOException e) {
        if (seq != failedHostSeq) {
            failedHostSeq = seq;
            hostFetchFailures = 0;
        }
        hostFetchFailures++;
        int maxFailures = image ? MAX_HOST_IMAGE_FETCH_FAILURES : MAX_HOST_FETCH_FAILURES;
        if (!(e instanceof SocketTimeoutException) && hostFetchFailures < maxFailures) {
            order.hostRetry(seqKey(seq));
            return;
        }
        HermitLog.warning("Clipboard: gave up on the host clipboard item after " + hostFetchFailures
                + " failed fetch(es): " + e);
        if (!reportedHostFetchFailed) {
            reportedHostFetchFailed = true;
            toast(R.string.hermit_clipboard_host_fetch_failed, HermitNotice.LONG);
        }
    }

    /**
     * The host clipboard was busy (another program holding it open) while item seq was fetched.
     * It is fetched again on the next poll without counting as a failure, unless the host stays
     * busy for more than MAX_HOST_BUSY_ANSWERS polls in a row: from then on each busy answer
     * counts as a failed fetch, so the item is given up on (hostFetchFailed) instead of fetched
     * every second for the rest of the stream.
     */
    private void hostBusy(long seq, boolean image, HostHttpResponseException e) {
        if (seq != busyHostSeq) {
            busyHostSeq = seq;
            hostBusyAnswers = 0;
        }
        if (hostBusyAnswers == 0) {
            HermitLog.info("Clipboard: the host clipboard is busy; fetching it again");
        }
        if (++hostBusyAnswers > MAX_HOST_BUSY_ANSWERS) {
            hostFetchFailed(seq, image, e);
        } else {
            order.hostRetry(seqKey(seq));
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
        long requested = System.currentTimeMillis(); // as in pollExtended
        byte[] data;
        try {
            data = http.hermitGetClipboard("text", MAX_TEXT_BYTES);
        } catch (HostHttpResponseException e) {
            if (e.getErrorCode() != 413) {
                throw e;
            }
            // The host text changed to something too large: the older text is not applied
            if (order.hostSeen(LEGACY_TOO_LARGE, requested, latestDeviceCopy)
                    == ClipboardChangeOrder.HostContent.FETCH) {
                pending = null;
                reportHostTooLarge();
            }
            return;
        }
        if (data.length == 0) {
            return;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String key = textKey(text);
        // The text comes with the check, so nothing is ever fetched again here
        if (order.hostSeen(key, requested, latestDeviceCopy)
                == ClipboardChangeOrder.HostContent.FETCH) {
            pending = new HostContent(text, null, key, order.hostTime());
        }
    }

    private void reportHostTooLarge() {
        if (!reportedHostTooLarge) {
            reportedHostTooLarge = true;
            toast(R.string.hermit_clipboard_host_too_large, HermitNotice.LONG);
        }
    }

    private void reportHostImageNotConvertible() {
        if (!reportedHostImageNotConvertible) {
            reportedHostImageNotConvertible = true;
            toast(R.string.hermit_clipboard_host_image_not_convertible, HermitNotice.LONG);
        }
    }

    private void pushText(String text, String key, long timestamp) {
        if (stopped) {
            return;
        }
        if (!ensureReady()) {
            if (mode == Mode.UNKNOWN) {
                deferredPush = () -> pushText(text, key, timestamp); // stays in flight until then
            } else {
                markNotSent(key);
            }
            return;
        }
        if (!order.deviceMayReplaceHost(timestamp)) {
            HermitLog.info("Clipboard text not sent: newer content was copied on the host");
            markSuperseded(key, timestamp);
            return;
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_TEXT_BYTES) {
            reportSendFailure(key, R.string.hermit_clipboard_too_large);
            markRefused(key, timestamp);
            return;
        }
        try {
            String reply = http.hermitSetClipboard("text", data, "text/plain; charset=utf-8");
            recordSent(reply, key, timestamp);
            markSent(key, timestamp);
            toast(R.string.hermit_clipboard_sent_text, HermitNotice.SHORT);
        } catch (HostHttpResponseException e) {
            handleSendError(e, key, timestamp, false);
        } catch (IOException e) {
            HermitLog.warning("Clipboard text not sent: " + e);
            reportSendFailure(key, R.string.hermit_clipboard_send_failed); // tried again next time
            markNotSent(key);
        }
    }

    private void pushImage(Uri uri, String mime, String key, long timestamp) {
        if (stopped) {
            return;
        }
        if (!ensureReady()) {
            if (mode == Mode.UNKNOWN) {
                deferredPush = () -> pushImage(uri, mime, key, timestamp); // stays in flight until then
            } else {
                markNotSent(key);
            }
            return;
        }
        if (mode != Mode.EXTENDED) {
            HermitLog.info("Clipboard images need a Shell host; only text is synced");
            reportSendFailure(key, R.string.hermit_clipboard_images_need_shell);
            markRefusedByHost(key, timestamp);
            return;
        }
        if (!order.deviceMayReplaceHost(timestamp)) {
            HermitLog.info("Clipboard image not sent: newer content was copied on the host");
            markSuperseded(key, timestamp);
            return;
        }
        byte[] png;
        try {
            png = readAsPng(appContext.getContentResolver(), uri, mime);
        } catch (IOException | RuntimeException e) {
            // RuntimeException: a SecurityException, or whatever a faulty content provider throws
            HermitLog.warning("Clipboard image could not be read: " + e);
            reportSendFailure(key, R.string.hermit_clipboard_image_unreadable);
            markRefused(key, timestamp);
            return;
        } catch (OutOfMemoryError e) {
            HermitLog.warning("Clipboard image too large to convert: " + e);
            reportSendFailure(key, R.string.hermit_clipboard_too_large);
            markRefused(key, timestamp);
            return;
        }
        if (png == null || png.length > MAX_IMAGE_BYTES) {
            reportSendFailure(key, R.string.hermit_clipboard_too_large);
            markRefused(key, timestamp);
            return;
        }
        // Only the header is decoded: the host takes at most 8192x8192 pixels
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            HermitLog.warning("Clipboard image could not be read: not a PNG");
            reportSendFailure(key, R.string.hermit_clipboard_image_unreadable);
            markRefused(key, timestamp);
            return;
        }
        if ((long) bounds.outWidth * bounds.outHeight > MAX_HOST_IMAGE_PIXELS) {
            reportSendFailure(key, R.string.hermit_clipboard_image_too_many_pixels);
            markRefused(key, timestamp);
            return;
        }
        try {
            String reply = http.hermitSetClipboard("image", png, "image/png");
            recordSent(reply, null, timestamp);
            modeAssumed = false;
            markSent(key, timestamp);
            toast(R.string.hermit_clipboard_sent_image, HermitNotice.SHORT);
        } catch (HostHttpResponseException e) {
            handleSendError(e, key, timestamp, true);
        } catch (IOException e) {
            // A network error (a timeout, a connection refused or reset, an upload cut off) may
            // be passing: sent once more on the next return, unless the host clipboard changes
            // first. After a second one it is not sent again until it is copied again, rather
            // than read again (Android's paste notice) and uploaded again (up to 32 MB) on every
            // return, as in Hermit for Windows. The stream ending (stop() aborts the send) is not
            // such an error: the image stays unsent for a later stream.
            if (stopped) {
                markNotSent(key);
            } else if (!key.equals(networkErrorKey)) {
                networkErrorKey = key;
                HermitLog.warning("Clipboard image not sent: " + e);
                reportSendFailure(key, R.string.hermit_clipboard_send_failed); // tried again next time
                markNotSent(key);
            } else {
                HermitLog.warning("Clipboard image not sent again: " + e + "; not sent again until it is copied again");
                markRefused(key, timestamp);
            }
        }
    }

    /** False when nothing can be sent now: the host is not ready yet, has no endpoint or refuses. */
    private boolean ensureReady() {
        if (mode == Mode.UNKNOWN) {
            init();
        }
        return (mode == Mode.EXTENDED || mode == Mode.LEGACY) && !writeDenied;
    }

    /** A failed GET. Returns false if polling stops for the rest of the stream. */
    private boolean handleReadError(HostHttpResponseException e) {
        switch (e.getErrorCode()) {
            case 401:
                // The host did not give this device the permission to read its clipboard;
                // sending may still be allowed (a separate permission)
                if (!readDenied) {
                    readDenied = true;
                    HermitLog.info("Clipboard sync: the host does not allow reading its clipboard");
                    toast(R.string.hermit_clipboard_read_denied, HermitNotice.LONG);
                }
                return false;
            case 404:
                // The host has no clipboard endpoint.
                mode = Mode.DISABLED;
                HermitLog.info("Clipboard sync: host has no clipboard endpoint");
                return false;
            case 503:
                // The host clipboard is busy (another program holding it open): the next poll
                // asks again, so it is not reported every second
                return true;
            default:
                // 413 is handled where content is fetched; a legacy host would report anything
                // else on every poll, so it is only logged
                HermitLog.warning("Clipboard check failed: HTTP " + e.getErrorCode());
                return true;
        }
    }

    /** A failed POST of a device clip. */
    private void handleSendError(HostHttpResponseException e, String key, long timestamp, boolean image) {
        int code = e.getErrorCode();
        HermitLog.warning("Clipboard " + (image ? "image" : "text") + " not sent: HTTP " + code);
        switch (code) {
            case 401:
                // The host did not give this device the permission to set its clipboard;
                // host changes still come back if reading is allowed
                if (!writeDenied) {
                    writeDenied = true;
                    toast(R.string.hermit_clipboard_write_denied, HermitNotice.LONG);
                }
                markNotSent(key);
                return;
            case 404:
                mode = Mode.DISABLED;
                HermitLog.info("Clipboard sync: host has no clipboard endpoint");
                markNotSent(key);
                return;
            case 413:
                // Larger than this host takes (Shell: 32 MB or 8192x8192 pixels for an image)
                reportSendFailure(key, R.string.hermit_clipboard_too_large);
                markRefusedByHost(key, timestamp);
                return;
            case 400:
                if (image && modeAssumed) {
                    // The guess was wrong: a host without the image extension (text only)
                    mode = Mode.LEGACY;
                    modeAssumed = false;
                    reportSendFailure(key, R.string.hermit_clipboard_images_need_shell);
                    markRefusedByHost(key, timestamp);
                    return;
                }
                break;
            case 500:
                if (image) {
                    // Often transient (the host clipboard held open by another process), but an
                    // image the host cannot take at all fails the same way on every return, each
                    // time read again (Android's paste notice) and uploaded again
                    if (!key.equals(serverErrorKey)) {
                        serverErrorKey = key;
                        serverErrors = 0;
                    }
                    if (++serverErrors >= MAX_IMAGE_SERVER_ERRORS) {
                        reportSendFailure(key, R.string.hermit_clipboard_send_failed);
                        markRefusedByHost(key, timestamp);
                        return;
                    }
                }
                break;
            case 503:
                // The host clipboard is busy (another program holding it open): not refused and
                // not counted as an error of this image, only tried again on the next return
                break;
            default:
                break;
        }
        // Anything else (403 while the host sets up the stream, 503 while the host clipboard is
        // busy, 500 the first time an image could not be set, ...): said once for this clip and
        // tried again on the next return to the stream
        reportSendFailure(key, R.string.hermit_clipboard_send_failed);
        markNotSent(key);
    }

    /** A device clip could not be sent: said once per clip, however often it is tried. */
    private void reportSendFailure(String key, int messageRes) {
        if (key.equals(failureReportedKey)) {
            return;
        }
        failureReportedKey = key;
        toast(messageRes, HermitNotice.LONG);
    }

    /**
     * A device clip with this timestamp reached the host (textKey: its text's key, null for an
     * image): what the host had before is older.
     */
    private void recordSent(String reply, String textKey, long timestamp) {
        long seq = parseLong(reply, "seq");
        if (seq >= 0) {
            order.deviceSent(true, seqKey(seq), timestamp); // our own write: do not fetch it back
        } else {
            // A text-only host checks its text: ours is not fetched back either
            order.deviceSent(mode == Mode.LEGACY && textKey != null, textKey, timestamp);
        }
        // Host content fetched by an earlier poll (the worker runs one thing at a time) is not
        // put over the device copy at the next focus loss
        pending = null;
    }

    private void toast(int messageRes, int length) {
        if (stopped) {
            return;
        }
        activity.runOnUiThread(() -> HermitNotice.show(appContext, messageRes, length));
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static byte[] readAsPng(ContentResolver resolver, Uri uri, String mime) throws IOException {
        byte[] data;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
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
        if (isPng(data)) {
            return data;
        }
        // The host clipboard takes PNG; convert anything else (JPEG screenshots, WebP, ...),
        // whatever the MIME type says: Shell answers 500 for bytes that are not PNG.
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
        // BitmapFactory ignores the EXIF orientation, so a portrait camera photo would arrive
        // on its side
        Matrix orientation = exifOrientation(data);
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) {
            throw new IOException("Unsupported image format: " + mime);
        }
        try {
            if (orientation != null) {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                        orientation, true);
                if (oriented != bitmap) {
                    bitmap.recycle();
                    bitmap = oriented;
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            return png.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * The rotation or flip the EXIF orientation of an image asks for, or null if none (or the
     * orientation cannot be read: below Android 7 it is not, and the image is sent as stored).
     */
    private static Matrix exifOrientation(byte[] data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        int orientation;
        try {
            orientation = new ExifInterface(new ByteArrayInputStream(data))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            HermitLog.warning("Clipboard image orientation could not be read: " + e);
            return null;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return null;
        }
        return matrix;
    }

    private static boolean isPng(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static String seqKey(long seq) {
        return "seq:" + seq;
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
