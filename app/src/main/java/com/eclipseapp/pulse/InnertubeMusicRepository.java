package com.eclipseapp.pulse;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

final class InnertubeMusicRepository {
    private static final String SONG_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";

    // Simple in-memory cache: query → tracks (max 20 entries)
    private final Map<String, List<MainActivity.Track>> searchCache =
        new LinkedHashMap<String, List<MainActivity.Track>>(20, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, List<MainActivity.Track>> e) {
                return size() > 20;
            }
        };

    interface Callback {
        void onLoaded(List<MainActivity.Track> tracks, String status);
    }

    interface TrackCallback {
        void onLoaded(MainActivity.Track track, String status);
    }

    private static final String ENDPOINT =
            "https://music.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
    private static final String CLIENT_VERSION = "1.20240410.01.00";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    void loadHome(Callback callback) {
        executor.execute(() -> {
            String[] queries = {"trending music indonesia", "new releases 2024", "top hits global"};
            final List<MainActivity.Track> combined = new ArrayList<>();
            final Set<String> seenIds = new HashSet<>();
            CountDownLatch latch = new CountDownLatch(queries.length);
            
            for (String q : queries) {
                executor.execute(() -> {
                    try {
                        String json = post(ENDPOINT, requestBody(q, SONG_FILTER));
                        List<MainActivity.Track> tracks = parseMusicRenderers(json);
                        synchronized (combined) {
                            for (MainActivity.Track t : tracks) {
                                if (t.sourceId != null && seenIds.add(t.sourceId)) {
                                    combined.add(t);
                                }
                            }
                        }
                    } catch (Exception e) {} finally {
                        latch.countDown();
                    }
                });
            }
            try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) {}
            if (combined.isEmpty()) combined.addAll(fallbackTracks());
            mainHandler.post(() -> callback.onLoaded(combined, "YouTube Music Feed"));
        });
    }

    void search(String query, Callback callback) {
        search(query == null || query.trim().isEmpty() ? "music" : query.trim(), false, callback);
    }

    void resolvePlayableTrack(MainActivity.Track seedTrack, TrackCallback callback) {
        String query = seedTrack == null ? "" : seedTrack.query();
        String safeQuery = query == null || query.trim().isEmpty() ? "music" : query.trim();
        executor.execute(() -> {
            try {
                String json = post(ENDPOINT, requestBody(safeQuery, SONG_FILTER));
                List<MainActivity.Track> tracks = parseMusicRenderers(json);
                MainActivity.Track best = firstPlayableTrack(tracks);
                mainHandler.post(() -> callback.onLoaded(best, best == null
                        ? "Source YouTube tidak ditemukan"
                        : "Source YouTube ditemukan"));
            } catch (Exception exception) {
                mainHandler.post(() -> callback.onLoaded(null, "Gagal mencari source YouTube"));
            }
        });
    }

    private void search(String query, boolean home, Callback callback) {
        // Return cached result immediately if available
        synchronized (searchCache) {
            List<MainActivity.Track> cached = searchCache.get(query);
            if (cached != null && !cached.isEmpty()) {
                mainHandler.post(() -> callback.onLoaded(cached,
                        home ? "YouTube Music Songs" : "Hasil lagu dari YouTube Music"));
                return;
            }
        }
        executor.execute(() -> {
            try {
                String json = post(ENDPOINT, requestBody(query, SONG_FILTER));
                List<MainActivity.Track> tracks = parseMusicRenderers(json);
                if (tracks.isEmpty()) {
                    tracks = fallbackTracks();
                }
                synchronized (searchCache) {
                    searchCache.put(query, tracks);
                }
                List<MainActivity.Track> finalTracks = tracks;
                mainHandler.post(() -> callback.onLoaded(
                        finalTracks,
                        home ? "YouTube Music Songs" : "Hasil lagu dari YouTube Music"
                ));
            } catch (Exception exception) {
                mainHandler.post(() -> callback.onLoaded(fallbackTracks(), "InnerTube gagal, pakai data lokal"));
            }
        });
    }

    /**
     * Fetch related/radio tracks for a given videoId (like Metrolist's autoplay queue).
     * Uses YouTube Music "next" endpoint to get the "Up Next" list.
     */
    void fetchRadio(String videoId, Callback callback) {
        if (videoId == null || videoId.isEmpty()) {
            mainHandler.post(() -> callback.onLoaded(new ArrayList<>(), "No video ID"));
            return;
        }
        executor.execute(() -> {
            try {
                String nextEndpoint = "https://music.youtube.com/youtubei/v1/next?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
                JSONObject body = new JSONObject();
                JSONObject context = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", "WEB_REMIX");
                client.put("clientVersion", CLIENT_VERSION);
                client.put("hl", "id");
                client.put("gl", "ID");
                context.put("client", client);
                body.put("context", context);
                body.put("videoId", videoId);
                body.put("isAudioOnly", true);
                body.put("enablePersistentPlaylistPanel", true);
                // Request radio/autoplay
                body.put("playlistId", "RDAMVM" + videoId);

                String json = post(nextEndpoint, body.toString());
                List<MainActivity.Track> related = parseNextResults(json, videoId);
                mainHandler.post(() -> callback.onLoaded(related,
                        related.isEmpty() ? "No related songs found" : related.size() + " related songs"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onLoaded(new ArrayList<>(), "Radio fetch failed"));
            }
        });
    }

    // Import Playlist Feature
    void importPlaylist(android.content.Context context, String playlistUrl, Callback callback) {
        executor.execute(() -> {
            try {
                String plId = "";
                if (playlistUrl.contains("list=")) {
                    plId = playlistUrl.split("list=")[1].split("&")[0];
                } else {
                    plId = playlistUrl;
                }
                
                String browseEndpoint = "https://music.youtube.com/youtubei/v1/browse?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
                JSONObject root = new JSONObject();
                JSONObject ctx = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", "WEB_REMIX");
                client.put("clientVersion", CLIENT_VERSION);
                client.put("hl", "id");
                client.put("gl", "ID");
                ctx.put("client", client);
                root.put("context", ctx);
                root.put("browseId", plId.startsWith("VL") ? plId : "VL" + plId);
                
                String json = post(browseEndpoint, root.toString());
                List<MainActivity.Track> tracks = parseMusicRenderers(json);
                
                if (tracks.isEmpty()) {
                    mainHandler.post(() -> callback.onLoaded(tracks, "Gagal import playlist (Kosong / ID Salah)"));
                    return;
                }
                
                String pid = LocalStorageManager.createPlaylist(context, "Imported: " + plId);
                for (MainActivity.Track t : tracks) {
                    LocalStorageManager.addToPlaylist(context, pid, t);
                }
                
                mainHandler.post(() -> callback.onLoaded(tracks, "Berhasil import " + tracks.size() + " lagu!"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onLoaded(new ArrayList<>(), "Error import playlist: " + e.getMessage()));
            }
        });
    }

    private List<MainActivity.Track> parseNextResults(String json, String currentVideoId) {
        List<MainActivity.Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (currentVideoId != null) seen.add(currentVideoId);
        try {
            JSONObject root = new JSONObject(json);
            // Navigate: contents > singleColumnMusicWatchNextResultsRenderer > tabbedRenderer >
            // watchNextTabbedResultsRenderer > tabs[0] > ... > playlistPanelRenderer > contents
            JSONObject contents = root.optJSONObject("contents");
            if (contents == null) return tracks;
            JSONObject scmwnr = contents.optJSONObject("singleColumnMusicWatchNextResultsRenderer");
            if (scmwnr == null) return tracks;
            JSONObject tabbedRenderer = scmwnr.optJSONObject("tabbedRenderer");
            if (tabbedRenderer == null) return tracks;
            JSONObject watchNext = tabbedRenderer.optJSONObject("watchNextTabbedResultsRenderer");
            if (watchNext == null) return tracks;
            JSONArray tabs = watchNext.optJSONArray("tabs");
            if (tabs == null || tabs.length() == 0) return tracks;
            JSONObject tab0 = tabs.optJSONObject(0);
            if (tab0 == null) return tracks;
            JSONObject tabRenderer = tab0.optJSONObject("tabRenderer");
            if (tabRenderer == null) return tracks;
            JSONObject tabContent = tabRenderer.optJSONObject("content");
            if (tabContent == null) return tracks;
            JSONObject musicQueue = tabContent.optJSONObject("musicQueueRenderer");
            if (musicQueue == null) return tracks;
            JSONObject queueContent = musicQueue.optJSONObject("content");
            if (queueContent == null) return tracks;
            JSONObject playlistPanel = queueContent.optJSONObject("playlistPanelRenderer");
            if (playlistPanel == null) return tracks;
            JSONArray items = playlistPanel.optJSONArray("contents");
            if (items == null) return tracks;

            for (int i = 0; i < items.length() && tracks.size() < 25; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject renderer = item.optJSONObject("playlistPanelVideoRenderer");
                if (renderer == null) continue;
                String vid = renderer.optString("videoId", "");
                if (vid.isEmpty() || seen.contains(vid)) continue;
                seen.add(vid);

                // Title
                String title = "";
                JSONObject titleObj = renderer.optJSONObject("title");
                if (titleObj != null) {
                    JSONArray runs = titleObj.optJSONArray("runs");
                    if (runs != null && runs.length() > 0)
                        title = runs.optJSONObject(0).optString("text", "");
                }
                if (title.isEmpty()) continue;

                // Artist from longBylineText
                String artist = "YouTube Music";
                JSONObject byline = renderer.optJSONObject("longBylineText");
                if (byline == null) byline = renderer.optJSONObject("shortBylineText");
                if (byline != null) {
                    JSONArray runs = byline.optJSONArray("runs");
                    if (runs != null && runs.length() > 0)
                        artist = runs.optJSONObject(0).optString("text", artist);
                }

                // Thumbnail (highest res)
                String thumbUrl = "";
                JSONObject thumb = renderer.optJSONObject("thumbnail");
                if (thumb != null) {
                    JSONArray thumbs = thumb.optJSONArray("thumbnails");
                    if (thumbs != null) {
                        for (int j = 0; j < thumbs.length(); j++) {
                            JSONObject t = thumbs.optJSONObject(j);
                            if (t != null) thumbUrl = t.optString("url", thumbUrl);
                        }
                    }
                }
                if (!thumbUrl.isEmpty()) {
                    thumbUrl = thumbUrl.replaceAll("w\\d+-h\\d+", "w544-h544");
                }

                // Duration
                String durText = "";
                JSONObject lenText = renderer.optJSONObject("lengthText");
                if (lenText != null) {
                    JSONArray runs = lenText.optJSONArray("runs");
                    if (runs != null && runs.length() > 0)
                        durText = runs.optJSONObject(0).optString("text", "");
                }

                tracks.add(new MainActivity.Track(title, artist, durText, 0, thumbUrl,
                        null, null, vid, "YouTube Music Radio"));
            }
        } catch (Exception e) {
            // parse error, return whatever we have
        }
        return tracks;
    }

    private String requestBody(String query, String params) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject context = new JSONObject();
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB_REMIX");
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", "id");
        client.put("gl", "ID");
        context.put("client", client);
        context.put("request", new JSONObject().put("useSsl", true));
        root.put("context", context);
        root.put("query", query);
        if (params != null && !params.trim().isEmpty()) {
            root.put("params", params);
        }
        return root.toString();
    }

    private String post(String endpoint, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", "https://music.youtube.com");
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Eclipse/1.0");

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes("UTF-8"));
        }

        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    private List<MainActivity.Track> parseMusicRenderers(String json) throws Exception {
        List<MainActivity.Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectTracks(new JSONObject(json), tracks, seen);
        return tracks;
    }

    private void collectTracks(Object node, List<MainActivity.Track> tracks, Set<String> seen) {
        if (tracks.size() >= 12 || node == null) {
            return;
        }

        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null) {
                MainActivity.Track track = parseTrack(renderer, tracks.size());
                if (track != null && seen.add(track.query())) {
                    tracks.add(track);
                }
            }
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                collectTracks(object.opt(names.optString(i)), tracks, seen);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectTracks(array.opt(i), tracks, seen);
            }
        }
    }

    private MainActivity.Track parseTrack(JSONObject renderer, int index) {
        if (!isSongRenderer(renderer)) {
            return null;
        }

        JSONArray flexColumns = renderer.optJSONArray("flexColumns");
        String title = "";
        String artist = "YouTube Music";
        String meta = "InnerTube";
        String videoId = songVideoIdFrom(renderer);

        if (flexColumns != null && flexColumns.length() > 0) {
            title = runsText(flexColumnText(flexColumns.optJSONObject(0)));
            if (flexColumns.length() > 1) {
                String subtitle = runsText(flexColumnText(flexColumns.optJSONObject(1)));
                if (!subtitle.isEmpty()) {
                    String[] parts = subtitle.split(" • ");
                    artist = likelyArtist(parts, artist);
                    meta = subtitle;
                }
            }
        }

        if (title.isEmpty()) {
            title = runsText(renderer.optJSONObject("title"));
        }
        if (isBadTitle(title)) {
            return null;
        }

        // Do NOT download bitmap here — adapters load thumbnails lazily via URL
        String thumb = thumbnailFrom(renderer);
        return new MainActivity.Track(clean(title), clean(artist), clean(meta), index, thumb, null, null, videoId, "YouTube Music Song");
    }

    private JSONObject flexColumnText(JSONObject column) {
        if (column == null) {
            return null;
        }
        JSONObject renderer = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return renderer == null ? null : renderer.optJSONObject("text");
    }

    private String runsText(JSONObject textObject) {
        if (textObject == null) {
            return "";
        }
        JSONArray runs = textObject.optJSONArray("runs");
        if (runs == null) {
            return clean(textObject.optString("text", "")).trim();
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) {
                continue;
            }
            String text = run.optString("text", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0 && !"•".equals(text)) {
                builder.append(' ');
            }
            builder.append(text);
            if ("•".equals(text)) {
                builder.append(' ');
            }
        }
        return clean(builder.toString()).replaceAll("\\s+", " ").replace(" • ", " • ").trim();
    }

    private String likelyArtist(String[] parts, String fallback) {
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()
                    && !"Song".equalsIgnoreCase(value)
                    && !"Video".equalsIgnoreCase(value)
                    && !"Album".equalsIgnoreCase(value)
                    && !"Single".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return fallback;
    }

    private boolean isBadTitle(String title) {
        String value = title == null ? "" : title.trim();
        return value.isEmpty()
                || value.length() > 140
                || value.startsWith("{")
                || value.startsWith("[")
                || value.contains("musicResponsiveListItemRenderer")
                || value.equalsIgnoreCase("Songs")
                || value.equalsIgnoreCase("Videos")
                || value.equalsIgnoreCase("Albums")
                || value.equalsIgnoreCase("Artists");
    }

    private String thumbnailFrom(JSONObject renderer) {
        JSONObject musicThumbnail = renderer
                .optJSONObject("thumbnail") == null
                ? null
                : renderer.optJSONObject("thumbnail").optJSONObject("musicThumbnailRenderer");
        JSONObject thumbnail = musicThumbnail == null ? null : musicThumbnail.optJSONObject("thumbnail");
        JSONArray candidates = thumbnail == null ? null : thumbnail.optJSONArray("thumbnails");
        if (candidates == null) {
            return "";
        }
        // Pick largest thumbnail (last in array = highest res)
        String best = "";
        int bestWidth = 0;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject t = candidates.optJSONObject(i);
            if (t == null) continue;
            String url = t.optString("url", "");
            int w = t.optInt("width", 0);
            if (!url.isEmpty() && w >= bestWidth) {
                best = url;
                bestWidth = w;
            }
        }
        // Upgrade to high-res (replace w/h params to 544)
        if (!best.isEmpty()) {
            best = best.replaceAll("w\\d+-h\\d+", "w544-h544");
            best = best.replaceAll("=w\\d+", "=w544");
            best = best.replaceAll("=h\\d+", "=h544");
        }
        return best;
    }

    private boolean isSongRenderer(JSONObject renderer) {
        if (renderer == null) {
            return false;
        }

        String pageType = browseEndpointPageType(
                renderer.optJSONObject("navigationEndpoint") == null
                        ? null
                        : renderer.optJSONObject("navigationEndpoint").optJSONObject("browseEndpoint")
        );
        if (!pageType.isEmpty()) {
            return false;
        }

        return !songVideoIdFrom(renderer).isEmpty();
    }

    private String browseEndpointPageType(JSONObject browseEndpoint) {
        if (browseEndpoint == null) {
            return "";
        }
        JSONObject supported = browseEndpoint.optJSONObject("browseEndpointContextSupportedConfigs");
        JSONObject musicConfig = supported == null ? null : supported.optJSONObject("browseEndpointContextMusicConfig");
        return musicConfig == null ? "" : musicConfig.optString("pageType", "");
    }

    private String songVideoIdFrom(JSONObject renderer) {
        if (renderer == null) {
            return "";
        }

        JSONObject playlistItemData = renderer.optJSONObject("playlistItemData");
        String playlistVideoId = playlistItemData == null ? "" : playlistItemData.optString("videoId", "");
        if (!playlistVideoId.isEmpty()) {
            return playlistVideoId;
        }

        JSONObject overlay = renderer.optJSONObject("overlay");
        JSONObject overlayRenderer = overlay == null ? null : overlay.optJSONObject("musicItemThumbnailOverlayRenderer");
        JSONObject content = overlayRenderer == null ? null : overlayRenderer.optJSONObject("content");
        JSONObject playButton = content == null ? null : content.optJSONObject("musicPlayButtonRenderer");
        JSONObject playNavigationEndpoint = playButton == null ? null : playButton.optJSONObject("playNavigationEndpoint");
        JSONObject watchEndpoint = playNavigationEndpoint == null ? null : playNavigationEndpoint.optJSONObject("watchEndpoint");
        String overlayVideoId = watchEndpoint == null ? "" : watchEndpoint.optString("videoId", "");
        if (!overlayVideoId.isEmpty()) {
            return overlayVideoId;
        }

        JSONObject navigationEndpoint = renderer.optJSONObject("navigationEndpoint");
        JSONObject directWatchEndpoint = navigationEndpoint == null ? null : navigationEndpoint.optJSONObject("watchEndpoint");
        return directWatchEndpoint == null ? "" : directWatchEndpoint.optString("videoId", "");
    }

    // downloadBitmap removed — thumbnails are loaded lazily by the adapter via loadThumb(url)

    private List<MainActivity.Track> fallbackTracks() {
        List<MainActivity.Track> tracks = new ArrayList<>();
        tracks.add(new MainActivity.Track("Cyber Synthesis", "Artist Name", "Local fallback", 0, "", null, null, "", "Fallback"));
        tracks.add(new MainActivity.Track("Midnight Echoes", "Smooth Quartette", "Local fallback", 1, "", null, null, "", "Fallback"));
        tracks.add(new MainActivity.Track("Blue Gravity", "Moon Roads", "Local fallback", 3, "", null, null, "", "Fallback"));
        tracks.add(new MainActivity.Track("Voltage Eye", "Midnight Echoes", "Local fallback", 4, "", null, null, "", "Fallback"));
        tracks.add(new MainActivity.Track("City Bloom", "Neon Cartel", "Local fallback", 5, "", null, null, "", "Fallback"));
        tracks.add(new MainActivity.Track("Neon Circuit", "Eclipse Lab", "Local fallback", 2, "", null, null, "", "Fallback"));
        return tracks;
    }

    private MainActivity.Track firstPlayableTrack(List<MainActivity.Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }
        for (MainActivity.Track track : tracks) {
            if (track != null && track.sourceId != null && !track.sourceId.trim().isEmpty()) {
                return track;
            }
        }
        return tracks.get(0);
    }

    private String clean(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
