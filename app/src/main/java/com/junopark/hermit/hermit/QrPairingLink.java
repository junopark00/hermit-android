package com.junopark.hermit.hermit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A Shell QR pairing link (notes/DESIGN-I-qr-pairing.md, section 3 and 6.1):
 *
 * <pre>hermit://pair?v=1&amp;host=&lt;host&gt;&amp;port=&lt;port&gt;&amp;fp=&lt;64 hex&gt;&amp;token=&lt;26 base32&gt;&amp;name=&lt;name&gt;</pre>
 *
 * The token is the one-time 128-bit secret that takes the PIN's place in the pairing key
 * derivation; it never goes on the wire. The fingerprint is the SHA-256 of the host's certificate
 * (DER), checked as soon as the host sends the certificate. The id, derived here from the token,
 * is what the client sends on plain HTTP to name the QR record. Plain Java, so it can be tested on
 * the JVM. The token is kept out of {@link #toString()} and must never be logged.
 */
public final class QrPairingLink {
    private static final String SCHEME = "hermit://";
    private static final String PATH = "pair";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TOKEN = Pattern.compile("[A-Z2-7]{26}");
    private static final String ID_PREFIX = "hermit-qr-id";
    private static final int ID_HEX_LENGTH = 16;

    /** Why a string is not a usable link. */
    public enum Problem {
        /** Not a hermit://pair link, or a required key is missing or malformed. */
        NOT_A_LINK,
        /** A hermit://pair link of a version this client does not know. */
        UNSUPPORTED_VERSION
    }

    public static final class InvalidLinkException extends Exception {
        public final Problem problem;

        InvalidLinkException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    /** IPv4, bracketed IPv6 or host name, as written in the link. */
    public final String host;
    public final int port;
    /** SHA-256 of the host certificate's DER, 64 lowercase hex digits. */
    public final String fingerprint;
    /** 26 uppercase base32 characters; used as the pairing PIN string. Never log it. */
    public final String token;
    /** SHA-256("hermit-qr-id" + token), first 16 lowercase hex digits; names the QR record on the host. */
    public final String id;
    /** The host's display name from the link, or null. Display only. */
    public final String name;

    private QrPairingLink(String host, int port, String fingerprint, String token, String name) {
        this.host = host;
        this.port = port;
        this.fingerprint = fingerprint;
        this.token = token;
        this.id = deriveId(token);
        this.name = name;
    }

    /** Whether the text, once trimmed, starts like a pairing link (case-insensitive scheme). */
    public static boolean looksLikeLink(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.length() > SCHEME.length() + PATH.length()
                && t.substring(0, SCHEME.length()).equalsIgnoreCase(SCHEME)
                && t.regionMatches(SCHEME.length(), PATH + "?", 0, PATH.length() + 1);
    }

    /**
     * Parses and validates a link. Surrounding whitespace is ignored, the scheme is matched without
     * regard to case, fp is normalized to lowercase and token to uppercase, unknown keys are ignored
     * and the first occurrence of a repeated key wins.
     */
    public static QrPairingLink parse(String text) throws InvalidLinkException {
        if (text == null) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String t = text.trim();
        if (t.length() <= SCHEME.length() || !t.substring(0, SCHEME.length()).equalsIgnoreCase(SCHEME)) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String rest = t.substring(SCHEME.length());
        int q = rest.indexOf('?');
        if (q < 0) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String path = rest.substring(0, q);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.equals(PATH)) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String query = rest.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }

        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = percentDecode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : percentDecode(pair.substring(eq + 1));
            if (key != null && value != null && !params.containsKey(key)) {
                params.put(key, value);
            }
        }

        String v = params.get("v");
        if (v == null) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        if (!v.equals("1")) {
            throw new InvalidLinkException(Problem.UNSUPPORTED_VERSION);
        }

        String host = params.get("host");
        if (host == null || host.isEmpty() || host.indexOf('%') >= 0) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        int port = parsePort(params.get("port"));
        if (port < 1 || port > 65535) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String fp = params.get("fp");
        if (fp == null) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        fp = fp.toLowerCase(Locale.ROOT);
        if (!FINGERPRINT.matcher(fp).matches()) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String token = params.get("token");
        if (token == null) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        token = token.toUpperCase(Locale.ROOT);
        if (!TOKEN.matcher(token).matches()) {
            throw new InvalidLinkException(Problem.NOT_A_LINK);
        }
        String name = params.get("name");
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) {
                name = null;
            }
        }
        return new QrPairingLink(host, port, fp, token, name);
    }

    private static int parsePort(String s) {
        if (s == null || s.isEmpty() || s.length() > 5) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(s);
    }

    /** RFC 3986 percent-decoding (a '+' stays a '+'); null for a malformed escape or invalid UTF-8. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 3;
            }
            else {
                // A run of literal characters, as a whole so surrogate pairs stay together
                int j = s.indexOf('%', i);
                if (j < 0) {
                    j = s.length();
                }
                byte[] utf8 = s.substring(i, j).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
                i = j;
            }
        }
        String decoded = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return decoded.indexOf('�') >= 0 ? null : decoded;
    }

    /** SHA-256("hermit-qr-id" || token ASCII), hex, first 16 characters, lowercase. */
    static String deriveId(String token) {
        return toHex(sha256((ID_PREFIX + token).getBytes(StandardCharsets.US_ASCII))).substring(0, ID_HEX_LENGTH);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Lowercase hex. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** The host's refusal reasons, from the QR_<reason> prefix of a /pair status_message. */
    public enum Refusal {
        NONE("none"), NETWORK("network"), EXPIRED("expired"), USED("used");

        final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }
    }

    /** The refusal a status_message names ("QR_used: ..."), or null for any other message. */
    public static Refusal refusalOf(String statusMessage) {
        if (statusMessage == null || !statusMessage.startsWith("QR_")) {
            return null;
        }
        int colon = statusMessage.indexOf(':');
        String reason = (colon < 0 ? statusMessage : statusMessage.substring(0, colon)).substring(3).trim();
        for (Refusal r : Refusal.values()) {
            if (r.reason.equals(reason)) {
                return r;
            }
        }
        return null;
    }

    /** host:port and the name: what may go to the log. */
    @Override
    public String toString() {
        return host + ":" + port + (name != null ? " (" + name + ")" : "");
    }
}
