package com.mte.relay;

public interface RelayStreamCompletionCallback {
    void onProgressUpdate(int bytesCompleted, int totalBytes);
}
