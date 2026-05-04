package com.eclipseapp.pulse;

import android.content.Context;
import android.util.Log;

import java.io.DataOutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * Last.fm Scrobbler — handles Now Playing updates and track scrobbles.
 * Auth: Last.fm Mobile Session (getMobileSession) using API key + secret + md5 credentials.
 * The user provides their own API key/secret via Settings.
 */
final class LastFmScrobbler {
    private static final String TAG = "LastFmScrobbler";
    private static final String API_URL = "https://ws.audioscrobbler.com/2.0/";
    private static final LastFmScrobbler INSTANCE = new LastFmScrobbler();

    private String sessionKey;

    private LastFmScrobbler() {}

    static LastFmScrobbler get() { return INSTANCE; }

    /** Call when app starts or user logs in to get a session key */
    void authenticate(Context context, Runnable onSuccess, Runnable onFailure) {
        String username = LocalStorageManager.getLastFmUsername(context);
        String password = LocalStorageManager.getLastFmPassword(context);
        String apiKey   = LocalStorageManager.getLastFmApiKey(context);
        String apiSecret= LocalStorageManager.getLastFmApiSecret(context);

        if (username.isEmpty() || password.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
            if (onFailure != null) onFailure.run();
            return;
        }

        new Thread(() -> {
            try {
                String authToken = md5(username.toLowerCase() + md5(password));
                TreeMap<String, String> params = new TreeMap<>();
                params.put("method", "auth.getMobileSession");
                params.put("api_key", apiKey);
                params.put("username", username);
                params.put("authToken", authToken);
                params.put("api_sig", sign(params, apiSecret));
                params.put("format", "json");

                String response = post(params);
                if (response != null && response.contains("\"key\"")) {
                    // Parse key from: {"session":{"name":"user","key":"XXXXX","subscriber":0}}
                    int start = response.indexOf("\"key\":\"") + 7;
                    int end = response.indexOf("\"", start);
                    sessionKey = response.substring(start, end);
                    LocalStorageManager.saveSetting(context, "lastfm_session_key", sessionKey);
                    if (onSuccess != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onSuccess);
                } else {
                    if (onFailure != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onFailure);
                }
            } catch (Exception e) {
                Log.e(TAG, "Auth failed", e);
                if (onFailure != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onFailure);
            }
        }, "lastfm-auth").start();
    }

    void init(Context context) {
        String saved = LocalStorageManager.getSettings(context).optString("lastfm_session_key", "");
        if (!saved.isEmpty()) sessionKey = saved;
    }

    boolean isAuthenticated() {
        return sessionKey != null && !sessionKey.isEmpty();
    }

    void logout(Context context) {
        sessionKey = null;
        LocalStorageManager.saveSetting(context, "lastfm_session_key", "");
    }

    /** Call when track starts playing */
    void updateNowPlaying(Context context, String title, String artist) {
        if (!isAuthenticated()) return;
        String apiKey    = LocalStorageManager.getLastFmApiKey(context);
        String apiSecret = LocalStorageManager.getLastFmApiSecret(context);
        if (apiKey.isEmpty()) return;

        new Thread(() -> {
            try {
                TreeMap<String, String> params = new TreeMap<>();
                params.put("method", "track.updateNowPlaying");
                params.put("track", title);
                params.put("artist", artist);
                params.put("api_key", apiKey);
                params.put("sk", sessionKey);
                params.put("api_sig", sign(params, apiSecret));
                params.put("format", "json");
                post(params);
            } catch (Exception e) {
                Log.e(TAG, "NowPlaying failed", e);
            }
        }, "lastfm-nowplaying").start();
    }

    /** Call when track has been played for >50% or >4 minutes */
    void scrobble(Context context, String title, String artist, long startTimestamp) {
        if (!isAuthenticated()) return;
        String apiKey    = LocalStorageManager.getLastFmApiKey(context);
        String apiSecret = LocalStorageManager.getLastFmApiSecret(context);
        if (apiKey.isEmpty()) return;

        new Thread(() -> {
            try {
                TreeMap<String, String> params = new TreeMap<>();
                params.put("method", "track.scrobble");
                params.put("track[0]", title);
                params.put("artist[0]", artist);
                params.put("timestamp[0]", String.valueOf(startTimestamp / 1000L));
                params.put("api_key", apiKey);
                params.put("sk", sessionKey);
                params.put("api_sig", sign(params, apiSecret));
                params.put("format", "json");
                post(params);
                Log.d(TAG, "Scrobbled: " + title + " - " + artist);
            } catch (Exception e) {
                Log.e(TAG, "Scrobble failed", e);
            }
        }, "lastfm-scrobble").start();
    }

    private String sign(TreeMap<String, String> params, String secret) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            if (!e.getKey().equals("format") && !e.getKey().equals("callback")) {
                sb.append(e.getKey()).append(e.getValue());
            }
        }
        sb.append(secret);
        return md5(sb.toString());
    }

    private String post(TreeMap<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append("&");
            body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.writeBytes(body.toString());
        }
        if (conn.getResponseCode() == 200) {
            java.io.InputStream is = conn.getInputStream();
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
        return null;
    }

    static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (Exception e) { return ""; }
    }
}
