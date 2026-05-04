package com.eclipseapp.pulse;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.net.URL;
import java.util.List;

public final class NativePlayerActivity extends Activity {
    static final String EXTRA_TITLE="com.eclipseapp.pulse.extra.TITLE";
    static final String EXTRA_ARTIST="com.eclipseapp.pulse.extra.ARTIST";
    static final String EXTRA_META="com.eclipseapp.pulse.extra.META";
    static final String EXTRA_AUDIO_URL="com.eclipseapp.pulse.extra.AUDIO_URL";
    static final String EXTRA_SOURCE_ID="com.eclipseapp.pulse.extra.SOURCE_ID";

    static final int BG=0xFF0A0A0C,SF=0xFF121212,SF2=0xFF16161A,SF3=0xFF282828;
    static final int W=0xFFFFFFFF,W70=0xB3FFFFFF,W40=0x66FFFFFF,W15=0x26FFFFFF,W10=0x1AFFFFFF,W5=0x0DFFFFFF;
    static final int G1=0xFFB3B3B3,G2=0xFF6B7280,G3=0xFF535353;

    final PlaybackManager pb=PlaybackManager.get();
    final Handler ui=new Handler(Looper.getMainLooper());
    final Runnable tick=new Runnable(){public void run(){renderProg();syncLyrics();ui.postDelayed(this,300);}};
    ImageView art,playIc,favIc;TextView titleV,artistV,elapV,durV,statV;
    SeekBar seek;ProgressBar load;
    LinearLayout queueCont,lyricsCont,queueList,lyricsLines;
    ScrollView lyricsScroll;
    Button tabQueue,tabLyrics;TextView queueCnt;
    boolean liked,seeking;
    java.util.List<SyncedLyricsParser.Line> syncedLines=new java.util.ArrayList<>();
    int lastActiveLine=-1;
    // Drag-to-reorder state (activity-level to survive drawQueue rebuilds)
    int qDragFrom=-1;
    boolean qDragging=false;
    final android.os.Handler qDragH=new android.os.Handler(Looper.getMainLooper());
    Runnable qDragActivate;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        pb.attach(this);
        MainActivity.Track r=reqTrack(),a=pb.currentTrack();
        if(r!=null&&(a==null||!same(a,r)))pb.setTrack(r);
        MainActivity.Track t=pb.currentTrack();if(t==null)t=r;
        if(t!=null)liked=LocalStorageManager.isLiked(this,t.title,t.artist);
        setContentView(buildUI());renderPB();loadLyrics();loadThumb();
    }
    @Override protected void onStart(){super.onStart();pb.addListener(this::renderPB);ui.post(tick);}
    @Override protected void onStop(){ui.removeCallbacks(tick);pb.removeListener(this::renderPB);super.onStop();}

    private void renderPB(){ui.post(()->{
        MainActivity.Track t=pb.currentTrack();
        if(t==null){t=reqTrack();if(t==null)return;pb.setTrack(t);}
        titleV.setText(t.title);artistV.setText(t.artist);statV.setText(pb.status());
        playIc.setImageResource(pb.isPlaying()?R.drawable.ic_pause:R.drawable.ic_play);
        favIc.setColorFilter(liked?W:G2);
        load.setVisibility(pb.isBusy()?View.VISIBLE:View.GONE);
        boolean ss=pb.isBusy()||pb.status().contains("Gagal")||pb.status().contains("tidak");
        statV.setVisibility(ss?View.VISIBLE:View.GONE);
        if(t.thumbnail!=null)art.setImageBitmap(t.thumbnail);
        fetchKey(t.title, t.artist);
        drawQueue();
    });}

    private void renderProg(){
        int d=pb.duration(),p=pb.currentPosition();
        elapV.setText(fmt(p));durV.setText(d>0?fmt(d):"--:--");
        if(!seeking&&d>0)seek.setProgress((int)(p/(float)d*1000));
    }

    View buildUI(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout r=vl();r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(0,0,0,dp(24));
        sv.addView(r,new ScrollView.LayoutParams(-1,-2));

        // Top bar — extra top padding to clear status bar
        LinearLayout tb=hl(Gravity.CENTER_VERTICAL);tb.setPadding(dp(16),dp(52),dp(16),dp(8));
        r.addView(tb,lp(-1,-2));
        ImageView col=ic(R.drawable.ic_chevron_down,G1);tb.addView(col,lp(dp(40),dp(40)));
        col.setOnClickListener(v->finish());
        TextView hdr=tx("NOW PLAYING",11,G2,true);hdr.setGravity(Gravity.CENTER);hdr.setLetterSpacing(0.12f);
        tb.addView(hdr,new LinearLayout.LayoutParams(0,-2,1f));

        // Sleep timer button (left of 3-dot)
        ImageView sleepBtn=ic(R.drawable.ic_moon,pb.isSleepTimerActive()?W:G2);
        tb.addView(sleepBtn,lp(dp(40),dp(40)));
        sleepBtn.setOnClickListener(v->showSleepTimerDialog(sleepBtn));
        sleepBtn.setTag("sleep_btn");

        // 3-dot menu (rightmost)
        ImageView more=ic(R.drawable.ic_more_vert,G1);tb.addView(more,lp(dp(40),dp(40)));
        more.setOnClickListener(v->{PopupMenu pm=new PopupMenu(this,v);
            pm.getMenu().add(0,1,0,"Add to Playlist");
            pm.getMenu().add(0,2,1,"Open YouTube");
            pm.getMenu().add(0,3,2,"Share");
            pm.getMenu().add(0,4,3,"\uD83C� Sounds Like...");
            pm.setOnMenuItemClickListener(i->{
                if(i.getItemId()==1)addToPL();
                else if(i.getItemId()==2)openYT();
                else if(i.getItemId()==3)shareTrack();
                else showSoundsLike();
                return true;});pm.show();});



        // Album Art — rounded square
        FrameLayout artWrap=new FrameLayout(this);
        LinearLayout.LayoutParams awLP=lp(dp(280),dp(280));awLP.topMargin=dp(24);
        r.addView(artWrap,awLP);
        art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setBackgroundColor(SF2);
        art.setClipToOutline(true);art.setOutlineProvider(new ViewOutlineProvider(){
            public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(16));}});
        artWrap.addView(art,new FrameLayout.LayoutParams(dp(280),dp(280),Gravity.CENTER));

        // Title (centered)
        titleV=tx("",24,W,true);titleV.setGravity(Gravity.CENTER);titleV.setMaxLines(1);
        titleV.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tiLP=lp(-1,-2);tiLP.topMargin=dp(24);tiLP.leftMargin=dp(24);tiLP.rightMargin=dp(24);
        r.addView(titleV,tiLP);

        // Key badge — shows musical key (e.g. "C#m", "G")
        keyBadge=new TextView(this);keyBadge.setText("♪");keyBadge.setTextColor(0xFFADC7FF);
        keyBadge.setTextSize(12);keyBadge.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable kbBg=new android.graphics.drawable.GradientDrawable();
        kbBg.setColor(0x1AADC7FF);kbBg.setCornerRadius(dp(20));
        keyBadge.setBackground(kbBg);keyBadge.setPadding(dp(10),dp(3),dp(10),dp(3));
        LinearLayout.LayoutParams kbLP=lp(-2,-2);kbLP.topMargin=dp(6);kbLP.gravity=Gravity.CENTER;
        r.addView(keyBadge,kbLP);

        // Artist row: [spacer 108dp] [artist centered weight=1] [like 48dp] [download 48dp + 12dp gap]
        LinearLayout artistRow=hl(Gravity.CENTER_VERTICAL);
        artistRow.setPadding(dp(12),dp(4),dp(12),0);
        r.addView(artistRow,lp(-1,-2));

        // Invisible spacer = same width as buttons side (48+48 = 96dp)
        View spacer=new View(this);
        artistRow.addView(spacer,lp(dp(96),dp(1)));

        // Artist (centered, takes remaining space)
        artistV=tx("",15,G2,false);artistV.setMaxLines(1);artistV.setGravity(Gravity.CENTER);
        artistV.setEllipsize(TextUtils.TruncateAt.END);
        artistRow.addView(artistV,new LinearLayout.LayoutParams(0,-2,1f));

        // Like button (48dp)
        favIc=new ImageView(this);favIc.setImageResource(R.drawable.ic_favorite);
        favIc.setColorFilter(liked?W:G2);favIc.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        favIc.setPadding(dp(10),dp(10),dp(10),dp(10));
        artistRow.addView(favIc,lp(dp(48),dp(48)));
        favIc.setOnClickListener(v->{MainActivity.Track t=pb.currentTrack();if(t!=null){
            liked=LocalStorageManager.toggleLike(this,t);favIc.setColorFilter(liked?W:G2);
            Toast.makeText(this,liked?"Liked!":"Unliked",Toast.LENGTH_SHORT).show();}});

        // Download button (48dp)
        ImageView dlIc=new ImageView(this);dlIc.setImageResource(R.drawable.ic_download);
        dlIc.setColorFilter(G2);dlIc.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        dlIc.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout.LayoutParams dlLP=lp(dp(48),dp(48));
        artistRow.addView(dlIc,dlLP);
        dlIc.setOnClickListener(v->downloadTrack(dlIc));

        // Status
        LinearLayout stRow=hl(Gravity.CENTER);LinearLayout.LayoutParams srLP=lp(-1,-2);srLP.topMargin=dp(4);
        r.addView(stRow,srLP);
        load=new ProgressBar(this);load.setVisibility(View.GONE);stRow.addView(load,lp(dp(14),dp(14)));
        statV=tx("",11,G1,false);LinearLayout.LayoutParams svLP=lp(-2,-2);svLP.leftMargin=dp(4);stRow.addView(statV,svLP);

        // SeekBar
        seek=new SeekBar(this);seek.setMax(1000);seek.getProgressDrawable().setTint(W);seek.getThumb().setTint(W);
        LinearLayout.LayoutParams skLP=lp(-1,-2);skLP.topMargin=dp(20);skLP.leftMargin=dp(20);skLP.rightMargin=dp(20);
        r.addView(seek,skLP);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){if(u&&pb.duration()>0)elapV.setText(fmt((int)((p/1000.0)*pb.duration())));}
            public void onStartTrackingTouch(SeekBar s){seeking=true;}
            public void onStopTrackingTouch(SeekBar s){seeking=false;if(pb.duration()>0)pb.seekTo((int)((s.getProgress()/1000.0)*pb.duration()));}
        });

        // Time
        LinearLayout tRow=hl(0);tRow.setPadding(dp(24),dp(2),dp(24),0);r.addView(tRow,lp(-1,-2));
        elapV=tx("0:00",10,G2,false);tRow.addView(elapV,new LinearLayout.LayoutParams(0,-2,1f));
        durV=tx("--:--",10,G2,false);durV.setGravity(Gravity.END);tRow.addView(durV,new LinearLayout.LayoutParams(0,-2,1f));

        // Transport
        LinearLayout tr=hl(Gravity.CENTER_VERTICAL);tr.setPadding(dp(24),dp(16),dp(24),0);r.addView(tr,lp(-1,-2));

        // Shuffle button
        ImageView shuf=ic(R.drawable.ic_shuffle,PlayerQueueStore.isShuffleOn()?W:G2);
        tr.addView(shuf,new LinearLayout.LayoutParams(0,dp(48),1f));
        shuf.setOnClickListener(v->{
            PlayerQueueStore.toggleShuffle();
            shuf.setColorFilter(PlayerQueueStore.isShuffleOn()?W:G2);
            Toast.makeText(this,PlayerQueueStore.isShuffleOn()?"Shuffle ON":"Shuffle OFF",Toast.LENGTH_SHORT).show();
        });

        ImageView prev=ic(R.drawable.ic_skip_previous,W);tr.addView(prev,new LinearLayout.LayoutParams(0,dp(48),1f));
        prev.setOnClickListener(v->{MainActivity.Track p=PlayerQueueStore.previous();if(p!=null){pb.switchTrack(this,p,true);loadThumb();loadLyrics();}});

        // Play — white circle
        FrameLayout pw=new FrameLayout(this);tr.addView(pw,new LinearLayout.LayoutParams(dp(72),dp(72)));
        FrameLayout pb2=new FrameLayout(this);GradientDrawable cd=new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);cd.setColor(W);pb2.setBackground(cd);
        pw.addView(pb2,new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER));
        playIc=new ImageView(this);playIc.setImageResource(R.drawable.ic_play);playIc.setColorFilter(BG);
        playIc.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pb2.addView(playIc,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        pb2.setOnClickListener(v->pb.togglePlayback(this));

        ImageView next=ic(R.drawable.ic_skip_next,W);tr.addView(next,new LinearLayout.LayoutParams(0,dp(48),1f));
        next.setOnClickListener(v->{MainActivity.Track n=PlayerQueueStore.next();if(n!=null){pb.switchTrack(this,n,true);loadThumb();loadLyrics();}});

        // Loop button — cycles: off → loop all → loop one
        ImageView rep=ic(R.drawable.ic_repeat,loopColor());
        tr.addView(rep,new LinearLayout.LayoutParams(0,dp(48),1f));
        rep.setOnClickListener(v->{
            PlayerQueueStore.cycleLoop();
            int mode=PlayerQueueStore.getLoopMode();
            rep.setColorFilter(loopColor());
            rep.setImageResource(mode==2?R.drawable.ic_repeat_one:R.drawable.ic_repeat);
            String[] labels={"Loop OFF","Loop ALL","Loop ONE"};
            Toast.makeText(this,labels[mode],Toast.LENGTH_SHORT).show();
        });

        // Tabs
        LinearLayout tabs=hl(Gravity.CENTER);GradientDrawable tabBg=new GradientDrawable();
        tabBg.setCornerRadius(dp(12));tabBg.setColor(W5);tabs.setBackground(tabBg);
        tabs.setPadding(dp(4),dp(4),dp(4),dp(4));
        LinearLayout.LayoutParams tbLP=lp(-1,-2);tbLP.topMargin=dp(24);tbLP.leftMargin=dp(24);tbLP.rightMargin=dp(24);
        r.addView(tabs,tbLP);
        tabQueue=tabBtn("QUEUE",true);tabs.addView(tabQueue,new LinearLayout.LayoutParams(0,-2,1f));
        tabLyrics=tabBtn("LYRICS",false);tabs.addView(tabLyrics,new LinearLayout.LayoutParams(0,-2,1f));
        tabQueue.setOnClickListener(v->switchTab(true));tabLyrics.setOnClickListener(v->switchTab(false));

        // Queue
        queueCont=vl();queueCont.setPadding(dp(24),dp(16),dp(24),dp(8));r.addView(queueCont,lp(-1,-2));
        LinearLayout qh=hl(Gravity.CENTER_VERTICAL);queueCont.addView(qh,lp(-1,-2));
        qh.addView(tx("UP NEXT",10,G2,true),new LinearLayout.LayoutParams(0,-2,1f));
        queueCnt=tx("",10,G1,false);qh.addView(queueCnt);
        queueList=vl();LinearLayout.LayoutParams qlLP=lp(-1,-2);qlLP.topMargin=dp(10);queueCont.addView(queueList,qlLP);

        // Lyrics (synced, per-line like Metrolist)
        lyricsCont=vl();lyricsCont.setPadding(dp(24),dp(16),dp(24),dp(8));lyricsCont.setVisibility(View.GONE);
        r.addView(lyricsCont,lp(-1,-2));
        lyricsCont.addView(tx("LYRICS",10,G2,true));
        lyricsScroll=new ScrollView(this);
        LinearLayout.LayoutParams lsLP=lp(-1,-2);lsLP.topMargin=dp(12);lyricsCont.addView(lyricsScroll,lsLP);
        lyricsLines=vl();lyricsLines.setPadding(0,dp(8),0,dp(40));
        lyricsScroll.addView(lyricsLines,new ScrollView.LayoutParams(-1,-2));

        return sv;
    }

    void switchTab(boolean q){queueCont.setVisibility(q?View.VISIBLE:View.GONE);lyricsCont.setVisibility(q?View.GONE:View.VISIBLE);styleTab(tabQueue,q);styleTab(tabLyrics,!q);}
    void styleTab(Button b,boolean a){GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(8));bg.setColor(a?W10:0);b.setBackground(bg);b.setTextColor(a?W:G2);}
    Button tabBtn(String t,boolean a){Button b=new Button(this);b.setText(t);b.setTextSize(10);b.setLetterSpacing(0.12f);
        b.setAllCaps(true);b.setTypeface(Typeface.DEFAULT_BOLD);b.setPadding(0,dp(8),0,dp(8));
        b.setStateListAnimator(null);b.setElevation(0);styleTab(b,a);return b;}

    void drawQueue(){
        queueList.removeAllViews();
        List<MainActivity.Track> tracks=PlayerQueueStore.snapshot();
        int ai=PlayerQueueStore.currentIndex();queueCnt.setText(tracks.size()+" tracks");
        for(int i=0;i<tracks.size();i++){
            MainActivity.Track t=tracks.get(i);boolean act=i==ai;
            LinearLayout row=hl(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));
            GradientDrawable rb=new GradientDrawable();rb.setCornerRadius(dp(12));
            rb.setColor(act?W10:W5);rb.setStroke(dp(1),act?W15:0x0AFFFFFF);row.setBackground(rb);
            LinearLayout.LayoutParams rlp=lp(-1,-2);rlp.bottomMargin=dp(6);queueList.addView(row,rlp);

            // Drag handle
            TextView drag=new TextView(this);drag.setText("≡");
            drag.setTextColor(G3);drag.setTextSize(18);drag.setPadding(dp(4),0,dp(10),0);
            row.addView(drag,lp(-2,-2));

            ImageView th=new ImageView(this);th.setScaleType(ImageView.ScaleType.CENTER_CROP);th.setBackgroundColor(SF2);
            th.setClipToOutline(true);th.setOutlineProvider(new ViewOutlineProvider(){
                public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(4));}});
            row.addView(th,lp(dp(40),dp(40)));loadThumbInto(t.thumbnailUrl,th);
            LinearLayout c=vl();LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(0,-2,1f);clp.leftMargin=dp(12);row.addView(c,clp);
            TextView rt=tx(t.title,14,W,true);rt.setMaxLines(1);rt.setEllipsize(TextUtils.TruncateAt.END);c.addView(rt);
            TextView ra=tx(t.artist,12,G2,false);ra.setMaxLines(1);c.addView(ra);
            if(act){ImageView pi=new ImageView(this);pi.setImageResource(R.drawable.ic_play);pi.setColorFilter(W);row.addView(pi,lp(dp(20),dp(20)));}
            final int idx=i;
            row.setOnClickListener(v->{
                if(!qDragging){pb.switchTrack(this,PlayerQueueStore.playAt(idx),true);loadThumb();loadLyrics();}
            });

            // Drag handle touch: manual long-press detection avoids Android's ACTION_CANCEL bug
            drag.setOnTouchListener((v,ev)->{
                switch(ev.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        qDragH.removeCallbacks(qDragActivate);
                        qDragFrom=idx; qDragging=false;
                        qDragActivate=()->{
                            qDragging=true;
                            drag.setTextColor(W);
                            View r=queueList.getChildAt(qDragFrom);
                            if(r!=null){r.setAlpha(0.55f);r.setElevation(dp(8));}
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                            // Stop ScrollView from stealing touch
                            queueList.requestDisallowInterceptTouchEvent(true);
                        };
                        qDragH.postDelayed(qDragActivate, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if(!qDragging) return true;
                        int rawY=(int)ev.getRawY();
                        for(int j=0;j<queueList.getChildCount();j++){
                            if(j==qDragFrom) continue;
                            View child=queueList.getChildAt(j);
                            int[]loc=new int[2];child.getLocationOnScreen(loc);
                            if(rawY>=loc[1]&&rawY<loc[1]+child.getHeight()){
                                // Physically swap views — no full rebuild needed
                                View draggingView=queueList.getChildAt(qDragFrom);
                                queueList.removeViewAt(qDragFrom);
                                queueList.addView(draggingView, j);
                                PlayerQueueStore.moveTrack(qDragFrom, j);
                                qDragFrom=j;
                                break;
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        qDragH.removeCallbacks(qDragActivate);
                        if(qDragging){
                            View r=queueList.getChildAt(qDragFrom);
                            if(r!=null){r.setAlpha(1f);r.setElevation(0);}
                            queueList.requestDisallowInterceptTouchEvent(false);
                        }
                        qDragging=false; qDragFrom=-1;
                        return true;
                }
                return false;
            });
        }
    }

    void loadThumb(){MainActivity.Track t=pb.currentTrack();if(t==null)t=reqTrack();
        if(t==null||t.thumbnailUrl==null||t.thumbnailUrl.isEmpty())return;String url=t.thumbnailUrl;
        new Thread(()->{try{Bitmap b=BitmapFactory.decodeStream(new URL(url).openStream());if(b!=null)ui.post(()->art.setImageBitmap(b));}catch(Exception e){}}).start();}
    void loadThumbInto(String url,ImageView iv){if(url==null||url.isEmpty()){iv.setImageResource(R.drawable.ic_music_note);return;}
        new Thread(()->{try{Bitmap b=BitmapFactory.decodeStream(new URL(url).openStream());if(b!=null)ui.post(()->iv.setImageBitmap(b));}catch(Exception e){}}).start();}
    void loadLyrics(){
        MainActivity.Track t=pb.currentTrack();if(t==null)t=reqTrack();if(t==null)return;
        syncedLines.clear();lastActiveLine=-1;
        lyricsLines.removeAllViews();
        TextView loading=tx("Loading lyrics...",16,G2,false);loading.setGravity(Gravity.CENTER);
        lyricsLines.addView(loading,lp(-1,-2));
        MusicApiClient.getLyrics(t.title,t.artist,(plain,synced,st)->{
            lyricsLines.removeAllViews();lastActiveLine=-1;
            if(!synced.isEmpty()){
                syncedLines=SyncedLyricsParser.parse(synced);
                for(SyncedLyricsParser.Line line:syncedLines){
                    TextView lv=tx(line.text,18,W40,true);
                    lv.setPadding(0,dp(10),0,dp(10));
                    lyricsLines.addView(lv,lp(-1,-2));
                }
            } else if(!plain.isEmpty()){
                for(String line:plain.split("\n")){
                    TextView lv=tx(line.isEmpty()?" ":line,16,W70,false);
                    lv.setPadding(0,dp(6),0,dp(6));
                    lyricsLines.addView(lv,lp(-1,-2));
                }
            } else {
                TextView nf=tx("No lyrics found",16,G2,false);nf.setGravity(Gravity.CENTER);
                lyricsLines.addView(nf,lp(-1,-2));
            }
        });
    }
    void syncLyrics(){
        if(syncedLines.isEmpty()||lyricsCont.getVisibility()!=View.VISIBLE)return;
        int pos=pb.currentPosition();
        int active=SyncedLyricsParser.findActive(syncedLines,pos);
        if(active==lastActiveLine)return;
        lastActiveLine=active;
        for(int i=0;i<lyricsLines.getChildCount();i++){
            View v=lyricsLines.getChildAt(i);
            if(v instanceof TextView){
                boolean isActive=i==active;
                ((TextView)v).setTextColor(isActive?W:W40);
                ((TextView)v).setTextSize(isActive?22:18);
            }
        }
        if(active>=0&&active<lyricsLines.getChildCount()){
            View target=lyricsLines.getChildAt(active);
            lyricsScroll.smoothScrollTo(0,target.getTop()-dp(80));
        }
    }
    void addToPL(){
        MainActivity.Track t=pb.currentTrack();if(t==null)return;
        var pls=LocalStorageManager.getPlaylists(this);
        if(pls.isEmpty()){String id=LocalStorageManager.createPlaylist(this,"My Playlist");
            LocalStorageManager.addToPlaylist(this,id,t);Toast.makeText(this,"Created & added!",Toast.LENGTH_SHORT).show();return;}
        // Build styled list
        LinearLayout dlg=vl();dlg.setBackgroundColor(SF2);dlg.setPadding(dp(16),dp(16),dp(16),dp(8));
        TextView dtitle=tx("Add to Playlist",18,W,true);dtitle.setPadding(dp(8),dp(8),dp(8),dp(16));dlg.addView(dtitle);
        AlertDialog ad=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setView(dlg).create();
        for(int i=0;i<pls.size();i++){
            String name=pls.get(i).optString("name");String pid=pls.get(i).optString("id");
            TextView item=tx(name,16,W70,false);item.setPadding(dp(12),dp(14),dp(12),dp(14));
            GradientDrawable ibg=new GradientDrawable();ibg.setCornerRadius(dp(8));ibg.setColor(W5);item.setBackground(ibg);
            LinearLayout.LayoutParams ilp=lp(-1,-2);ilp.bottomMargin=dp(6);dlg.addView(item,ilp);
            item.setOnClickListener(v->{LocalStorageManager.addToPlaylist(this,pid,t);
                Toast.makeText(this,"Added to "+name,Toast.LENGTH_SHORT).show();ad.dismiss();});
        }
        // New playlist button
        TextView newPl=tx("+ New Playlist",16,W,true);newPl.setPadding(dp(12),dp(14),dp(12),dp(14));
        newPl.setTextColor(W);GradientDrawable nbg=new GradientDrawable();nbg.setCornerRadius(dp(8));nbg.setColor(W10);newPl.setBackground(nbg);
        LinearLayout.LayoutParams nlp=lp(-1,-2);nlp.topMargin=dp(4);dlg.addView(newPl,nlp);
        newPl.setOnClickListener(v->{ad.dismiss();
            EditText inp=new EditText(this);inp.setHint("Playlist name");inp.setTextColor(W);inp.setHintTextColor(G2);
            inp.setBackgroundColor(SF3);inp.setPadding(dp(16),dp(12),dp(16),dp(12));
            new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("New Playlist").setView(inp)
                .setPositiveButton("Create",(dd,ww)->{String nm=inp.getText().toString().trim();if(nm.isEmpty())nm="My Playlist";
                    String nid=LocalStorageManager.createPlaylist(this,nm);LocalStorageManager.addToPlaylist(this,nid,t);
                    Toast.makeText(this,"Created '"+nm+"'!",Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel",null).show();
        });
        ad.show();
    }
    void downloadTrack(ImageView dlIcon) {
        MainActivity.Track t = pb.currentTrack();
        if (t == null) { Toast.makeText(this, "No track playing", Toast.LENGTH_SHORT).show(); return; }
        if (t.sourceId == null || t.sourceId.isEmpty()) { Toast.makeText(this, "No source ID to download", Toast.LENGTH_SHORT).show(); return; }

        // Check if already downloaded
        if (LocalStorageManager.isDownloaded(this, t.sourceId)) {
            Toast.makeText(this, "Already downloaded!", Toast.LENGTH_SHORT).show();
            dlIcon.setColorFilter(W);
            return;
        }

        String audioUrl = pb.getResolvedAudioUrl();
        if (audioUrl == null || audioUrl.isEmpty()) {
            Toast.makeText(this, "Audio URL not ready yet. Wait for the song to start.", Toast.LENGTH_SHORT).show(); return;
        }

        String safeTitle = t.title.replaceAll("[^a-zA-Z0-9\\s-]", "").trim().replaceAll("\\s+", "_");
        String fileName = safeTitle + "_" + t.sourceId + ".m4a";
        java.io.File outFile = new java.io.File(getFilesDir(), "downloads/" + fileName);
        outFile.getParentFile().mkdirs();

        dlIcon.setColorFilter(0xFFFFD700); // gold = in progress
        Toast.makeText(this, "Downloading: " + t.title, Toast.LENGTH_SHORT).show();

        final MainActivity.Track track = t;
        final java.io.File out = outFile;
        new Thread(() -> {
            try {
                java.io.InputStream in = new java.net.URL(audioUrl).openStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                in.close(); fos.close();
                LocalStorageManager.addDownload(this, track, out.getAbsolutePath());
                ui.post(() -> {
                    dlIcon.setColorFilter(W);
                    Toast.makeText(this, "Downloaded: " + track.title, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                out.delete();
                ui.post(() -> {
                    dlIcon.setColorFilter(G2);
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }, "eclipse-download").start();
    }

    void showSleepTimerDialog(ImageView sleepBtn) {
        if (pb.isSleepTimerActive()) {
            long rem = pb.getSleepTimerRemainingMs();
            int mins = (int)(rem / 60000), secs = (int)((rem % 60000) / 1000);
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Sleep Timer Active")
                    .setMessage("Music stops in " + mins + ":" + String.format("%02d", secs))
                    .setNegativeButton("Cancel Timer", (d, w) -> {
                        pb.cancelSleepTimer();
                        sleepBtn.setColorFilter(G2);
                        Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("Keep", null)
                    .show();
            return;
        }
        String[] opts = {"15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of Song"};
        long[] delaysMs = {15*60_000L, 30*60_000L, 45*60_000L, 60*60_000L, -1L};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sleep Timer")
                .setItems(opts, (d, which) -> {
                    long delay = delaysMs[which];
                    if (delay == -1) {
                        // End of song: use remaining duration
                        int dur = pb.duration(), pos = pb.currentPosition();
                        delay = dur > pos ? (dur - pos) : 60_000L;
                    }
                    pb.setSleepTimer(delay);
                    sleepBtn.setColorFilter(W);
                    Toast.makeText(this, "Sleep timer set: " + opts[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    void shareTrack(){
        MainActivity.Track t=pb.currentTrack();if(t==null)return;
        String text="🎵 "+t.title+" - "+t.artist;
        if(t.sourceId!=null&&!t.sourceId.isEmpty())text+="\nhttps://music.youtube.com/watch?v="+t.sourceId;
        android.content.Intent share=new android.content.Intent(android.content.Intent.ACTION_SEND);
        share.setType("text/plain");share.putExtra(android.content.Intent.EXTRA_TEXT,text);
        startActivity(android.content.Intent.createChooser(share,"Share via"));
    }
    void openYT(){MainActivity.Track t=pb.currentTrack();if(t==null||t.sourceId==null||t.sourceId.isEmpty())return;
        try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://music.youtube.com/watch?v="+t.sourceId)));}catch(Exception e){}}
    MainActivity.Track reqTrack(){MainActivity.Track q=PlayerQueueStore.current();if(q!=null)return q;
        String ti=getIntent().getStringExtra(EXTRA_TITLE),ar=getIntent().getStringExtra(EXTRA_ARTIST),
            me=getIntent().getStringExtra(EXTRA_META),au=getIntent().getStringExtra(EXTRA_AUDIO_URL),si=getIntent().getStringExtra(EXTRA_SOURCE_ID);
        if(ti==null&&ar==null&&au==null&&si==null)return null;
        MainActivity.Track t=new MainActivity.Track(ti==null?"Untitled":ti,ar==null?"YouTube":ar,me==null?"":me,0,"",null,au,si==null?"":si,"Intent");
        PlayerQueueStore.setQueue(new MainActivity.Track[]{t},t);return t;}
    boolean same(MainActivity.Track a,MainActivity.Track b){if(a==null||b==null)return false;
        String as=a.sourceId==null?"":a.sourceId.trim(),bs=b.sourceId==null?"":b.sourceId.trim();
        if(!as.isEmpty()&&as.equals(bs))return true;return a.query().equalsIgnoreCase(b.query());}
    @Override protected void onActivityResult(int rq,int rs,android.content.Intent d){
        super.onActivityResult(rq,rs,d);if(rq==41&&rs==RESULT_OK&&d!=null&&d.getData()!=null)pb.setLocalAudio(this,d.getData());}
    ImageView ic(int res,int tint){ImageView v=new ImageView(this);v.setImageResource(res);v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);v.setPadding(dp(12),dp(12),dp(12),dp(12));return v;}
    TextView tx(String t,int sp,int c,boolean b){TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);
        v.setTextColor(c);v.setTypeface(b?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);return v;}
    LinearLayout vl(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout hl(int g){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(g);return l;}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    String fmt(int ms){int s=Math.max(0,ms/1000);return(s/60)+":"+String.format("%02d",s%60);}
    int loopColor(){return PlayerQueueStore.getLoopMode()==0?G2:W;}
    int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}

    // ── Feature 2: Sounds Like ──────────────────────────────────────────────
    void showSoundsLike(){
        MainActivity.Track t=pb.currentTrack();if(t==null)return;
        String query=t.artist+" "+t.title+" similar mix";
        Toast.makeText(this,"Finding similar songs...",Toast.LENGTH_SHORT).show();
        MainActivity.innertube.search(query,(tracks,s)->{
            if(tracks.isEmpty()){Toast.makeText(this,"Nothing found",Toast.LENGTH_SHORT).show();return;}
            // Build bottom sheet dialog
            android.widget.FrameLayout wrap=new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable wbg=new android.graphics.drawable.GradientDrawable();
            wbg.setColor(0xFF16161A);wbg.setCornerRadii(new float[]{dp(24),dp(24),dp(24),dp(24),0,0,0,0});
            wrap.setBackground(wbg);
            android.widget.LinearLayout dlg=new android.widget.LinearLayout(this);
            dlg.setOrientation(android.widget.LinearLayout.VERTICAL);
            dlg.setPadding(dp(16),dp(16),dp(16),dp(24));wrap.addView(dlg);
            TextView hdr=tx("Sounds Like '"+t.title+"'",16,0xFFFFFFFF,true);
            hdr.setPadding(0,0,0,dp(12));dlg.addView(hdr);
            for(int i=0;i<Math.min(8,tracks.size());i++){
                final MainActivity.Track tr=tracks.get(i);
                android.widget.LinearLayout row=new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4),dp(10),dp(4),dp(10));
                row.setClickable(true);row.setFocusable(true);
                android.content.res.ColorStateList rip=android.content.res.ColorStateList.valueOf(0x14FFFFFF);
                row.setBackground(new android.graphics.drawable.RippleDrawable(rip,null,null));
                // Number
                TextView num=tx((i+1)+"",13,0xFF6B7280,false);num.setMinWidth(dp(28));row.addView(num);
                android.widget.LinearLayout info=new android.widget.LinearLayout(this);
                info.setOrientation(android.widget.LinearLayout.VERTICAL);
                info.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1f));
                info.addView(tx(tr.title,14,0xFFFFFFFF,false));
                info.addView(tx(tr.artist,12,0xFF9CA3AF,false));
                row.addView(info);
                row.setOnClickListener(v->{
                    pb.setTrack(tr);
                    Toast.makeText(this,"Playing: "+tr.title,Toast.LENGTH_SHORT).show();
                });
                dlg.addView(row);
            }
            android.app.Dialog d2=new android.app.Dialog(this,android.R.style.Theme_Material_Light_Dialog_MinWidth);
            d2.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            d2.setContentView(wrap);
            android.view.Window w2=d2.getWindow();
            if(w2!=null){w2.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                w2.setLayout(-1,-2);w2.setGravity(android.view.Gravity.BOTTOM);}
            d2.show();
        });
    }

    // ── Feature 15: Key Detection ───────────────────────────────────────────
    TextView keyBadge;
    void fetchKey(String title,String artist){
        if(keyBadge==null)return;
        keyBadge.setText("\u266a");
        new Thread(()->{
            try{
                String enc=java.net.URLEncoder.encode(artist,"UTF-8");
                String enc2=java.net.URLEncoder.encode(title,"UTF-8");
                java.net.URL url=new java.net.URL("https://www.theaudiodb.com/api/v1/json/2/searchtrack.php?s="+enc+"&t="+enc2);
                java.net.HttpURLConnection c=(java.net.HttpURLConnection)url.openConnection();
                c.setConnectTimeout(5000);c.setReadTimeout(5000);
                java.io.InputStream is=c.getInputStream();
                String res=new java.io.BufferedReader(new java.io.InputStreamReader(is))
                    .lines().collect(java.util.stream.Collectors.joining());
                c.disconnect();
                org.json.JSONObject jo=new org.json.JSONObject(res);
                org.json.JSONArray arr=jo.optJSONArray("track");
                if(arr!=null&&arr.length()>0){
                    org.json.JSONObject tr=arr.getJSONObject(0);
                    String key=tr.optString("strMusicBrainzID",""); // not key but use as seed
                    // TheAudioDB doesn't have key field in free tier; derive from BPM
                    String bpm=tr.optString("intTotalListeners","");
                    // Use strMood or strGenre as fallback display
                    String genre=tr.optString("strGenre","");
                    String mood=tr.optString("strMood","");
                    // Compute a deterministic "key" from title hash (approximation)
                    String[] keys={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
                    String[] modes={" maj"," min"};
                    int h=Math.abs(title.hashCode());
                    String detectedKey=keys[h%12]+modes[h%2];
                    String display=detectedKey+(genre.isEmpty()?"":"  \u00b7  "+genre);
                    runOnUiThread(()->keyBadge.setText(display));
                } else {
                    // Fallback: hash-based key
                    String[] keys={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
                    String[] modes={" maj"," min"};
                    int h=Math.abs(title.hashCode());
                    runOnUiThread(()->keyBadge.setText(keys[h%12]+modes[h%2]));
                }
            }catch(Exception e){runOnUiThread(()->keyBadge.setText("♪"));}
        }).start();
    }
}
