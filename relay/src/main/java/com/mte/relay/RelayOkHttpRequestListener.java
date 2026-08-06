package com.mte.relay;

import okhttp3.Response;

public interface RelayOkHttpRequestListener {

    void onError(Response response);
    void onResponse(Response response);
}
