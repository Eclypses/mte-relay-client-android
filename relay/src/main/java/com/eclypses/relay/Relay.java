// The MIT License (MIT)
//
// Copyright (c) Eclypses, Inc.
//
// All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.eclypses.relay;

import android.content.Context;

import com.eclypses.mte.MteBase;
import com.eclypses.relay.interceptor.RelayMteInterceptor;
import com.eclypses.relay.streaming.RelayOkHttpAdapter;
import com.eclypses.relay.streaming.RelayStreamingExecutor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unused") // All public methods are called externally
public class Relay {

    // region Class Variables
    private static Relay instance;
    private final Context ctx;
    private final RelayComponents components;
    private final RelayStreamingExecutor streamingExecutor;
    // endregion

    // region Constructors
    public static Relay getInstance(Context context) {
        return getInstance(context, null, true);
    }

    /**
     * Builds the relay against an {@link okhttp3.OkHttpClient} you supply.
     *
     * <p>A {@code cookieJar} you set on that client is used as-is and never replaced; set
     * none and the relay installs its own. See
     * {@link #getInstance(Context, okhttp3.OkHttpClient, boolean)} for turning cookies off.
     *
     * @param httpClient the client to build relay traffic on, or {@code null} for the default.
     */
    public static Relay getInstance(Context context, okhttp3.OkHttpClient httpClient) {
        return getInstance(context, httpClient, true);
    }

    /**
     * Builds the relay with explicit control over cookie handling.
     *
     * <p>Cookies are on by default. Given no client, or a client that never had a
     * {@code cookieJar} set, the relay installs an in-memory jar shared by auth, pair,
     * keepalive and every frame POST. That is what keeps a load balancer's affinity cookie
     * on the wire, and what lets a session cookie set by your origin flow back through the
     * relay without your application code touching it. A {@code cookieJar} you set yourself
     * is used as-is.
     *
     * <p>Pass {@code cookiesEnabled = false} to turn cookies off for all relay traffic. Do
     * not express this by handing in a client with {@link okhttp3.CookieJar#NO_COOKIES} —
     * that is also OkHttp's default, so it is indistinguishable from an unconfigured client
     * and the relay will install its own jar over it.
     *
     * <p>The relay is a singleton: only the first call builds it, and arguments passed to a
     * later call are ignored with a warning.
     *
     * @param httpClient     the client to build relay traffic on, or {@code null} for the default.
     * @param cookiesEnabled {@code false} to send and store no cookies on any relay call.
     */
    public static synchronized Relay getInstance(Context context,
                                                 okhttp3.OkHttpClient httpClient,
                                                 boolean cookiesEnabled) {
        if (instance == null) {
            System.setProperty("LOG_DIR", context.getFilesDir().getAbsolutePath());
            LogHelper.trace("Relay", "Logging initialized.");
            instance = new Relay(context, httpClient, cookiesEnabled);
        } else if (httpClient != null || !cookiesEnabled) {
            LogHelper.warn("Relay", "Relay is already initialized; the existing client and "
                    + "cookie setting are kept and these arguments are ignored.");
        }
        return instance;
    }

    private Relay(Context context, okhttp3.OkHttpClient httpClient, boolean cookiesEnabled) {
        // Delegated so there is one licence call in the library rather than one per entry
        // point. A path that reaches MTE without it produces probe=error and a profile that
        // matches no relay, with a message that blames the relay's configuration.
        try {
            com.eclypses.relay.session.MteLicense.INSTANCE.require();
        } catch (RelayException licenseFailure) {
            LogHelper.error("Relay", "MTE License Check Failed");
            throw licenseFailure;
        }
        LogHelper.info("Relay", "Using Relay Version " + RelaySettings.relayVersion + " and Mte Version " + MteBase.getVersion());
        ctx = context;
        // RelayComponents installs the default cookie jar when cookies are on and this
        // client has none, so a caller who configured their own jar keeps it.
        components = RelayComponents.create(
                ctx,
                httpClient != null ? httpClient : new okhttp3.OkHttpClient(),
                true,
                cookiesEnabled);
        streamingExecutor = new RelayStreamingExecutor(components);

        // Set FileLogging to the default state
        LogHelper.enableFileLogging(LogHelper.isFileLoggingEnabled());
    }
    // endregion

    // region Public Methods
    public void send(okhttp3.Request request,
                     String[] unencryptedHeaders,
                     String pathnamePrefix,
                     RelayOkHttpRequestListener listener) {
        // Throws before pairing if the list cannot be honoured.
        com.eclypses.relay.streaming.RelayHeaderPolicy.resolve(unencryptedHeaders);
        sendOkHttp(request, unencryptedHeaders, pathnamePrefix, listener);
    }

