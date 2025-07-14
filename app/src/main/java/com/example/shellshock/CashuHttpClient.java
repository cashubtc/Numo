package com.example.shellshock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;

public class CashuHttpClient {

    private final OkHttpClient httpClient;
    private final String mintUrl;
    private final ObjectMapper objectMapper;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public CashuHttpClient(String mintUrl) {
        this.httpClient = new OkHttpClient();
        this.mintUrl = mintUrl;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public Map<String, Object> post(String path, Map<String, Object> requestBodyMap) throws IOException {
        String json = objectMapper.writeValueAsString(requestBodyMap);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(mintUrl + path)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return objectMapper.readValue(response.body().string(), Map.class);
        }
    }
}
