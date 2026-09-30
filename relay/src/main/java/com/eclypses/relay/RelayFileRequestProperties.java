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

import java.util.Map;

public class RelayFileRequestProperties {

    // region Class Variables
    public final String serverPath;
    public String route;
    public String pathnamePrefix;
    public String downloadPath;
    public RelayStreamCallback relayStreamCallback;

    public final Map<String,String> origHeaders;
    public final String[] unencryptedHeaders;
    // endregion

    // region Constructors
    // Constructor is required in calling app
    public RelayFileRequestProperties(String serverPath,
                                      String route,
                                      String pathnamePrefix,
                                      Map<String,String> origHeaders,
                                      String[] unencryptedHeaders,
                                      RelayStreamCallback relayStreamCallback) {
        this.serverPath = serverPath;
        this.route = route;
        this.pathnamePrefix = pathnamePrefix;
        this.origHeaders = origHeaders;
        this.unencryptedHeaders = resolveUnencryptedHeaders(unencryptedHeaders);
        this.relayStreamCallback = relayStreamCallback;
    }

    // Constructor is required in calling app
    public RelayFileRequestProperties(String serverPath,
                                      String route,
                                      String downloadPath,
                                      String pathnamePrefix,
                                      Map<String, String> origHeaders,
                                      String[] unencryptedHeaders) {
        this.serverPath = serverPath;
        this.route = route;
        this.downloadPath = downloadPath;
        this.pathnamePrefix = pathnamePrefix;
        this.origHeaders = origHeaders;
        this.unencryptedHeaders = resolveUnencryptedHeaders(unencryptedHeaders);
    }

    /**
     * Validates the caller's list and normalises "nothing to expose" to null.
     *
     * <p>Under the previous whitelist this defaulted to {@code {"Content-Length",
     * "Content-Type"}}, meaning "encrypt those two". The list now names headers to leave
     * UNENCRYPTED, so carrying that default forward would ask to expose them — and both
     * are reserved, so every default file request would throw. Null and empty now mean
     * "encrypt everything", which is what the old default was reaching for.
     *
     * @throws IllegalArgumentException if the list cannot be honoured.
     */
    private static String[] resolveUnencryptedHeaders(String[] unencryptedHeaders) {
        if (unencryptedHeaders == null || unencryptedHeaders.length == 0) {
            return null;
        }
        // Throws on an empty name, a wildcard with other entries, an invalid header-name
        // character, or a reserved name. Validating here means a malformed list is
        // reported at construction rather than after a pairing.
        com.eclypses.relay.streaming.RelayHeaderPolicy.resolve(unencryptedHeaders);
        return unencryptedHeaders;
    }
    // endregion

}
