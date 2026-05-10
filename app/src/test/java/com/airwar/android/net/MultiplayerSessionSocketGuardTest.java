package com.airwar.android.net;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import okhttp3.WebSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultiplayerSessionSocketGuardTest {

    @Test
    public void activeSocketAcceptsItsOwnCallbacks() {
        WebSocket socket = fakeWebSocket();

        assertTrue(WebSocketGuards.isActiveSocket(socket, socket));
    }

    @Test
    public void staleSocketCallbacksAreIgnored() {
        WebSocket activeSocket = fakeWebSocket();
        WebSocket staleSocket = fakeWebSocket();

        assertFalse(WebSocketGuards.isActiveSocket(activeSocket, staleSocket));
    }

    private static WebSocket fakeWebSocket() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("close".equals(name) || "send".equals(name) || "cancel".equals(name)) {
                return true;
            }
            if ("queueSize".equals(name)) {
                return 0L;
            }
            if ("toString".equals(name)) {
                return "FakeWebSocket";
            }
            return null;
        };
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class},
                handler
        );
    }
}