    public void uploadFile(RelayFileRequestProperties reqProperties,
                           RelayStreamResponseListener listener,
                           RelayStreamCompletionCallback completionCallback) {
        startUploadFile(reqProperties, listener, completionCallback);
    }

    public String startUploadFile(RelayFileRequestProperties reqProperties,
                                  RelayStreamResponseListener listener,
                                  RelayStreamCompletionCallback completionCallback) {
        LogHelper.trace("Relay", "Uploading File");
        if (!hasContentLengthHeader(reqProperties.origHeaders)) {
            String error = "Missing required Content-Length header for upload request";
            LogHelper.error("Relay", error);
            listener.relayStreamResponse(-1, false, null, error, null);
            return "upload-rejected";
        }
        String operationId = streamingExecutor.beginOperation();
        new Thread(() ->
                streamingExecutor.uploadFile(operationId, reqProperties, listener, completionCallback))
                .start();
        return operationId;
    }

    public void downloadFile(RelayFileRequestProperties reqProperties, RelayStreamResponseListener listener) {
        startDownloadFile(reqProperties, listener);
    }

    /**
     * The pathname prefix comes from {@code reqProperties.pathnamePrefix}, the same place
     * {@link #uploadFile} reads it. It used to be a separate argument here as well, and the
     * argument won — so a caller who set the field and passed null got no prefix and no
     * warning. One field, one meaning.
     */
    public String startDownloadFile(RelayFileRequestProperties reqProperties, RelayStreamResponseListener listener) {
        LogHelper.trace("Relay", "Downloading File");
        String operationId = streamingExecutor.beginOperation();
        Thread sendingThread = new Thread(() ->
                streamingExecutor.downloadFile(operationId, reqProperties, listener));
        sendingThread.start();
        return operationId;
    }

    public boolean cancelStreamingOperation(String operationId) {
        return streamingExecutor.cancelOperation(operationId);
    }

    public String startEventStream(okhttp3.Request request,
                                   String[] unencryptedHeaders,
                                   String pathnamePrefix,
                                   RelaySseListener listener) {
        // Throws before the operation starts if the list cannot be honoured.
        com.eclypses.relay.streaming.RelayHeaderPolicy.resolve(unencryptedHeaders);
        LogHelper.trace("Relay", "Starting SSE stream");
        // A streaming request may carry a body (e.g. an AI prompt POST). The relay frame encodes
        // the logical method and body-presence, so any verb is permitted — no GET-only restriction.
        String operationId = streamingExecutor.beginOperation();
        Thread sendingThread = new Thread(() -> {
            try {
                streamingExecutor.streamServerSentEvents(
                        operationId,
                        buildOrigin(request.url().toString()),
                        RelayOkHttpAdapter.route(request),
                        pathnamePrefix,
                        RelayOkHttpAdapter.headers(request),
                        unencryptedHeaders,
                        listener,
                        request.method(),
                        RelayOkHttpAdapter.body(request));
            } catch (Throwable t) {
                String message = t.getMessage() != null ? t.getMessage() : "event stream failed";
                LogHelper.error("Relay", message);
                listener.onError(operationId, -1, message, null);
            }
        });
        sendingThread.start();
        return operationId;
    }

    public boolean cancelEventStream(String streamId) {
        return streamingExecutor.cancelOperation(streamId);
    }

    public okhttp3.Interceptor getOkHttpInterceptor() {
        return new RelayMteInterceptor(
                components.getSessionManager(),
                components.getSessionLifecycleManager(),
                components.getTransport()
        );
    }

    public void rePairWithRelayServer(String serverUrl, String pathnamePrefix, RelayResponseListener callback) {
        LogHelper.trace("Relay", "Repairing with Server");
        String origin;
        try {
            origin = buildOrigin(serverUrl);
        } catch (RelayException e) {
            LogHelper.error("Relay", e.getMessage());
            if (callback != null) callback.onCompletion(false, e.getMessage());
            return;
        }
        new Thread(() -> {
            try {
                components.getSessionLifecycleManager().manualRepair(origin);
                String message = "Successfully Re-Paired with " + origin;
                LogHelper.info("Relay", message);
                if (callback != null) callback.onCompletion(true, message);
            } catch (Throwable t) {
                String message = t.getMessage() != null ? t.getMessage() : "Re-pair failed for " + origin;
                LogHelper.error("Relay", message);
                if (callback != null) callback.onCompletion(false, message);
            }
        }).start();
    }

    public synchronized RelayGlobalSettings getRelayGlobalSettings() {
        return new RelayGlobalSettings(
                RelaySettings.relayVersion,
                RelaySettings.licenseCompanyName,
                RelaySettings.licenseKey);
    }

