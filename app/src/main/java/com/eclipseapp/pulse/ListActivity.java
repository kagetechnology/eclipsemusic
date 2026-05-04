package com.eclipseapp.pulse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.net.URL;
import java.util.*;

public class ListActivity extends Activity {
    static final int BG=0xFF0A0A0C,SF=0xFF16161A,SF2=0xFF1C2027,SF3=0xFF272A32;
    static final int W=0xFFFFFFFF,W70=0xB3FFFFFF,W40=0x66FFFFFF,W10=0x1AFFFFFF,W5=0x0DFFFFFF;
    static final int G1=0xFFB3B3B3,G2=0xFF6B7280;
    final Handler ui=new Handler(Looper.getMainLooper());
    final Map<String,Bitmap> cache=new HashMap<>();
    List<MainActivity.Track> tracks;
    String title;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        title=getIntent().getStringExtra("title");
        tracks=(List<MainActivity.Track>)getIntent().getSerializableExtra("tracks");
        if(tracks==null)tracks=new ArrayList<>();
        setContentView(buildUI());
    }

    View buildUI(){
        ScrollView sv=new ScrollView(this);sv.setBackgroundColor(BG);sv.setFillViewport(true);
        LinearLayout root=vl();root.setPadding(0,0,0,dp(24));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));

        // Top bar (glass)
        LinearLayout tb=hl(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xCC0A0A0C);tb.setPadding(dp(16),dp(48),dp(16),dp(12));
        root.addView(tb,lp(-1,-2));
        ImageView back=ic(R.drawable.ic_chevron_down,G1);tb.addView(back,lp(dp(40),dp(40)));
        back.setOnClickListener(v->finish());
        TextView hdr=tx(title!=null?title:"Playlist",18,W,true);
        hdr.setPadding(dp(12),0,0,0);
        tb.addView(hdr,new LinearLayout.LayoutParams(0,-2,1f));
        ImageView more=ic(R.drawable.ic_more_vert,G1);tb.addView(more,lp(dp(40),dp(40)));

        // Hero section
        if(!tracks.isEmpty()){
            LinearLayout hero=hl(Gravity.BOTTOM);hero.setPadding(dp(24),dp(8),dp(24),dp(16));
            root.addView(hero,lp(-1,-2));

            // Cover art (first track thumb)
            ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(SF);
            cover.setClipToOutline(true);cover.setOutlineProvider(new ViewOutlineProvider(){
                public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(12));}});
            hero.addView(cover,lp(dp(140),dp(140)));
            loadThumb(tracks.get(0).thumbnailUrl,cover);

            // Info
            LinearLayout info=vl();info.setPadding(dp(16),0,0,0);
            hero.addView(info,new LinearLayout.LayoutParams(0,-2,1f));
            TextView label=tx("PLAYLIST",10,G2,true);label.setLetterSpacing(0.2f);info.addView(label);
            TextView tit=tx(title!=null?title:"Playlist",28,W,true);
            tit.setMaxLines(2);LinearLayout.LayoutParams titLP=lp(-1,-2);titLP.topMargin=dp(4);info.addView(tit,titLP);
            TextView sub=tx(tracks.size()+" songs",14,G1,false);
            LinearLayout.LayoutParams subLP=lp(-1,-2);subLP.topMargin=dp(4);info.addView(sub,subLP);

            // Action buttons row
            LinearLayout actions=hl(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actLP=lp(-1,-2);actLP.topMargin=dp(12);info.addView(actions,actLP);

            // Play all button (white circle)
            FrameLayout playWrap=new FrameLayout(this);
            GradientDrawable cd=new GradientDrawable();cd.setShape(GradientDrawable.OVAL);cd.setColor(W);
            playWrap.setBackground(cd);
            ImageView playIc=new ImageView(this);playIc.setImageResource(R.drawable.ic_play);playIc.setColorFilter(BG);
            playIc.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            playWrap.addView(playIc,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            actions.addView(playWrap,lp(dp(48),dp(48)));
            playWrap.setOnClickListener(v->playAll(0));

            // Shuffle button
            ImageView shufBtn=circleBtn(R.drawable.ic_shuffle);
            LinearLayout.LayoutParams shLP=lp(dp(40),dp(40));shLP.leftMargin=dp(8);actions.addView(shufBtn,shLP);
            shufBtn.setOnClickListener(v->{PlayerQueueStore.toggleShuffle();playAll(0);});
        }

        // Search in playlist
        LinearLayout searchRow=hl(Gravity.CENTER_VERTICAL);
        GradientDrawable srBg=new GradientDrawable();srBg.setCornerRadius(dp(24));srBg.setColor(W5);
        srBg.setStroke(dp(1),W10);searchRow.setBackground(srBg);
        searchRow.setPadding(dp(16),dp(10),dp(16),dp(10));
        LinearLayout.LayoutParams srLP=lp(-1,-2);srLP.leftMargin=dp(24);srLP.rightMargin=dp(24);srLP.topMargin=dp(12);
        root.addView(searchRow,srLP);
        ImageView sIc=new ImageView(this);sIc.setImageResource(R.drawable.ic_search);sIc.setColorFilter(G2);
        searchRow.addView(sIc,lp(dp(20),dp(20)));
        EditText searchInput=new EditText(this);searchInput.setHint("Find in playlist");
        searchInput.setHintTextColor(G2);searchInput.setTextColor(W);searchInput.setTextSize(14);
        searchInput.setBackground(null);searchInput.setSingleLine(true);
        LinearLayout.LayoutParams siLP=new LinearLayout.LayoutParams(0,-2,1f);siLP.leftMargin=dp(8);
        searchRow.addView(searchInput,siLP);

        // Track list header
        LinearLayout listHdr=hl(0);listHdr.setPadding(dp(24),dp(16),dp(24),dp(8));
        root.addView(listHdr,lp(-1,-2));
        listHdr.addView(tx("#",10,G2,true),lp(dp(32),-2));
        listHdr.addView(tx("TITLE",10,G2,true),new LinearLayout.LayoutParams(0,-2,1f));

        // Track items container
        LinearLayout listCont=vl();listCont.setPadding(dp(16),0,dp(16),0);
        root.addView(listCont,lp(-1,-2));

        for(int i=0;i<tracks.size();i++){
            MainActivity.Track t=tracks.get(i);
            int idx=i;boolean isFirst=i==0;
            LinearLayout row=hl(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(8),dp(8),dp(8));
            GradientDrawable rb=new GradientDrawable();rb.setCornerRadius(dp(8));
            if(isFirst){rb.setColor(W10);rb.setStroke(dp(1),W10);}
            row.setBackground(rb);
            LinearLayout.LayoutParams rlp=lp(-1,-2);rlp.bottomMargin=dp(2);listCont.addView(row,rlp);

            // Number / equalizer
            if(isFirst){
                // Animated bars indicator
                LinearLayout bars=hl(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
                bars.setPadding(dp(4),dp(4),dp(4),dp(4));
                for(int b=0;b<3;b++){
                    View bar=new View(this);bar.setBackgroundColor(W);
                    int h=b==0?dp(12):b==1?dp(6):dp(9);
                    LinearLayout.LayoutParams blp=lp(dp(3),h);blp.leftMargin=dp(1);bars.addView(bar,blp);
                }
                row.addView(bars,lp(dp(32),dp(32)));
            } else {
                TextView num=tx(String.valueOf(i+1),12,G2,true);num.setGravity(Gravity.CENTER);
                row.addView(num,lp(dp(32),dp(32)));
            }

            // Thumbnail
            ImageView th=new ImageView(this);th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setBackgroundColor(SF);th.setClipToOutline(true);
            th.setOutlineProvider(new ViewOutlineProvider(){
                public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(4));}});
            LinearLayout.LayoutParams thLP=lp(dp(40),dp(40));thLP.leftMargin=dp(8);
            row.addView(th,thLP);loadThumb(t.thumbnailUrl,th);

            // Title + Artist
            LinearLayout txt=vl();LinearLayout.LayoutParams txLP=new LinearLayout.LayoutParams(0,-2,1f);
            txLP.leftMargin=dp(12);row.addView(txt,txLP);
            TextView rt=tx(t.title,14,isFirst?W:W70,true);rt.setMaxLines(1);
            rt.setEllipsize(TextUtils.TruncateAt.END);txt.addView(rt);
            TextView ra=tx(t.artist,12,G2,false);ra.setMaxLines(1);txt.addView(ra);

            // More icon
            ImageView mi=new ImageView(this);mi.setImageResource(R.drawable.ic_more_vert);
            mi.setColorFilter(G2);mi.setPadding(dp(8),dp(8),dp(8),dp(8));
            row.addView(mi,lp(dp(32),dp(32)));

            row.setOnClickListener(v->playAll(idx));
        }

        // Filter tracks on search
        searchInput.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                String q=s.toString().toLowerCase().trim();
                for(int i=0;i<listCont.getChildCount();i++){
                    if(i<tracks.size()){
                        MainActivity.Track t=tracks.get(i);
                        boolean match=q.isEmpty()||t.title.toLowerCase().contains(q)||t.artist.toLowerCase().contains(q);
                        listCont.getChildAt(i).setVisibility(match?View.VISIBLE:View.GONE);
                    }
                }
            }
            public void afterTextChanged(Editable s){}
        });

        return sv;
    }

    void playAll(int startIdx){
        if(tracks.isEmpty())return;
        MainActivity.Track[] arr=tracks.toArray(new MainActivity.Track[0]);
        PlayerQueueStore.setQueue(arr,arr[Math.min(startIdx,arr.length-1)]);
        PlaybackManager.get().switchTrack(this,arr[Math.min(startIdx,arr.length-1)],true);
        Intent intent=new Intent(this,NativePlayerActivity.class);
        startActivity(intent);
    }

    ImageView circleBtn(int res){
        ImageView v=new ImageView(this);v.setImageResource(res);v.setColorFilter(G1);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);v.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1),W10);v.setBackground(bg);return v;
    }

    void loadThumb(String url,ImageView iv){
        if(url==null||url.isEmpty()){iv.setImageResource(R.drawable.ic_music_note);return;}
        Bitmap c=cache.get(url);if(c!=null){iv.setImageBitmap(c);return;}
        iv.setImageResource(R.drawable.ic_music_note);
        new Thread(()->{try{Bitmap bmp=BitmapFactory.decodeStream(new URL(url).openStream());
            if(bmp!=null){cache.put(url,bmp);ui.post(()->iv.setImageBitmap(bmp));}
        }catch(Exception e){}}).start();
    }

    ImageView ic(int res,int tint){ImageView v=new ImageView(this);v.setImageResource(res);v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);v.setPadding(dp(12),dp(12),dp(12),dp(12));return v;}
    TextView tx(String t,int sp,int c,boolean b){TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);
        v.setTextColor(c);v.setTypeface(b?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);return v;}
    LinearLayout vl(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout hl(int g){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(g);return l;}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
