package com.eclipseapp.pulse;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Home screen widget showing current track + Play/Pause/Next/Prev controls.
 * Updated by PlaybackService on every playback state change.
 */
public final class MusicWidget extends AppWidgetProvider {

    static final String ACTION_PREV      = "com.eclipseapp.pulse.WIDGET_PREV";
    static final String ACTION_PLAY_PAUSE= "com.eclipseapp.pulse.WIDGET_PLAY_PAUSE";
    static final String ACTION_NEXT      = "com.eclipseapp.pulse.WIDGET_NEXT";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateWidgets(context, manager, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_PREV:
                sendServiceAction(context, "PREV"); break;
            case ACTION_PLAY_PAUSE:
                sendServiceAction(context, "PLAY_PAUSE"); break;
            case ACTION_NEXT:
                sendServiceAction(context, "NEXT"); break;
            case AppWidgetManager.ACTION_APPWIDGET_UPDATE:
                AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                int[] ids = mgr.getAppWidgetIds(new ComponentName(context, MusicWidget.class));
                updateWidgets(context, mgr, ids);
                break;
        }
    }

    private void sendServiceAction(Context context, String action) {
        Intent i = new Intent(context, PlaybackService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    /** Called from PlaybackService to push a live update to all widget instances */
    static void pushUpdate(Context context, String title, String artist, boolean isPlaying, Bitmap art) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MusicWidget.class));
        if (ids.length == 0) return;
        updateWidgets(context, manager, ids, title, artist, isPlaying, art);
    }

    private static void updateWidgets(Context ctx, AppWidgetManager mgr, int[] ids) {
        PlaybackManager pb = PlaybackManager.get();
        MainActivity.Track t = pb.currentTrack();
        updateWidgets(ctx, mgr, ids,
                t != null ? t.title  : "Not playing",
                t != null ? t.artist : "Eclipse Music",
                pb.isPlaying(),
                t != null ? t.thumbnail : null);
    }

    private static void updateWidgets(Context ctx, AppWidgetManager mgr, int[] ids,
                                      String title, String artist, boolean isPlaying, Bitmap art) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_player);

        rv.setTextViewText(R.id.widget_title, title != null ? title : "Not playing");
        rv.setTextViewText(R.id.widget_artist, artist != null ? artist : "Eclipse Music");
        rv.setImageViewResource(R.id.widget_play_pause,
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        if (art != null) rv.setImageViewBitmap(R.id.widget_art, art);
        else rv.setImageViewResource(R.id.widget_art, R.drawable.ic_music_note);

        rv.setOnClickPendingIntent(R.id.widget_prev, buildIntent(ctx, ACTION_PREV));
        rv.setOnClickPendingIntent(R.id.widget_play_pause, buildIntent(ctx, ACTION_PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.widget_next, buildIntent(ctx, ACTION_NEXT));

        // Tap on widget title/art opens the player
        Intent open = new Intent(ctx, NativePlayerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int openFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        rv.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(ctx, 0, open, openFlags));

        for (int id : ids) mgr.updateAppWidget(id, rv);
    }

    private static PendingIntent buildIntent(Context ctx, String action) {
        Intent i = new Intent(ctx, MusicWidget.class);
        i.setAction(action);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, flags);
    }
}
