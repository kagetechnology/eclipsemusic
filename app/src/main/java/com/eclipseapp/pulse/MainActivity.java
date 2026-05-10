package com.eclipseapp.pulse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    /* ======== Data Model (must match all existing consumers) ======== */
    public static class Track implements Serializable {
        public String title;
        public String artist;
        public String meta;
        public int artStyle;
        public String thumbnailUrl;
        public transient Bitmap thumbnail;
        public String audioUrl;
        public String sourceId;
        public String sourceName;
        public String id; // convenience alias for sourceId

        public Track(String title, String artist, String meta, int artStyle,
                     String thumbnailUrl, Bitmap thumbnail, String audioUrl,
                     String sourceId, String sourceName) {
            this.title = title;
            this.artist = artist;
            this.meta = meta;
            this.artStyle = artStyle;
            this.thumbnailUrl = thumbnailUrl;
            this.thumbnail = thumbnail;
            this.audioUrl = audioUrl;
            this.sourceId = sourceId;
            this.sourceName = sourceName;
            this.id = sourceId;
        }

        // Convenience constructor for quick creation
        public Track(String id, String title, String artist, String thumbnailUrl) {
            this(title, artist, "", 0, thumbnailUrl, null, null, id, "YouTube Music");
        }

        public String query() {
            return (artist == null ? "" : artist) + " " + (title == null ? "" : title);
        }
    }

    /* ======== State ======== */
    private BottomNavigationView bottomNav;
    private View miniPlayerContainer;
    private ViewGroup fragmentContainer;
    private View homeView, searchView, libraryView, settingsView;
    private int currentTab = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    static final InnertubeMusicRepository innertube = new InnertubeMusicRepository();
    private final Map<String, Bitmap> thumbCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fragmentContainer = findViewById(R.id.fragment_container);
        miniPlayerContainer = findViewById(R.id.mini_player_container);
        bottomNav = findViewById(R.id.bottom_nav);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) switchTab(0);
            else if (id == R.id.nav_search) switchTab(1);
            else if (id == R.id.nav_library) switchTab(2);
            else if (id == R.id.nav_settings) switchTab(3);
            return true;
        });

        LayoutInflater inflater = LayoutInflater.from(this);
        homeView = inflater.inflate(R.layout.fragment_home, fragmentContainer, false);
        searchView = inflater.inflate(R.layout.fragment_search, fragmentContainer, false);
        libraryView = inflater.inflate(R.layout.fragment_library, fragmentContainer, false);
        settingsView = createSettingsView();

        PlaybackManager.get().attach(this);
        DiscordRPC.get().connect(this); // Auto-connect if token saved
        // Restore last session
        if (PlaybackManager.get().currentTrack() == null) {
            Track lastTrack = LocalStorageManager.getLastSessionTrack(this);
            if (lastTrack != null) {
                int lastPos = LocalStorageManager.getLastSessionPosition(this);
                PlayerQueueStore.setQueue(new Track[]{lastTrack}, lastTrack);
                PlaybackManager.get().setTrack(lastTrack);
                if (lastPos > 0) {
                    mainHandler.postDelayed(() -> PlaybackManager.get().seekTo(lastPos), 800);
                }
            }
        }
        switchTab(0);
        setupMiniPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateMiniPlayer();
        if (currentTab == 2) refreshLibrary();
    }

    /* ======== Tab Switching ======== */
    private void switchTab(int tab) {
        currentTab = tab;
        fragmentContainer.removeAllViews();
        switch (tab) {
            case 0: fragmentContainer.addView(homeView); setupHome(); break;
            case 1: fragmentContainer.addView(searchView); setupSearch(); break;
            case 2: fragmentContainer.addView(libraryView); setupLibrary(); break;
            case 3: fragmentContainer.addView(settingsView); break;
        }
    }

    /* ======================================================================
     *  HOME TAB
     * ====================================================================== */
    private boolean homeLoaded = false;

    private void setupHome() {
        TextView greeting = homeView.findViewById(R.id.greeting);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 12 ? "Good morning ☀️" : hour < 17 ? "Good afternoon 🎵" : "Good evening 🌙");

        if (homeLoaded) return;

        ProgressBar loading    = homeView.findViewById(R.id.home_loading);

        // Section containers
        View sectionRecent   = homeView.findViewById(R.id.section_recent);
        View sectionPicks    = homeView.findViewById(R.id.section_picks);
        View sectionNew      = homeView.findViewById(R.id.section_new);
        View sectionCharts   = homeView.findViewById(R.id.section_charts);
        View sectionMood     = homeView.findViewById(R.id.section_mood);
        View sectionTrending = homeView.findViewById(R.id.section_trending);

        // RecyclerViews
        RecyclerView recentList   = homeView.findViewById(R.id.recent_list);
        RecyclerView picksList    = homeView.findViewById(R.id.picks_list);
        RecyclerView newList      = homeView.findViewById(R.id.new_list);
        RecyclerView chartsList   = homeView.findViewById(R.id.charts_list);
        RecyclerView moodList     = homeView.findViewById(R.id.mood_list);
        RecyclerView trendingList = homeView.findViewById(R.id.trending_list);

        // Layout managers
        recentList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        picksList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        newList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        chartsList.setLayoutManager(new LinearLayoutManager(this));
        moodList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        trendingList.setLayoutManager(new LinearLayoutManager(this));

        // ── Jump Back In (history, instant) ──────────────────────────────
        List<Track> history = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getHistory(this));
        if (!history.isEmpty()) {
            List<Track> recent = history.subList(0, Math.min(8, history.size()));
            recentList.setAdapter(new CardAdapter(recent));
            sectionRecent.setVisibility(View.VISIBLE);
        }

        // "See all" wires (open search with prefilled query)
        TextView picksSeeAll  = homeView.findViewById(R.id.picks_see_all);
        TextView newSeeAll    = homeView.findViewById(R.id.new_see_all);
        TextView chartsSeeAll = homeView.findViewById(R.id.charts_see_all);
        if (picksSeeAll != null)  picksSeeAll.setOnClickListener(v -> openSearch("top hits 2025"));
        if (newSeeAll != null)    newSeeAll.setOnClickListener(v -> openSearch("new music 2025"));
        if (chartsSeeAll != null) chartsSeeAll.setOnClickListener(v -> openSearch("billboard hot 100 2025"));

        // Hide loading as soon as ANY section has data
        Runnable hideLoading = () -> { loading.setVisibility(View.GONE); homeLoaded = true; };
        final boolean[] loadingHidden = {false};
        Runnable onSectionReady = () -> {
            if (!loadingHidden[0]) { loadingHidden[0] = true; hideLoading.run(); }
        };

        // ── Quick Picks ──────────────────────────────────────────────────
        innertube.search("top hits 2025", (tracks, s) -> {
            if (!tracks.isEmpty()) {
                picksList.setAdapter(new CardAdapter(tracks.subList(0, Math.min(8, tracks.size()))));
                sectionPicks.setVisibility(View.VISIBLE);
            }
            onSectionReady.run();
        });

        // ── New Releases ─────────────────────────────────────────────────
        innertube.search("new songs 2025", (tracks, s) -> {
            if (!tracks.isEmpty()) {
                newList.setAdapter(new CardAdapter(tracks.subList(0, Math.min(8, tracks.size()))));
                sectionNew.setVisibility(View.VISIBLE);
            }
            onSectionReady.run();
        });

        // ── Top Charts (vertical list) ───────────────────────────────────
        innertube.search("billboard hot 100 2025", (tracks, s) -> {
            if (!tracks.isEmpty()) {
                chartsList.setAdapter(new SongAdapter(tracks.subList(0, Math.min(6, tracks.size()))));
                sectionCharts.setVisibility(View.VISIBLE);
            }
            onSectionReady.run();
        });

        // ── Mood Mixes + Trending in one call ────────────────────────────
        innertube.search("chill lofi vibes 2025", (tracks, s) -> {
            if (!tracks.isEmpty()) {
                int mid = tracks.size() / 2;
                moodList.setAdapter(new CardAdapter(tracks.subList(0, Math.min(mid, 6))));
                sectionMood.setVisibility(View.VISIBLE);
                if (tracks.size() > mid) {
                    trendingList.setAdapter(new SongAdapter(tracks.subList(mid, tracks.size())));
                    sectionTrending.setVisibility(View.VISIBLE);
                }
            }
            onSectionReady.run();
        });
    }

    /** Pre-fill search tab and switch to it */
    private void openSearch(String query) {
        bottomNav.setSelectedItemId(R.id.nav_search);
        mainHandler.postDelayed(() -> {
            EditText input = searchView.findViewById(R.id.search_input);
            if (input != null) { input.setText(query); input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE); }
        }, 200);
    }

    /* ======================================================================
     *  SEARCH TAB
     * ====================================================================== */
    private void setupSearch() {
        EditText input = searchView.findViewById(R.id.search_input);
        ProgressBar loading = searchView.findViewById(R.id.search_loading);
        RecyclerView results = searchView.findViewById(R.id.search_results);
        TextView empty = searchView.findViewById(R.id.search_empty);
        ImageView clearBtn = searchView.findViewById(R.id.search_clear);
        View browseSection = searchView.findViewById(R.id.browse_section);

        results.setLayoutManager(new LinearLayoutManager(this));

        // Helper: reset to browse state (fixes floating song bug)
        Runnable showBrowse = () -> {
            results.setAdapter(null);           // ← key fix: clear adapter
            results.setVisibility(View.GONE);
            loading.setVisibility(View.GONE);
            empty.setText("Play what you love");
            if (browseSection != null) browseSection.setVisibility(View.VISIBLE);
        };

        input.setOnEditorActionListener((v, actionId, event) -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return false;
            if (browseSection != null) browseSection.setVisibility(View.GONE);
            loading.setVisibility(View.VISIBLE);
            results.setVisibility(View.GONE);
            innertube.search(query, (tracks, status) -> {
                loading.setVisibility(View.GONE);
                if (tracks.isEmpty()) {
                    empty.setText("No results found");
                    if (browseSection != null) {
                        browseSection.setVisibility(View.VISIBLE);
                    }
                } else {
                    results.setVisibility(View.VISIBLE);
                    results.setAdapter(new SongAdapter(tracks));
                }
            });
            return true;
        });

        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                // Auto-reset if user manually empties input
                if (s.length() == 0) showBrowse.run();
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        clearBtn.setOnClickListener(v -> {
            input.setText("");  // triggers TextWatcher → showBrowse
        });

        // Mic button — parent FrameLayout is the clickable target
        View micParent = searchView.findViewById(R.id.search_mic);
        if (micParent != null) {
            // The FrameLayout wrapping the mic is clickable; wire mic ImageView
            micParent.setOnClickListener(v -> startRecognize(input, loading, results, empty, browseSection));
        }

        // Recommendations based on history
        RecyclerView recList = searchView.findViewById(R.id.search_recommendations_list);
        if (recList != null) {
            recList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            List<org.json.JSONObject> history = LocalStorageManager.getHistory(this);
            String recQuery = "trending songs 2025";
            if (history != null && !history.isEmpty()) {
                String artist = history.get(0).optString("artist", "");
                if (!artist.isEmpty()) {
                    recQuery = artist + " best hits";
                }
            }
            innertube.search(recQuery, (tracks, status) -> {
                if (!tracks.isEmpty()) {
                    recList.setAdapter(new CardAdapter(tracks.subList(0, Math.min(8, tracks.size()))));
                }
            });
        }

        // Genre browse chips
        int[] chipViewIds = {
            R.id.chip_trending, R.id.chip_pop,   R.id.chip_hiphop, R.id.chip_electronic,
            R.id.chip_rnb,      R.id.chip_lofi
        };
        String[] chipQueries = {
            "trending hits 2025",    "best pop songs 2025",
            "hip hop hits 2025",     "electronic music 2025",
            "rnb songs 2025",        "lofi hip hop chill"
        };
        String[] chipTitles = {
            "Trending", "Pop",
            "Hip-Hop",  "Electronic",
            "R&B",      "Lo-Fi"
        };
        for (int i = 0; i < chipViewIds.length; i++) {
            View chip = searchView.findViewById(chipViewIds[i]);
            if (chip == null) continue;
            final String query = chipQueries[i];
            final String title = chipTitles[i];
            chip.setOnClickListener(v -> {
                Intent intent = new Intent(this, ListActivity.class);
                intent.putExtra("title", title);
                intent.putExtra("query", query);
                startActivity(intent);
            });
        }
    }

    private static final int PERM_MIC = 1001;
    private Runnable pendingRecognize;

    private void startRecognize(EditText input, ProgressBar loading, RecyclerView results, TextView empty, View browseSection) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingRecognize = () -> startRecognize(input, loading, results, empty, browseSection);
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_MIC);
            return;
        }

        // Show custom full-screen listening dialog
        android.app.Dialog listenDlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        listenDlg.setContentView(R.layout.dialog_recognize);
        listenDlg.setCancelable(false);

        TextView recognizeText = listenDlg.findViewById(R.id.recognize_text);
        View micGlow = listenDlg.findViewById(R.id.mic_glow);
        View ringInner = listenDlg.findViewById(R.id.mic_ring_inner);
        View ringOuter = listenDlg.findViewById(R.id.mic_ring_outer);

        listenDlg.findViewById(R.id.btn_cancel_recognize).setOnClickListener(v -> listenDlg.dismiss());
        listenDlg.findViewById(R.id.btn_back_recognize).setOnClickListener(v -> listenDlg.dismiss());

        // Pulse animation for rings
        android.animation.ObjectAnimator scaleInnerX = android.animation.ObjectAnimator.ofFloat(ringInner, "scaleX", 1f, 1.2f, 1f);
        android.animation.ObjectAnimator scaleInnerY = android.animation.ObjectAnimator.ofFloat(ringInner, "scaleY", 1f, 1.2f, 1f);
        scaleInnerX.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleInnerY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleInnerX.setDuration(1000);
        scaleInnerY.setDuration(1000);

        android.animation.ObjectAnimator scaleOuterX = android.animation.ObjectAnimator.ofFloat(ringOuter, "scaleX", 1f, 1.3f, 1f);
        android.animation.ObjectAnimator scaleOuterY = android.animation.ObjectAnimator.ofFloat(ringOuter, "scaleY", 1f, 1.3f, 1f);
        scaleOuterX.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleOuterY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleOuterX.setDuration(1200);
        scaleOuterY.setDuration(1200);

        android.animation.AnimatorSet pulse = new android.animation.AnimatorSet();
        pulse.playTogether(scaleInnerX, scaleInnerY, scaleOuterX, scaleOuterY);
        pulse.start();

        listenDlg.setOnDismissListener(d -> pulse.cancel());
        listenDlg.show();

        MusicRecognizer.recognize(
                msg -> { if (listenDlg.isShowing()) runOnUiThread(() -> recognizeText.setText(msg)); },
                (title, artist, status) -> {
                    if (listenDlg.isShowing()) listenDlg.dismiss();
                    if (title != null && !title.isEmpty()) {
                        // Auto search the recognized song
                        String query = title + " " + artist;
                        input.setText(query);
                        if (browseSection != null) browseSection.setVisibility(View.GONE);
                        loading.setVisibility(View.VISIBLE);
                        empty.setVisibility(View.GONE);
                        results.setVisibility(View.GONE);
                        innertube.search(query, (tracks, s) -> {
                            loading.setVisibility(View.GONE);
                            if (!tracks.isEmpty()) {
                                results.setVisibility(View.VISIBLE);
                                results.setAdapter(new SongAdapter(tracks));
                                Toast.makeText(this, "🎵 " + title + " - " + artist, Toast.LENGTH_LONG).show();
                            } else {
                                empty.setText("No results for: " + title);
                                empty.setVisibility(View.VISIBLE);
                            }
                        });
                    } else {
                        Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_MIC && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingRecognize != null) {
                pendingRecognize.run();
                pendingRecognize = null;
            }
        } else if (requestCode == PERM_MIC) {
            Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show();
        }
    }

    /* ======================================================================
     *  LIBRARY TAB
     * ====================================================================== */
    private void setupLibrary() {
        refreshLibrary();
        libraryView.findViewById(R.id.btn_create_playlist).setOnClickListener(v -> showCreatePlaylistDialog());
        libraryView.findViewById(R.id.card_liked).setOnClickListener(v -> {
            List<Track> liked = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getLikedSongs(this));
            showListScreen("Liked Songs", liked);
        });
        // Liked Songs play button
        View likedPlay = libraryView.findViewById(R.id.liked_play_btn);
        if (likedPlay != null) {
            likedPlay.setOnClickListener(v -> {
                List<Track> liked = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getLikedSongs(this));
                if (!liked.isEmpty()) {
                    PlayerQueueStore.setQueue(liked.toArray(new Track[0]), liked.get(0));
                    PlaybackManager.get().setTrack(liked.get(0));
                    PlaybackManager.get().togglePlayback(this);
                }
            });
        }
        // Filter chips (visual toggle only — full filtering can be added later)
        View chipPlaylists   = libraryView.findViewById(R.id.chip_playlists);
        View chipArtists     = libraryView.findViewById(R.id.chip_artists);
        View chipAlbums      = libraryView.findViewById(R.id.chip_albums);
        View chipDownloaded  = libraryView.findViewById(R.id.chip_downloaded);

        if (chipPlaylists != null) {
            chipPlaylists.setOnClickListener(v -> {
                List<Track> plTracks = new ArrayList<>();
                for (JSONObject p : LocalStorageManager.getPlaylists(this)) {
                    JSONArray arr = p.optJSONArray("songs");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            Track t = LocalStorageManager.jsonToTrack(arr.optJSONObject(i));
                            if (t != null) plTracks.add(t);
                        }
                    }
                }
                showListScreen("Playlists", plTracks);
            });
        }
        if (chipArtists != null) {
            chipArtists.setOnClickListener(v -> {
                List<Track> liked = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getLikedSongs(this));
                liked.sort((a,b) -> (a.artist==null?"":a.artist).compareToIgnoreCase(b.artist==null?"":b.artist));
                showListScreen("Artists", liked);
            });
        }
        if (chipAlbums != null) {
            chipAlbums.setOnClickListener(v -> {
                List<Track> liked = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getLikedSongs(this));
                liked.sort((a,b) -> (a.title==null?"":a.title).compareToIgnoreCase(b.title==null?"":b.title));
                showListScreen("Albums", liked);
            });
        }
        if (chipDownloaded != null) {
            chipDownloaded.setOnClickListener(v -> {
                List<Track> dl = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getDownloads(this));
                showListScreen("Downloaded", dl);
            });
        }
        // Recently Added — search button
        View libSearch = libraryView.findViewById(R.id.lib_search_btn);
        if (libSearch != null) {
            libSearch.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_search));
        }
        // See all for recently added
        View seeAll = libraryView.findViewById(R.id.recently_see_all);
        if (seeAll != null) {
            seeAll.setOnClickListener(v -> {
                List<Track> history = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getHistory(this));
                showListScreen("Recently Added", history);
            });
        }
    }

    private void refreshLibrary() {
        List<Track> likedTracks  = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getLikedSongs(this));
        List<Track> historyTracks = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getHistory(this));
        List<JSONObject> playlists = LocalStorageManager.getPlaylists(this);

        // Liked Songs count
        TextView likedCount = libraryView.findViewById(R.id.liked_count);
        if (likedCount != null) {
            likedCount.setText(likedTracks.size() + " tracks");
        }

        // ── Recently Added Grid (2 columns) ──────────────────────────────
        RecyclerView recentlyGrid = libraryView.findViewById(R.id.recently_added_grid);
        if (recentlyGrid != null) {
            List<Track> recentItems = historyTracks.subList(0, Math.min(6, historyTracks.size()));
            if (!recentItems.isEmpty()) {
                GridLayoutManager glm = new GridLayoutManager(this, 2);
                recentlyGrid.setLayoutManager(glm);
                recentlyGrid.setAdapter(new CardAdapter(recentItems));
                libraryView.findViewById(R.id.section_recently_added).setVisibility(View.VISIBLE);
            } else {
                libraryView.findViewById(R.id.section_recently_added).setVisibility(View.GONE);
            }
        }

        // ── Jump Back In List ──────────────────────────────────────────────
        RecyclerView jumpBackList = libraryView.findViewById(R.id.jump_back_list);
        if (jumpBackList != null) {
            List<Track> jumpItems = historyTracks.subList(0, Math.min(5, historyTracks.size()));
            if (!jumpItems.isEmpty()) {
                jumpBackList.setLayoutManager(new LinearLayoutManager(this));
                jumpBackList.setAdapter(new SongAdapter(jumpItems));
                libraryView.findViewById(R.id.section_jump_back).setVisibility(View.VISIBLE);
            } else {
                libraryView.findViewById(R.id.section_jump_back).setVisibility(View.GONE);
            }
        }

        // ── Playlists ──────────────────────────────────────────────────────
        RecyclerView playlistList = libraryView.findViewById(R.id.playlist_list);
        TextView playlistEmpty = libraryView.findViewById(R.id.playlist_empty);

        if (playlists.isEmpty()) {
            playlistList.setVisibility(View.GONE);
            playlistEmpty.setVisibility(View.VISIBLE);
        } else {
            playlistEmpty.setVisibility(View.GONE);
            playlistList.setVisibility(View.VISIBLE);
            playlistList.setLayoutManager(new LinearLayoutManager(this));
            playlistList.setAdapter(new PlaylistAdapter(playlists));
        }
    }

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF8E8E9F);
        input.setBackgroundColor(0xFF1A1A2E);
        input.setPadding(32, 24, 32, 24);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Create Playlist")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        LocalStorageManager.createPlaylist(this, name);
                        refreshLibrary();
                        Toast.makeText(this, "Playlist created", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showListScreen(String title, List<Track> tracks) {
        Intent intent = new Intent(this, ListActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("tracks", new ArrayList<>(tracks));
        startActivity(intent);
    }

    /* ======================================================================
     *  SETTINGS TAB
     * ====================================================================== */
    // ── Settings design helpers ───────────────────────────────────────────
    private static final int ST_BG=0xFF0A0A0C,ST_CARD=0xFF16161A,ST_W=0xFFFFFFFF;
    private static final int ST_PRIMARY=0xFFADC7FF,ST_G1=0xFFB3B3B3,ST_G2=0xFF6B7280;
    private static final int ST_DIV=0x0DFFFFFF,ST_BORDER=0x0DFFFFFF,ST_RIPPLE=0x14FFFFFF;
    private int sdp(int dp){return Math.round(dp*getResources().getDisplayMetrics().density);}

    private android.widget.LinearLayout stCard(){
        android.widget.LinearLayout c=new android.widget.LinearLayout(this);
        c.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(ST_CARD);bg.setCornerRadius(sdp(16));bg.setStroke(1,ST_BORDER);
        c.setBackground(bg);c.setClipToOutline(true);
        c.setOutlineProvider(new android.view.ViewOutlineProvider(){
            public void getOutline(android.view.View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),sdp(16));}});
        return c;
    }
    private android.widget.TextView stLabel(String text){
        android.widget.TextView t=new android.widget.TextView(this);
        t.setText(text);t.setTextColor(ST_PRIMARY);t.setTextSize(10);
        t.setLetterSpacing(0.15f);t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=sdp(8);t.setLayoutParams(lp);return t;
    }
    private android.widget.LinearLayout stRow(String icon,String title,String sub,boolean nav,Runnable onClick){
        android.widget.LinearLayout row=new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(sdp(16),sdp(14),sdp(16),sdp(14));
        if(onClick!=null){
            row.setClickable(true);row.setFocusable(true);
            android.content.res.ColorStateList rip=android.content.res.ColorStateList.valueOf(ST_RIPPLE);
            row.setBackground(new android.graphics.drawable.RippleDrawable(rip,null,null));
            row.setOnClickListener(v->onClick.run());
        }
        android.widget.LinearLayout iconBox=new android.widget.LinearLayout(this);
        android.graphics.drawable.GradientDrawable ibg=new android.graphics.drawable.GradientDrawable();
        ibg.setColor(0x1AADC7FF);ibg.setCornerRadius(sdp(8));iconBox.setBackground(ibg);
        iconBox.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams ilp=new android.widget.LinearLayout.LayoutParams(sdp(36),sdp(36));
        ilp.rightMargin=sdp(14);iconBox.setLayoutParams(ilp);
        android.widget.TextView ico=new android.widget.TextView(this);
        ico.setText(icon);ico.setTextSize(16);ico.setTextColor(ST_PRIMARY);
        ico.setGravity(android.view.Gravity.CENTER);iconBox.addView(ico);row.addView(iconBox);
        android.widget.LinearLayout txt=new android.widget.LinearLayout(this);
        txt.setOrientation(android.widget.LinearLayout.VERTICAL);
        txt.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1f));
        android.widget.TextView tv=new android.widget.TextView(this);
        tv.setText(title);tv.setTextColor(ST_W);tv.setTextSize(15);txt.addView(tv);
        if(sub!=null&&!sub.isEmpty()){
            android.widget.TextView sv2=new android.widget.TextView(this);
            sv2.setText(sub);sv2.setTextColor(ST_G2);sv2.setTextSize(12);txt.addView(sv2);
        }
        row.addView(txt);
        if(nav){
            android.widget.TextView ch=new android.widget.TextView(this);
            ch.setText("›");ch.setTextColor(ST_G2);ch.setTextSize(22);ch.setPadding(sdp(4),0,0,0);
            row.addView(ch);
        }
        return row;
    }
    private android.view.View stDivider(){
        android.view.View d=new android.view.View(this);
        d.setBackgroundColor(ST_DIV);
        android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-1,1);
        lp.leftMargin=sdp(16);lp.rightMargin=sdp(16);d.setLayoutParams(lp);return d;
    }

    private View createSettingsView() {
        android.widget.ScrollView sv=new android.widget.ScrollView(this);
        sv.setBackgroundColor(ST_BG);
        android.widget.LinearLayout root=new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(sdp(20),sdp(56),sdp(20),sdp(100));
        sv.addView(root,new android.widget.LinearLayout.LayoutParams(-1,-2));

        // ─── PROFILE HEADER CARD ─────────────────────────────────────────
        android.widget.LinearLayout profileCard = new android.widget.LinearLayout(this);
        profileCard.setOrientation(android.widget.LinearLayout.VERTICAL);
        profileCard.setGravity(android.view.Gravity.CENTER);
        profileCard.setPadding(sdp(20), sdp(28), sdp(20), sdp(24));
        android.widget.LinearLayout.LayoutParams profileLP = new android.widget.LinearLayout.LayoutParams(-1, -2);
        profileLP.bottomMargin = sdp(28);
        profileCard.setLayoutParams(profileLP);
        android.graphics.drawable.GradientDrawable profileBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{0xFF1F1050, 0xFF0D1B40, 0xFF0A0A14});
        profileBg.setCornerRadius(sdp(20));
        profileBg.setStroke(sdp(1), 0x33ADC7FF);
        profileCard.setBackground(profileBg);
        profileCard.setClipToOutline(true);
        // Icon circle
        android.widget.LinearLayout iconCircle = new android.widget.LinearLayout(this);
        iconCircle.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(0x1AADC7FF);
        iconBg.setStroke(sdp(1), 0x40ADC7FF);
        iconCircle.setBackground(iconBg);
        android.widget.LinearLayout.LayoutParams iconLP = new android.widget.LinearLayout.LayoutParams(sdp(80), sdp(80));
        iconLP.bottomMargin = sdp(14);
        iconCircle.setLayoutParams(iconLP);
        android.widget.TextView iconTv = new android.widget.TextView(this);
        iconTv.setText("♬"); iconTv.setTextSize(32); iconTv.setTextColor(ST_PRIMARY);
        iconTv.setGravity(android.view.Gravity.CENTER);
        iconCircle.addView(iconTv);
        profileCard.addView(iconCircle);
        // App name
        android.widget.TextView appNameTv = new android.widget.TextView(this);
        appNameTv.setText("Eclipse Music"); appNameTv.setTextColor(ST_W);
        appNameTv.setTextSize(22); appNameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appNameTv.setGravity(android.view.Gravity.CENTER);
        profileCard.addView(appNameTv);
        // Version badge
        android.widget.TextView verTv = new android.widget.TextView(this);
        verTv.setText("v1.0  ·  Your music, your way"); verTv.setTextColor(0x80ADC7FF);
        verTv.setTextSize(12); verTv.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams verLP = new android.widget.LinearLayout.LayoutParams(-1,-2);
        verLP.topMargin = sdp(5); verTv.setLayoutParams(verLP);
        profileCard.addView(verTv);
        root.addView(profileCard);

        // ─── AUDIO section ───────────────────────────────────────────────
        root.addView(stLabel("AUDIO"));
        android.widget.LinearLayout audioCard=stCard();
        android.widget.LinearLayout.LayoutParams cardLP=new android.widget.LinearLayout.LayoutParams(-1,-2);
        cardLP.bottomMargin=sdp(20);


        // Current quality display
        String[] qualityLabels={"Low (64 kbps)","Medium (128 kbps)","High (256 kbps)"};
        String[] qualityKeys={"low","medium","high"};
        String curQ=LocalStorageManager.getAudioQuality(this);
        int curQIdx=2;for(int i=0;i<qualityKeys.length;i++)if(qualityKeys[i].equals(curQ))curQIdx=i;
        final int[] selQ={curQIdx};
        final android.widget.TextView[] qSubRef={null};
        android.widget.LinearLayout qRow=stRow("♪","Audio Quality",qualityLabels[curQIdx],true,()->{
            String[] opts={qualityLabels[0],qualityLabels[1],qualityLabels[2]};
            new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Audio Quality").setSingleChoiceItems(opts,selQ[0],(d,w)->selQ[0]=w)
                .setPositiveButton("Apply",(d,w)->{
                    LocalStorageManager.setAudioQuality(this,qualityKeys[selQ[0]]);
                    if(qSubRef[0]!=null)qSubRef[0].setText(qualityLabels[selQ[0]]);
                }).setNegativeButton("Cancel",null).show();
        });
        // grab subtitle ref
        if(qRow.getChildCount()>1&&qRow.getChildAt(1) instanceof android.widget.LinearLayout){
            android.widget.LinearLayout qt=(android.widget.LinearLayout)qRow.getChildAt(1);
            if(qt.getChildCount()>1)qSubRef[0]=(android.widget.TextView)qt.getChildAt(1);
        }
        audioCard.addView(qRow);audioCard.addView(stDivider());

        // Crossfade
        int curCf=LocalStorageManager.getCrossfadeDuration(this);
        final int[]selCf={curCf};
        final android.widget.TextView[]cfSubRef={null};
        android.widget.LinearLayout cfRow=stRow("⇌","Crossfade",curCf==0?"Off":curCf+"s",true,()->{
            android.widget.LinearLayout dlg=new android.widget.LinearLayout(this);
            dlg.setOrientation(android.widget.LinearLayout.VERTICAL);dlg.setPadding(sdp(24),sdp(16),sdp(24),sdp(8));
            android.widget.TextView cfVal=new android.widget.TextView(this);
            cfVal.setTextColor(ST_W);cfVal.setTextSize(14);
            cfVal.setText(selCf[0]==0?"Off":selCf[0]+"s");dlg.addView(cfVal);
            android.widget.SeekBar sb=new android.widget.SeekBar(this);
            sb.setMax(10);sb.setProgress(selCf[0]);
            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(android.widget.SeekBar s,int p,boolean u){selCf[0]=p;cfVal.setText(p==0?"Off":p+"s");}
                public void onStartTrackingTouch(android.widget.SeekBar s){}public void onStopTrackingTouch(android.widget.SeekBar s){}});
            dlg.addView(sb);
            new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Crossfade Duration").setView(dlg)
                .setPositiveButton("Apply",(d,w)->{
                    LocalStorageManager.setCrossfadeDuration(this,selCf[0]);
                    if(cfSubRef[0]!=null)cfSubRef[0].setText(selCf[0]==0?"Off":selCf[0]+"s");
                }).setNegativeButton("Cancel",null).show();
        });
        if(cfRow.getChildCount()>1&&cfRow.getChildAt(1) instanceof android.widget.LinearLayout){
            android.widget.LinearLayout ct=(android.widget.LinearLayout)cfRow.getChildAt(1);
            if(ct.getChildCount()>1)cfSubRef[0]=(android.widget.TextView)ct.getChildAt(1);
        }
        audioCard.addView(cfRow);audioCard.addView(stDivider());

        // Equalizer
        audioCard.addView(stRow("≋","Equalizer","5-band EQ · Bass · Spatial",true,()->{
            android.widget.LinearLayout dlg=new android.widget.LinearLayout(this);
            dlg.setOrientation(android.widget.LinearLayout.VERTICAL);dlg.setPadding(sdp(16),sdp(8),sdp(16),sdp(8));
            EqualizerManager eq=EqualizerManager.get();
            String[]bandNames={"60Hz","230Hz","910Hz","3.6kHz","14kHz"};
            for(int b=0;b<5;b++){
                final int band=b;
                android.widget.LinearLayout br=new android.widget.LinearLayout(this);
                br.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                br.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.TextView bn=new android.widget.TextView(this);
                bn.setText(bandNames[b]);bn.setTextColor(ST_G1);bn.setTextSize(12);
                android.widget.LinearLayout.LayoutParams bnlp=new android.widget.LinearLayout.LayoutParams(sdp(56),-2);
                bn.setLayoutParams(bnlp);br.addView(bn);
                android.widget.SeekBar bs=new android.widget.SeekBar(this);bs.setMax(2000);
                try{bs.setProgress(eq.getBandLevel(this,b)+1000);}catch(Exception e){bs.setProgress(1000);}
                bs.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1f));
                bs.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
                    public void onProgressChanged(android.widget.SeekBar s,int p,boolean u){try{eq.setBandLevel(MainActivity.this,band,(short)(p-1000));}catch(Exception ignored){}}
                    public void onStartTrackingTouch(android.widget.SeekBar s){}
                    public void onStopTrackingTouch(android.widget.SeekBar s){}});
                br.addView(bs);dlg.addView(br);
            }
            new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Equalizer").setView(dlg).setPositiveButton("Done",null).show();
        }));
        root.addView(audioCard,cardLP);

        // ─── CONNECTIONS section ─────────────────────────────────────────
        root.addView(stLabel("CONNECTIONS"));
        android.widget.LinearLayout connCard=stCard();

        // Discord
        boolean dcConn=DiscordRPC.get().isConnected();
        final android.widget.TextView[]dcSubRef={null};
        android.widget.LinearLayout dcRow=stRow("🎮","Discord Rich Presence",dcConn?"Connected":"Not connected",true,()->{
            if(DiscordRPC.get().isConnected()){
                new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Discord")
                    .setMessage("Disconnect Discord Rich Presence?")
                    .setPositiveButton("Disconnect",(d,w)->{DiscordRPC.get().disconnect();if(dcSubRef[0]!=null)dcSubRef[0].setText("Not connected");})
                    .setNegativeButton("Cancel",null).show();
            } else {
                Intent i=new Intent(this,DiscordLoginActivity.class);startActivity(i);
            }
        });
        if(dcRow.getChildCount()>1&&dcRow.getChildAt(1) instanceof android.widget.LinearLayout){
            android.widget.LinearLayout dt=(android.widget.LinearLayout)dcRow.getChildAt(1);
            if(dt.getChildCount()>1)dcSubRef[0]=(android.widget.TextView)dt.getChildAt(1);
        }
        connCard.addView(dcRow);connCard.addView(stDivider());

        // Last.fm
        boolean lfConn=LastFmScrobbler.get().isAuthenticated();
        String lfUser=LocalStorageManager.getLastFmUsername(this);
        final android.widget.TextView[]lfSubRef={null};
        android.widget.LinearLayout lfRow=stRow("🎙","Last.fm Scrobbling",lfConn?"@"+lfUser:"Not connected",true,()->{
            if(LastFmScrobbler.get().isAuthenticated()){
                new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Last.fm").setMessage("Disconnect Last.fm scrobbling?")
                    .setPositiveButton("Disconnect",(d,w)->{LastFmScrobbler.get().logout(this);if(lfSubRef[0]!=null)lfSubRef[0].setText("Not connected");})
                    .setNegativeButton("Cancel",null).show();
            } else {
                android.widget.LinearLayout dlg=new android.widget.LinearLayout(this);
                dlg.setOrientation(android.widget.LinearLayout.VERTICAL);dlg.setPadding(sdp(24),sdp(8),sdp(24),sdp(8));
                android.widget.EditText eu=new android.widget.EditText(this);eu.setHint("Username");eu.setTextColor(ST_W);eu.setHintTextColor(ST_G2);dlg.addView(eu);
                android.widget.EditText ep=new android.widget.EditText(this);ep.setHint("Password");ep.setTextColor(ST_W);ep.setHintTextColor(ST_G2);
                ep.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);dlg.addView(ep);
                android.widget.EditText ek=new android.widget.EditText(this);ek.setHint("API Key");ek.setTextColor(ST_W);ek.setHintTextColor(ST_G2);dlg.addView(ek);
                android.widget.EditText es=new android.widget.EditText(this);es.setHint("API Secret");es.setTextColor(ST_W);es.setHintTextColor(ST_G2);dlg.addView(es);
                new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Connect Last.fm").setView(dlg)
                    .setPositiveButton("Connect",(d,w)->{
                        String u=eu.getText().toString().trim(),p=ep.getText().toString().trim();
                        String k=ek.getText().toString().trim(),sec=es.getText().toString().trim();
                        if(u.isEmpty()||p.isEmpty()||k.isEmpty()||sec.isEmpty()){Toast.makeText(this,"Fill all fields",Toast.LENGTH_SHORT).show();return;}
                        LocalStorageManager.saveLastFmCredentials(this,u,p,k,sec);
                        LastFmScrobbler.get().authenticate(this,
                            ()->{Toast.makeText(this,"✅ Last.fm connected!",Toast.LENGTH_SHORT).show();if(lfSubRef[0]!=null)lfSubRef[0].setText("@"+u);},
                            ()->{Toast.makeText(this,"❌ Auth failed",Toast.LENGTH_LONG).show();});
                    }).setNegativeButton("Cancel",null).show();
            }
        });
        if(lfRow.getChildCount()>1&&lfRow.getChildAt(1) instanceof android.widget.LinearLayout){
            android.widget.LinearLayout lt=(android.widget.LinearLayout)lfRow.getChildAt(1);
            if(lt.getChildCount()>1)lfSubRef[0]=(android.widget.TextView)lt.getChildAt(1);
        }
        connCard.addView(lfRow);
        root.addView(connCard,cardLP);

        // ─── MY STATS section ────────────────────────────────────────────
        root.addView(stLabel("MY STATS"));
        android.widget.LinearLayout statsCard=stCard();
        statsCard.setPadding(sdp(16),sdp(16),sdp(16),sdp(16));
        long totSec=LocalStorageManager.getTotalListeningSeconds(this);
        int totH=(int)(totSec/3600),totM=(int)((totSec%3600)/60);
        android.widget.TextView statTotal=new android.widget.TextView(this);
        statTotal.setText("⏱  "+(totH>0?totH+"h ":"")+totM+" min total");
        statTotal.setTextColor(ST_W);statTotal.setTextSize(16);
        statTotal.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);statsCard.addView(statTotal);
        List<String> topT=LocalStorageManager.getTopTracks(this,3);
        List<String> topA=LocalStorageManager.getTopArtists(this,3);
        if(!topT.isEmpty()){
            android.widget.TextView tl=new android.widget.TextView(this);
            tl.setText("Top Tracks");tl.setTextColor(ST_G2);tl.setTextSize(11);
            tl.setLetterSpacing(0.1f);android.widget.LinearLayout.LayoutParams tlp=new android.widget.LinearLayout.LayoutParams(-1,-2);tlp.topMargin=sdp(12);tl.setLayoutParams(tlp);
            statsCard.addView(tl);
            for(String s:topT){android.widget.TextView tv=new android.widget.TextView(this);tv.setText("  "+s);tv.setTextColor(ST_G1);tv.setTextSize(13);statsCard.addView(tv);}
        }
        if(!topA.isEmpty()){
            android.widget.TextView al=new android.widget.TextView(this);
            al.setText("Top Artists");al.setTextColor(ST_G2);al.setTextSize(11);
            al.setLetterSpacing(0.1f);android.widget.LinearLayout.LayoutParams alp=new android.widget.LinearLayout.LayoutParams(-1,-2);alp.topMargin=sdp(12);al.setLayoutParams(alp);
            statsCard.addView(al);
            for(String s:topA){android.widget.TextView tv=new android.widget.TextView(this);tv.setText("  "+s);tv.setTextColor(ST_G1);tv.setTextSize(13);statsCard.addView(tv);}
        }
        if(totSec==0){android.widget.TextView noSt=new android.widget.TextView(this);noSt.setText("No data yet — start listening!");noSt.setTextColor(ST_G2);noSt.setTextSize(13);statsCard.addView(noSt);}
        root.addView(statsCard,cardLP);

        // ─── DATA section ─────────────────────────────────────────────────
        root.addView(stLabel("DATA"));
        android.widget.LinearLayout dataCard=stCard();
        dataCard.addView(stRow("⊘","Clear History","Remove all recent plays",true,()->{
            new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Clear History").setMessage("Remove all play history?")
                .setPositiveButton("Clear",(d,w)->{LocalStorageManager.clearHistory(this);Toast.makeText(this,"History cleared",Toast.LENGTH_SHORT).show();})
                .setNegativeButton("Cancel",null).show();
        }));
        dataCard.addView(stDivider());
        dataCard.addView(stRow("🎁","Eclipse Wrapped 2025","Your year in music",true,()->showWrapped()));
        root.addView(dataCard,cardLP);

        // ─── ABOUT ────────────────────────────────────────────────────────
        android.widget.TextView about=new android.widget.TextView(this);
        about.setText("Eclipse Music  ·  v1.0");about.setTextColor(ST_G2);about.setTextSize(12);
        about.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams aLP=new android.widget.LinearLayout.LayoutParams(-1,-2);aLP.topMargin=sdp(8);
        about.setLayoutParams(aLP);root.addView(about);

        return sv;
    }

    private void showWrapped() {
        long totSec = LocalStorageManager.getTotalListeningSeconds(this);
        List<Track> histList = LocalStorageManager.jsonArrayToTracks(LocalStorageManager.getHistory(this));
        List<String> topT = LocalStorageManager.getTopTracks(this, 5);
        List<String> topA = LocalStorageManager.getTopArtists(this, 3);
        long hours = totSec/3600, mins = (totSec%3600)/60;

        android.widget.ScrollView sv2 = new android.widget.ScrollView(this);
        android.widget.LinearLayout root2 = new android.widget.LinearLayout(this);
        root2.setOrientation(android.widget.LinearLayout.VERTICAL);
        root2.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root2.setPadding(sdp(24),sdp(20),sdp(24),sdp(40));
        sv2.addView(root2);
        android.graphics.drawable.GradientDrawable grad = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF1A1040,0xFF0D1B40,0xFF0A0A0C});
        sv2.setBackground(grad);

        android.widget.TextView close2 = new android.widget.TextView(this);
        close2.setText("\u2715  Close"); close2.setTextColor(0x80FFFFFF); close2.setTextSize(14);
        android.widget.LinearLayout.LayoutParams clp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
        clp2.bottomMargin=sdp(8); close2.setLayoutParams(clp2); root2.addView(close2);

        android.widget.TextView yr2 = new android.widget.TextView(this);
        yr2.setText("2025"); yr2.setTextColor(0x70FFFFFF); yr2.setTextSize(13);
        yr2.setLetterSpacing(0.2f); yr2.setGravity(android.view.Gravity.CENTER); root2.addView(yr2);
        android.widget.TextView title2 = new android.widget.TextView(this);
        title2.setText("\uD83C\uDF81 Eclipse Wrapped"); title2.setTextColor(0xFFFFFFFF); title2.setTextSize(28);
        title2.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title2.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams t2lp = new android.widget.LinearLayout.LayoutParams(-1,-2);
        t2lp.topMargin=sdp(4); t2lp.bottomMargin=sdp(28); title2.setLayoutParams(t2lp); root2.addView(title2);

        java.util.function.BiConsumer<String,String> addStat2 = (label2, val2) -> {
            android.widget.LinearLayout sc = new android.widget.LinearLayout(this);
            sc.setOrientation(android.widget.LinearLayout.VERTICAL);
            sc.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable sbg2 = new android.graphics.drawable.GradientDrawable();
            sbg2.setColor(0x1AFFFFFF); sbg2.setCornerRadius(sdp(20));
            sc.setBackground(sbg2); sc.setPadding(sdp(20),sdp(20),sdp(20),sdp(20));
            android.widget.LinearLayout.LayoutParams slp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
            slp2.bottomMargin=sdp(12); sc.setLayoutParams(slp2);
            android.widget.TextView vv = new android.widget.TextView(this);
            vv.setText(val2); vv.setTextColor(0xFFFFFFFF); vv.setTextSize(34);
            vv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            vv.setGravity(android.view.Gravity.CENTER); sc.addView(vv);
            android.widget.TextView ll = new android.widget.TextView(this);
            ll.setText(label2); ll.setTextColor(0x99FFFFFF); ll.setTextSize(13);
            ll.setGravity(android.view.Gravity.CENTER); sc.addView(ll);
            root2.addView(sc);
        };
        addStat2.accept("Hours listened in 2025", hours+"h "+mins+"m");
        addStat2.accept("Total songs played", String.valueOf(histList.size()));

        if (!topT.isEmpty()) {
            android.widget.LinearLayout tc = new android.widget.LinearLayout(this);
            tc.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable tbg2 = new android.graphics.drawable.GradientDrawable();
            tbg2.setColor(0x1AADC7FF); tbg2.setCornerRadius(sdp(20));
            tc.setBackground(tbg2); tc.setPadding(sdp(20),sdp(16),sdp(20),sdp(16));
            android.widget.LinearLayout.LayoutParams tclp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
            tclp2.bottomMargin=sdp(12); tc.setLayoutParams(tclp2);
            android.widget.TextView tl2 = new android.widget.TextView(this);
            tl2.setText("\uD83C\uDFB5  Top Songs"); tl2.setTextColor(0xFFADC7FF); tl2.setTextSize(14);
            tl2.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tc.addView(tl2);
            for (int i=0;i<topT.size();i++) {
                android.widget.TextView tv2 = new android.widget.TextView(this);
                tv2.setText((i+1)+".  "+topT.get(i)); tv2.setTextColor(0xFFFFFFFF); tv2.setTextSize(15);
                android.widget.LinearLayout.LayoutParams vlp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
                vlp2.topMargin=sdp(8); tv2.setLayoutParams(vlp2); tc.addView(tv2);
            }
            root2.addView(tc);
        }
        if (!topA.isEmpty()) {
            android.widget.LinearLayout ac2 = new android.widget.LinearLayout(this);
            ac2.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable abg2 = new android.graphics.drawable.GradientDrawable();
            abg2.setColor(0x1AFFB695); abg2.setCornerRadius(sdp(20));
            ac2.setBackground(abg2); ac2.setPadding(sdp(20),sdp(16),sdp(20),sdp(16));
            android.widget.LinearLayout.LayoutParams aclp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
            aclp2.bottomMargin=sdp(20); ac2.setLayoutParams(aclp2);
            android.widget.TextView al2 = new android.widget.TextView(this);
            al2.setText("\uD83C\uDFA4  Top Artists"); al2.setTextColor(0xFFFFB695); al2.setTextSize(14);
            al2.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); ac2.addView(al2);
            for (int i=0;i<topA.size();i++) {
                android.widget.TextView av2 = new android.widget.TextView(this);
                av2.setText((i+1)+".  "+topA.get(i)); av2.setTextColor(0xFFFFFFFF); av2.setTextSize(15);
                android.widget.LinearLayout.LayoutParams avlp2 = new android.widget.LinearLayout.LayoutParams(-1,-2);
                avlp2.topMargin=sdp(8); av2.setLayoutParams(avlp2); ac2.addView(av2);
            }
            root2.addView(ac2);
        }
        android.widget.Button share2 = new android.widget.Button(this);
        share2.setText("\uD83D\uDCE4  Share My Wrapped"); share2.setTextColor(0xFF0A0A0C); share2.setTextSize(15);
        android.graphics.drawable.GradientDrawable shbg = new android.graphics.drawable.GradientDrawable();
        shbg.setColor(0xFFADC7FF); shbg.setCornerRadius(sdp(50));
        share2.setBackground(shbg);
        android.widget.LinearLayout.LayoutParams shLP2 = new android.widget.LinearLayout.LayoutParams(-1,sdp(52));
        shLP2.topMargin=sdp(4); share2.setLayoutParams(shLP2);
        share2.setOnClickListener(v -> {
            StringBuilder sb2 = new StringBuilder();
            sb2.append("\uD83C\uDF81 My Eclipse Wrapped 2025\n");
            sb2.append("\u23F1 ").append(hours).append("h ").append(mins).append("m listened\n");
            if (!topT.isEmpty()) sb2.append("\n\uD83C\uDFB5 Top Song: ").append(topT.get(0));
            if (!topA.isEmpty()) sb2.append("\n\uD83C\uDFA4 Top Artist: ").append(topA.get(0));
            sb2.append("\n\nListening on Eclipse Music \uD83C\uDF19");
            Intent si = new Intent(Intent.ACTION_SEND);
            si.setType("text/plain"); si.putExtra(Intent.EXTRA_TEXT, sb2.toString());
            startActivity(Intent.createChooser(si,"Share Wrapped"));
        });
        root2.addView(share2);
        AlertDialog wd = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(sv2).create();
        close2.setOnClickListener(v -> wd.dismiss());
        wd.show();
    }

    /* ======================================================================
     *  MINI PLAYER
     * ====================================================================== */
    private PlaybackManager.Listener miniPlayerListener;

    private void setupMiniPlayer() {
        miniPlayerContainer.setOnClickListener(v -> {
            Track t = PlaybackManager.get().currentTrack();
            if (t != null) {
                Intent intent = new Intent(this, NativePlayerActivity.class);
                intent.putExtra(NativePlayerActivity.EXTRA_TITLE, t.title);
                intent.putExtra(NativePlayerActivity.EXTRA_ARTIST, t.artist);
                intent.putExtra(NativePlayerActivity.EXTRA_META, t.meta);
                intent.putExtra(NativePlayerActivity.EXTRA_SOURCE_ID, t.sourceId);
                startActivity(intent);
            }
        });

        // Play/pause — direct click on the ImageView
        ImageView playPause = miniPlayerContainer.findViewById(R.id.mini_play_pause);
        if (playPause != null) {
            playPause.setOnClickListener(v -> {
                PlaybackManager.get().togglePlayback(this);
                updateMiniPlayer();
            });
        }

        // Prev button
        ImageView prevBtn = miniPlayerContainer.findViewById(R.id.mini_prev);
        if (prevBtn != null) {
            prevBtn.setOnClickListener(v -> {
                Track pt = PlayerQueueStore.previous();
                if (pt != null) PlaybackManager.get().switchTrack(this, pt, true);
            });
        }

        // Next button
        ImageView nextBtn = miniPlayerContainer.findViewById(R.id.mini_next);
        if (nextBtn != null) {
            nextBtn.setOnClickListener(v -> {
                Track nt = PlayerQueueStore.next();
                if (nt != null) PlaybackManager.get().switchTrack(this, nt, true);
            });
        }

        miniPlayerListener = () -> mainHandler.post(this::updateMiniPlayer);
        PlaybackManager.get().addListener(miniPlayerListener);
    }

    private void updateMiniPlayer() {
        PlaybackManager pm = PlaybackManager.get();
        Track t = pm.currentTrack();
        if (t != null) {
            miniPlayerContainer.setVisibility(View.VISIBLE);
            ((TextView) miniPlayerContainer.findViewById(R.id.mini_title)).setText(t.title);
            ((TextView) miniPlayerContainer.findViewById(R.id.mini_artist)).setText(t.artist);
            ((ImageView) miniPlayerContainer.findViewById(R.id.mini_play_pause))
                    .setImageResource(pm.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            loadThumb(t.thumbnailUrl, miniPlayerContainer.findViewById(R.id.mini_thumbnail));

            // Progress bar
            View progress = miniPlayerContainer.findViewById(R.id.mini_progress);
            if (progress != null && pm.duration() > 0) {
                float ratio = pm.currentPosition() / (float) pm.duration();
                int parentWidth = miniPlayerContainer.getWidth();
                if (parentWidth > 0) {
                    progress.getLayoutParams().width = (int) (parentWidth * Math.max(0, Math.min(1, ratio)));
                    progress.requestLayout();
                }
            }
        }

        if (pm.isPlaying()) {
            mainHandler.postDelayed(this::updateMiniPlayer, 500);
        }
    }

    /* ======================================================================
     *  PLAY TRACK
     * ====================================================================== */
    void playTrack(Track track) {
        LocalStorageManager.addToHistory(this, track);
        PlaybackManager.get().switchTrack(this, track, true);
        updateMiniPlayer();

        // Open player screen
        Intent intent = new Intent(this, NativePlayerActivity.class);
        intent.putExtra(NativePlayerActivity.EXTRA_TITLE, track.title);
        intent.putExtra(NativePlayerActivity.EXTRA_ARTIST, track.artist);
        intent.putExtra(NativePlayerActivity.EXTRA_META, track.meta);
        intent.putExtra(NativePlayerActivity.EXTRA_SOURCE_ID, track.sourceId);
        startActivity(intent);
    }

    /* ======================================================================
     *  ADAPTERS
     * ====================================================================== */
    class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {
        private final List<Track> tracks;
        SongAdapter(List<Track> tracks) { this.tracks = tracks; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Track t = tracks.get(pos);
            h.title.setText(t.title);
            h.artist.setText(t.artist);
            loadThumb(t.thumbnailUrl, h.thumb);
            h.itemView.setOnClickListener(v -> playTrack(t));
            h.more.setOnClickListener(v -> showTrackMenu(v, t));
        }

        @Override public int getItemCount() { return tracks.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb; TextView title, artist; ImageView more;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.song_thumb);
                title = v.findViewById(R.id.song_title);
                artist = v.findViewById(R.id.song_artist);
                more = v.findViewById(R.id.song_more);
            }
        }
    }

    class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
        private final List<Track> tracks;
        CardAdapter(List<Track> tracks) { this.tracks = tracks; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Track t = tracks.get(pos);
            h.title.setText(t.title);
            h.artist.setText(t.artist);
            loadThumb(t.thumbnailUrl, h.thumb);
            h.itemView.setOnClickListener(v -> playTrack(t));
        }

        @Override public int getItemCount() { return tracks.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb; TextView title, artist;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.card_thumb);
                title = v.findViewById(R.id.card_title);
                artist = v.findViewById(R.id.card_artist);
            }
        }
    }

    class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {
        private final List<JSONObject> playlists;
        PlaylistAdapter(List<JSONObject> playlists) { this.playlists = playlists; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject p = playlists.get(pos);
            String name = p.optString("name", "Playlist");
            String pid = p.optString("id", "");
            JSONArray songs = p.optJSONArray("songs");
            int count = songs != null ? songs.length() : 0;

            h.title.setText(name);
            h.artist.setText(count + " songs");
            h.thumb.setImageResource(R.drawable.ic_music_note);
            h.thumb.setScaleType(ImageView.ScaleType.CENTER);
            h.thumb.setBackgroundColor(0xFF252540);

            h.itemView.setOnClickListener(v -> {
                List<Track> tracks = new ArrayList<>();
                if (songs != null) {
                    for (int i = 0; i < songs.length(); i++) {
                        Track t = LocalStorageManager.jsonToTrack(songs.optJSONObject(i));
                        if (t != null) tracks.add(t);
                    }
                }
                showListScreen(name, tracks);
            });

            h.more.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(MainActivity.this, v);
                popup.getMenu().add("Delete");
                popup.setOnMenuItemClickListener(item -> {
                    LocalStorageManager.deletePlaylist(MainActivity.this, pid);
                    refreshLibrary();
                    return true;
                });
                popup.show();
            });
        }

        @Override public int getItemCount() { return playlists.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb; TextView title, artist; ImageView more;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.song_thumb);
                title = v.findViewById(R.id.song_title);
                artist = v.findViewById(R.id.song_artist);
                more = v.findViewById(R.id.song_more);
            }
        }
    }

    /* ======================================================================
     *  TRACK CONTEXT MENU
     * ====================================================================== */
    private void showTrackMenu(View anchor, Track track) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean isLiked = LocalStorageManager.isLiked(this, track.title, track.artist);
        popup.getMenu().add(0, 1, 0, "Play");
        popup.getMenu().add(0, 2, 1, isLiked ? "Unlike" : "Like");
        popup.getMenu().add(0, 3, 2, "Add to Playlist");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: playTrack(track); break;
                case 2:
                    boolean liked = LocalStorageManager.toggleLike(this, track);
                    Toast.makeText(this,
                            liked ? "Added to Liked Songs" : "Removed from Liked Songs",
                            Toast.LENGTH_SHORT).show();
                    break;
                case 3: showAddToPlaylistDialog(track); break;
            }
            return true;
        });
        popup.show();
    }

    private void showAddToPlaylistDialog(Track track) {
        List<JSONObject> playlists = LocalStorageManager.getPlaylists(this);
        if (playlists.isEmpty()) {
            Toast.makeText(this, "Create a playlist first", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[playlists.size()];
        String[] ids = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).optString("name", "Playlist");
            ids[i] = playlists.get(i).optString("id", "");
        }

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Add to Playlist")
                .setItems(names, (d, which) -> {
                    LocalStorageManager.addToPlaylist(this, ids[which], track);
                    Toast.makeText(this, "Added to " + names[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /* ======================================================================
     *  IMAGE LOADING
     * ====================================================================== */
    void loadThumb(String url, ImageView target) {
        if (url == null || url.isEmpty()) {
            target.setImageResource(R.drawable.ic_music_note);
            return;
        }
        Bitmap cached = thumbCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(R.drawable.ic_music_note);
        new Thread(() -> {
            try {
                Bitmap bmp = BitmapFactory.decodeStream(new URL(url).openStream());
                if (bmp != null) {
                    thumbCache.put(url, bmp);
                    mainHandler.post(() -> target.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {}
        }).start();
    }
}
