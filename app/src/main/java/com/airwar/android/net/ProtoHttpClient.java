package com.airwar.android.net;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProtoHttpClient {
    private static final MediaType PROTOBUF = MediaType.get("application/x-protobuf");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    // 所有 HTTP 接口都使用 protobuf 二进制请求体和响应体。
    public <T extends MessageLite> T post(String baseUrl, String path, MessageLite body, Parser<T> parser) throws IOException {
        Request request = new Request.Builder()
                .url(NetworkConfig.httpUrl(baseUrl, path))
                .post(RequestBody.create(body.toByteArray(), PROTOBUF))
                .header("Content-Type", "application/x-protobuf")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + path);
            }
            if (response.body() == null) {
                throw new IOException("Empty response body for " + path);
            }
            return parser.parseFrom(response.body().bytes());
        }
    }
}
