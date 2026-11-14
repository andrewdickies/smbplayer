package com.smbplayer.tv;

import android.content.res.AssetFileDescriptor;
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
    private final LinearLayout root, body, info;
    private final TextView title, artist, time;
    private final ImageView cover;
    private final ListView lyricsView;
    private final SeekBar progress;
    private final Button play, follow, modes;
    private List<Lyrics.Line> lyrics = Collections.emptyList();
    private BaseAdapter adapter;
    private LibVLC engine;
    private MediaPlayer player;
    private AssetFileDescriptor descriptor;
    private SmbPlaybackServer server;
    private int index, generation, activeLine=-1, mode;
    private boolean dragging, automatic=true, paused, ended, closed;
    private Future<?> job;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if(closed)return;
            if(player!=null) {
                long position=Math.max(0,player.getTime()), length=player.getLength();
                if(!dragging)progress.setProgress(length>0?(int)(position*1000/length):0);
                progress.setEnabled(player.isSeekable()&&length>0);
                String label=labelTime(position)+" / "+labelTime(length);
                if(!label.contentEquals(time.getText()))time.setText(label);
                if(server!=null)server.updatePlayback(position,length);
                updateLyrics(position);
            }
            ui.postDelayed(this,180);
        }
    };

    MusicViewer(MainActivity host,List<MainActivity.Entry> songs,List<MainActivity.Entry> sidecars,int index) {
        this.host=host;this.songs=songs;this.sidecars=sidecars;this.index=index;
        root=new LinearLayout(host);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff121212);
        root.setPadding(host.dp(24),host.dp(12),host.dp(24),host.dp(18));
        LinearLayout top=new LinearLayout(host);
        top.addView(host.iconButton("back","返回目录",host::closeMediaViewer));
        root.addView(top);
        body=new LinearLayout(host);body.setGravity(Gravity.CENTER);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        info=new LinearLayout(host);info.setOrientation(LinearLayout.VERTICAL);info.setGravity(Gravity.CENTER);
        body.addView(info,new LinearLayout.LayoutParams(0,-1,1));
        cover=new ImageView(host);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setImageDrawable(new ActionIcon("music"));
        int coverSize=Math.min(host.dp(215),host.getResources().getDisplayMetrics().heightPixels/5);
        info.addView(cover,new LinearLayout.LayoutParams(coverSize,coverSize));
        title=host.text("",25);title.setGravity(Gravity.CENTER);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(title);
        artist=host.text("",21);artist.setGravity(Gravity.CENTER);artist.setSingleLine(true);info.addView(artist);
        GestureDetector swipe=new GestureDetector(host,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onFling(MotionEvent start,MotionEvent end,float vx,float vy){
                if(start!=null && Math.abs(end.getX()-start.getX())>host.dp(55) && Math.abs(vx)>Math.abs(vy)) {
                    change(vx<0?1:-1,false);return true;
                }
                return false;
            }
        });
        cover.setOnTouchListener((v,e)->swipe.onTouchEvent(e));
        lyricsView=new ListView(host){
            @Override public boolean onTouchEvent(MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN)setFollowing(false);
                return super.onTouchEvent(event);
            }
        };
        lyricsView.setDivider(null);lyricsView.setFocusableInTouchMode(true);
        lyricsView.setClipToPadding(false);
        body.addView(lyricsView,new LinearLayout.LayoutParams(0,-1,1.35f));
        lyricsView.setVisibility(View.GONE);
        adapter=new BaseAdapter(){
            @Override public int getCount(){return lyrics.size();}
            @Override public Object getItem(int i){return lyrics.get(i);}
            @Override public long getItemId(int i){return i;}
            @Override public View getView(int pos,View convert,ViewGroup parent){
                TextView line=convert instanceof TextView?(TextView)convert:host.text("",23);
                line.setPadding(host.dp(16),host.dp(10),host.dp(16),host.dp(10));
                line.setText(lyrics.get(pos).text);
                line.setTextColor(pos==activeLine?0xff69e1f3:0xffcccccc);
                line.setTypeface(pos==activeLine?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
                return line;
            }
        };
        lyricsView.setAdapter(adapter);
        lyricsView.setOnItemClickListener((parent,v,position,id)->seekLyric(position));
        lyricsView.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)setFollowing(false);
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                int selected=lyricsView.getSelectedItemPosition();if(selected>=0)seekLyric(selected);return true;
            }
            return false;
        });
        time=host.text("",20);root.addView(time);
        progress=new SeekBar(host);progress.setMax(1000);progress.setFocusableInTouchMode(true);
        progress.setContentDescription("音乐播放进度");
        root.addView(progress,new LinearLayout.LayoutParams(-1,host.dp(48)));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;seekProgress(bar.getProgress());}
            public void onProgressChanged(SeekBar bar,int position,boolean user){if(user&&!dragging)seekProgress(position);}
        });
        LinearLayout controls=new LinearLayout(host);controls.setGravity(Gravity.CENTER);root.addView(controls);
        controls.addView(host.iconButton("previous","上一首",()->change(-1,false)));
        play=host.iconButton("pause","暂停",this::toggle);controls.addView(play);
        controls.addView(host.iconButton("next","下一首",()->change(1,false)));
        modes=host.iconButton("repeat","顺序播放",this::cycleMode);controls.addView(modes);
        follow=host.iconButton("target","回到当前句",()->{setFollowing(true);scrollLyrics();});controls.addView(follow);
        follow.setVisibility(View.GONE);
        host.setContentView(root);
        play.requestFocusFromTouch();
        start();
        ui.post(tick);
    }

    private void cycleMode(){
        mode=(mode+1)%4;
        String[] labels={"顺序播放","单曲循环","列表循环","随机播放"};
        host.updateIcon(modes,mode==3?"shuffle":"repeat",labels[mode]);
        Toast.makeText(host,labels[mode],Toast.LENGTH_SHORT).show();
    }
    boolean key(KeyEvent event){
        int code=event.getKeyCode();
        boolean media=code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS
                ||code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PAUSE;
        if(!media && (progress.hasFocus()||lyricsView.hasFocus()||follow.hasFocus()))return false;
        int action=0;
        if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS)action=-1;
        else if(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_MEDIA_NEXT)action=1;
        else if(code==KeyEvent.KEYCODE_DPAD_UP)action=2;
        else if(code==KeyEvent.KEYCODE_DPAD_DOWN&& !lyrics.isEmpty())action=3;
        else if(media)action=4;
        else return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
            if(action==-1||action==1)change(action,false);
            if(action==2)progress.requestFocusFromTouch();
            if(action==3){setFollowing(false);lyricsView.requestFocusFromTouch();lyricsView.setSelection(Math.max(0,activeLine));}
            if(action==4){if(code==KeyEvent.KEYCODE_MEDIA_PLAY)setPaused(false);else if(code==KeyEvent.KEYCODE_MEDIA_PAUSE)setPaused(true);else toggle();}
        }
        return true;
    }

    String currentUrl(){return songs.get(index).url;}
    void pause(){setPaused(true);}
    private void toggle(){setPaused(!paused);}
    private void setPaused(boolean value){
        if(!value && ended){start();return;}
        paused=value;
        host.updateIcon(play,paused?"play":"pause",paused?"播放":"暂停");
        if(player!=null){if(paused)player.pause();else player.play();}
    }
    private void change(int delta,boolean ended){
        int next=index+delta;
        if(mode==3&&songs.size()>1&&delta>0)next=(index+1+new Random().nextInt(songs.size()-1))%songs.size();
        if(ended&&mode==1)next=index;
        if(ended&&mode==0&&next>=songs.size()){this.ended=true;setPaused(true);return;}
        index=Math.floorMod(next,songs.size());
        start();
    }
    private void start(){
        ended=false;
        int token=++generation;
        if(job!=null)job.cancel(true);
        releasePlayback();
        MainActivity.Entry song=songs.get(index);
        title.setText(song.name);artist.setText("正在读取音乐…");cover.setImageDrawable(new ActionIcon("music"));
        time.setText("00:00 / —");progress.setEnabled(false);
        lyrics=Collections.emptyList();activeLine=-1;adapter.notifyDataSetChanged();lyricsView.setVisibility(View.GONE);setFollowing(true);
        paused=false;host.updateIcon(play,"pause","暂停");
        job=worker.submit(()->{
            SmbPlaybackServer bridge=null;
            try {
                if(song.url.startsWith("smb://"))bridge=host.musicBridge(song);
                SmbPlaybackServer ready=bridge;
                ui.post(()->{
                    if(closed||token!=generation){stopBridge(ready);return;}
                    server=ready;
                    try{openPlayer(song,ready,token);}catch(IOException|RuntimeException e){artist.setText("无法播放此音乐");releasePlayback();}
                });
                List<Lyrics.Line> lines=readLyrics(song);
                ui.post(()->{
                    if(closed||token!=generation)return;
                    lyrics=lines;adapter.notifyDataSetChanged();lyricsView.setVisibility(lines.isEmpty()?View.GONE:View.VISIBLE);
                });
                readMetadata(song,ready,token);
            }catch(IOException|RuntimeException e){
                stopBridge(bridge);
                ui.post(()->{if(!closed&&token==generation)artist.setText("无法读取音乐，请检查来源");});
            }
        });
    }

    private void openPlayer(MainActivity.Entry song,SmbPlaybackServer bridge,int token)throws IOException {
        engine=new LibVLC(host,new ArrayList<>(Arrays.asList("--no-video","--network-caching=3500")));
        player=new MediaPlayer(engine);
        player.setVolume(100);
        Media media;
        if(bridge!=null)media=new Media(engine,Uri.parse(bridge.url()));
        else {
            descriptor=host.getContentResolver().openAssetFileDescriptor(Uri.parse(song.url),"r");
            if(descriptor==null)throw new IOException("No file descriptor");
            media=new Media(engine,descriptor);
        }
        try{media.addOption(":no-video");player.setMedia(media);}finally{media.release();}
        player.setEventListener(event->{
            if(closed||token!=generation)return;
            if(event.type==MediaPlayer.Event.EndReached)change(1,true);
            else if(event.type==MediaPlayer.Event.EncounteredError){artist.setText("无法解码此音乐");releasePlayback();}
            else if(event.type==MediaPlayer.Event.Playing&&paused)player.pause();
        });
        if(!paused)player.play();
        artist.setText("");
    }

    private List<Lyrics.Line> readLyrics(MainActivity.Entry song){
        String stem=song.name.substring(0,song.name.lastIndexOf('.'));
        for(MainActivity.Entry entry:sidecars)if(entry.name.equalsIgnoreCase(stem+".lrc")){
            try(InputStream in=host.openAuxiliary(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){
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
            ui.post(()->{
                if(closed||token!=generation){if(bitmap!=null)bitmap.recycle();return;}
                if(name!=null&&!name.isEmpty())title.setText(name);
                if(author!=null&&!author.isEmpty())artist.setText(author);
                if(bitmap!=null)cover.setImageBitmap(bitmap);
            });
        }catch(Exception|OutOfMemoryError ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
    }
    private void updateLyrics(long position){
        int left=0,right=lyrics.size();
        while(left<right){int mid=(left+right)/2;if(lyrics.get(mid).millis<=position)left=mid+1;else right=mid;}
        int current=left-1;
        if(current==activeLine)return;
        activeLine=current;
        for(int i=0;i<lyricsView.getChildCount();i++){
            TextView row=(TextView)lyricsView.getChildAt(i);
            boolean active=lyricsView.getFirstVisiblePosition()+i==activeLine;
            row.setTextColor(active?0xff69e1f3:0xffcccccc);row.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        }
        if(automatic)scrollLyrics();
    }
    private void scrollLyrics(){
        if(activeLine>=0)lyricsView.smoothScrollToPositionFromTop(activeLine,Math.max(0,lyricsView.getHeight()/2-host.dp(23)),320);
    }
    private void setFollowing(boolean value){automatic=value;follow.setVisibility(value||lyrics.isEmpty()?View.GONE:View.VISIBLE);}
    private void seekLyric(int index){if(index>=0&&index<lyrics.size()){seek(lyrics.get(index).millis);setFollowing(true);play.requestFocusFromTouch();}}
    private void seekProgress(int value){if(player!=null)seek(player.getLength()*value/1000);}
    private void seek(long value){if(player!=null&&player.isSeekable()){if(server!=null)server.prepareSeek(value);player.setTime(Math.max(0,value));}}
    private static String labelTime(long millis){
        if(millis<0)return "—";
        return String.format(Locale.ROOT,"%02d:%02d",millis/60000,millis/1000%60);
    }
    private void releasePlayback(){
        if(player!=null){player.setEventListener(null);player.stop();player.release();player=null;}
        if(engine!=null){engine.release();engine=null;}
        if(descriptor!=null){try{descriptor.close();}catch(IOException ignored){}descriptor=null;}
        if(server!=null){stopBridge(server);server=null;}
    }
    private void stopBridge(SmbPlaybackServer bridge){if(bridge!=null)host.closeMusicBridge(bridge);}
    @Override public void close(){closed=true;generation++;ui.removeCallbacks(tick);if(job!=null)job.cancel(true);worker.shutdownNow();releasePlayback();}
}
