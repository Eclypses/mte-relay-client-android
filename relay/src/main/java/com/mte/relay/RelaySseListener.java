package com.mte.relay;

import java.util.List;
import java.util.Map;

public interface RelaySseListener {
    void onOpened(String streamId, int statusCode, Map<String, List<String>> responseHeaders);

    void onData(String streamId, byte[] data);

    void onCompleted(String streamId);

    void onCancelled(String streamId);

    void onError(String streamId, int statusCode, String errorMessage, Map<String, List<String>> responseHeaders);
}