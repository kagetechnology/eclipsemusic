package com.eclipseapp.pulse;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics client using LrcLib.net (same as Metrolist).
 * Completely free, no API key, no auth needed.
 * Also provides search suggestions via YouTube Music InnerTube.
 */
final class MusicApiClient {
    private static final String LRCLIB_URL = "https://lrclib.net/api/search";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Patterns to clean title (ported from Metrolist LrcLib.kt)
    private static final Pattern[] TITLE_CLEANUP_PATTERNS = {
            Pattern.compile("\\s*\\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*【.*?】"),
            Pattern.compile("\\s*\\|.*$"),
            Pattern.compile("\\s*-\\s*(official|video|audio|lyrics|lyric|visualizer).*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\(feat\\..*?\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\(ft\\..*?\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*feat\\..*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*ft\\..*$", Pattern.CASE_INSENSITIVE),
    };

    private static final String[] ARTIST_SEPARATORS = {" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with "};

    interface LyricsCallback {
        void onResult(String plainLyrics, String syncedLyrics, String status);
    }

    interface SearchSuggestionsCallback {
        void onResult(java.util.List<String> suggestions);
    }

    private MusicApiClient() {}

    /**
     * Get lyrics from LrcLib.net (same system as Metrolist).
     * Uses multi-strategy search like Metrolist:
     * 1. Cleaned title + cleaned artist
     * 2. Cleaned title only
     * 3. Combined query search
     */
    static void getLyrics(String title, String artist, LyricsCallback callback) {
        new Thread(() -> {
            try {
                String cleanedTitle = cleanTitle(title);
                String cleanedArtist = cleanArtist(artist);

                // Strategy 1: track_name + artist_name
                JSONArray results = lrcLibSearch(cleanedTitle, cleanedArtist, null);
                JSONObject best = findBestLyrics(results, cleanedTitle, cleanedArtist);

                // Strategy 2: track_name only
                if (best == null) {
                    results = lrcLibSearch(cleanedTitle, null, null);
                    best = findBestLyrics(results, cleanedTitle, cleanedArtist);
                }

                // Strategy 3: query search
                if (best == null) {
                    results = lrcLibSearch(null, null, cleanedArtist + " " + cleanedTitle);
                    best = findBestLyrics(results, cleanedTitle, cleanedArtist);
                }

                // Strategy 4: query with just title
                if (best == null) {
                    results = lrcLibSearch(null, null, cleanedTitle);
                    best = findBestLyrics(results, cleanedTitle, cleanedArtist);
                }

                // Strategy 5: original title + artist
                if (best == null && !cleanedTitle.equals(title.trim())) {
                    results = lrcLibSearch(title.trim(), artist.trim(), null);
                    best = findBestLyrics(results, title, artist);
                }

                if (best != null) {
                    String synced = best.optString("syncedLyrics", "");
                    String plain = best.optString("plainLyrics", "");
                    final String fSynced = synced;
                    final String fPlain = plain;
                    mainHandler.post(() -> callback.onResult(fPlain, fSynced, "Lirik ditemukan"));
                } else {
                    mainHandler.post(() -> callback.onResult("", "", "Lirik tidak ditemukan"));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResult("", "", "Gagal mencari lirik: " + e.getMessage()));
            }
        }, "lrclib-lyrics").start();
    }

