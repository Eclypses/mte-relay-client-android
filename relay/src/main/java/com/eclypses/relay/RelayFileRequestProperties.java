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
    public final String[] headersToEncrypt;
    // endregion

    // region Constructors
    // Constructor is required in calling app
    public RelayFileRequestProperties(String serverPath,
                                      String route,
                                      String pathnamePrefix,
                                      Map<String,String> origHeaders,
                                      String[] headersToEncrypt,
                                      RelayStreamCallback relayStreamCallback) {
        this.serverPath = serverPath;
        this.route = route;
        this.pathnamePrefix = pathnamePrefix;
        this.origHeaders = origHeaders;
        this.headersToEncrypt = resolveHeadersToEncrypt(headersToEncrypt);
        this.relayStreamCallback = relayStreamCallback;
    }

    // Constructor is required in calling app
    public RelayFileRequestProperties(String serverPath,
                                      String route,
                                      String downloadPath,
                                      String pathnamePrefix,
                                      Map<String, String> origHeaders,
                                      String[] headersToEncrypt) {
        this.serverPath = serverPath;
        this.route = route;
        this.downloadPath = downloadPath;
        this.pathnamePrefix = pathnamePrefix;
        this.origHeaders = origHeaders;
        this.headersToEncrypt = resolveHeadersToEncrypt(headersToEncrypt);
    }

    private static String[] resolveHeadersToEncrypt(String[] headersToEncrypt) {
        if (headersToEncrypt == null || headersToEncrypt.length == 0) {
            return new String[] {"Content-Length", "Content-Type"};
        }
        return headersToEncrypt;
    }
    // endregion

}
