package com.eclipseapp.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All local data stored in SharedPreferences as JSON.
 * No database needed — simple and portable.
 */
final class LocalStorageManager {
    private static final String PREFS_NAME = "eclipse_music_data";
    private static final String KEY_LIKED_SONGS = "liked_songs_v1";
    private static final String KEY_PLAYLISTS = "playlists_v1";
    private static final String KEY_HISTORY = "play_history_v1";
    private static final String KEY_DOWNLOADS = "downloads_v1";
    private static final String KEY_SETTINGS = "settings_v1";
    private static final int MAX_HISTORY = 100;

    private LocalStorageManager() {}

    // ==================== LIKED SONGS ====================

    static List<JSONObject> getLikedSongs(Context context) {
        return loadJsonArray(context, KEY_LIKED_SONGS);
    }

    static boolean isLiked(Context context, String title, String artist) {
        List<JSONObject> songs = getLikedSongs(context);
        for (JSONObject song : songs) {
            if (matchesSong(song, title, artist)) return true;
        }
        return false;
    }

    static boolean toggleLike(Context context, MainActivity.Track track) {
        if (track == null) return false;
        List<JSONObject> songs = getLikedSongs(context);

        // Check if already liked
        for (int i = 0; i < songs.size(); i++) {
            if (matchesSong(songs.get(i), track.title, track.artist)) {
                songs.remove(i);
                saveJsonArray(context, KEY_LIKED_SONGS, songs);
                return false; // unliked
            }
        }

        // Add to liked
        songs.add(0, trackToJson(track));
        saveJsonArray(context, KEY_LIKED_SONGS, songs);
        return true; // liked
    }

    // ==================== PLAYLISTS ====================

    static List<JSONObject> getPlaylists(Context context) {
        return loadJsonArray(context, KEY_PLAYLISTS);
    }

