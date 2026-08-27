package com.eclypses.relay;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Outcomes of {@link Relay#send}.
 *
 * <p>The three methods mirror what OkHttp itself reports, so the distinction a
 * caller already relies on survives the move to the relay:
 *
 * <ul>
 *   <li>{@link #onResponse} — the server replied with 2xx.</li>
 *   <li>{@link #onError} — the server replied, but not with 2xx. The status and
 *       body are the origin's.</li>
 *   <li>{@link #onFailure} — no HTTP response was ever obtained. The device was
 *       offline, the host would not resolve, the connection timed out, pairing
 *       failed, or a decode failed.</li>
 * </ul>
 *
 * <p>Keeping {@code onFailure} separate matters: a request that never reached the
 * network is not a server error, and reporting it as one leads callers to tell
 * users the backend is down when they are in airplane mode.
 *
 * <p>All three are delivered on a background thread. Post to the main thread
 * before touching views.
 */
public interface RelayOkHttpRequestListener {

    /** The server responded with a non-2xx status. Status and body are the origin's. */
    void onError(Response response);

    /** The server responded with 2xx. */
    void onResponse(Response response);

    /**
     * No HTTP response was obtained — the request failed before or during
     * transport, or the relay could not complete it.
     *
     * @param request the request that was being sent
     * @param cause   the underlying failure. Commonly an {@link java.io.IOException}
     *                for network conditions; other types indicate a relay or MTE
     *                failure rather than a transport one.
     */
    void onFailure(Request request, Throwable cause);
}
