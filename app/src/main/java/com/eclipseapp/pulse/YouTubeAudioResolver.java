package com.eclipseapp.pulse;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

final class YouTubeAudioResolver {
    static class Resolution {
        String url;
        long durationMs;
        Resolution(String url, long durationMs) {
            this.url = url;
            this.durationMs = durationMs;
        }
    }

    interface Callback {
        void onResolved(String audioUrl, long durationMs);

        void onError(String message);
    }

    private static final Map<String, Resolution> cache = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    /** Clear cache (e.g. when quality setting changes) */
    static void clearCache() { cache.clear(); }

    private YouTubeAudioResolver() {
    }

    static void resolve(String videoId, String quality, Callback callback) {
        if (videoId == null || videoId.trim().isEmpty()) {
            callback.onError("Track ini belum punya video id YouTube.");
            return;
        }

        String cleanVideoId = videoId.trim();
        String cacheKey = cleanVideoId + ":" + (quality == null ? "high" : quality);
        Resolution cached = cache.get(cacheKey);
        if (cached != null && cached.url != null && !cached.url.trim().isEmpty()) {
            callback.onResolved(cached.url, cached.durationMs);
            return;
        }

        new Thread(() -> {
            try {
                ensureInitialized();
                StreamInfo info = StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=" + cleanVideoId);
                List<AudioStream> audioStreams = info.getAudioStreams();
                if (audioStreams == null || audioStreams.isEmpty()) {
                    callback.onError("YouTube tidak mengembalikan audio stream.");
                    return;
                }

                // Filter by quality preference
                int maxBitrate = targetBitrate(quality);
                List<AudioStream> filtered = audioStreams.stream()
                        .filter(s -> s != null && s.getContent() != null && !s.getContent().trim().isEmpty())
                        .filter(s -> maxBitrate <= 0 || safeAverageBitrate(s) <= maxBitrate)
                        .collect(java.util.stream.Collectors.toList());
                // If no stream under target bitrate, fall back to all
                if (filtered.isEmpty()) filtered = audioStreams.stream()
                        .filter(s -> s != null && s.getContent() != null && !s.getContent().trim().isEmpty())
                        .collect(java.util.stream.Collectors.toList());

                AudioStream bestStream = filtered.stream()
                        .max(Comparator
                                .comparingInt(YouTubeAudioResolver::scoreStream)
                                .thenComparingInt(YouTubeAudioResolver::safeAverageBitrate))
                        .orElse(null);

                if (bestStream == null) {
                    callback.onError("Audio stream YouTube tidak valid.");
                    return;
                }

                String url = bestStream.getContent();
                long durationMs = info.getDuration() * 1000L;
                cache.put(cacheKey, new Resolution(url, durationMs));
                callback.onResolved(url, durationMs);
            } catch (Exception exception) {
                callback.onError(exception.getMessage() == null ? "Gagal mengambil audio dari YouTube." : exception.getMessage());
            }
        }, "youtube-audio-resolver").start();
    }

    /** Max bitrate in kbps per quality tier (0 = no limit) */
    private static int targetBitrate(String quality) {
        if (quality == null) return 0;
        switch (quality) {
            case "low":    return 70;    // ~48-64 kbps streams
            case "medium": return 150;   // ~128 kbps streams
            default:       return 0;     // high = no limit
        }
    }

    private static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        NewPipe.init(new NewPipeDownloader());
        initialized = true;
    }

    private static int scoreStream(AudioStream stream) {
        int score = safeAverageBitrate(stream);
        String trackName = stream.getAudioTrackName();
        if (trackName != null) {
            String lowered = trackName.toLowerCase();
            if (lowered.contains("original")) {
                score += 5000;
            }
            if (lowered.contains("descriptive") || lowered.contains("dub")) {
                score -= 4000;
            }
        }
        return score;
    }

    private static int safeAverageBitrate(AudioStream stream) {
        int averageBitrate = stream.getAverageBitrate();
        if (averageBitrate > 0) {
            return averageBitrate;
        }
        return Math.max(stream.getBitrate(), 0);
    }

    private static final class NewPipeDownloader extends Downloader {
        private static final MediaType OCTET = MediaType.get("application/octet-stream");
        private final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            okhttp3.Response response = client.newCall(toOkHttpRequest(request)).execute();
            if (response.code() == 429) {
                response.close();
                throw new ReCaptchaException("reCaptcha challenge requested", request.url());
            }
            try {
                String body = response.body() == null ? null : response.body().string();
                return new Response(
                        response.code(),
                        response.message(),
                        response.headers().toMultimap(),
                        body,
                        response.request().url().toString()
                );
            } finally {
                response.close();
            }
        }

        private okhttp3.Request toOkHttpRequest(Request request) {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(request.url())
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) Eclipse/1.0");

            Map<String, List<String>> headers = request.headers();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();
                if (name == null || values == null) {
                    continue;
                }
                builder.removeHeader(name);
                for (String value : values) {
                    builder.addHeader(name, value);
                }
            }

            byte[] data = request.dataToSend();
            RequestBody body = data == null ? null : RequestBody.create(data, OCTET);
            builder.method(request.httpMethod(), body);
            return builder.build();
        }
    }
}