    /**
     * Get search suggestions from YouTube Music InnerTube.
     */
    static void getSearchSuggestions(String query, SearchSuggestionsCallback callback) {
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String url = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";

                JSONObject body = new JSONObject();
                JSONObject context = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", "WEB_REMIX");
                client.put("clientVersion", "1.20240410.01.00");
                client.put("hl", "id");
                client.put("gl", "ID");
                context.put("client", client);
                body.put("context", context);
                body.put("input", query);

                String json = httpPost(url, body.toString());
                JSONObject root = new JSONObject(json);

                java.util.List<String> suggestions = new java.util.ArrayList<>();
                JSONArray contents = root.optJSONArray("contents");
                if (contents != null) {
                    for (int i = 0; i < contents.length(); i++) {
                        JSONObject item = contents.optJSONObject(i);
                        if (item == null) continue;
                        JSONObject renderer = item.optJSONObject("searchSuggestionRenderer");
                        if (renderer == null) continue;
                        JSONObject suggestion = renderer.optJSONObject("suggestion");
                        if (suggestion == null) continue;
                        JSONArray runs = suggestion.optJSONArray("runs");
                        if (runs == null) continue;
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < runs.length(); j++) {
                            JSONObject run = runs.optJSONObject(j);
                            if (run != null) sb.append(run.optString("text", ""));
                        }
                        if (sb.length() > 0) suggestions.add(sb.toString());
                    }
                }
                mainHandler.post(() -> callback.onResult(suggestions));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResult(new java.util.ArrayList<>()));
            }
        }, "search-suggestions").start();
    }

    // ==================== LrcLib Helpers ====================

    private static JSONArray lrcLibSearch(String trackName, String artistName, String query) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(LRCLIB_URL);
        urlBuilder.append("?");
        if (query != null) {
            urlBuilder.append("q=").append(URLEncoder.encode(query, "UTF-8"));
        }
        if (trackName != null) {
            if (query != null) urlBuilder.append("&");
            urlBuilder.append("track_name=").append(URLEncoder.encode(trackName, "UTF-8"));
        }
        if (artistName != null) {
            urlBuilder.append("&artist_name=").append(URLEncoder.encode(artistName, "UTF-8"));
        }

        String json = httpGet(urlBuilder.toString());
        return new JSONArray(json);
    }

    private static JSONObject findBestLyrics(JSONArray results, String title, String artist) {
        if (results == null || results.length() == 0) return null;

        // Prefer synced lyrics, then plain
        JSONObject bestSynced = null;
        JSONObject bestPlain = null;
        double bestSyncedScore = -1;
        double bestPlainScore = -1;

        for (int i = 0; i < results.length(); i++) {
            JSONObject track = results.optJSONObject(i);
            if (track == null) continue;

            String syncedLyrics = track.optString("syncedLyrics", "");
            String plainLyrics = track.optString("plainLyrics", "");
            if (syncedLyrics.isEmpty() && plainLyrics.isEmpty()) continue;

            String trackName = track.optString("trackName", "");
            String artistName = track.optString("artistName", "");
            double score = similarity(title, trackName) + similarity(artist, artistName);

            if (!syncedLyrics.isEmpty() && score > bestSyncedScore) {
                bestSyncedScore = score;
                bestSynced = track;
            }
            if (!plainLyrics.isEmpty() && score > bestPlainScore) {
                bestPlainScore = score;
                bestPlain = track;
            }
        }

        return bestSynced != null ? bestSynced : bestPlain;
    }

    private static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        String s1 = a.trim().toLowerCase();
        String s2 = b.trim().toLowerCase();
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0;
        if (s1.contains(s2) || s2.contains(s1)) return 0.8;
        return 0;
    }

    // ==================== Title/Artist Cleanup (Metrolist patterns) ====================

    private static String cleanTitle(String title) {
        if (title == null) return "";
        String cleaned = title.trim();
        for (Pattern pattern : TITLE_CLEANUP_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }
        return cleaned.trim();
    }

    private static String cleanArtist(String artist) {
        if (artist == null) return "";
        String cleaned = artist.trim();
        for (String sep : ARTIST_SEPARATORS) {
            int idx = cleaned.toLowerCase().indexOf(sep.toLowerCase());
            if (idx > 0) {
                cleaned = cleaned.substring(0, idx);
                break;
            }
        }
        return cleaned.trim();
    }

    // ==================== HTTP ====================

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "EclipseApp/1.0 (https://github.com/eclipseapp)");
        connection.setRequestProperty("Accept", "application/json");

        try {
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new Exception("HTTP " + code);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String httpPost(String urlStr, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Eclipse/1.0");
        connection.setRequestProperty("Origin", "https://music.youtube.com");
        connection.setRequestProperty("Referer", "https://music.youtube.com/");

        try (java.io.OutputStream os = connection.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        try {
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new Exception("HTTP " + code);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            connection.disconnect();
        }
    }
}
