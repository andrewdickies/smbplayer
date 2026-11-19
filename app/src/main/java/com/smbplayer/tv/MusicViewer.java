package com.smbplayer.tv;

import android.content.*;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.videolan.libvlc.*;

final class MusicViewer implements AutoCloseable {
    private final MainActivity host;
    private final List<MainActivity.Entry> songs;
    private final List<MainActivity.Entry> sidecars;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FrameLayout root;
    private final LinearLayout body, info, top, controls;
    private final FrameLayout rightPane;
    private final LinearLayout queuePanel;
    private final Runnable fitPane;
    private android.animation.ValueAnimator paneMotion;
    private boolean paneControls;
    private boolean paneReady;
    private boolean paneMoving;
    private final TextView title, artist, time, bufferingRate;
    private final ImageView cover, artworkBackground;
    private final android.animation.ValueAnimator coverRotation;
    private float coverAngle;
    private final ListView lyricsView, queueView;
    private final SeekBar progress;
    private final Button play, stop, follow, modes, queueButton;
    private boolean queueOpen, controlsShown=true, seekPreview;
    private boolean controlsBeforeQueue;
    private long previewTarget=-1, previewOrigin=-1, previewDeadline;
    private final Runnable autoHide=()->{if(canAutoHide())hideControls();};
    private final Set<Integer> heldKeys=new HashSet<>();
    private boolean touchActive;
    private final Runnable hideSeekPreview=this::hideSeekPreview;
    private long exitAt;
    private long cacheDuration, lastCacheRefresh;
    private List<long[]> cacheRanges = Collections.emptyList();
    private boolean coverAvailable, coverPlaying;
    private final DownloadRate downloadRate=new DownloadRate();
    private long bufferingSince;
    private final Map<String,String> songTitles=new HashMap<>(),songArtists=new HashMap<>();
    private List<Lyrics.Line> lyrics = Collections.emptyList();
    private BaseAdapter adapter;
    private MusicPlaybackService playback;
    private MusicPlaybackService.Source source;
    private boolean stopped, privatePlayback;
    private long lastDuration;
    private boolean bound;
    private final MusicPlaybackService.Listener playbackListener=new MusicPlaybackService.Listener(){
        @Override public void onTrack(){
            if(closed)return;
            if(!playback.hasQueue()){showStopped();return;}
            source=playback.source();privatePlayback=playback.privatePlayback();
            songs.clear();songs.addAll(playback.songs());
            sidecars.clear();sidecars.addAll(playback.sidecars());
            index=playback.index();mode=playback.mode();
            loadTrack();
        }
        @Override public void onStopped(){showStopped();}
        @Override public void onEvent(MediaPlayer.Event event){
            if(closed)return;
            player=playback.player();server=playback.server();paused=playback.paused();ended=playback.ended();
            host.updateIcon(play,paused?"play":"pause",paused?"播放":"暂停");
            root.setKeepScreenOn(!paused);
            if(event.type==MediaPlayer.Event.EncounteredError)artist.setText("无法播放");
            if(event.type==MediaPlayer.Event.Playing){
                clearBufferingRate();updateCoverPlayback(!paused);scheduleHide();
            }else if(event.type==MediaPlayer.Event.Paused){
                clearBufferingRate();updateCoverPlayback(false);cancelAutoHide();
            }else if(event.type==MediaPlayer.Event.Buffering){
                boolean waiting=!paused&&event.getBuffering()<100f;
                if(waiting&&bufferingSince==0)bufferingSince=SystemClock.elapsedRealtime();
                if(!waiting)clearBufferingRate();
                updateCoverPlayback(!paused&&!waiting);
            }
        }
    };
    private final ServiceConnection playbackConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(closed||stopped)return;
            playback=((MusicPlaybackService.LocalBinder)binder).service();
            playback.listen(playbackListener);
            if(Objects.equals(playback.url(),songs.get(index).url)&&playback.hasQueue()){
                source=playback.source();privatePlayback=playback.privatePlayback();
            }else playback.mode(mode);
            playback.playQueue(songs,sidecars,index,source,privatePlayback);
        }
        @Override public void onServiceDisconnected(ComponentName name){
            playback=null;player=null;server=null;setPaused(true);
        }
    };
    private MediaPlayer player;
    private SmbPlaybackServer server;
    private int index, generation, activeLine=-1, lyricCursor=-1, mode;
    private long lyricTime=-1, lyricSeekTarget=-1, lyricSeekStarted;
    private boolean dragging, automatic=true, paused, ended, closed;
    private Future<?> job;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if(closed)return;
            if(player!=null) {
                long reported=player.getTime(), position=Math.max(0,reported), length=player.getLength();
                if(length>0)lastDuration=length;
                long shown=seekPreview&&previewTarget>=0?previewTarget:position;
                if(!dragging){int value=length>0?(int)(shown*1000/length):0;if(progress.getProgress()!=value)progress.setProgress(value);}
                progress.setEnabled(player.isSeekable()&&length>0);
                refreshCache(position, length);
                String label=labelTime(shown)+" / "+labelTime(length);
                if(seekPreview)label+="   "+String.format(Locale.ROOT,"%+d s",(shown-previewOrigin)/1000);
                if(!label.contentEquals(time.getText()))time.setText(label);
                updateLyricClock(reported);
            }
            ui.postDelayed(this,180);
        }
    };

    MusicViewer(MainActivity host,List<MainActivity.Entry> songs,List<MainActivity.Entry> sidecars,int index) {
        this.host=host;this.songs=new ArrayList<>(songs);this.sidecars=new ArrayList<>(sidecars);this.index=index;
        source=MusicPlaybackService.Source.from(host.musicContext());privatePlayback=host.isPrivatePlayback();
        FrameLayout screen=new FrameLayout(host);
        screen.setBackgroundColor(0xff121212);
        artworkBackground=new ImageView(host);
        artworkBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkBackground.setColorFilter(0xb8000000);
        artworkBackground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        screen.addView(artworkBackground,new FrameLayout.LayoutParams(-1,-1));
        root=new FrameLayout(host){
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    touchActive=true;
                    cancelAutoHide();
                }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                    touchActive=false;
                    scheduleHide();
                }
                return super.dispatchTouchEvent(event);
            }
        };
        root.setPadding(host.dp(24),host.dp(12),host.dp(24),host.dp(18));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root.setOnClickListener(v->toggleControls());
        root.setFocusable(false);
        body=new LinearLayout(host);body.setGravity(Gravity.CENTER);
        root.addView(body,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout chrome=new LinearLayout(host);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setFocusable(false);
        chrome.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top=new LinearLayout(host);
        top.setClickable(true);top.setFocusable(false);
        top.addView(host.iconButton("back","返回目录",host::closeMediaViewer));
        chrome.addView(top);
        chrome.addView(new Space(host),new LinearLayout.LayoutParams(1,0,1));
        LinearLayout bottomControls=new LinearLayout(host);
        bottomControls.setOrientation(LinearLayout.VERTICAL);
        chrome.addView(bottomControls);
        info=new LinearLayout(host);info.setOrientation(LinearLayout.VERTICAL);info.setGravity(Gravity.CENTER);
        body.addView(info,new LinearLayout.LayoutParams(0,-1,1));
        cover=new ImageView(host){
            @Override protected void onDraw(Canvas canvas){
                int save=canvas.save();
                canvas.rotate(coverAngle,getWidth()/2f,getHeight()/2f);
                super.onDraw(canvas);
                canvas.restoreToCount(save);
            }
        };
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setContentDescription("专辑封面");
        android.graphics.drawable.GradientDrawable disc=new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(0xa6283032);
        cover.setBackground(disc);
        cover.setClipToOutline(true);
        coverRotation=android.animation.ValueAnimator.ofFloat(0f,360f);
        coverRotation.addUpdateListener(animation->{
            coverAngle=(float)animation.getAnimatedValue();
            cover.invalidate();
        });
        coverRotation.setInterpolator(new android.view.animation.LinearInterpolator());
        coverRotation.setDuration(42000L);
        coverRotation.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        int coverSize=Math.min(host.dp(320),host.getResources().getDisplayMetrics().heightPixels*36/100);
        LinearLayout.LayoutParams coverSpace=new LinearLayout.LayoutParams(coverSize,coverSize);
        coverSpace.bottomMargin=host.dp(8);
        info.addView(cover,coverSpace);
        info.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            int size=Math.min(coverSize,Math.min((right-left)*3/4,(bottom-top)*11/20));
            if(size>0&&size!=coverSpace.width){
                coverSpace.width=coverSpace.height=size;
                cover.setLayoutParams(coverSpace);
            }
        });
        title=host.text("",25);title.setGravity(Gravity.CENTER);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(title);
        artist=host.text("",21);artist.setTextColor(MainActivity.SECONDARY);artist.setGravity(Gravity.CENTER);artist.setSingleLine(true);info.addView(artist);
        GestureDetector swipe=new GestureDetector(host,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){toggleControls();return true;}
            @Override public boolean onFling(MotionEvent start,MotionEvent end,float vx,float vy){
                if(start!=null && Math.abs(end.getX()-start.getX())>host.dp(55) && Math.abs(vx)>Math.abs(vy)) {
                    change(vx<0?1:-1,false);return true;
                }
                return false;
            }
        });
        cover.setOnTouchListener((v,e)->swipe.onTouchEvent(e));
        info.setOnClickListener(v->toggleControls());
        title.setOnClickListener(v->toggleControls());
        artist.setOnClickListener(v->toggleControls());
        info.setFocusable(false);title.setFocusable(false);artist.setFocusable(false);
        rightPane=new FrameLayout(host);
        body.addView(rightPane,new LinearLayout.LayoutParams(0,-1,1.35f));
        lyricsView=new ListView(host){
            @Override public boolean onTouchEvent(MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN)smoothScrollBy(0,0);
                return super.onTouchEvent(event);
            }
        };
        lyricsView.setDivider(null);
        lyricsView.setFocusable(false);
        lyricsView.setClipToPadding(false);
        lyricsView.setVerticalScrollBarEnabled(false);
        lyricsView.setVerticalFadingEdgeEnabled(true);
        lyricsView.setFadingEdgeLength(host.dp(22));
        lyricsView.setOnScrollListener(new AbsListView.OnScrollListener(){
            @Override public void onScrollStateChanged(AbsListView view,int state){
                if(state==SCROLL_STATE_TOUCH_SCROLL)setFollowing(false);
            }
            @Override public void onScroll(AbsListView view,int first,int count,int total){}
        });
        lyricsView.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{
            if(b-t==ob-ot)return;
            int space=Math.max(0,(b-t)/2-host.dp(24));
            lyricsView.setPadding(0,space,0,space);
            if(!paneMoving&&automatic&&activeLine>=0)lyricsView.post(this::scrollLyrics);
        });
        FrameLayout.LayoutParams lyricSpace=new FrameLayout.LayoutParams(-1,-1);
        rightPane.addView(lyricsView,lyricSpace);
        lyricsView.setVisibility(View.GONE);
        queuePanel=new LinearLayout(host);
        queuePanel.setOrientation(LinearLayout.VERTICAL);
        queuePanel.setPadding(host.dp(14),host.dp(12),host.dp(14),host.dp(10));
        android.graphics.drawable.GradientDrawable panelStyle=new android.graphics.drawable.GradientDrawable();
        panelStyle.setColor(0xe6182125);
        panelStyle.setCornerRadius(host.dp(16));
        queuePanel.setBackground(panelStyle);
        TextView queueTitle=host.text("播放队列",24);
        queueTitle.setPadding(host.dp(12),0,host.dp(12),host.dp(8));
        queuePanel.addView(queueTitle);
        queueView=new ListView(host);
        queueView.setFocusableInTouchMode(true);
        queueView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        queueView.setContentDescription("歌曲队列");
        host.styleListFocus(queueView,0x2268c9dd);
        queuePanel.addView(queueView,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout.LayoutParams queueSpace=new FrameLayout.LayoutParams(-1,-1);
        queueSpace.setMargins(host.dp(14),0,host.dp(12),0);
        rightPane.addView(queuePanel,queueSpace);
        queuePanel.setVisibility(View.GONE);
        fitPane=()->{
            int height=rightPane.getHeight();
            if(height<=0)return;
            int reserved=bottomControls.getHeight()+host.dp(8);
            int topInset=Math.min(host.dp(56),height/10);
            int bottomInset=controlsShown&&!queueOpen?Math.max(topInset,reserved):topInset;
            int balance=bottomInset-topInset;
            boolean visibleControls=controlsShown&&!queueOpen;
            if(!paneReady||paneControls!=visibleControls){
                if(paneMotion!=null){paneMotion.removeAllListeners();paneMotion.cancel();}
                paneControls=visibleControls;
                lyricSpace.topMargin=topInset;
                int from=lyricSpace.bottomMargin;
                if(!paneReady){
                    lyricSpace.bottomMargin=bottomInset;
                    lyricsView.setLayoutParams(lyricSpace);
                    info.setPadding(0,0,0,balance);
                    paneReady=true;
                }else{
                    paneMoving=true;
                    paneMotion=android.animation.ValueAnimator.ofInt(from,bottomInset);
                    paneMotion.setDuration(220);
                    paneMotion.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                    paneMotion.addUpdateListener(animation->{
                        lyricSpace.bottomMargin=(int)animation.getAnimatedValue();
                        lyricsView.setLayoutParams(lyricSpace);
                        info.setPadding(0,0,0,Math.max(0,lyricSpace.bottomMargin-topInset));
                    });
                    paneMotion.addListener(new android.animation.AnimatorListenerAdapter(){
                        @Override public void onAnimationEnd(android.animation.Animator animation){
                            paneMoving=false;
                            if(closed)return;
                            lyricsView.post(()->{
                                if(automatic)scrollLyrics();
                                else if(lyricCursor>=0)selectLyric(lyricCursor);
                            });
                        }
                    });
                    paneMotion.start();
                }
            }
            int queueInset=Math.min(host.dp(48),height/9);
            int queueStart=Math.max(host.dp(14),rightPane.getWidth()/7);
            if(queueSpace.topMargin!=queueInset||queueSpace.bottomMargin!=queueInset||queueSpace.leftMargin!=queueStart){
                queueSpace.topMargin=queueSpace.bottomMargin=queueInset;
                queueSpace.leftMargin=queueStart;
                queuePanel.setLayoutParams(queueSpace);
            }
        };
        rightPane.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitPane.run());
        bottomControls.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitPane.run());
        adapter=new BaseAdapter(){
            @Override public int getCount(){return lyrics.size();}
            @Override public Object getItem(int i){return lyrics.get(i);}
            @Override public long getItemId(int i){return i;}
            @Override public View getView(int pos,View convert,ViewGroup parent){
                TextView line=convert instanceof TextView?(TextView)convert:host.text("",23);
                line.setPadding(host.dp(16),host.dp(10),host.dp(16),host.dp(10));
                line.setGravity(Gravity.CENTER);
                line.setText(lyrics.get(pos).text);
                styleLyric(line,pos);
                return line;
            }
        };
        lyricsView.setAdapter(adapter);
        lyricsView.setSelector(android.R.color.transparent);
        lyricsView.setOnItemClickListener((parent,v,position,id)->seekLyric(position));
        time=host.text("",20);time.setSingleLine(true);time.setClickable(true);time.setFocusable(false);bottomControls.addView(time);
        progress=new SeekBar(host) {
            private final Paint cachePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected synchronized void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if(cacheDuration<=0||cacheRanges.isEmpty())return;
                float left=getPaddingLeft(), width=getWidth()-left-getPaddingRight();
                float cursor=left+width*getProgress()/getMax();
                float thumbRadius=getThumb()==null?0:getThumb().getBounds().width()/2f;
                float center=(getHeight()+getPaddingTop()-getPaddingBottom())/2f;
                cachePaint.setColor(0xffaaaaaa);
                for(long[] range:cacheRanges){
                    float start=Math.max(cursor+thumbRadius,left+width*range[0]/cacheDuration);
                    float end=Math.min(left+width,left+width*range[1]/cacheDuration);
                    if(end>start)canvas.drawRect(start,center-host.dp(3),end,center+host.dp(3),cachePaint);
                }
            }
        };
        progress.setProgressDrawable(host.tvProgressTrack());
        progress.setId(View.generateViewId());
        progress.setMax(1000);
        progress.setKeyProgressIncrement(20);
        progress.setFocusableInTouchMode(true);
        progress.setClickable(true);
        progress.setPadding(host.dp(16),host.dp(6),host.dp(16),host.dp(6));
        android.graphics.drawable.StateListDrawable focus=new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
        border.setCornerRadius(host.dp(8));
        border.setColor(0xff203136);
        border.setStroke(host.dp(3),0xff69e1f3);
        focus.addState(new int[]{android.R.attr.state_focused},border);
        focus.addState(new int[]{},new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        progress.setBackground(focus);
        progress.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff69e1f3));
        progress.setContentDescription("音乐播放进度");
        bottomControls.addView(progress,new LinearLayout.LayoutParams(-1,host.dp(48)));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;cancelAutoHide();}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;seekProgress(bar.getProgress());scheduleHide();}
            public void onProgressChanged(SeekBar bar,int position,boolean user){if(user&&!dragging)seekProgress(position);}
        });
        controls=new LinearLayout(host);controls.setGravity(Gravity.CENTER);
        controls.setClickable(true);controls.setFocusable(false);bottomControls.addView(controls);
        Button previous=host.iconButton("previous","上一首",()->change(-1,false));
        controls.addView(previous);
        play=host.iconButton("pause","暂停",this::toggle);controls.addView(play);
        stop=host.iconButton("stop","停止",this::stopPlayback);controls.addView(stop);
        Button next=host.iconButton("next","下一首",()->change(1,false));
        controls.addView(next);
        modes=host.iconButton("sequence","顺序播放",this::cycleMode);controls.addView(modes);
        follow=host.iconButton("target","回到当前句",()->{setFollowing(true);play.requestFocusFromTouch();});controls.addView(follow);
        follow.setVisibility(View.GONE);
        queueButton=host.iconButton("list","播放队列",this::toggleQueue);
        controls.addView(queueButton);
        for(Button button:new Button[]{previous,play,stop,next,modes,follow,queueButton}){
            button.setNextFocusUpId(progress.getId());
            LinearLayout.LayoutParams size=(LinearLayout.LayoutParams)button.getLayoutParams();
            size.setMargins(host.dp(4),0,host.dp(4),0);
        }
        progress.setNextFocusDownId(play.getId());
        lyricsView.setId(View.generateViewId());
        queueView.setId(View.generateViewId());
        queueView.setNextFocusDownId(queueButton.getId());
        queueButton.setOnKeyListener((view,key,event)->{
            if(key!=KeyEvent.KEYCODE_DPAD_UP||!queueOpen)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN)queueView.requestFocusFromTouch();
            return true;
        });
        queueView.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return songs.size();}
            @Override public Object getItem(int position){return songs.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                TextView row=recycled instanceof TextView?(TextView)recycled:host.text("",22);
                MainActivity.Entry song=songs.get(position);
                String name=songTitles.getOrDefault(song.url,song.name);
                String artistName=songArtists.get(song.url);
                row.setText((position==MusicViewer.this.index?"▶  ":"")+name+(artistName==null?"":" · "+artistName));
                row.setTextColor(position==MusicViewer.this.index?0xff69e1f3:Color.WHITE);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(host.dp(12),host.dp(13),host.dp(12),host.dp(13));
                return row;
            }
        });
        queueView.setOnItemClickListener((list,view,position,id)->{
            closeQueue();
            if(position!=MusicViewer.this.index){MusicViewer.this.index=position;start();}
        });
        root.addView(chrome,new FrameLayout.LayoutParams(-1,-1));
        bufferingRate=host.text("",22);
        bufferingRate.setShadowLayer(host.dp(4),0,host.dp(1),Color.BLACK);
        bufferingRate.setVisibility(View.GONE);
        FrameLayout.LayoutParams rateSpot=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        rateSpot.bottomMargin=host.dp(125);
        root.addView(bufferingRate,rateSpot);
        screen.addView(root,new FrameLayout.LayoutParams(-1,-1));
        host.setContentView(screen);
        play.requestFocusFromTouch();
        bindPlayback();
        ui.post(tick);
    }

    private void bindPlayback(){
        if(closed||bound)return;
        stopped=false;
        stop.setEnabled(true);
        Intent service=new Intent(host,MusicPlaybackService.class);
        if(Build.VERSION.SDK_INT>=26)host.startForegroundService(service);else host.startService(service);
        bound=host.bindService(service,playbackConnection,android.content.Context.BIND_AUTO_CREATE);
    }

    private void stopPlayback(){
        MusicPlaybackService running=playback!=null?playback:MusicPlaybackService.current;
        if(running!=null)running.stopPlayback();
        else host.stopService(new Intent(host,MusicPlaybackService.class));
        if(!stopped)showStopped();
    }

    private void showStopped(){
        if(closed||stopped)return;
        stopped=true;paused=true;ended=false;generation++;
        if(job!=null)job.cancel(true);
        ui.removeCallbacks(tick);
        cancelAutoHide();endPreview();
        player=null;server=null;dragging=false;
        root.setKeepScreenOn(false);
        updateCoverPlayback(false);clearBufferingRate();
        cacheDuration=0;cacheRanges=Collections.emptyList();progress.invalidate();
        progress.setProgress(0);progress.setEnabled(false);
        time.setText("00:00 / "+labelTime(lastDuration));
        lyricSeekTarget=-1;lyricTime=0;updateLyrics(0);
        host.updateIcon(play,"play","播放");
        stop.setEnabled(false);
        queueOpen=false;setFollowing(true);showControls(true);
        if(playback!=null)playback.detach(playbackListener);
        playback=null;
        if(bound){host.unbindService(playbackConnection);bound=false;}
    }

    private void refreshCache(long position,long duration) {
        if(server==null)return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastCacheRefresh<750)return;
        lastCacheRefresh=now;
        double rate=downloadRate.sample(server.streamedRemoteBytes(),now);
        if(bufferingSince!=0&&!paused&&now-bufferingSince>=900){
            bufferingRate.setText(DownloadRate.format(rate));
            bufferingRate.setVisibility(View.VISIBLE);
        }
        server.updatePlayback(position,duration);
        cacheDuration=duration;
        cacheRanges=server.bufferedRanges(duration);
        if(cacheRanges.isEmpty()){
            cacheRanges=server.estimatedAudioBuffer(duration);
        }
        progress.invalidate();
    }

    private void cycleMode(){
        mode=(mode+1)%4;
        if(playback!=null)playback.mode(mode);
        String[] labels={"顺序播放","单曲循环","列表循环","随机播放"};
        host.updateIcon(modes,new String[]{"sequence","repeatOne","repeat","shuffle"}[mode],labels[mode]);
        scheduleHide();
    }
    private void toggleControls(){
        if(queueOpen){closeQueue();return;}
        if(controlsShown)hideControls();else showControls(true);
    }

    private void showControls(boolean focus){
        endPreview();
        controlsShown=true;
        syncLayout();
        if(focus)play.requestFocusFromTouch();
        scheduleHide();
    }

    private void hideControls(){
        cancelAutoHide();
        controlsShown=false;
        setFollowing(true);
        syncLayout();
    }

    private void cancelAutoHide(){ui.removeCallbacks(autoHide);}

    private boolean canAutoHide(){
        return controlsShown&&!queueOpen&&!paused&&!dragging&&!touchActive&&heldKeys.isEmpty()
                &&automatic&&player!=null&&player.isPlaying();
    }

    private void scheduleHide(){
        cancelAutoHide();
        if(canAutoHide())ui.postDelayed(autoHide,4000);
    }

    private void syncLayout(){
        top.setVisibility(controlsShown&&!queueOpen?View.VISIBLE:View.GONE);
        controls.setVisibility(controlsShown&&!queueOpen?View.VISIBLE:View.INVISIBLE);
        boolean progressVisible=!queueOpen&&(controlsShown||seekPreview);
        time.setVisibility(progressVisible?View.VISIBLE:View.INVISIBLE);
        progress.setVisibility(progressVisible?View.VISIBLE:View.INVISIBLE);
        progress.setFocusable(controlsShown&&!queueOpen);
        lyricsView.setVisibility(!queueOpen&&!lyrics.isEmpty()?View.VISIBLE:View.GONE);
        queuePanel.setVisibility(queueOpen?View.VISIBLE:View.GONE);
        rightPane.setVisibility(queueOpen||!lyrics.isEmpty()?View.VISIBLE:View.GONE);
        if(!queueOpen&&lyrics.isEmpty()&&info.getPaddingBottom()!=0)info.setPadding(0,0,0,0);
        rightPane.post(fitPane);
    }

    private void toggleQueue(){
        if(queueOpen){closeQueue();return;}
        exitAt=0;
        endPreview();
        controlsBeforeQueue=controlsShown;
        queueOpen=true;
        setFollowing(true);
        cancelAutoHide();
        syncLayout();
        ((BaseAdapter)queueView.getAdapter()).notifyDataSetChanged();
        queueView.setItemChecked(index,true);
        queueView.requestFocusFromTouch();
        queueView.setSelection(index);
    }

    private void closeQueue(){
        queueOpen=false;
        controlsShown=controlsBeforeQueue;
        syncLayout();
        if(controlsShown)queueButton.requestFocusFromTouch();
        if(automatic)scrollLyrics();
        scheduleHide();
    }

    void back(){
        if(queueOpen){closeQueue();return;}
        if(lyricCursor>=0){setFollowing(true);play.requestFocusFromTouch();return;}
        if(controlsShown){hideControls();exitAt=0;return;}
        if(seekPreview){endPreview();syncLayout();exitAt=0;return;}
        long now=SystemClock.elapsedRealtime();
        if(exitAt>0&&now-exitAt<=2400){host.closeMediaViewer();return;}
        exitAt=now;
        Toast.makeText(host,"再次按返回退出音乐播放",Toast.LENGTH_SHORT).show();
    }

    private void seekFromHidden(int direction,int repeats){
        if(player==null||!player.isSeekable()||player.getLength()<=0)return;
        long current=Math.max(0,player.getTime()),length=player.getLength();
        if(!seekPreview){previewOrigin=current;previewTarget=current;}
        long step=TvSeekPolicy.stepMillis(host.seekStep(),repeats);
        previewTarget=Math.max(0,Math.min(length-1,previewTarget+direction*step));
        seekPreview=true;
        previewDeadline=SystemClock.uptimeMillis()+2000;
        syncLayout();
        seek(previewTarget);
        ui.removeCallbacks(hideSeekPreview);
        ui.postAtTime(hideSeekPreview,previewDeadline);
    }

    private void hideSeekPreview(){
        if(!seekPreview)return;
        long now=SystemClock.uptimeMillis();
        if(now<previewDeadline){ui.postAtTime(hideSeekPreview,previewDeadline);return;}
        endPreview();syncLayout();
    }

    private void endPreview(){
        seekPreview=false;previewTarget=-1;previewOrigin=-1;
        ui.removeCallbacks(hideSeekPreview);
    }

    boolean key(KeyEvent event){
        int code=event.getKeyCode();
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        boolean confirm=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_BUTTON_A;
        if(confirm||code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_RIGHT){
            if(down)heldKeys.add(code);
            else if(event.getAction()==KeyEvent.ACTION_UP)heldKeys.remove(code);
            scheduleHide();
        }
        if(down&&code!=KeyEvent.KEYCODE_BACK)exitAt=0;
        boolean media=code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS
                ||code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PAUSE;
        if(media){
            if(down&&event.getRepeatCount()==0){
                if(code==KeyEvent.KEYCODE_MEDIA_PREVIOUS)change(-1,false);
                else if(code==KeyEvent.KEYCODE_MEDIA_NEXT)change(1,false);
                else if(code==KeyEvent.KEYCODE_MEDIA_PLAY)setPaused(false);
                else if(code==KeyEvent.KEYCODE_MEDIA_PAUSE)setPaused(true);
                else toggle();
            }
            return true;
        }
        if(queueOpen)return false;
        if(lyricCursor>=0){
            if(confirm){
                if(down&&event.getRepeatCount()==0)seekLyric(lyricCursor);
                return true;
            }
            if(code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_RIGHT){
                if(down&&(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN))
                    selectLyric(lyricCursor+(code==KeyEvent.KEYCODE_DPAD_UP?-1:1));
                return true;
            }
        }
        if(!controlsShown){
            if(code==KeyEvent.KEYCODE_DPAD_UP){if(down&&event.getRepeatCount()==0){showControls(true);}return true;}
            if(code==KeyEvent.KEYCODE_DPAD_DOWN){if(down&&event.getRepeatCount()==0)toggleQueue();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(down&&event.getRepeatCount()%4==0)seekFromHidden(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,event.getRepeatCount());
                return true;
            }
            if(confirm){if(down&&event.getRepeatCount()==0)toggle();return true;}
            return false;
        }
        if(progress.hasFocus()&&code==KeyEvent.KEYCODE_DPAD_UP&&!lyrics.isEmpty()){
            if(down){
                lyricsView.smoothScrollBy(0,0);
                setFollowing(false);
                lyricsView.setFocusable(true);
                lyricsView.setFocusableInTouchMode(true);
                lyricsView.requestFocusFromTouch();
                selectLyric(Math.max(0,activeLine));
            }
            return true;
        }
        return false;
    }

    String currentUrl(){return songs.get(index).url;}
    void pause(){heldKeys.clear();touchActive=false;setPaused(true);}
    private void toggle(){setPaused(!paused);}
    private void setPaused(boolean value){
        if(stopped){if(!value)start();return;}
        paused=value;
        root.setKeepScreenOn(!paused);
        if(paused){updateCoverPlayback(false);clearBufferingRate();}
        host.updateIcon(play,paused?"play":"pause",paused?"播放":"暂停");
        if(playback!=null)playback.setPaused(value);
        if(paused)cancelAutoHide();else scheduleHide();
    }
    private void change(int delta,boolean ended){
        if(playback!=null)playback.change(delta,ended);
        else if(stopped){index=Math.floorMod(index+delta,songs.size());start();}
    }
    private void start(){
        if(playback!=null)playback.playAt(index);
        else bindPlayback();
    }

    private void loadTrack(){
        stopped=false;stop.setEnabled(true);
        lastDuration=0;
        ui.removeCallbacks(tick);ui.post(tick);
        cancelAutoHide();endPreview();
        ended=false;
        int token=++generation;
        if(job!=null)job.cancel(true);
        player=playback.player();server=playback.server();
        downloadRate.reset();
        lastCacheRefresh=0;cacheDuration=0;
        cacheRanges=Collections.emptyList();
        progress.setProgress(0);
        progress.invalidate();
        MainActivity.Entry song=songs.get(index);
        title.setText(song.name);artist.setText("");
        coverAvailable=false;coverPlaying=false;
        coverRotation.cancel();coverAngle=0;
        int inset=host.dp(28);
        cover.setPadding(inset,inset,inset,inset);
        cover.setImageDrawable(new ActionIcon("music"));
        artworkBackground.setImageDrawable(null);
        time.setText("00:00 / —");progress.setEnabled(false);
        lyrics=Collections.emptyList();activeLine=-1;lyricTime=-1;lyricSeekTarget=-1;
        adapter.notifyDataSetChanged();lyricsView.setVisibility(View.GONE);setFollowing(true);
        paused=playback.paused();root.setKeepScreenOn(!paused);host.updateIcon(play,paused?"play":"pause",paused?"播放":"暂停");
        syncLayout();
        SmbPlaybackServer bridge=server;
        List<MainActivity.Entry> lyricFiles=new ArrayList<>(sidecars);
        job=worker.submit(()->{
            List<Lyrics.Line> lines=readLyrics(song,lyricFiles);
            ui.post(()->{
                if(closed||token!=generation)return;
                lyrics=lines;adapter.notifyDataSetChanged();syncLayout();
            });
            readMetadata(song,bridge,token);
        });
        updateCoverPlayback(!paused);
        scheduleHide();
    }

    private List<Lyrics.Line> readLyrics(MainActivity.Entry song,List<MainActivity.Entry> files){
        String stem=song.name.substring(0,song.name.lastIndexOf('.'));
        for(MainActivity.Entry entry:files)if(entry.name.equalsIgnoreCase(stem+".lrc")){
            try(InputStream in=playback.openAuxiliary(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];int n;
                while((n=in.read(bytes))!=-1){if(out.size()+n>1024*1024)return Collections.emptyList();out.write(bytes,0,n);}
                return Lyrics.parse(out.toByteArray());
            }catch(IOException|RuntimeException ignored){return Collections.emptyList();}
        }
        return Collections.emptyList();
    }

    private void readMetadata(MainActivity.Entry song,SmbPlaybackServer bridge,int token){
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            if(bridge==null)retriever.setDataSource(host,Uri.parse(song.url));
            else retriever.setDataSource(bridge.url(),Collections.emptyMap());
            String name=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String author=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] data=retriever.getEmbeddedPicture();
            Bitmap art=null;
            if(data!=null&&data.length<=8*1024*1024){
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(data,0,data.length,options);
                options.inJustDecodeBounds=false;options.inSampleSize=1;
                while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>800)options.inSampleSize*=2;
                art=BitmapFactory.decodeByteArray(data,0,data.length,options);
            }
            Bitmap bitmap=art;
            Bitmap backdrop=artworkBackdrop(bitmap);
            ui.post(()->{
                if(closed||token!=generation){
                    if(bitmap!=null)bitmap.recycle();
                    if(backdrop!=null)backdrop.recycle();
                    return;
                }
                if(playback!=null)playback.metadata(name,author);
                if(name!=null&&!name.isEmpty()){songTitles.put(song.url,name);title.setText(name);}
                if(author!=null&&!author.isEmpty()){songArtists.put(song.url,author);artist.setText(author);}
                if(bitmap!=null){
                    cover.setPadding(0,0,0,0);
                    cover.setImageBitmap(bitmap);
                    coverAvailable=true;
                    updateCoverPlayback(coverPlaying);
                }
                if(backdrop!=null)artworkBackground.setImageBitmap(backdrop);
            });
        }catch(Exception|OutOfMemoryError ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
    }

    private void updateCoverPlayback(boolean playing){
        coverPlaying=playing;
        if(coverAvailable&&playing){
            if(coverRotation.isPaused())coverRotation.resume();
            else if(!coverRotation.isStarted())coverRotation.start();
        }else if(coverRotation.isStarted()&&!coverRotation.isPaused())coverRotation.pause();
    }

    private void clearBufferingRate(){
        bufferingSince=0;
        bufferingRate.setVisibility(View.GONE);
    }

    private static Bitmap artworkBackdrop(Bitmap cover){
        if(cover==null)return null;
        final int size=48, radius=14;
        try{
            Bitmap sampled=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(sampled);
            canvas.drawColor(0xff121212);
            // Match the center crop used by the background without processing a screen-sized bitmap.
            float scale=(float)size/Math.min(cover.getWidth(),cover.getHeight());
            float width=cover.getWidth()*scale,height=cover.getHeight()*scale;
            canvas.drawBitmap(cover,null,new RectF((size-width)/2,(size-height)/2,
                    (size+width)/2,(size+height)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels=new int[size*size],horizontal=new int[pixels.length],vertical=new int[pixels.length];
            sampled.getPixels(pixels,0,size,0,0,size,size);
            float[] weights=new float[2*radius+1];
            float total=0;
            for(int offset=-radius;offset<=radius;offset++){
                float weight=(float)Math.exp(-(offset*offset)/50.0);
                weights[offset+radius]=weight;
                total+=weight;
            }
            for(int i=0;i<weights.length;i++)weights[i]/=total;
            blurArtwork(pixels,horizontal,size,weights,true);
            blurArtwork(horizontal,vertical,size,weights,false);
            sampled.setPixels(vertical,0,size,0,0,size,size);
            return sampled;
        }catch(RuntimeException|OutOfMemoryError ignored){return null;}
    }

    private static void blurArtwork(int[] source,int[] result,int size,float[] weights,boolean horizontal){
        int radius=weights.length/2;
        for(int y=0;y<size;y++){
            for(int x=0;x<size;x++){
                float red=0,green=0,blue=0;
                for(int offset=-radius;offset<=radius;offset++){
                    int row=horizontal?y:Math.max(0,Math.min(size-1,y+offset));
                    int column=horizontal?Math.max(0,Math.min(size-1,x+offset)):x;
                    int color=source[row*size+column];
                    float weight=weights[offset+radius];
                    red+=Color.red(color)*weight;
                    green+=Color.green(color)*weight;
                    blue+=Color.blue(color)*weight;
                }
                result[y*size+x]=Color.rgb(Math.round(red),Math.round(green),Math.round(blue));
            }
        }
    }
    private void updateLyricClock(long reported){
        if(reported<0)return;
        if(lyricSeekTarget>=0){
            long elapsed=SystemClock.uptimeMillis()-lyricSeekStarted;
            if(elapsed<2500&&(reported<lyricSeekTarget-120||reported>lyricSeekTarget+elapsed+1000))return;
            lyricTime=elapsed<2500?Math.max(lyricSeekTarget,reported):reported;
            lyricSeekTarget=-1;
        }
        lyricTime=Math.max(lyricTime,reported);
        updateLyrics(lyricTime);
    }

    private void updateLyrics(long position){
        int left=0,right=lyrics.size();
        while(left<right){int mid=(left+right)/2;if(lyrics.get(mid).millis<=position)left=mid+1;else right=mid;}
        int current=left-1;
        if(current==activeLine)return;
        activeLine=current;
        if(android.util.Log.isLoggable("SmbPlayerLyrics",android.util.Log.VERBOSE))
            android.util.Log.v("SmbPlayerLyrics","line="+activeLine+" time="+position);
        refreshLyricRows();
        if(automatic&&!queueOpen)scrollLyrics();
    }

    private void refreshLyricRows(){
        for(int i=0;i<lyricsView.getChildCount();i++){
            TextView row=(TextView)lyricsView.getChildAt(i);
            styleLyric(row,lyricsView.getFirstVisiblePosition()+i);
        }
    }

    private void styleLyric(TextView row,int position){
        boolean active=position==activeLine, selected=position==lyricCursor;
        row.setSelected(active);
        row.setTextColor(active?0xff69e1f3:0xffcccccc);
        row.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        if(row.isActivated()!=selected){
            row.setActivated(selected);
            row.setBackground(selected?host.buttonBackground(0x2268c9dd,0xff69e1f3):null);
        }
    }

    private void selectLyric(int position){
        lyricCursor=Math.max(0,Math.min(lyrics.size()-1,position));
        lyricsView.setSelectionFromTop(lyricCursor,0);
        refreshLyricRows();
    }
    private void scrollLyrics(){
        if(paneMoving||activeLine<0||!lyricsView.isShown()||lyricsView.getHeight()==0)return;
        int first=lyricsView.getFirstVisiblePosition();
        View row=lyricsView.getChildAt(activeLine-first);
        if(row!=null){
            int offset=row.getTop()+row.getHeight()/2-lyricsView.getHeight()/2;
            if(Math.abs(offset)>1)lyricsView.smoothScrollBy(offset,300);
        } else if(activeLine==lyricsView.getLastVisiblePosition()+1||activeLine==first-1){
            lyricsView.smoothScrollToPositionFromTop(activeLine,0,300);
        } else lyricsView.setSelectionFromTop(activeLine,0);
    }
    private void setFollowing(boolean value){
        boolean resume=value&&!automatic;
        automatic=value;
        follow.setVisibility(value||lyrics.isEmpty()?View.GONE:View.VISIBLE);
        if(value){
            lyricCursor=-1;
            lyricsView.setFocusable(false);
            refreshLyricRows();
        }
        if(resume)scrollLyrics();
        if(!value)cancelAutoHide();else scheduleHide();
    }
    private void seekLyric(int index){
        if(index<0||index>=lyrics.size())return;
        seek(lyrics.get(index).millis);
        setFollowing(true);
        if(controlsShown)play.requestFocusFromTouch();
    }
    private void seekProgress(int value){if(player!=null)seek(player.getLength()*value/1000);}
    private void seek(long value){
        if(player==null||!player.isSeekable())return;
        long target=Math.max(0,value);
        lyricTime=lyricSeekTarget=target;
        lyricSeekStarted=SystemClock.uptimeMillis();
        if(playback!=null)playback.seek(target);
        updateLyrics(target);
    }
    private static String labelTime(long millis){
        if(millis<0)return "—";
        return String.format(Locale.ROOT,"%02d:%02d",millis/60000,millis/1000%60);
    }
    @Override public void close(){
        closed=true;generation++;ui.removeCallbacksAndMessages(null);
        if(job!=null)job.cancel(true);
        if(paneMotion!=null)paneMotion.cancel();
        worker.shutdownNow();coverRotation.cancel();root.setKeepScreenOn(false);
        if(playback!=null)playback.detach(playbackListener);
        if(bound){host.unbindService(playbackConnection);bound=false;}
        playback=null;player=null;server=null;
    }
}
