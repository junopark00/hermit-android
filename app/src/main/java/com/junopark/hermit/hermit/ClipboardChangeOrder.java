package com.junopark.hermit.hermit;

/**
 * Hermit: when both clipboards changed, the most recent change wins (as in Hermit for Windows).
 * Host content that could not be fetched (the host was busy, a network error) is fetched again
 * until something newer replaces it, and only then.
 *
 * Changes are put in order by wall-clock time, System.currentTimeMillis(), the time base of
 * ClipDescription.getTimestamp():
 * - a device copy at its clip timestamp (Android 8.0+). It is only seen when the stream window
 *   gets focus again, but the timestamp says when it was made. Clips Hermit put on the device
 *   clipboard itself are not device copies;
 * - a host change (a new host clipboard sequence number; for text-only hosts, new text) at the
 *   time the request of the poll that first found it was issued: at most a poll interval after it
 *   was made, or as long as a transfer in progress (a poll waits behind it on the worker);
 * - a device clip sent to the host takes the time of its clip timestamp;
 * - host content found when sync is set up is not a change only at stream start; a setup that
 *   succeeds later takes it for a host change made when the last setup try failed (hostSetUp).
 *
 * So:
 * - a device clip is sent only while no host change was found after it (deviceMayReplaceHost);
 *   otherwise it is dropped and the host's newer content wins;
 * - host content is fetched (again) only while no device copy came after it (hostSeen), and put
 *   on the device clipboard only then (hostMayReplaceDevice); otherwise it is dropped and the
 *   device copy wins, and is sent.
 *
 * A device clip without a timestamp (Android 7 and earlier, time 0 or less) cannot be placed: it
 * is never held back, and does not hold host content back.
 *
 * The clipboard worker keeps one of these for the host side and runs its jobs one at a time, so
 * the state needs no lock; only hostTime() is also read on the UI thread. The UI thread keeps the
 * time of the latest device copy and hands it to every poll.
 */
final class ClipboardChangeOrder {
    enum HostContent {
        UNCHANGED,   // already fetched, given up on, or replaced by a device clip we sent
        SUPERSEDED,  // waiting to be fetched again, but a device copy came after it: dropped now
        FETCH,       // new, or waiting and still the latest change: fetch it
    }

    private boolean hostKnown;
    private String hostKey;
    private volatile long hostTime;
    private boolean hostPending;

    /**
     * The host holds content it held already when sync started: known, but not a change, so it
     * is not fetched.
     */
    void hostRecorded(String key) {
        hostKnown = true;
        hostKey = key;
        hostPending = false;
    }

    /**
     * Sync was set up and found host content key. Only the setup at stream start records it as
     * the host's content before the stream (hostRecorded). A setup that failed then and succeeds
     * later cannot tell whether the host's clipboard changed in between (the user copied there
     * during the stream), so the content is a host change made at lastFailedSetup, when the last
     * setup try that failed was started: a device clip copied before that (waiting to be sent
     * since) no longer replaces it, a device copy made after it does, and the next poll fetches
     * the host content while no such device copy came. The trade-off: when the host did not
     * change after all, its older content wins over that older device clip, which is then not
     * sent (copy it again).
     */
    void hostSetUp(String key, boolean atStart, long lastFailedSetup) {
        if (atStart) {
            hostRecorded(key);
            return;
        }
        hostKnown = true;
        hostKey = key;
        hostTime = Math.max(hostTime, lastFailedSetup);
        hostPending = true;
    }

    /**
     * A poll whose request was issued at time now found host content key (its clipboard sequence
     * number, or for text-only hosts its text), the latest device copy having been made at
     * latestDeviceCopy. FETCH also takes the content: call hostRetry when it could not be fetched
     * in a way that may pass.
     */
    HostContent hostSeen(String key, long now, long latestDeviceCopy) {
        if (!hostKnown || !key.equals(hostKey)) {
            hostKnown = true;
            hostKey = key;
            hostTime = Math.max(hostTime, now);
            hostPending = false;
            return HostContent.FETCH;
        }
        if (!hostPending) {
            return HostContent.UNCHANGED;
        }
        hostPending = false;
        return hostMayReplaceDevice(hostTime, latestDeviceCopy) ? HostContent.FETCH : HostContent.SUPERSEDED;
    }

    /**
     * Host content key could not be fetched (in a way that may pass): fetched again on a later
     * poll, unless newer host content was seen (or sent) meanwhile or a device copy comes first.
     */
    void hostRetry(String key) {
        if (hostKnown && key.equals(hostKey)) {
            hostPending = true;
        }
    }

    /**
     * A device clip with timestamp deviceTime is now on the host. keyKnown: the host said under
     * which key (its sequence number), so a later poll does not fetch it back. Host content
     * waiting to be fetched again is gone from the host.
     */
    void deviceSent(boolean keyKnown, String key, long deviceTime) {
        if (keyKnown) {
            hostKnown = true;
            hostKey = key;
        }
        hostPending = false;
        hostTime = Math.max(hostTime, deviceTime);
    }

    /**
     * Whether a device clip with timestamp deviceTime may still replace the host's content: no
     * host change (nor a newer device clip) reached the host after it. Any thread.
     */
    boolean deviceMayReplaceHost(long deviceTime) {
        return deviceTime <= 0 || deviceTime >= hostTime;
    }

    /**
     * Whether host content of a change at hostTime may still replace the device clipboard, the
     * latest device copy having been made at latestDeviceCopy (0 or less: none known).
     */
    static boolean hostMayReplaceDevice(long hostTime, long latestDeviceCopy) {
        return hostTime > latestDeviceCopy;
    }

    /** The time of the change the host's content came from (0 before any). */
    long hostTime() {
        return hostTime;
    }
}
