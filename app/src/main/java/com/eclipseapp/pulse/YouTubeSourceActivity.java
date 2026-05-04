package com.eclipseapp.pulse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.ArrayList;

public final class YouTubeSourceActivity extends Activity {
    public static final String EXTRA_QUERY = "com.eclipseapp.pulse.extra.QUERY";

    private static final int BACKGROUND = Color.rgb(14, 17, 25);
    private static final int SURFACE = Color.rgb(21, 24, 34);
    private static final int BORDER = Color.rgb(43, 48, 61);
    private static final int TEXT = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(156, 166, 184);
    private static final int BLUE = Color.rgb(67, 167, 255);

    private final InnertubeMusicRepository repository = new InnertubeMusicRepository();
    private EditText searchBox;
    private LinearLayout results;
    private ProgressBar loader;
    private List<MainActivity.Track> currentTracks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        setContentView(createContent());

        String query = getIntent().getStringExtra(EXTRA_QUERY);
        load(query == null ? "" : query);
    }

    private LinearLayout createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), 0);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(topBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        TextView back = button("<", TEXT, SURFACE);
        topBar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(38)));
        back.setOnClickListener(v -> finish());

        TextView title = text("YouTube Data", 19, BLUE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, dp(12), 0, dp(12));
        root.addView(searchRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Cari lagu dari YouTube");
        searchBox.setHintTextColor(MUTED);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(14);
        searchBox.setPadding(dp(14), 0, dp(14), 0);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setBackground(rounded(SURFACE, BORDER, 12, 1));
        searchBox.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                load(searchBox.getText().toString());
                return true;
            }
            return false;
        });
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(46), 1));

        TextView search = button("Search", Color.rgb(4, 18, 30), BLUE);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(82), dp(46));
        searchParams.leftMargin = dp(10);
        searchRow.addView(search, searchParams);
        search.setOnClickListener(v -> load(searchBox.getText().toString()));

        loader = new ProgressBar(this);
        loader.setVisibility(ProgressBar.GONE);
        root.addView(loader, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));

        ScrollView scrollView = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(14), 0, dp(26));
        scrollView.addView(results);
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        return root;
    }

    private void load(String query) {
        String clean = query == null ? "" : query.trim();
        searchBox.setText(clean);
        searchBox.setSelection(searchBox.getText().length());
        loader.setVisibility(ProgressBar.VISIBLE);
        results.removeAllViews();
        TextView loading = text("Mengambil data lagu dari YouTube...", 13, MUTED, Typeface.NORMAL);
        loading.setGravity(Gravity.CENTER);
        results.addView(loading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        repository.search(clean, (tracks, status) -> {
            loader.setVisibility(ProgressBar.GONE);
            drawResults(tracks, status);
        });
    }

    private void drawResults(List<MainActivity.Track> tracks, String status) {
        currentTracks = new ArrayList<>(tracks);
        results.removeAllViews();
        TextView statusView = text(status, 12, MUTED, Typeface.NORMAL);
        statusView.setPadding(dp(2), 0, dp(2), dp(12));
        results.addView(statusView);

        for (MainActivity.Track track : tracks) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(rounded(SURFACE, BORDER, 12, 1));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(12);
            results.addView(row, rowParams);

            TextView title = text(track.title, 16, TEXT, Typeface.BOLD);
            row.addView(title);
            TextView artist = text(track.artist + " • " + track.meta, 12, MUTED, Typeface.NORMAL);
            LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            artistParams.topMargin = dp(4);
            row.addView(artist, artistParams);

            TextView hint = text("Tap untuk putar dari YouTube di player Eclipse", 11, BLUE, Typeface.BOLD);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = dp(10);
            row.addView(hint, hintParams);

            row.setOnClickListener(v -> openPlayer(track));
        }
    }

    private void openPlayer(MainActivity.Track track) {
        PlayerQueueStore.setQueue(currentTracks, track);
        Intent intent = new Intent(this, NativePlayerActivity.class);
        intent.putExtra(NativePlayerActivity.EXTRA_TITLE, track.title);
        intent.putExtra(NativePlayerActivity.EXTRA_ARTIST, track.artist);
        intent.putExtra(NativePlayerActivity.EXTRA_META, track.meta);
        intent.putExtra(NativePlayerActivity.EXTRA_AUDIO_URL, track.audioUrl);
        intent.putExtra(NativePlayerActivity.EXTRA_SOURCE_ID, track.sourceId);
        startActivity(intent);
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private TextView button(String value, int color, int fill) {
        TextView view = text(value, 13, color, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(fill, BORDER, 12, fill == BLUE ? 0 : 1));
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}