    static String createPlaylist(Context context, String name) {
        List<JSONObject> playlists = getPlaylists(context);
        String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            JSONObject playlist = new JSONObject();
            playlist.put("id", id);
            playlist.put("name", name);
            playlist.put("songs", new JSONArray());
            playlist.put("createdAt", System.currentTimeMillis());
            playlists.add(0, playlist);
            saveJsonArray(context, KEY_PLAYLISTS, playlists);
        } catch (Exception ignored) {}
        return id;
    }

    static void renamePlaylist(Context context, String playlistId, String newName) {
        List<JSONObject> playlists = getPlaylists(context);
        for (JSONObject playlist : playlists) {
            if (playlistId.equals(playlist.optString("id"))) {
                try { playlist.put("name", newName); } catch (Exception ignored) {}
                break;
            }
        }
        saveJsonArray(context, KEY_PLAYLISTS, playlists);
    }

    static void deletePlaylist(Context context, String playlistId) {
        List<JSONObject> playlists = getPlaylists(context);
        playlists.removeIf(p -> playlistId.equals(p.optString("id")));
        saveJsonArray(context, KEY_PLAYLISTS, playlists);
    }

    static JSONObject getPlaylist(Context context, String playlistId) {
        List<JSONObject> playlists = getPlaylists(context);
        for (JSONObject playlist : playlists) {
            if (playlistId.equals(playlist.optString("id"))) return playlist;
        }
        return null;
    }

    static void addToPlaylist(Context context, String playlistId, MainActivity.Track track) {
        List<JSONObject> playlists = getPlaylists(context);
        for (JSONObject playlist : playlists) {
            if (playlistId.equals(playlist.optString("id"))) {
                try {
                    JSONArray songs = playlist.optJSONArray("songs");
                    if (songs == null) songs = new JSONArray();
                    // Check duplicate
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject s = songs.optJSONObject(i);
                        if (s != null && matchesSong(s, track.title, track.artist)) return;
                    }
                    songs.put(trackToJson(track));
                    playlist.put("songs", songs);
                } catch (Exception ignored) {}
                break;
            }
        }
        saveJsonArray(context, KEY_PLAYLISTS, playlists);
    }

    static void removeFromPlaylist(Context context, String playlistId, String title, String artist) {
        List<JSONObject> playlists = getPlaylists(context);
        for (JSONObject playlist : playlists) {
            if (playlistId.equals(playlist.optString("id"))) {
                try {
                    JSONArray songs = playlist.optJSONArray("songs");
                    if (songs == null) break;
                    JSONArray filtered = new JSONArray();
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject s = songs.optJSONObject(i);
                        if (s != null && !matchesSong(s, title, artist)) {
                            filtered.put(s);
                        }
                    }
                    playlist.put("songs", filtered);
                } catch (Exception ignored) {}
                break;
            }
        }
        saveJsonArray(context, KEY_PLAYLISTS, playlists);
    }

    // ==================== PLAY HISTORY ====================

    static List<JSONObject> getHistory(Context context) {
        return loadJsonArray(context, KEY_HISTORY);
    }

    static void addToHistory(Context context, MainActivity.Track track) {
        if (track == null) return;
        List<JSONObject> history = getHistory(context);

        // Remove duplicate if exists
        history.removeIf(h -> matchesSong(h, track.title, track.artist));

        // Add to top
        try {
            JSONObject entry = trackToJson(track);
            entry.put("playedAt", System.currentTimeMillis());
            history.add(0, entry);
        } catch (Exception ignored) {}

        // Trim to max
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }

        saveJsonArray(context, KEY_HISTORY, history);
    }

    static void clearHistory(Context context) {
        saveJsonArray(context, KEY_HISTORY, new ArrayList<>());
    }

    // ==================== DOWNLOADS ====================

    static List<JSONObject> getDownloads(Context context) {
        return loadJsonArray(context, KEY_DOWNLOADS);
    }

    static void addDownload(Context context, MainActivity.Track track, String filePath) {
        if (track == null) return;
        List<JSONObject> downloads = getDownloads(context);

        // Remove duplicate
        downloads.removeIf(d -> matchesSong(d, track.title, track.artist));

        try {
            JSONObject entry = trackToJson(track);
            entry.put("filePath", filePath);
            entry.put("downloadedAt", System.currentTimeMillis());
            downloads.add(0, entry);
        } catch (Exception ignored) {}

        saveJsonArray(context, KEY_DOWNLOADS, downloads);
    }

    static void removeDownload(Context context, String title, String artist) {
        List<JSONObject> downloads = getDownloads(context);
        downloads.removeIf(d -> matchesSong(d, title, artist));
        saveJsonArray(context, KEY_DOWNLOADS, downloads);
    }

    static String getDownloadPath(Context context, String title, String artist) {
        List<JSONObject> downloads = getDownloads(context);
        for (JSONObject d : downloads) {
            if (matchesSong(d, title, artist)) return d.optString("filePath", "");
        }
        return null;
    }

    static String getDownloadPathBySourceId(Context context, String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        List<JSONObject> downloads = getDownloads(context);
        for (JSONObject d : downloads) {
            if (sourceId.equals(d.optString("sourceId", ""))) return d.optString("filePath", "");
        }
        return null;
    }

    static boolean isDownloaded(Context context, String sourceId) {
        String path = getDownloadPathBySourceId(context, sourceId);
        if (path == null || path.isEmpty()) return false;
        return new java.io.File(path).exists();
    }

    // ==================== SETTINGS ====================

    static JSONObject getSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_SETTINGS, "{}");
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    static void saveSetting(Context context, String key, Object value) {
        JSONObject settings = getSettings(context);
        try { settings.put(key, value); } catch (Exception ignored) {}
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SETTINGS, settings.toString()).apply();
    }

    // "low" = 48kbps, "medium" = 128kbps, "high" = best available
    static String getAudioQuality(Context context) {
        return getSettings(context).optString("audio_quality", "high");
    }

    static void setAudioQuality(Context context, String quality) {
        saveSetting(context, "audio_quality", quality);
    }

    // ==================== CROSSFADE ====================

    static int getCrossfadeDuration(Context context) {
        return getSettings(context).optInt("crossfade_seconds", 0);
    }

    static void setCrossfadeDuration(Context context, int seconds) {
        saveSetting(context, "crossfade_seconds", seconds);
    }

    // ==================== LAST.FM ====================

    static String getLastFmUsername(Context context) {
        return getSettings(context).optString("lastfm_username", "");
    }
    static String getLastFmPassword(Context context) {
        return getSettings(context).optString("lastfm_password", "");
    }
    static String getLastFmApiKey(Context context) {
        return getSettings(context).optString("lastfm_api_key", "");
    }
    static String getLastFmApiSecret(Context context) {
        return getSettings(context).optString("lastfm_api_secret", "");
    }
    static void saveLastFmCredentials(Context context, String username, String password, String apiKey, String apiSecret) {
        saveSetting(context, "lastfm_username", username);
        saveSetting(context, "lastfm_password", password);
        saveSetting(context, "lastfm_api_key", apiKey);
        saveSetting(context, "lastfm_api_secret", apiSecret);
    }

    // ==================== LAST SESSION ====================

    static void saveLastSession(Context context, MainActivity.Track track, int positionMs) {
        if (track == null) return;
        try {
            JSONObject session = trackToJson(track);
            session.put("positionMs", positionMs);
            saveSetting(context, "last_session", session.toString());
        } catch (Exception ignored) {}
    }

    static MainActivity.Track getLastSessionTrack(Context context) {
        try {
            String raw = getSettings(context).optString("last_session", "");
            if (raw.isEmpty()) return null;
            return jsonToTrack(new JSONObject(raw));
        } catch (Exception e) { return null; }
    }

    static int getLastSessionPosition(Context context) {
        try {
            String raw = getSettings(context).optString("last_session", "");
            if (raw.isEmpty()) return 0;
            return new JSONObject(raw).optInt("positionMs", 0);
        } catch (Exception e) { return 0; }
    }

    static void clearLastSession(Context context) {
        saveSetting(context, "last_session", "");
    }

    // ==================== LISTENING STATS ====================

    static void addListeningTime(Context context, String title, String artist, int seconds) {
        if (seconds <= 0 || title == null || title.isEmpty()) return;
        try {
            JSONObject stats = getStatsJson(context);
            long total = stats.optLong("total_seconds", 0) + seconds;
            stats.put("total_seconds", total);

            // Per-track stats
            JSONObject tracks = stats.optJSONObject("tracks");
            if (tracks == null) tracks = new JSONObject();
            String key = (title + " — " + artist).replace("\"", "");
            tracks.put(key, tracks.optInt(key, 0) + seconds);
            stats.put("tracks", tracks);

            // Per-artist stats
            JSONObject artists = stats.optJSONObject("artists");
            if (artists == null) artists = new JSONObject();
            String akey = (artist == null ? "Unknown" : artist).replace("\"", "");
            artists.put(akey, artists.optInt(akey, 0) + seconds);
            stats.put("artists", artists);

            saveStatsJson(context, stats);
        } catch (Exception ignored) {}
    }

    static JSONObject getStatsJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString("listening_stats", "{}");
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    private static void saveStatsJson(Context context, JSONObject stats) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("listening_stats", stats.toString()).apply();
    }

    static long getTotalListeningSeconds(Context context) {
        return getStatsJson(context).optLong("total_seconds", 0);
    }

    /** Returns top N tracks as List of "title — artist: Xmin" strings */
    static List<String> getTopTracks(Context context, int n) {
        List<String> result = new ArrayList<>();
        try {
            JSONObject tracks = getStatsJson(context).optJSONObject("tracks");
            if (tracks == null) return result;
            List<String[]> pairs = new ArrayList<>();
            java.util.Iterator<String> keys = tracks.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                pairs.add(new String[]{k, String.valueOf(tracks.optInt(k, 0))});
            }
            pairs.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
            for (int i = 0; i < Math.min(n, pairs.size()); i++) {
                int secs = Integer.parseInt(pairs.get(i)[1]);
                result.add(pairs.get(i)[0] + " — " + formatMinutes(secs));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Returns top N artists as List of "artist: Xmin" strings */
    static List<String> getTopArtists(Context context, int n) {
        List<String> result = new ArrayList<>();
        try {
            JSONObject artists = getStatsJson(context).optJSONObject("artists");
            if (artists == null) return result;
            List<String[]> pairs = new ArrayList<>();
            java.util.Iterator<String> keys = artists.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                pairs.add(new String[]{k, String.valueOf(artists.optInt(k, 0))});
            }
            pairs.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
            for (int i = 0; i < Math.min(n, pairs.size()); i++) {
                int secs = Integer.parseInt(pairs.get(i)[1]);
                result.add(pairs.get(i)[0] + " — " + formatMinutes(secs));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String formatMinutes(int totalSeconds) {
        int h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        return m + " min";
    }

    // ==================== CONVERTERS ====================

    static JSONObject trackToJson(MainActivity.Track track) {
        JSONObject json = new JSONObject();
        try {
            json.put("title", track.title == null ? "" : track.title);
            json.put("artist", track.artist == null ? "" : track.artist);
            json.put("meta", track.meta == null ? "" : track.meta);
            json.put("artStyle", track.artStyle);
            json.put("thumbnailUrl", track.thumbnailUrl == null ? "" : track.thumbnailUrl);
            json.put("audioUrl", track.audioUrl == null ? "" : track.audioUrl);
            json.put("sourceId", track.sourceId == null ? "" : track.sourceId);
            json.put("sourceName", track.sourceName == null ? "" : track.sourceName);
        } catch (Exception ignored) {}
        return json;
    }

    static MainActivity.Track jsonToTrack(JSONObject json) {
        if (json == null) return null;
        return new MainActivity.Track(
                json.optString("title", ""),
                json.optString("artist", ""),
                json.optString("meta", ""),
                json.optInt("artStyle", 0),
                json.optString("thumbnailUrl", ""),
                null, // Bitmap loaded lazily
                json.optString("audioUrl", ""),
                json.optString("sourceId", ""),
                json.optString("sourceName", "Local")
        );
    }

    static List<MainActivity.Track> jsonArrayToTracks(List<JSONObject> jsonList) {
        List<MainActivity.Track> tracks = new ArrayList<>();
        for (JSONObject json : jsonList) {
            MainActivity.Track track = jsonToTrack(json);
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    // ==================== HELPERS ====================

    private static boolean matchesSong(JSONObject json, String title, String artist) {
        if (json == null) return false;
        String t = json.optString("title", "").trim().toLowerCase();
        String a = json.optString("artist", "").trim().toLowerCase();
        return t.equals((title == null ? "" : title).trim().toLowerCase())
                && a.equals((artist == null ? "" : artist).trim().toLowerCase());
    }

    private static List<JSONObject> loadJsonArray(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(key, "[]");
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) list.add(obj);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static void saveJsonArray(Context context, String key, List<JSONObject> list) {
        JSONArray array = new JSONArray();
        for (JSONObject obj : list) array.put(obj);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(key, array.toString()).apply();
    }
}
