package com.eclipseapp.pulse;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class PlaybackManager {
    interface Listener {
        void onPlaybackStateChanged();
    }

    private static final PlaybackManager INSTANCE = new PlaybackManager();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final InnertubeMusicRepository repository = new InnertubeMusicRepository();

    private Context appContext;
    private MediaPlayer mediaPlayer;
    private MainActivity.Track currentTrack;
    private Uri localAudioUri;
    private String resolvedAudioUrl;
    private long resolvedDurationMs;
    private boolean prepared;
    private boolean resolvingAudio;
    private String status = "Pilih track untuk mulai memutar.";

    // Sleep Timer
    private long sleepTimerEndMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean sleepTimerActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Crossfade
    private MediaPlayer crossfadePlayer;
    private boolean crossfading = false;
    private final Runnable crossfadeMonitor = new Runnable() { public void run() { checkCrossfade(); if (prepared) mainHandler.postDelayed(this, 500); } };

    // Stats / Session
    private long trackStartMs = 0;
    private long statsSaveMs = 0;
    private final Runnable sessionSaver = new Runnable() { public void run() { saveSessionAndStats(); if (prepared) mainHandler.postDelayed(this, 10_000); } };

    // Advanced Audio
    private float currentTempo = 1.0f;
    private float currentPitch = 1.0f;

    private PlaybackManager() {
    }

    static PlaybackManager get() {
        return INSTANCE;
    }

    void attach(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        LastFmScrobbler.get().init(appContext);
    }

    void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    MainActivity.Track currentTrack() {
        return currentTrack;
    }

    String status() {
        return status;
    }

    String getResolvedAudioUrl() {
        return resolvedAudioUrl;
    }

    boolean isBusy() {
        return resolvingAudio || (mediaPlayer != null && !prepared);
    }

    boolean isResolvingAudio() {
        return resolvingAudio;
    }

    boolean isPrepared() {
        return prepared;
    }

    boolean isPlaying() {
        return mediaPlayer != null && prepared && mediaPlayer.isPlaying();
    }

    /** True if explicitly paused by user (not transitioning between tracks) */
    boolean isPaused() {
        return prepared && mediaPlayer != null && !mediaPlayer.isPlaying();
    }

    boolean usesLocalAudio() {
        return localAudioUri != null;
    }

    int currentPosition() {
        if (mediaPlayer == null || !prepared) {
            return 0;
        }
        try {
            return Math.max(mediaPlayer.getCurrentPosition(), 0);
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    void seekTo(int positionMs) {
        if (mediaPlayer != null && prepared) {
            try {
                mediaPlayer.seekTo(positionMs);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    int duration() {
        if (mediaPlayer == null || !prepared) {
            return 0;
        }
        try {
            return Math.max(mediaPlayer.getDuration(), 0);
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    long getResolvedDurationMs() {
        return resolvedDurationMs;
    }

    void setTrack(MainActivity.Track track) {
        switchTrackInternal(track, false);
    }

    void switchTrack(Context context, MainActivity.Track track, boolean autoPlay) {
        attach(context);
        switchTrackInternal(track, autoPlay);
    }

    void togglePlayback(Context context) {
        attach(context);
        if (currentTrack == null) {
            status = "Belum ada track aktif.";
            notifyListeners();
            return;
        }
        if (localAudioUri == null && playableAudioUrl().isEmpty()) {
            if (resolvingAudio) {
                return;
            }
            resolveYouTubeSourceAndAudio(true);
            return;
        }
        if (mediaPlayer == null) {
            prepare();
            return;
        }
        if (!prepared) {
            return;
        }
        if (mediaPlayer.isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            status = usesLocalAudio()
                    ? "Audio lokal dijeda."
                    : "Audio YouTube dijeda.";
            notifyListeners();
        }
    }

    void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying() && prepared) {
            mediaPlayer.start();
            status = usesLocalAudio()
                    ? "Memutar audio lokal di Eclipse."
                    : "Memutar audio YouTube di Eclipse.";
            notifyListeners();
        }
    }

    void next() {
        MainActivity.Track nextTrack = PlayerQueueStore.next();
        if (nextTrack != null) {
            switchTrackInternal(nextTrack, true);
        }
    }

    void previous() {
        MainActivity.Track previousTrack = PlayerQueueStore.previous();
        if (previousTrack != null) {
            switchTrackInternal(previousTrack, true);
        }
    }

    // ==================== SLEEP TIMER ====================

    void setSleepTimer(long delayMs) {
        sleepTimerEndMs = System.currentTimeMillis() + delayMs;
        sleepTimerActive.set(true);
        mainHandler.removeCallbacksAndMessages("sleeptimer");
        mainHandler.postAtTime(() -> {
            sleepTimerActive.set(false);
            sleepTimerEndMs = 0;
            pause();
        }, "sleeptimer", android.os.SystemClock.uptimeMillis() + delayMs);
    }

    void cancelSleepTimer() {
        sleepTimerActive.set(false);
        sleepTimerEndMs = 0;
        mainHandler.removeCallbacksAndMessages("sleeptimer");
    }

    boolean isSleepTimerActive() {
        return sleepTimerActive.get();
    }

    long getSleepTimerRemainingMs() {
        if (!sleepTimerActive.get()) return 0;
        long remaining = sleepTimerEndMs - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    // ==================== ADVANCED AUDIO ====================

    void setTempoAndPitch(float tempo, float pitch) {
        this.currentTempo = tempo;
        this.currentPitch = pitch;
        if (mediaPlayer != null && prepared) {
            try {
                android.media.PlaybackParams params = new android.media.PlaybackParams();
                params.setSpeed(tempo);
                params.setPitch(pitch);
                mediaPlayer.setPlaybackParams(params);
            } catch (Exception ignored) {}
        }
    }

    float getTempo() { return currentTempo; }
    float getPitch() { return currentPitch; }

    void setLocalAudio(Context context, Uri uri) {
        attach(context);
        if (uri == null) {
            return;
        }
        localAudioUri = uri;
        resolvedAudioUrl = null;
        stopPlayerInternal();
        status = "Audio lokal siap diputar di player Eclipse.";
        notifyListeners();
        prepare();
    }

    private void switchTrackInternal(MainActivity.Track track, boolean autoPlay) {
        if (track == null) {
            return;
        }
        stopPlayerInternal();
        currentTrack = track;
        localAudioUri = null;
        resolvedAudioUrl = null;
        resolvingAudio = false;
        status = playbackStatus(track);
        PlayerQueueStore.updateCurrent(track);
        notifyListeners();
        if (autoPlay) {
            togglePlayback(appContext);
        }
        // Auto-populate queue with related songs (like Metrolist)
        if (PlayerQueueStore.needsRadio() && track.sourceId != null && !track.sourceId.isEmpty()) {
            fetchRadioForTrack(track.sourceId);
        }
        // Discord RPC is updated in onPrepared (after duration is available)
    }

    private boolean fetchingRadio = false;

    private void fetchRadioForTrack(String videoId) {
        if (fetchingRadio) return;
        fetchingRadio = true;
        repository.fetchRadio(videoId, (tracks, radioStatus) -> {
            fetchingRadio = false;
            if (!tracks.isEmpty()) {
                PlayerQueueStore.appendRadio(tracks);
                notifyListeners(); // refresh queue UI
            }
        });
    }

    private void resolveYouTubeSourceAndAudio(boolean autoPlay) {
        if (currentTrack == null) {
            return;
        }
        if (currentTrack.sourceId != null && !currentTrack.sourceId.trim().isEmpty()) {
            resolveYouTubeAudio(autoPlay);
            return;
        }

        String requestedQuery = currentTrack.query();
        resolvingAudio = true;
        status = "Mencari source lagu di YouTube...";
        notifyListeners();

        repository.resolvePlayableTrack(currentTrack, (resolvedTrack, resultStatus) -> mainHandler.post(() -> {
            if (currentTrack == null || !requestedQuery.equals(currentTrack.query())) {
                return;
            }
            if (resolvedTrack == null || resolvedTrack.sourceId == null || resolvedTrack.sourceId.trim().isEmpty()) {
                resolvingAudio = false;
                status = "Source YouTube tidak ditemukan untuk track ini.";
                notifyListeners();
                return;
            }
            currentTrack = mergeResolvedTrack(currentTrack, resolvedTrack);
            PlayerQueueStore.updateCurrent(currentTrack);
            resolvingAudio = false;
            status = resultStatus;
            notifyListeners();
            resolveYouTubeAudio(autoPlay);
        }));
    }

    private void resolveYouTubeAudio(boolean autoPlay) {
        if (currentTrack == null) {
            return;
        }
        String sourceId = currentTrack.sourceId == null ? "" : currentTrack.sourceId.trim();
        if (sourceId.isEmpty()) {
            status = "Track ini belum punya source id YouTube.";
            notifyListeners();
            return;
        }

        resolvingAudio = true;
        status = "Mengambil audio stream dari YouTube...";
        notifyListeners();

        YouTubeAudioResolver.resolve(sourceId, appContext != null ? LocalStorageManager.getAudioQuality(appContext) : "high", new YouTubeAudioResolver.Callback() {
            @Override
            public void onResolved(String audioUrl, long durationMs) {
                mainHandler.post(() -> {
                    if (currentTrack == null || !sourceId.equals(currentTrack.sourceId)) {
                        return;
                    }
                    resolvingAudio = false;
                    resolvedAudioUrl = audioUrl == null ? "" : audioUrl.trim();
                    resolvedDurationMs = durationMs;
                    status = "Audio YouTube siap diputar di Eclipse.";
                    notifyListeners();
                    if (autoPlay && !resolvedAudioUrl.isEmpty()) {
                        prepare();
                    }
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (currentTrack == null || !sourceId.equals(currentTrack.sourceId)) {
                        return;
                    }
                    resolvingAudio = false;
                    status = message == null || message.trim().isEmpty()
                            ? "Gagal mengambil audio dari YouTube."
                            : message;
                    notifyListeners();
                });
            }
        });
    }

    private void prepare() {
        if (appContext == null || currentTrack == null) {
            return;
        }

        // Start Foreground Service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            appContext.startForegroundService(new Intent(appContext, PlaybackService.class));
        } else {
            appContext.startService(new Intent(appContext, PlaybackService.class));
        }

        String remoteUrl = playableAudioUrl();
        if (localAudioUri == null && remoteUrl.isEmpty()) {
            status = playbackStatus(currentTrack);
            notifyListeners();
            return;
        }

        try {
            stopPlayerInternal();
            status = "Menyiapkan audio...";
            notifyListeners();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            if (localAudioUri != null) {
                mediaPlayer.setDataSource(appContext, localAudioUri);
            } else {
                mediaPlayer.setDataSource(remoteUrl);
            }
            mediaPlayer.setOnPreparedListener(player -> {
                prepared = true;
                try {
                    android.media.PlaybackParams params = new android.media.PlaybackParams();
                    params.setSpeed(currentTempo);
                    params.setPitch(currentPitch);
                    player.setPlaybackParams(params);
                } catch (Exception ignored) {}
                player.start();
                // Attach Equalizer
                try { EqualizerManager.get().attach(appContext, player.getAudioSessionId()); } catch (Exception ignored) {}
                status = usesLocalAudio()
                        ? "Memutar audio lokal di Eclipse."
                        : "Memutar audio YouTube di Eclipse.";
                // Track play history
                if (appContext != null && currentTrack != null) {
                    LocalStorageManager.addToHistory(appContext, currentTrack);
                }
                // Stats: record start time and start periodic session saver
                trackStartMs = System.currentTimeMillis();
                statsSaveMs = trackStartMs;
                mainHandler.removeCallbacks(sessionSaver);
                mainHandler.postDelayed(sessionSaver, 10_000);
                // Last.fm: now playing
                try {
                    if (currentTrack != null)
                        LastFmScrobbler.get().updateNowPlaying(appContext, currentTrack.title, currentTrack.artist);
                } catch (Exception ignored) {}
                // Update Discord RPC now that duration is available
                try {
                    if (currentTrack != null) {
                        String cleanArtist = currentTrack.artist;
                        // Remove duplicate artist names and innerTube bullet metadata
                        if (cleanArtist != null) {
                            if (cleanArtist.contains("•")) {
                                cleanArtist = cleanArtist.split("•")[0].trim();
                            }
                            if (cleanArtist.contains(" - ")) {
                                cleanArtist = cleanArtist.split(" - ")[0].trim();
                            }
                        }
                        DiscordRPC.get().updatePresence(currentTrack.title, cleanArtist, currentTrack.thumbnailUrl, (int) resolvedDurationMs);
                    }
                } catch (Exception ignored) {}
                // Start crossfade monitor
                mainHandler.removeCallbacks(crossfadeMonitor);
                mainHandler.postDelayed(crossfadeMonitor, 500);
                notifyListeners();
            });
            mediaPlayer.setOnCompletionListener(player -> mainHandler.post(() -> {
                // Last.fm: scrobble if at least 50% played or > 4 min
                flushScrobble();
                mainHandler.removeCallbacks(sessionSaver);
                mainHandler.removeCallbacks(crossfadeMonitor);
                crossfading = false;
                if (crossfadePlayer != null) { crossfadePlayer.release(); crossfadePlayer = null; }
                MainActivity.Track nextTrack = PlayerQueueStore.next();
                if (nextTrack == null) {
                    status = "Queue selesai diputar.";
                    notifyListeners();
                    return;
                }
                switchTrackInternal(nextTrack, true);
            }));
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                stopPlayerInternal();
                status = "Audio tidak bisa diputar dari sumber ini.";
                notifyListeners();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception exception) {
            stopPlayerInternal();
            status = "Audio tidak bisa diputar.";
            notifyListeners();
        }
    }

    private String playableAudioUrl() {
        if (resolvedAudioUrl != null && !resolvedAudioUrl.trim().isEmpty()) {
            return resolvedAudioUrl;
        }
        if (currentTrack != null && currentTrack.audioUrl != null && !currentTrack.audioUrl.trim().isEmpty()) {
            return currentTrack.audioUrl.trim();
        }
        return "";
    }

    private String playbackStatus(MainActivity.Track track) {
        if (track == null) {
            return "Pilih track untuk mulai memutar.";
        }
        if (track.audioUrl != null && !track.audioUrl.trim().isEmpty()) {
            return "Siap diputar dengan player native Eclipse.";
        }
        if (track.sourceId != null && !track.sourceId.trim().isEmpty()) {
            return "Track YouTube siap di-resolve. Tekan play untuk mengambil audio.";
        }
        return "Track ini belum punya source YouTube yang siap pakai. Tekan play untuk mencarinya otomatis.";
    }

    private MainActivity.Track mergeResolvedTrack(MainActivity.Track original, MainActivity.Track resolved) {
        return new MainActivity.Track(
                resolved.title == null || resolved.title.trim().isEmpty() ? original.title : resolved.title,
                resolved.artist == null || resolved.artist.trim().isEmpty() ? original.artist : resolved.artist,
                resolved.meta == null || resolved.meta.trim().isEmpty() ? original.meta : resolved.meta,
                original.artStyle,
                resolved.thumbnailUrl == null || resolved.thumbnailUrl.trim().isEmpty() ? original.thumbnailUrl : resolved.thumbnailUrl,
                resolved.thumbnail == null ? original.thumbnail : resolved.thumbnail,
                resolved.audioUrl == null || resolved.audioUrl.trim().isEmpty() ? original.audioUrl : resolved.audioUrl,
                resolved.sourceId == null || resolved.sourceId.trim().isEmpty() ? original.sourceId : resolved.sourceId,
                resolved.sourceName == null || resolved.sourceName.trim().isEmpty() ? original.sourceName : resolved.sourceName
        );
    }

    private void stopPlayerInternal() {
        mainHandler.removeCallbacks(crossfadeMonitor);
        mainHandler.removeCallbacks(sessionSaver);
        flushScrobble();
        EqualizerManager.get().release();
        crossfading = false;
        if (crossfadePlayer != null) { crossfadePlayer.release(); crossfadePlayer = null; }
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        prepared = false;
    }

    private void flushScrobble() {
        try {
            if (appContext == null || currentTrack == null || trackStartMs == 0) return;
            long played = (System.currentTimeMillis() - trackStartMs) / 1000;
            long duration = resolvedDurationMs > 0 ? resolvedDurationMs / 1000 : 300;
            if (played >= Math.min(duration / 2, 240)) {
                LastFmScrobbler.get().scrobble(appContext, currentTrack.title, currentTrack.artist, trackStartMs);
            }
        } catch (Exception ignored) {}
        trackStartMs = 0;
    }

    private void saveSessionAndStats() {
        try {
            if (appContext == null || currentTrack == null) return;
            // Save session position
            int pos = currentPosition();
            LocalStorageManager.saveLastSession(appContext, currentTrack, pos);
            // Accumulate stats (seconds since last save)
            long now = System.currentTimeMillis();
            int delta = (int)((now - statsSaveMs) / 1000);
            statsSaveMs = now;
            if (delta > 0 && prepared)
                LocalStorageManager.addListeningTime(appContext, currentTrack.title, currentTrack.artist, delta);
        } catch (Exception ignored) {}
    }

    private void checkCrossfade() {
        if (!prepared || mediaPlayer == null || crossfading) return;
        int fadeSecs = appContext != null ? LocalStorageManager.getCrossfadeDuration(appContext) : 0;
        if (fadeSecs <= 0) return;
        int dur = mediaPlayer.getDuration();
        int pos = mediaPlayer.getCurrentPosition();
        if (dur <= 0) return;
        int remaining = dur - pos;
        int fadeMs = fadeSecs * 1000;
        if (remaining > fadeMs) return; // not yet time to crossfade
        MainActivity.Track next = PlayerQueueStore.peekNext();
        if (next == null) return;
        crossfading = true;
        // Pre-start crossfade player with next track
        startCrossfadeTo(next, fadeMs, remaining);
    }

    private void startCrossfadeTo(MainActivity.Track next, int fadeMs, int remaining) {
        if (next.sourceId == null || next.sourceId.isEmpty()) return;
        YouTubeAudioResolver.resolve(next.sourceId, appContext != null ? LocalStorageManager.getAudioQuality(appContext) : "high",
            new YouTubeAudioResolver.Callback() {
                public void onResolved(String url, long durMs) {
                    mainHandler.post(() -> {
                        if (!crossfading) return;
                        try {
                            crossfadePlayer = new MediaPlayer();
                            crossfadePlayer.setAudioAttributes(new AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
                            crossfadePlayer.setDataSource(url);
                            crossfadePlayer.setVolume(0f, 0f);
                            crossfadePlayer.prepareAsync();
                            crossfadePlayer.setOnPreparedListener(p -> {
                                p.start();
                                animateCrossfade(fadeMs);
                            });
                        } catch (Exception e) { crossfading = false; }
                    });
                }
                public void onError(String msg) { crossfading = false; }
            });
    }

    private void animateCrossfade(int fadeMs) {
        final int steps = 20;
        final int intervalMs = Math.max(fadeMs / steps, 50);
        final float[] vol = {0f};
        mainHandler.post(new Runnable() {
            public void run() {
                if (!crossfading) return; // Crossfade was cancelled
                vol[0] = Math.min(1f, vol[0] + 1f / steps);
                float fadeIn = vol[0], fadeOut = 1f - vol[0];
                if (mediaPlayer != null) mediaPlayer.setVolume(fadeOut, fadeOut);
                if (crossfadePlayer != null) crossfadePlayer.setVolume(fadeIn, fadeIn);
                if (vol[0] < 1f) {
                    mainHandler.postDelayed(this, intervalMs);
                } else {
                    // Swap players — release old, promote crossfadePlayer to mediaPlayer
                    MediaPlayer old = mediaPlayer;
                    mediaPlayer = crossfadePlayer;
                    crossfadePlayer = null;
                    crossfading = false;
                    if (old != null) old.release();

                    // Wire up completion/error listeners on the new mediaPlayer
                    // so auto-next and error recovery continue to work
                    mediaPlayer.setOnCompletionListener(p -> mainHandler.post(() -> {
                        flushScrobble();
                        mainHandler.removeCallbacks(sessionSaver);
                        mainHandler.removeCallbacks(crossfadeMonitor);
                        crossfading = false;
                        if (crossfadePlayer != null) { crossfadePlayer.release(); crossfadePlayer = null; }
                        MainActivity.Track nextTrack = PlayerQueueStore.next();
                        if (nextTrack == null) {
                            status = "Queue selesai diputar.";
                            notifyListeners();
                            return;
                        }
                        switchTrackInternal(nextTrack, true);
                    }));
                    mediaPlayer.setOnErrorListener((p, what, extra) -> {
                        stopPlayerInternal();
                        status = "Audio tidak bisa diputar dari sumber ini.";
                        notifyListeners();
                        return true;
                    });

                    // Restart the crossfade monitor and session saver for the new track
                    mainHandler.removeCallbacks(crossfadeMonitor);
                    mainHandler.postDelayed(crossfadeMonitor, 500);
                    mainHandler.removeCallbacks(sessionSaver);
                    mainHandler.postDelayed(sessionSaver, 10_000);

                    // The queue was already advanced by PlayerQueueStore.peekNext() logic;
                    // call next() to move the currentIndex to the track that's now playing
                    MainActivity.Track n = PlayerQueueStore.next();
                    currentTrack = n != null ? n : currentTrack;
                    trackStartMs = System.currentTimeMillis();
                    statsSaveMs = trackStartMs;
                    notifyListeners();
                }
            }
        });
    }

    private void notifyListeners() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onPlaybackStateChanged();
            }
        });
    }
}
