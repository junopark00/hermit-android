package com.junopark.hermit.nvstream.http;

import android.os.Build;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.junopark.hermit.BuildConfig;
import com.junopark.hermit.HermitLog;
import com.junopark.hermit.nvstream.ConnectionContext;
import com.junopark.hermit.nvstream.http.PairingManager.PairState;
import com.junopark.hermit.nvstream.jni.MoonBridge;

import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class NvHTTP {
    private String uniqueId;
    private PairingManager pm;

    private static final int DEFAULT_HTTPS_PORT = 47984;
    public static final int DEFAULT_HTTP_PORT = 47989;
    public static final int SHORT_CONNECTION_TIMEOUT = 3000;
    public static final int LONG_CONNECTION_TIMEOUT = 5000;
    public static final int READ_TIMEOUT = 7000;

    // Print URL and content to logcat on debug builds
    private static boolean verbose = BuildConfig.DEBUG;

    private HttpUrl baseUrlHttp;

    private int httpsPort;
    
    private OkHttpClient httpClientLongConnectTimeout;
    private OkHttpClient httpClientLongConnectNoReadTimeout;
    private OkHttpClient httpClientShortConnectTimeout;

    private X509TrustManager defaultTrustManager;
    private X509TrustManager trustManager;
    private X509KeyManager keyManager;
    private X509Certificate serverCert;

    void setServerCert(X509Certificate serverCert) {
        this.serverCert = serverCert;
    }

    private static X509TrustManager getDefaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (KeyStoreException e) {
            throw new RuntimeException(e);
        }

        throw new IllegalStateException("No X509 trust manager found");
    }

    private void initializeHttpState(final ClientCryptoProvider cryptoProvider) {
        keyManager = new X509KeyManager() {
            public String chooseClientAlias(String[] keyTypes,
                    Principal[] issuers, Socket socket) { return "Hermit-RSA"; }
            public String chooseServerAlias(String keyType, Principal[] issuers,
                    Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[] {cryptoProvider.getClientCertificate()};
            }
            public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            public PrivateKey getPrivateKey(String alias) {
                return cryptoProvider.getClientPrivateKey();
            }
            public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        };

        defaultTrustManager = getDefaultTrustManager();
        trustManager = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
                throw new IllegalStateException("Should never be called");
            }
            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                try {
                    // Try the default trust manager first to allow pairing with certificates
                    // that chain up to a trusted root CA. This will raise CertificateException
                    // if the certificate is not trusted (expected for the host's self-signed certs).
                    defaultTrustManager.checkServerTrusted(certs, authType);
                } catch (CertificateException e) {
                    // Check the server certificate if we've paired to this host
                    if (certs.length == 1 && NvHTTP.this.serverCert != null) {
                        if (!certs[0].equals(NvHTTP.this.serverCert)) {
                            throw new CertificateException("Certificate mismatch");
                        }
                    }
                    else {
                        // The cert chain doesn't look like a self-signed cert or we don't have
                        // a certificate pinned, so re-throw the original validation error.
                        throw e;
                    }
                }
            }
        };

        HostnameVerifier hv = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                try {
                    Certificate[] certificates = session.getPeerCertificates();
                    if (certificates.length == 1 && certificates[0].equals(NvHTTP.this.serverCert)) {
                        // Allow any hostname if it's our pinned cert
                        return true;
                    }
                } catch (SSLPeerUnverifiedException e) {
                    e.printStackTrace();
                }

                // Fall back to default HostnameVerifier for validating CA-issued certs
                return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session);
            }
        };

        httpClientLongConnectTimeout = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .hostnameVerifier(hv)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .connectTimeout(LONG_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .proxy(Proxy.NO_PROXY)
                .fastFallback(false)
                .build();

        httpClientShortConnectTimeout = httpClientLongConnectTimeout.newBuilder()
                .connectTimeout(SHORT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public HttpUrl getHttpsUrl(boolean likelyOnline) throws IOException {
        if (httpsPort == 0) {
            // Fetch the HTTPS port if we don't have it already
            httpsPort = getHttpsPort(openHttpConnectionToString(likelyOnline ? httpClientLongConnectTimeout : httpClientShortConnectTimeout,
                    baseUrlHttp, "serverinfo"));
        }

        return new HttpUrl.Builder().scheme("https").host(baseUrlHttp.host()).port(httpsPort).build();
    }
    
    public NvHTTP(ComputerDetails.AddressTuple address, int httpsPort, String uniqueId, X509Certificate serverCert, ClientCryptoProvider cryptoProvider) throws IOException {
        // Use the same UID as other GameStream clients so we can quit games
        // started by them.
        this.uniqueId = "0123456789ABCDEF";

        this.serverCert = serverCert;

        initializeHttpState(cryptoProvider);

        this.httpsPort = httpsPort;

        try {
            // If this is an IPv4-mapped IPv6 address, OkHTTP will choke on it if it's
            // in IPv6 form, because InetAddress.getByName() will return an Inet4Address
            // for what OkHTTP thinks is an IPv6 address. Normalize it into IPv4 form
            // to avoid triggering this bug.
            String addressString = address.address;
            if (addressString.contains(":") && addressString.contains(".")) {
                InetAddress addr = InetAddress.getByName(addressString);
                if (addr instanceof Inet4Address) {
                    addressString = ((Inet4Address)addr).getHostAddress();
                }
            }

            this.baseUrlHttp = new HttpUrl.Builder()
                    .scheme("http")
                    .host(addressString)
                    .port(address.port)
                    .build();
        } catch (IllegalArgumentException e) {
            // Encapsulate IllegalArgumentException into IOException for callers to handle more easily
            throw new IOException(e);
        }

        this.pm = new PairingManager(this, cryptoProvider);
    }

    static String getXmlString(Reader r, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);
        int eventType = xpp.getEventType();
        Stack<String> currentTag = new Stack<String>();
        
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
            case (XmlPullParser.START_TAG):
                if (xpp.getName().equals("root")) {
                    verifyResponseStatus(xpp);
                }
                currentTag.push(xpp.getName());
                break;
            case (XmlPullParser.END_TAG):
                currentTag.pop();
                break;
            case (XmlPullParser.TEXT):
                if (currentTag.peek().equals(tagname)) {
                    return xpp.getText();
                }
                break;
            }
            eventType = xpp.next();
        }

        if (throwIfMissing) {
            // We throw an XmlPullParserException here for ease of handling in all the various callers.
            // We could also throw an IOException, but some callers expect those in cases where the
            // host may not be reachable. We want to distinguish unreachable hosts vs. hosts that
            // are returning garbage XML to us, so we use XmlPullParserException instead.
            throw new XmlPullParserException("Missing mandatory field in host response: "+tagname);
        }

        return null;
    }

    static String getXmlString(String str, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        return getXmlString(new StringReader(str), tagname, throwIfMissing);
    }
    
    private static void verifyResponseStatus(XmlPullParser xpp) throws HostHttpResponseException {
        // We use Long.parseLong() because in rare cases the host can send back a status code of
        // 0xFFFFFFFF, which will cause Integer.parseInt() to throw a NumberFormatException due
        // to exceeding Integer.MAX_VALUE. We'll get the desired error code of -1 by just casting
        // the resulting long into an int.
        int statusCode = (int)Long.parseLong(xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code"));
        if (statusCode != 200) {
            String statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message");
            if (statusCode == -1 && "Invalid".equals(statusMsg)) {
                // Special case handling an audio capture error which the host doesn't
                // provide any useful status message for.
                statusCode = 418;
                // Hermit: shown localized by ServerHelper.describeHostError (code 418)
                statusMsg = "Missing audio capture device";
            }
            throw new HostHttpResponseException(statusCode, statusMsg);
        }
    }
    
    public String getServerInfo(boolean likelyOnline) throws IOException, XmlPullParserException {
        String resp;

        // If we believe the PC is online, give it a little extra time to respond
        OkHttpClient client = likelyOnline ? httpClientLongConnectTimeout : httpClientShortConnectTimeout;
        
        //
        // TODO: Shield Hub uses HTTP for this and is able to get an accurate PairStatus with HTTP.
        // For some reason, we always see PairStatus is 0 over HTTP and only 1 over HTTPS. It looks
        // like there are extra request headers required to make this stuff work over HTTP.
        //

        // When we have a pinned cert, use HTTPS to fetch serverinfo and fall back on cert mismatch
        if (serverCert != null) {
            try {
                try {
                    resp = openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo");
                } catch (SSLHandshakeException e) {
                    // Detect if we failed due to a server cert mismatch
                    if (e.getCause() instanceof CertificateException) {
                        // Jump to the HostHttpResponseException handler to retry
                        // over HTTP which will allow us to pair again to update the cert
                        throw new HostHttpResponseException(401, "Server certificate mismatch");
                    }
                    else {
                        throw e;
                    }
                }

                // This will throw an exception if the request came back with a failure status.
                // We want this because it will throw us into the HTTP case if the client is unpaired.
                getServerVersion(resp);
            }
            catch (HostHttpResponseException e) {
                if (e.getErrorCode() == 401) {
                    // Cert validation error - fall back to HTTP
                    return openHttpConnectionToString(client, baseUrlHttp, "serverinfo");
                }

                // If it's not a cert validation error, throw it
                throw e;
            }

            return resp;
        }
        else {
            // No pinned cert, so use HTTP
            return openHttpConnectionToString(client, baseUrlHttp, "serverinfo");
        }
    }

    private static ComputerDetails.AddressTuple makeTuple(String address, int port) {
        if (address == null) {
            return null;
        }

        return new ComputerDetails.AddressTuple(address, port);
    }

    public ComputerDetails getComputerDetails(String serverInfo) throws IOException, XmlPullParserException {
        ComputerDetails details = new ComputerDetails();

        details.name = getXmlString(serverInfo, "hostname", false);
        if (details.name == null || details.name.isEmpty()) {
            details.name = "UNKNOWN";
        }

        // UUID is mandatory to determine which machine is responding
        details.uuid = getXmlString(serverInfo, "uniqueid", true);

        details.httpsPort = getHttpsPort(serverInfo);

        details.macAddress = getXmlString(serverInfo, "mac", false);

        // FIXME: Do we want to use the current port?
        details.localAddress = makeTuple(getXmlString(serverInfo, "LocalIP", false), baseUrlHttp.port());

        // This is missing on recent NVIDIA GameStream versions, but open-source hosts (Sunshine, Apollo, Shell) send it
        details.externalPort = getExternalPort(serverInfo);
        details.remoteAddress = makeTuple(getXmlString(serverInfo, "ExternalIP", false), details.externalPort);

        details.pairState = getPairState(serverInfo);
        details.runningGameId = getCurrentGame(serverInfo);

        // The MJOLNIR codename was used by NVIDIA GameStream but never by any third-party server
        details.nvidiaServer = getXmlString(serverInfo, "state", true).contains("MJOLNIR");

        // We could reach it so it's online
        details.state = ComputerDetails.State.ONLINE;

        return details;
    }
    
    public ComputerDetails getComputerDetails(boolean likelyOnline) throws IOException, XmlPullParserException {
        return getComputerDetails(getServerInfo(likelyOnline));
    }

    // This hack is Android-specific but we do it on all platforms
    // because it doesn't really matter
    private OkHttpClient performAndroidTlsHack(OkHttpClient client) {
        // Doing this each time we create a socket is required
        // to avoid the SSLv3 fallback that causes connection failures
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(new KeyManager[] { keyManager }, new TrustManager[] { trustManager }, new SecureRandom());
            return client.newBuilder().sslSocketFactory(sc.getSocketFactory(), trustManager).build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpUrl getCompleteUrl(HttpUrl baseUrl, String path, String query) {
        return baseUrl.newBuilder()
                .addPathSegment(path)
                .query(query)
                .addQueryParameter("uniqueid", uniqueId)
                .addQueryParameter("uuid", UUID.randomUUID().toString())
                .build();
    }

    private ResponseBody openHttpConnection(OkHttpClient client, HttpUrl baseUrl, String path) throws IOException {
        return openHttpConnection(client, baseUrl, path, null);
    }

    // Read timeout should be enabled for any HTTP query that requires no outside action
    // on the host. Examples of queries that DO require outside action are launch, resume, and quit.
    // The initial pair query does require outside action (user entering a PIN) but subsequent pairing
    // queries do not.
    private ResponseBody openHttpConnection(OkHttpClient client, HttpUrl baseUrl, String path, String query) throws IOException {
        if (cancelled) {
            throw new IOException("Cancelled");
        }
        HttpUrl completeUrl = getCompleteUrl(baseUrl, path, query);
        Request request = new Request.Builder().url(completeUrl).get().build();
        Response response = performAndroidTlsHack(client).newCall(request).execute();

        ResponseBody body = response.body();
        
        if (response.isSuccessful()) {
            return body;
        }
        
        // Unsuccessful, so close the response body
        if (body != null) {
            body.close();
        }
        
        if (response.code() == 404) {
            throw new FileNotFoundException(completeUrl.toString());
        }
        else {
            throw new HostHttpResponseException(response.code(), response.message());
        }
    }

    private String openHttpConnectionToString(OkHttpClient client, HttpUrl baseUrl, String path) throws IOException {
        return openHttpConnectionToString(client, baseUrl, path, null);
    }

    private String openHttpConnectionToString(OkHttpClient client, HttpUrl baseUrl, String path, String query) throws IOException {
        try {
            ResponseBody resp = openHttpConnection(client, baseUrl, path, query);
            String respString = resp.string();
            resp.close();

            if (verbose && !path.equals("serverinfo")) {
                HermitLog.info(getCompleteUrl(baseUrl, path, query)+" -> "+respString);
            }

            return respString;
        } catch (IOException e) {
            if (verbose && !path.equals("serverinfo")) {
                HermitLog.warning(getCompleteUrl(baseUrl, path, query)+" -> "+e.getMessage());
                e.printStackTrace();
            }
            
            throw e;
        }
    }

    public String getServerVersion(String serverInfo) throws XmlPullParserException, IOException {
        // appversion is present in all supported host versions
        return getXmlString(serverInfo, "appversion", true);
    }

    public PairingManager.PairState getPairState() throws IOException, XmlPullParserException {
        return getPairState(getServerInfo(true));
    }

    public PairingManager.PairState getPairState(String serverInfo) throws IOException, XmlPullParserException {
        // appversion is present in all supported host versions
        return NvHTTP.getXmlString(serverInfo, "PairStatus", true).equals("1") ?
                PairState.PAIRED : PairState.NOT_PAIRED;
    }
    
    public long getMaxLumaPixelsH264(String serverInfo) throws XmlPullParserException, IOException {
        // MaxLumaPixelsH264 wasn't present on old host versions
        String str = getXmlString(serverInfo, "MaxLumaPixelsH264", false);
        if (str != null) {
            return Long.parseLong(str);
        } else {
            return 0;
        }
    }
    
    public long getMaxLumaPixelsHEVC(String serverInfo) throws XmlPullParserException, IOException {
        // MaxLumaPixelsHEVC wasn't present on old host versions
        String str = getXmlString(serverInfo, "MaxLumaPixelsHEVC", false);
        if (str != null) {
            return Long.parseLong(str);
        } else {
            return 0;
        }
    }

    // Possible meaning of bits
    // Bit 0: H.264 Baseline
    // Bit 1: H.264 High
    // ----
    // Bit 8: HEVC Main
    // Bit 9: HEVC Main10
    // Bit 10: HEVC Main10 4:4:4
    // Bit 11: ???
    public long getServerCodecModeSupport(String serverInfo) throws XmlPullParserException, IOException {
        // ServerCodecModeSupport wasn't present on old host versions
        String str = getXmlString(serverInfo, "ServerCodecModeSupport", false);
        if (str != null) {
            return Long.parseLong(str);
        } else {
            return 0;
        }
    }
    
    public String getGpuType(String serverInfo) throws XmlPullParserException, IOException {
        // ServerCodecModeSupport wasn't present on old host versions
        return getXmlString(serverInfo, "gputype", false);
    }

    public String getGameStreamVersion(String serverInfo) throws XmlPullParserException, IOException {
        // ServerCodecModeSupport wasn't present on old host versions
        return getXmlString(serverInfo, "GfeVersion", false);
    }
    
    public boolean supports4K(String serverInfo) throws XmlPullParserException, IOException {
        // Only allow 4K on GameStream 3.x. The version field wasn't present on very old hosts.
        String gsVersionStr = getXmlString(serverInfo, "GfeVersion", false);
        if (gsVersionStr == null || gsVersionStr.startsWith("2.")) {
            return false;
        }

        return true;
    }

    public int getCurrentGame(String serverInfo) throws IOException, XmlPullParserException {
        // GameStream 2.8 started keeping currentgame set to the last game played. As a result, it no longer
        // has the semantics that its name would indicate. To contain the effects of this change as much
        // as possible, we'll force the current game to zero if the server isn't in a streaming session.
        if (getXmlString(serverInfo, "state", true).endsWith("_SERVER_BUSY")) {
            return Integer.parseInt(getXmlString(serverInfo, "currentgame", true));
        }
        else {
            return 0;
        }
    }

    public int getHttpsPort(String serverInfo) {
        try {
            return Integer.parseInt(getXmlString(serverInfo, "HttpsPort", true));
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            return DEFAULT_HTTPS_PORT;
        } catch (IOException e) {
            e.printStackTrace();
            return DEFAULT_HTTPS_PORT;
        }
    }

    public int getExternalPort(String serverInfo) {
        // This is an extension which is not present in NVIDIA GameStream. Open-source hosts (Sunshine, Apollo, Shell) send it to be able
        // to support dynamic HTTP WAN ports without requiring the user to manually enter the port.
        try {
            return Integer.parseInt(getXmlString(serverInfo, "ExternalPort", true));
        } catch (XmlPullParserException e) {
            // Expected on hosts without this extension
            return baseUrlHttp.port();
        } catch (IOException e) {
            e.printStackTrace();
            return baseUrlHttp.port();
        }
    }

    /**
     * Get an app by ID
     * @param appId The ID of the app
     * @see #getAppByName(String) for alternative.
     * @return app details, or null if no app with that ID exists
     */
    public NvApp getAppById(int appId) throws IOException, XmlPullParserException {
        LinkedList<NvApp> appList = getAppList();
        for (NvApp appFromList : appList) {
            if (appFromList.getAppId() == appId) {
                return appFromList;
            }
        }
        return null;
    }

    /**
     * Get an app by name
     * NOTE: It is perfectly valid for multiple apps to have the same name,
     * this function will only return the first one it finds.
     * Consider using getAppById instead.
     * @param appName The name of the app
     * @see #getAppById(int) for alternative.
     * @return app details, or null if no app with that name exists
     */
    public NvApp getAppByName(String appName) throws IOException, XmlPullParserException {
        LinkedList<NvApp> appList = getAppList();
        for (NvApp appFromList : appList) {
            if (appFromList.getAppName().equalsIgnoreCase(appName)) {
                return appFromList;
            }
        }
        return null;
    }

    public PairingManager getPairingManager() {
        return pm;
    }
    
    public static LinkedList<NvApp> getAppListByReader(Reader r) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);
        int eventType = xpp.getEventType();
        LinkedList<NvApp> appList = new LinkedList<NvApp>();
        Stack<String> currentTag = new Stack<String>();
        boolean rootTerminated = false;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
            case (XmlPullParser.START_TAG):
                if (xpp.getName().equals("root")) {
                    verifyResponseStatus(xpp);
                }
                currentTag.push(xpp.getName());
                if (xpp.getName().equals("App")) {
                    appList.addLast(new NvApp());
                }
                break;
            case (XmlPullParser.END_TAG):
                currentTag.pop();
                if (xpp.getName().equals("root")) {
                    rootTerminated = true;
                }
                break;
            case (XmlPullParser.TEXT):
                NvApp app = appList.getLast();
                if (currentTag.peek().equals("AppTitle")) {
                    app.setAppName(xpp.getText());
                } else if (currentTag.peek().equals("ID")) {
                    app.setAppId(xpp.getText());
                } else if (currentTag.peek().equals("IsHdrSupported")) {
                    app.setHdrSupported(xpp.getText().equals("1"));
                }
                break;
            }
            eventType = xpp.next();
        }
        
        // Throw a malformed XML exception if we've not seen the root tag ended
        if (!rootTerminated) {
            throw new XmlPullParserException("Malformed XML: Root tag was not terminated");
        }
        
        // Ensure that all apps in the list are initialized
        ListIterator<NvApp> i = appList.listIterator();
        while (i.hasNext()) {
            NvApp app = i.next();
            
            // Remove uninitialized apps
            if (!app.isInitialized()) {
                HermitLog.warning("Host returned incomplete app: "+app.getAppId()+" "+app.getAppName());
                i.remove();
            }
        }
        
        return appList;
    }
    
    public String getAppListRaw() throws IOException {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "applist");
    }
    
    public LinkedList<NvApp> getAppList() throws HostHttpResponseException, IOException, XmlPullParserException {
        if (verbose) {
            // Use the raw function so the app list is printed
            return getAppListByReader(new StringReader(getAppListRaw()));
        }
        else {
            try (final ResponseBody resp = openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true), "applist")) {
                return getAppListByReader(new InputStreamReader(resp.byteStream()));
            }
        }
    }

    String executePairingCommand(String additionalArguments, boolean enableReadTimeout) throws HostHttpResponseException, IOException {
        return openHttpConnectionToString(enableReadTimeout ? httpClientLongConnectTimeout : httpClientLongConnectNoReadTimeout,
                baseUrlHttp, "pair", "devicename=roth&updateState=1&" + additionalArguments);
    }

    String executePairingChallenge() throws HostHttpResponseException, IOException {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true),
                "pair", "devicename=roth&updateState=1&phrase=pairchallenge");
    }

    /**
     * Hermit: aborts the requests in flight on this connection, e.g. the pairing request that
     * waits for the PIN (it has no read timeout). Every client here shares one dispatcher.
     */
    public void cancelPendingRequests() {
        // Also the requests between the steps of a pairing, which cancelAll() can't reach
        cancelled = true;
        httpClientLongConnectTimeout.dispatcher().cancelAll();
    }

    // Set by cancelPendingRequests(): later requests on this connection fail at once
    private volatile boolean cancelled;

    public void unpair() throws IOException {
        openHttpConnectionToString(httpClientLongConnectTimeout, baseUrlHttp, "unpair");
    }
    
    public InputStream getBoxArt(NvApp app) throws IOException {
        ResponseBody resp = openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true), "appasset", "appid=" + app.getAppId() + "&AssetType=2&AssetIdx=0");
        return resp.byteStream();
    }
    
    public int getServerMajorVersion(String serverInfo) throws XmlPullParserException, IOException {
        return getServerAppVersionQuad(serverInfo)[0];
    }
    
    public int[] getServerAppVersionQuad(String serverInfo) throws XmlPullParserException, IOException {
        String serverVersion = getServerVersion(serverInfo);
        if (serverVersion == null) {
            throw new IllegalArgumentException("Missing server version field");
        }
        String[] serverVersionSplit = serverVersion.split("\\.");
        if (serverVersionSplit.length != 4) {
            throw new IllegalArgumentException("Malformed server version field: "+serverVersion);
        }
        int[] ret = new int[serverVersionSplit.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = Integer.parseInt(serverVersionSplit[i]);
        }
        return ret;
    }

    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    
    public boolean launchApp(ConnectionContext context, String verb, int appId, boolean enableHdr) throws IOException, XmlPullParserException {
        // Using an FPS value over 60 causes SOPS to default to 720p60,
        // so force it to 0 to ensure the correct resolution is set. We
        // used to use 60 here but that locked the frame rate to 60 FPS
        // on GameStream 3.20.3.
        int fps = context.isNvidiaServerSoftware && context.streamConfig.getLaunchRefreshRate() > 60 ?
                0 : context.streamConfig.getLaunchRefreshRate();

        boolean enableSops = context.streamConfig.getSops();
        if (context.isNvidiaServerSoftware) {
            // Using an unsupported resolution (not 720p, 1080p, or 4K) causes
            // the host to force SOPS to 720p60. This is fine for < 720p resolutions like
            // 360p or 480p, but it is not ideal for 1440p and other resolutions.
            // When we detect an unsupported resolution, disable SOPS unless it's under 720p.
            // FIXME: Detect support resolutions using the serverinfo response, not a hardcoded list
            if (context.negotiatedWidth * context.negotiatedHeight > 1280 * 720 &&
                    context.negotiatedWidth * context.negotiatedHeight != 1920 * 1080 &&
                    context.negotiatedWidth * context.negotiatedHeight != 3840 * 2160) {
                HermitLog.info("Disabling SOPS due to non-standard resolution: "+context.negotiatedWidth+"x"+context.negotiatedHeight);
                enableSops = false;
            }
        }

        String xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), verb,
            "appid=" + appId +
            "&mode=" + context.negotiatedWidth + "x" + context.negotiatedHeight + "x" + fps +
            "&additionalStates=1&sops=" + (enableSops ? 1 : 0) +
            "&rikey="+bytesToHex(context.riKey.getEncoded()) +
            "&rikeyid="+context.riKeyId +
            (!enableHdr ? "" : "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
            "&localAudioPlayMode=" + (context.streamConfig.getPlayLocalAudio() ? 1 : 0) +
            "&surroundAudioInfo=" + context.streamConfig.getAudioConfiguration().getSurroundAudioInfo() +
            "&remoteControllersBitmap=" + context.streamConfig.getAttachedGamepadMask() +
            "&gcmap=" + context.streamConfig.getAttachedGamepadMask() +
            "&gcpersist="+(context.streamConfig.getPersistGamepadsAfterDisconnect() ? 1 : 0) +
            MoonBridge.getLaunchUrlQueryParameters());
        if ((verb.equals("launch") && !getXmlString(xmlStr, "gamesession", true).equals("0") ||
                (verb.equals("resume") && !getXmlString(xmlStr, "resume", true).equals("0")))) {
            // sessionUrl0 will be missing for older host versions
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false);
            return true;
        }
        else {
            return false;
        }
    }
    
    public boolean quitApp() throws IOException, XmlPullParserException {
        String xmlStr = openHttpConnectionToString(httpClientLongConnectNoReadTimeout, getHttpsUrl(true), "cancel");
        if (getXmlString(xmlStr, "cancel", true).equals("0")) {
            return false;
        }

        // Newer GameStream versions will just return success even if quitting fails
        // if we're not the original requestor.
        if (getCurrentGame(getServerInfo(true)) != 0) {
            // Generate a synthetic HostHttpResponseException letting the caller know
            // that they can't kill someone else's stream.
            throw new HostHttpResponseException(599, "");
        }

        return true;
    }

    // Hermit: clipboard sync with Shell (GET and POST /actions/clipboard?type=...).
    // "text" works with any host with the clipboard endpoint; "info" and "image" are Shell extensions. HTTP errors
    // arrive as HostHttpResponseException (401 permission denied, 403 not streaming,
    // 400 type not supported, 404 no clipboard endpoint, 413 too large, 422 content the host
    // cannot hand over, 503 host clipboard busy). For a 422 the error message is the first line
    // of Shell's body, which says why (e.g. "image-not-convertible").
    private Response hermitClipboardCall(String type, byte[] body, String contentType) throws IOException {
        HttpUrl url = getHttpsUrl(true).newBuilder()
                .addPathSegments("actions/clipboard")
                .addQueryParameter("type", type)
                .addQueryParameter("uniqueid", uniqueId)
                .addQueryParameter("uuid", UUID.randomUUID().toString())
                .build();
        Request.Builder request = new Request.Builder().url(url);
        if (body != null) {
            request.post(RequestBody.create(body, MediaType.parse(contentType)));
        }
        Response response = performAndroidTlsHack(httpClientLongConnectTimeout).newCall(request.build()).execute();
        if (!response.isSuccessful()) {
            int code = response.code();
            String message = code == 422 ? firstBodyLine(response.body()) : response.message();
            response.close();
            throw new HostHttpResponseException(code, message);
        }
        return response;
    }

    /** The first line of a short error body (at most 256 bytes are read), or "" if there is none. */
    private static String firstBodyLine(ResponseBody body) {
        if (body == null) {
            return "";
        }
        byte[] buffer = new byte[256];
        int length = 0;
        try (InputStream in = body.byteStream()) {
            int n;
            while (length < buffer.length && (n = in.read(buffer, length, buffer.length - length)) > 0) {
                length += n;
            }
        } catch (IOException e) {
            return "";
        }
        String text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        int end = text.indexOf('\n');
        return (end >= 0 ? text.substring(0, end) : text).trim();
    }

    public byte[] hermitGetClipboard(String type, int maxBytes) throws IOException {
        try (Response response = hermitClipboardCall(type, null, null)) {
            ResponseBody body = response.body();
            if (body == null) {
                return new byte[0];
            }
            long length = body.contentLength();
            if (length > maxBytes) {
                throw new HostHttpResponseException(413, "Clipboard content too large");
            }
            byte[] data = body.bytes();
            if (data.length > maxBytes) {
                throw new HostHttpResponseException(413, "Clipboard content too large");
            }
            return data;
        }
    }

    /**
     * Hermit: Shell changes the bitrate of this client's running stream without restarting it
     * (GET /actions/bitrate?kbps=N). Returns the host's encoding bitrate in kbps, or -1 if the reply
     * has no "bitrate=" line (a host that answers unknown requests with 200 changed nothing). Throws
     * HostHttpResponseException 404 (not Shell) or 501 (encoder cannot), 403 if not streaming.
     */
    public int hermitSetLiveBitrate(int kbps) throws IOException {
        HttpUrl url = getHttpsUrl(true).newBuilder()
                .addPathSegments("actions/bitrate")
                .addQueryParameter("kbps", Integer.toString(kbps))
                .addQueryParameter("uniqueid", uniqueId)
                .addQueryParameter("uuid", UUID.randomUUID().toString())
                .build();
        try (Response response = performAndroidTlsHack(httpClientLongConnectTimeout)
                .newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new HostHttpResponseException(response.code(), response.message());
            }
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            for (String line : text.split("\n")) {
                if (line.startsWith("bitrate=")) {
                    try {
                        return Integer.parseInt(line.substring(8).trim());
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
            }
            return -1;
        }
    }

    /** Returns the host's reply ("seq=N" from Shell, empty from other hosts). */
    public String hermitSetClipboard(String type, byte[] data, String contentType) throws IOException {
        try (Response response = hermitClipboardCall(type, data, contentType)) {
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    /** What Shell answers to GET /actions/power?action=query. */
    public static class PowerState {
        /** The host can turn itself off or restart (a host without it answers 404). */
        public boolean supported;
        /** This device may do it (the host's launch permission). */
        public boolean allowed;
        /** Other devices streaming from the host right now. */
        public final List<String> clients = new ArrayList<>();
    }

    private String hermitPowerCall(String action, boolean force) throws IOException {
        HttpUrl.Builder builder = getHttpsUrl(true).newBuilder()
                .addPathSegments("actions/power")
                .addQueryParameter("action", action)
                .addQueryParameter("uniqueid", uniqueId)
                .addQueryParameter("uuid", UUID.randomUUID().toString());
        if (force) {
            builder.addQueryParameter("force", "1");
        }
        try (Response response = performAndroidTlsHack(httpClientLongConnectTimeout)
                .newCall(new Request.Builder().url(builder.build()).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new HostHttpResponseException(response.code(), response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    /**
     * Hermit: asks Shell whether this device may turn it off or restart it, and who else is
     * streaming. Throws HostHttpResponseException 404 when the host has no such request.
     */
    public PowerState hermitPowerQuery() throws IOException {
        PowerState state = new PowerState();
        for (String line : hermitPowerCall("query", false).split("\n")) {
            if (line.startsWith("supported=")) {
                state.supported = line.substring(10).trim().equals("1");
            }
            else if (line.startsWith("allowed=")) {
                state.allowed = line.substring(8).trim().equals("1");
            }
            else if (line.startsWith("client=")) {
                state.clients.add(line.substring(7).trim());
            }
        }
        return state;
    }

    /**
     * Hermit: turns the host off ("shutdown") or restarts it ("restart"); force also closes apps
     * that would hold it back. Returns false when the host answered but did nothing.
     */
    public boolean hermitPowerAction(String action, boolean force) throws IOException {
        return hermitPowerCall(action, force).startsWith("ok");
    }
}