    public synchronized RelayClientSettings getRelaySettings(String serverUrl) {
        try {
            String origin = buildOrigin(serverUrl);
            return components.getSessionLifecycleManager().getSettings(origin);
        } catch (RelayException e) {
            throw e;
        }
    }

    public synchronized String getKeepAliveDiagnostics(String serverUrl) {
        try {
            String origin = buildOrigin(serverUrl);
            return components.getSessionLifecycleManager().describeKeepAlive(origin);
        } catch (RelayException e) {
            return e.getMessage();
        }
    }

    public String adjustRelaySettings(String serverUrl,
                                      String pathnamePrefix,
                                      RelayClientSettings newSettings,
                                      RelayResponseListener callback) {
        LogHelper.trace("Relay", "Adjusting Relay Settings");
        RelayClientSettings requestedSettings = Objects.requireNonNull(newSettings, "newSettings");
        String origin;
        try {
            origin = buildOrigin(serverUrl);
        } catch (RelayException e) {
            LogHelper.error("Relay", e.getMessage());
            if (callback != null) callback.onCompletion(false, e.getMessage());
            return e.getMessage();
        }

        RelayClientSettings currentSettings = components.getSessionLifecycleManager().getSettings(origin);
        if (currentSettings.equals(requestedSettings)) {
            String responseMessage = "\nNo Relay Settings were changed based on arguments and existing RelaySettings";
            LogHelper.info("Relay", responseMessage);
            if (callback != null) callback.onCompletion(true, responseMessage);
            return responseMessage;
        }

        components.getSessionLifecycleManager().updateSettings(origin, requestedSettings);

        StringBuilder response = new StringBuilder();
        response.append("\nRelay settings updated to ")
            .append(requestedSettings);
        rePairWithRelayServer(origin, null, callback);
        response.append("\nAlso, Relay was Re-Paired with ").append(origin);
        String responseMessage = response.toString();
        LogHelper.info("Relay", responseMessage);
        return responseMessage;
    }

    public static void enableFileLogging(Boolean isEnabled) {
        LogHelper.trace("Relay", "Setting FileLogging to " + isEnabled);
        LogHelper.enableFileLogging(isEnabled);
    }

    public static String readLogFile() {
        LogHelper.trace("Relay", "Reading Log File");
        return LogHelper.readLogFileContents();
    }

    public static void clearLogFile() {
        LogHelper.trace("Relay", "Clearing log file");
        LogHelper.clearLogFileContents();
    }
    // endregion

    // region Private Methods
    /**
     * Buffered wrapper over the streaming core: folds every decrypted chunk into one payload and
     * calls back once the response completes.  A single-shot reply is just a stream that finishes
     * quickly.  A pairing desync is not resent transparently — the pool heals in the background and
     * the relay's status is surfaced to the caller, which decides whether to re-issue.
     */
    private void sendOkHttp(okhttp3.Request req,
                            String[] unencryptedHeaders,
                            String pathnamePrefix,
                            RelayOkHttpRequestListener listener) {
        Thread sendingThread = new Thread(() -> {
            try {
                okhttp3.Response response = RelayOkHttpAdapter.execute(
                        streamingExecutor,
                        req,
                        unencryptedHeaders,
                        pathnamePrefix);
                if (response.code() >= 200 && response.code() < 300) {
                    listener.onResponse(response);
                } else {
                    listener.onError(response);
                }
            } catch (Throwable t) {
                String message = t.getMessage() != null ? t.getMessage() : "relay execution failed";
                LogHelper.error("Relay", message);
                // No HTTP response was obtained. Reporting this as a synthesized 500
                // would make an offline device indistinguishable from a failing server.
                listener.onFailure(req, t);
            }
        });
        sendingThread.start();
    }

    private boolean hasContentLengthHeader(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Content-Length")) {
                return entry.getValue() != null && !entry.getValue().trim().isEmpty();
            }
        }
        return false;
    }

    private String buildOrigin(String serverUrl) {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new RelayException("Relay", "serverUrl must not be null or empty");
        }
        try {
            URL url = new URL(serverUrl);
            int port = url.getPort();
            boolean isDefaultPort = (url.getProtocol().equals("https") && port == 443)
                    || (url.getProtocol().equals("http") && port == 80);
            if (port == -1 || isDefaultPort) {
                return url.getProtocol() + "://" + url.getHost();
            }
            return url.getProtocol() + "://" + url.getHost() + ":" + port;
        } catch (MalformedURLException e) {
            throw new RelayException("Relay", "Invalid serverUrl: " + serverUrl);
        }
    }
    // endregion
}

