package com.airwar.android.net;

import androidx.annotation.Nullable;

import okhttp3.WebSocket;

final class WebSocketGuards {
    private WebSocketGuards() {
    }

    static boolean isActiveSocket(@Nullable WebSocket activeSocket, WebSocket callbackSocket) {
        return activeSocket == callbackSocket;
    }
}
