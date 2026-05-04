package com.eclipseapp.pulse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import android.graphics.drawable.Drawable;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

public class PlaybackService extends MediaBrowserServiceCompat implements PlaybackManager.Listener {

    private static final String CHANNEL_ID = "eclipse_playback_channel";
    private static final int NOTIFICATION_ID = 101;

    public static final String ACTION_PLAY       = "com.eclipseapp.pulse.ACTION_PLAY";
    public static final String ACTION_PAUSE      = "com.eclipseapp.pulse.ACTION_PAUSE";
    public static final String ACTION_PLAY_PAUSE = "com.eclipseapp.pulse.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT       = "com.eclipseapp.pulse.ACTION_NEXT";
    public static final String ACTION_PREV       = "com.eclipseapp.pulse.ACTION_PREV";
    public static final String ACTION_STOP       = "com.eclipseapp.pulse.ACTION_STOP";
    private static final String MEDIA_ROOT_ID    = "eclipse_root";

    private MediaSessionCompat mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        mediaSession = new MediaSessionCompat(this, "EclipseMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                PlaybackManager.get().resume();
            }

            @Override
            public void onPause() {
                PlaybackManager.get().pause();
            }

            @Override
            public void onSkipToNext() {
                PlaybackManager.get().next();
            }

            @Override
            public void onSkipToPrevious() {
                PlaybackManager.get().previous();
            }
        });
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());
        PlaybackManager.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    PlaybackManager.get().resume(); break;
                case ACTION_PAUSE:
                    PlaybackManager.get().pause(); break;
                case ACTION_PLAY_PAUSE:
                    PlaybackManager.get().togglePlayback(getApplicationContext()); break;
                case ACTION_NEXT:
                    PlaybackManager.get().next(); break;
                case ACTION_PREV:
                    PlaybackManager.get().previous(); break;
                case ACTION_STOP:
                    stopPlayback(); break;
            }
        }

        updateNotification();
        return START_NOT_STICKY;
    }

    private void stopPlayback() {
        PlaybackManager.get().pause(); // Pause playback
        stopForeground(true);
        stopSelf();
    }

    private void updateNotification() {
        MainActivity.Track track = PlaybackManager.get().currentTrack();
        if (track == null) {
            stopForeground(true);
            return;
        }

        boolean isPlaying = PlaybackManager.get().isPlaying();

        // Update MediaSession state
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, PlaybackSessionPosition(), 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build());

        // Update MediaSession metadata
        long durationMs = PlaybackManager.get().getResolvedDurationMs();
        if (durationMs <= 0) {
            durationMs = PlaybackManager.get().duration(); // Fallback to MediaPlayer duration if available
        }
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (track.thumbnail != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, track.thumbnail);
        }
        mediaSession.setMetadata(metaBuilder.build());

        // Build notification
        PendingIntent playPauseIntent = isPlaying
                ? getPendingIntent(ACTION_PAUSE)
                : getPendingIntent(ACTION_PLAY);

        int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        String playPauseLabel = isPlaying ? "Pause" : "Play";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .addAction(R.drawable.ic_skip_previous, "Previous", getPendingIntent(ACTION_PREV))
                .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
                .addAction(R.drawable.ic_skip_next, "Next", getPendingIntent(ACTION_NEXT))
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(getPendingIntent(ACTION_STOP)));

        // Intent to open app when notification is clicked
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(openAppPendingIntent);

        if (track.thumbnail != null) {
            builder.setLargeIcon(track.thumbnail);
            startForeground(NOTIFICATION_ID, builder.build());
            // Only stop foreground when explicitly paused by user (not during loading next track)
            if (!isPlaying && PlaybackManager.get().isPaused()) stopForeground(false);
        } else if (track.thumbnailUrl != null && !track.thumbnailUrl.isEmpty()) {
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
            startForeground(NOTIFICATION_ID, builder.build());
            if (!isPlaying && PlaybackManager.get().isPaused()) stopForeground(false);

            Glide.with(this)
                    .asBitmap()
                    .load(track.thumbnailUrl)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            track.thumbnail = resource;
                            builder.setLargeIcon(resource);
                            
                            // Re-update the metadata so lock screen updates too
                            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, resource);
                            mediaSession.setMetadata(metaBuilder.build());
                            
                            NotificationManager manager = getSystemService(NotificationManager.class);
                            if (manager != null) {
                                manager.notify(NOTIFICATION_ID, builder.build());
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}
                    });
        } else {
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
            startForeground(NOTIFICATION_ID, builder.build());
            if (!isPlaying) stopForeground(false);
        }
        // Push widget update with thumbnail
        Bitmap widgetArt = track.thumbnail;
        MusicWidget.pushUpdate(this, track.title, track.artist, isPlaying, widgetArt);
    }

    private long PlaybackSessionPosition() {
        return PlaybackManager.get().currentPosition();
    }

    private PendingIntent getPendingIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Eclipse Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows media playback controls");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // MediaBrowserServiceCompat handles the MEDIA_BROWSER_SERVICE intent;
        // for all others return null.
        IBinder binder = super.onBind(intent);
        return binder;
    }

    // ─── Android Auto / MediaBrowser ───────────────────────────────────────────

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, android.os.Bundle rootHints) {
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        try {
            // Expose liked songs as browseable items
            List<org.json.JSONObject> liked = LocalStorageManager.getLikedSongs(getApplicationContext());
            for (org.json.JSONObject j : liked) {
                String title  = j.optString("title", "Unknown");
                String artist = j.optString("artist", "");
                String srcId  = j.optString("sourceId", "");
                MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                        .setMediaId(srcId)
                        .setTitle(title)
                        .setSubtitle(artist)
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
        } catch (Exception ignored) {}
        result.sendResult(items);
    }

    @Override
    public void onPlaybackStateChanged() {
        updateNotification();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PlaybackManager.get().removeListener(this);
        mediaSession.setActive(false);
        mediaSession.release();
    }
}
