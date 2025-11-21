package com.electricdreams.shellshock.nostr;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Minimal OkHttp-based nostr WebSocket client.
 *
 * Responsibilities:
 *  - Connect to a list of relay URLs.
 *  - On connect, send a REQ for kind 1059 with #p=[our pubkey].
 *  - Parse EVENT messages and hand NostrEvent objects to a handler.
 *  - Attempt simple reconnect with backoff on failures.
 */
public final class NostrWebSocketClient {

    public interface EventHandler {
        void onEvent(String relayUrl, NostrEvent event);
        void onError(String relayUrl, String message, Throwable t);
    }

    private static final String TAG = "NostrWebSocketClient";

    private final OkHttpClient okHttpClient;
    private final List<String> relayUrls;
    private final String pubkeyHex;
    private final EventHandler handler;
    private final String subscriptionId;

    private final Map<String, WebSocketState> sockets = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private volatile boolean running = false;

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private static final class WebSocketState {
        volatile WebSocket webSocket;
        volatile long backoffMs = INITIAL_BACKOFF_MS;
    }

    public NostrWebSocketClient(List<String> relayUrls, String pubkeyHex, EventHandler handler) {
        this(new OkHttpClient.Builder()
                     .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout, rely on WS pings
                     .build(),
             relayUrls,
             pubkeyHex,
             handler);
    }

    public NostrWebSocketClient(OkHttpClient okHttpClient,
                                List<String> relayUrls,
                                String pubkeyHex,
                                EventHandler handler) {
        this.okHttpClient = okHttpClient;
        this.relayUrls = relayUrls != null ? new ArrayList<>(relayUrls) : Collections.emptyList();
        this.pubkeyHex = pubkeyHex;
        this.handler = handler;
        this.subscriptionId = UUID.randomUUID().toString().substring(0, 8);
    }

    public void start() {
        if (running) return;
        running = true;
        Log.d(TAG, "Starting NostrWebSocketClient with subscriptionId=" + subscriptionId
                + " pubkey=" + pubkeyHex + " relays=" + relayUrls);
        for (String url : relayUrls) {
            if (url == null || url.isEmpty()) continue;
            connectRelay(url);
        }
    }

    public void stop() {
        running = false;
        Log.d(TAG, "Stopping NostrWebSocketClient");
        for (Map.Entry<String, WebSocketState> e : sockets.entrySet()) {
            WebSocket ws = e.getValue().webSocket;
            if (ws != null) {
                try {
                    ws.close(1000, "client stop");
                } catch (Exception ignore) {
                }
            }
        }
        sockets.clear();
    }

    private void connectRelay(final String relayUrl) {
        if (!running) return;
        Log.d(TAG, "Connecting to nostr relay: " + relayUrl);
        Request request = new Request.Builder().url(relayUrl).build();

        WebSocketState state = sockets.computeIfAbsent(relayUrl, k -> new WebSocketState());

        WebSocket ws = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket open: " + relayUrl);
                state.webSocket = webSocket;
                state.backoffMs = INITIAL_BACKOFF_MS; // reset backoff on success
                sendReq(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(relayUrl, text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleMessage(relayUrl, bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket closing: " + relayUrl + " code=" + code + " reason=" + reason);
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + relayUrl + " code=" + code + " reason=" + reason);
                state.webSocket = null;
                scheduleReconnect(relayUrl, state);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure: " + relayUrl + " error=" + t.getMessage(), t);
                state.webSocket = null;
                if (handler != null) {
                    handler.onError(relayUrl, "websocket failure", t);
                }
                scheduleReconnect(relayUrl, state);
            }
        });

        state.webSocket = ws;
    }

    private void sendReq(WebSocket webSocket) {
        if (pubkeyHex == null || pubkeyHex.length() != 64) {
            Log.e(TAG, "Cannot send REQ: invalid pubkey=" + pubkeyHex);
            return;
        }
        JsonArray root = new JsonArray();
        root.add("REQ");
        root.add(subscriptionId);

        JsonObject filter = new JsonObject();
        JsonArray kinds = new JsonArray();
        kinds.add(1059); // giftwrap
        filter.add("kinds", kinds);

        JsonArray pList = new JsonArray();
        pList.add(pubkeyHex);
        filter.add("#p", pList);

        // Optionally, could add limit/since/etc.
        root.add(filter);

        String msg = gson.toJson(root);
        Log.d(TAG, "Sending REQ to relay: " + msg);
        webSocket.send(msg);
    }

    private void handleMessage(String relayUrl, String text) {
        try {
            JsonElement je = gson.fromJson(text, JsonElement.class);
            if (!je.isJsonArray()) return;
            JsonArray arr = je.getAsJsonArray();
            if (arr.size() == 0) return;
            String type = arr.get(0).getAsString();
            if ("EVENT".equals(type) && arr.size() >= 3) {
                String subId = arr.get(1).getAsString();
                if (!subscriptionId.equals(subId)) {
                    return; // event for some other subscription
                }
                JsonElement evElem = arr.get(2);
                NostrEvent event = gson.fromJson(evElem, NostrEvent.class);
                if (event != null && handler != null) {
                    handler.onEvent(relayUrl, event);
                }
            } else if ("NOTICE".equals(type) && arr.size() >= 2) {
                String msg = arr.get(1).getAsString();
                Log.w(TAG, "NOTICE from " + relayUrl + ": " + msg);
            } else if ("CLOSED".equals(type) && arr.size() >= 3) {
                String subId = arr.get(1).getAsString();
                String reason = arr.get(2).getAsString();
                Log.w(TAG, "CLOSED from " + relayUrl + " for sub=" + subId + " reason=" + reason);
            } else if ("EOSE".equals(type)) {
                Log.d(TAG, "EOSE from " + relayUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message from " + relayUrl + ": " + e.getMessage(), e);
            if (handler != null) {
                handler.onError(relayUrl, "parse error", e);
            }
        }
    }

    private void scheduleReconnect(String relayUrl, WebSocketState state) {
        if (!running) return;
        long delay = state.backoffMs;
        state.backoffMs = Math.min(state.backoffMs * 2, MAX_BACKOFF_MS);
        Log.d(TAG, "Scheduling reconnect to " + relayUrl + " in " + delay + "ms");
        new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
            if (running) {
                connectRelay(relayUrl);
            }
        }).start();
    }
}
