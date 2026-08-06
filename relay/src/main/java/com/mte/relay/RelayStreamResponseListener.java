package com.mte.relay;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

public interface RelayStreamResponseListener {
    void relayStreamResponse(
            int statusCode,
            boolean success,
            String responseStr,
            String errorMessage,
            Map<String, List<String>> responseHeaders);
}
