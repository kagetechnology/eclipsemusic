package com.eclipseapp.pulse;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * Discord Rich Presence via Gateway WebSocket (based on Metrolist/KizzyRPC approach).
 * Uses Gateway v9, external assets API for cover art, session resume for stability.
 */
final class DiscordRPC {
    private static final String TAG = "DiscordRPC";
    private static final String GATEWAY = "wss://gateway.discord.gg/?v=9&encoding=json";
    private static final String APP_ID = "1344267823987560500";
    private static final String PREFS = "discord_rpc";
    private static final DiscordRPC INSTANCE = new DiscordRPC();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();

    private WebSocket ws;
    private String token;
    private boolean identified;
    private int heartbeatInterval;
    private Integer lastSequence;
    private String sessionId;
    private String resumeGatewayUrl;
    private Runnable heartbeatTask;
    private String currentTitle, currentArtist, currentThumb;
    private long playStartMs;
    private int durationMs;
    private long reconnectDelay = 1000;
    private boolean intentionalClose;
    private boolean isPaused;
    private long pausedAtMs;

    private DiscordRPC() {}
    static DiscordRPC get() { return INSTANCE; }

    boolean isConnected() { return ws != null && identified; }

    String getToken(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null);
    }

    void saveToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("token", token).apply();
        this.token = token;
    }

    void logout(Context ctx) {
        intentionalClose = true;
        // Clear presence first
        if (ws != null && identified) {
            try {
                JSONObject d = new JSONObject();
                d.put("since", JSONObject.NULL);
                d.put("status", "online");
                d.put("afk", false);
                d.put("activities", new JSONArray());
                JSONObject payload = new JSONObject();
                payload.put("op", 3);
                payload.put("d", d);
                send(payload);
            } catch (Exception ignored) {}
        }
        // Delay close so presence clear goes through
        handler.postDelayed(() -> {
            disconnect();
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            token = null;
            sessionId = null;
            resumeGatewayUrl = null;
        }, 600);
    }

    void connect(Context ctx) {
        try {
            token = getToken(ctx);
            if (token == null || token.isEmpty()) return;
            if (ws != null) closeSocket();
            intentionalClose = false;

            String url = resumeGatewayUrl != null ? resumeGatewayUrl : GATEWAY;
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "Discord-Android/314013;RNA")
                    .build();

            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response r) {
                    Log.d(TAG, "Gateway connected");
                    reconnectDelay = 1000; // reset backoff
                }

                @Override public void onMessage(WebSocket w, String text) {
                    try { handleMessage(new JSONObject(text), ctx); }
                    catch (Exception e) { Log.e(TAG, "Parse error", e); }
                }

                @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                    Log.e(TAG, "WS failed: " + t.getMessage());
                    identified = false;
                    ws = null;
                    if (!intentionalClose) scheduleReconnect(ctx);
                }

                @Override public void onClosing(WebSocket w, int code, String reason) {
                    Log.d(TAG, "WS closing: " + code + " " + reason);
                }

                @Override public void onClosed(WebSocket w, int code, String reason) {
                    Log.d(TAG, "WS closed: " + code + " " + reason);
                    identified = false;
                    ws = null;
                    // 4000 = reconnectable
                    if (code == 4000 && !intentionalClose) {
                        handler.postDelayed(() -> connect(ctx), 200);
                    } else if (!intentionalClose) {
                        scheduleReconnect(ctx);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
        }
    }

    private void scheduleReconnect(Context ctx) {
        if (intentionalClose) return;
        Log.d(TAG, "Reconnecting in " + reconnectDelay + "ms");
        handler.postDelayed(() -> connect(ctx), reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, 60000); // max 60s
    }

    void disconnect() {
        intentionalClose = true;
        identified = false;
        currentTitle = null;
        if (heartbeatTask != null) handler.removeCallbacks(heartbeatTask);
        closeSocket();
    }

    private void closeSocket() {
        if (ws != null) { try { ws.close(1000, "bye"); } catch (Exception e) {} ws = null; }
    }

    void updatePresence(String title, String artist, String thumbUrl, int fallbackDurationMs) {
        currentTitle = title;
        currentArtist = artist;
        currentThumb = thumbUrl;
        isPaused = false;
        playStartMs = System.currentTimeMillis();
        new Thread(() -> {
            if (fallbackDurationMs > 1000) {
                durationMs = fallbackDurationMs;
            } else {
                // Wait up to 5 seconds for MediaPlayer to fetch the stream duration
                try {
                    for (int i = 0; i < 20; i++) {
                        Thread.sleep(250);
                        try { durationMs = PlaybackManager.get().duration(); } catch (Exception e) { durationMs = 0; }
                        if (durationMs > 1000) break;
                    }
                } catch (Exception ignored) {}
            }
            if (identified) sendPresence();
        }).start();
    }

    void clearPresence() {
        currentTitle = null;
        if (identified) sendPresence();
    }

    void setPaused(boolean paused) {
        if (this.isPaused == paused) return;
        this.isPaused = paused;
        if (paused) {
            pausedAtMs = System.currentTimeMillis();
        } else {
            long pauseDuration = System.currentTimeMillis() - pausedAtMs;
            playStartMs += pauseDuration;
        }
        if (identified) sendPresence();
    }

    // --- Internal ---

    private void handleMessage(JSONObject msg, Context ctx) throws Exception {
        int op = msg.optInt("op", -1);
        if (msg.has("s") && !msg.isNull("s")) lastSequence = msg.getInt("s");

        switch (op) {
            case 10: // Hello
                heartbeatInterval = msg.getJSONObject("d").getInt("heartbeat_interval");
                startHeartbeat();
                // Resume or identify
                if (lastSequence != null && lastSequence > 0 && sessionId != null) {
                    sendResume();
                } else {
                    sendIdentify();
                }
                break;
            case 11: // Heartbeat ACK
                break;
            case 0: // Dispatch
                String t = msg.optString("t", "");
                if ("READY".equals(t)) {
                    identified = true;
                    JSONObject d = msg.optJSONObject("d");
                    if (d != null) {
                        sessionId = d.optString("session_id", null);
                        String rgu = d.optString("resume_gateway_url", null);
                        if (rgu != null) resumeGatewayUrl = rgu + "/?v=9&encoding=json";
                    }
                    Log.d(TAG, "READY! session=" + sessionId);
                    if (currentTitle != null) sendPresence();
                } else if ("RESUMED".equals(t)) {
                    identified = true;
                    Log.d(TAG, "Session resumed");
                    if (currentTitle != null) sendPresence();
                }
                break;
            case 7: // Reconnect
                closeSocket();
                handler.postDelayed(() -> connect(ctx), 200);
                break;
            case 9: // Invalid session
                identified = false;
                sessionId = null;
                resumeGatewayUrl = null;
                lastSequence = null;
                // Re-identify after 150ms
                handler.postDelayed(this::sendIdentify, 150);
                break;
        }
    }

    private void sendIdentify() {
        if (token == null) return;
        try {
            JSONObject d = new JSONObject();
            d.put("token", token);
            JSONObject props = new JSONObject();
            props.put("os", "Android");
            props.put("browser", "Discord Android");
            props.put("device", android.os.Build.DEVICE);
            d.put("properties", props);
            d.put("intents", 0);

            JSONObject payload = new JSONObject();
            payload.put("op", 2);
            payload.put("d", d);
            send(payload);
            Log.d(TAG, "Sent IDENTIFY");
        } catch (Exception e) { Log.e(TAG, "Identify failed", e); }
    }

    private void sendResume() {
        try {
            JSONObject d = new JSONObject();
            d.put("token", token);
            d.put("session_id", sessionId);
            d.put("seq", lastSequence);

            JSONObject payload = new JSONObject();
            payload.put("op", 6);
            payload.put("d", d);
            send(payload);
            Log.d(TAG, "Sent RESUME");
        } catch (Exception e) { Log.e(TAG, "Resume failed", e); }
    }

    private void sendPresence() {
        try {
            JSONObject d = new JSONObject();
            d.put("since", System.currentTimeMillis());
            d.put("status", "online");
            d.put("afk", true);

            JSONArray activities = new JSONArray();
            if (currentTitle != null && !currentTitle.isEmpty()) {
                JSONObject activity = new JSONObject();
                activity.put("name", "Eclipse");
                activity.put("type", 2); // LISTENING
                activity.put("application_id", APP_ID);
                activity.put("details", currentTitle);
                String artistDisplay = currentArtist != null ? currentArtist : "Unknown";
                if (isPaused) {
                    activity.put("state", artistDisplay + " (Paused)");
                } else {
                    activity.put("state", artistDisplay);
                    
                    // Timestamps (Discord expects milliseconds)
                    JSONObject timestamps = new JSONObject();
                    timestamps.put("start", playStartMs);
                    if (durationMs > 1000) {
                        timestamps.put("end", playStartMs + durationMs);
                    }
                    activity.put("timestamps", timestamps);
                }

                // Assets — resolve external image for cover art
                JSONObject assets = new JSONObject();
                if (currentThumb != null && !currentThumb.isEmpty()) {
                    String resolvedImage = resolveExternalAsset(currentThumb);
                    if (resolvedImage != null) {
                        assets.put("large_image", resolvedImage);
                    } else {
                        assets.put("large_image", currentThumb);
                    }
                    assets.put("large_text", currentTitle + " - " + currentArtist);
                } else {
                    assets.put("large_image", "eclipse_logo");
                    assets.put("large_text", "Eclipse");
                }
                activity.put("assets", assets);

                // Buttons
                JSONArray buttons = new JSONArray();
                buttons.put("Listen on YouTube Music");
                activity.put("buttons", buttons);

                JSONObject metadata = new JSONObject();
                JSONArray btnUrls = new JSONArray();
                MainActivity.Track ct = PlaybackManager.get().currentTrack();
                if (ct != null && ct.sourceId != null) {
                    btnUrls.put("https://music.youtube.com/watch?v=" + ct.sourceId);
                } else {
                    btnUrls.put("https://music.youtube.com");
                }
                metadata.put("button_urls", btnUrls);
                activity.put("metadata", metadata);

                activities.put(activity);
            }
            d.put("activities", activities);

            JSONObject payload = new JSONObject();
            payload.put("op", 3);
            payload.put("d", d);
            send(payload);
            Log.d(TAG, "Presence updated: " + currentTitle);
        } catch (Exception e) { Log.e(TAG, "Presence failed", e); }
    }

    /**
     * Resolve external image URL to Discord-hosted mp: asset (like Metrolist/KizzyRPC).
     * POST /api/v9/applications/{APP_ID}/external-assets with {"urls":["..."]}
     */
    private String resolveExternalAsset(String imageUrl) {
        if (imageUrl.startsWith("mp:")) return imageUrl;
        try {
            String api = "https://discord.com/api/v9/applications/" + APP_ID + "/external-assets";
            JSONObject body = new JSONObject();
            JSONArray urls = new JSONArray();
            urls.put(imageUrl);
            body.put("urls", urls);

            Request req = new Request.Builder().url(api)
                    .header("Authorization", token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Discord-Android/314013;RNA")
                    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONArray arr = new JSONArray(resp.body().string());
                    if (arr.length() > 0) {
                        String path = arr.getJSONObject(0).optString("external_asset_path", null);
                        if (path != null) return "mp:" + path;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "External asset resolve failed: " + e.getMessage());
        }
        return null;
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) handler.removeCallbacks(heartbeatTask);
        heartbeatTask = new Runnable() {
            @Override public void run() {
                try {
                    JSONObject hb = new JSONObject();
                    hb.put("op", 1);
                    hb.put("d", lastSequence != null ? lastSequence : JSONObject.NULL);
                    send(hb);
                } catch (Exception e) {}
                handler.postDelayed(this, heartbeatInterval);
            }
        };
        handler.postDelayed(heartbeatTask, heartbeatInterval);
    }

    private void send(JSONObject payload) {
        if (ws != null) ws.send(payload.toString());
    }
}
