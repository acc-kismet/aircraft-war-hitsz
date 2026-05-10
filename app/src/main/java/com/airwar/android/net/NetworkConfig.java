package com.airwar.android.net;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class NetworkConfig {
    // 联调默认走 Android 模拟器访问宿主机的地址。
    // 后续切换测试环境或正式环境时，优先改这里或菜单页输入值，不要在业务代码里散改地址。
    public static final String DEFAULT_BASE_URL = "http://kismet.uno:8080";
    public static final String PREFS_NAME = "multiplayer_prefs";
    public static final String PREF_BASE_URL = "base_url";
    public static final String PREF_USERNAME = "username";
    public static final String PREF_ROOM_ID = "room_id";
    public static final String PREF_AVATAR_ID = "avatar_id";

    private NetworkConfig() {
    }

    // 统一规整服务端地址，避免页面层重复处理 http:// 和尾部斜杠。
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        return normalized;
    }

    public static String httpUrl(String baseUrl, String path) {
        return normalizeBaseUrl(baseUrl) + path;
    }

    // WebSocket 地址和 HTTP 地址共用同一个 baseUrl，只在这里做协议转换。
    public static String wsUrl(String baseUrl, String roomId, String username) {
        String normalized = normalizeBaseUrl(baseUrl);
        String wsBase;
        if (normalized.startsWith("https://")) {
            wsBase = "wss://" + normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            wsBase = "ws://" + normalized.substring("http://".length());
        } else {
            wsBase = "ws://" + normalized;
        }
        return wsBase + "/ws?room_id=" + encode(roomId) + "&username=" + encode(username);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 should always be supported", e);
        }
    }
}
