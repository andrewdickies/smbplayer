// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.app.*;
import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import jcifs.CIFSContext;
import jcifs.context.BaseContext;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

/** Owns playback independently of any activity or view. All state changes run on the main thread. */
public final class MusicPlaybackService extends Service {
    static final String OPEN="com.smbplayer.tv.OPEN_MUSIC";
    private static final String CHANNEL="music_playback";
    private static final int NOTIFICATION=41;
    static MusicPlaybackService current;
    interface Listener {
        void onTrack();
        void onEvent(MediaPlayer.Event event);
        void onStopped();
    }
    /** Retains connection settings for replay, with no live sockets or network handles. */
    static final class Source {
        private final jcifs.Configuration configuration;
        private final jcifs.Credentials credentials;
        private Source(CIFSContext context){configuration=context.getConfig();credentials=context.getCredentials();}
        static Source from(CIFSContext context){return context==null?null:new Source(context);}
        CIFSContext connect(){return new BaseContext(configuration).withCredentials(credentials);}
    }
    final class LocalBinder extends Binder {MusicPlaybackService service(){return MusicPlaybackService.this;}}
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LocalBinder binder=new LocalBinder();
    private final List<MainActivity.Entry> songs=new ArrayList<>(), sidecars=new ArrayList<>();
    private Listener listener;
    private CIFSContext context;
    private Source source;
    private LibVLC engine;
    private MediaPlayer player;
    private SmbPlaybackServer server;
    private AssetFileDescriptor descriptor;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private int index,mode;
    private volatile int generation;
    private volatile boolean destroyed;
    private boolean paused,ended,privatePlayback,foreground,focusHeld,resumeOnFocus,preparingContext;
    private String title="音乐",artist="";
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(destroyed)return;
            if(player!=null){
                if(server!=null)server.updatePlayback(Math.max(0,player.getTime()),player.getLength());
                publishState();
            }
            ui.postDelayed(this,750);
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focus=change->{
        if(change==AudioManager.AUDIOFOCUS_GAIN){
            if(player!=null)player.setVolume(100);
            if(resumeOnFocus){resumeOnFocus=false;setPaused(false);}
        }else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            if(player!=null)player.setVolume(25);
        }else{
            resumeOnFocus=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT&&!paused;
            pausePlayback(false);
            if(change==AudioManager.AUDIOFOCUS_LOSS)abandonFocus();
        }
    };
    private final BroadcastReceiver noisy=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){setPaused(true);}
    };

    @Override public void onCreate(){
        super.onCreate();current=this;
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"smbplayer:music");
        wake.setReferenceCounted(false);
        session=new MediaSession(this,"smbplayer music");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){setPaused(false);}
            @Override public void onPause(){setPaused(true);}
            @Override public void onStop(){stopPlayback();}
            @Override public void onSkipToNext(){change(1,false);}
            @Override public void onSkipToPrevious(){change(-1,false);}
            @Override public void onSeekTo(long position){seek(position);}
        });
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy,filter);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"音乐播放",NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null,null);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        ui.post(tick);
    }

    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null){
            String action=intent.getAction();
            if("stop".equals(action)){stopPlayback();return START_NOT_STICKY;}
            if("pause".equals(action))setPaused(true);
            if("play".equals(action))setPaused(false);
            if("next".equals(action))change(1,false);
            if("previous".equals(action))change(-1,false);
        }
        updateNotification();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    void listen(Listener value){listener=value;}
    void detach(Listener value){if(listener==value)listener=null;}
    List<MainActivity.Entry> songs(){return new ArrayList<>(songs);}
    List<MainActivity.Entry> sidecars(){return new ArrayList<>(sidecars);}
    int index(){return index;}
    int mode(){return mode;}
    void mode(int value){mode=value;}
    MediaPlayer player(){return player;}
    SmbPlaybackServer server(){return server;}
    boolean paused(){return paused;}
    boolean ended(){return ended;}
    boolean hasQueue(){return !songs.isEmpty();}
    boolean privatePlayback(){return privatePlayback;}
    Source source(){return source;}
    String url(){return songs.isEmpty()?null:songs.get(index).url;}

    void playQueue(List<MainActivity.Entry> tracks,List<MainActivity.Entry> lyrics,int selected,Source source,boolean privacy){
        if(Objects.equals(url(),tracks.get(selected).url)&&player!=null){
            if(listener!=null)listener.onTrack();
            return;
        }
        int request=++generation;releasePlayer();
        CIFSContext old=context;
        context=null;preparingContext=true;
        this.source=source;
        songs.clear();songs.addAll(tracks);sidecars.clear();sidecars.addAll(lyrics);
        privatePlayback=privacy;index=selected;
        worker.execute(()->{
            if(old!=null)try{old.close();}catch(Exception ignored){}
            CIFSContext copy;
            try{
                copy=source==null?null:source.connect();
            }catch(RuntimeException error){
                ui.post(()->{
                    if(!destroyed&&request==generation){preparingContext=false;paused=true;updateNotification();}
                });
                return;
            }
            ui.post(()->{
                if(destroyed||request!=generation){
                    if(copy!=null)new Thread(()->{try{copy.close();}catch(Exception ignored){}},"music-cleanup").start();
                    return;
                }
                context=copy;preparingContext=false;open();
            });
        });
    }

    void playAt(int selected){index=Math.floorMod(selected,songs.size());if(!preparingContext)open();}
    void change(int delta,boolean automatic){
        if(songs.isEmpty())return;
        int next=index+delta;
        if(mode==3&&songs.size()>1&&delta>0)next=(index+1+new Random().nextInt(songs.size()-1))%songs.size();
        if(automatic&&mode==1)next=index;
        if(automatic&&mode==0&&next>=songs.size()){ended=true;setPaused(true);return;}
        playAt(next);
    }
    private void open(){
        int token=++generation;
        releasePlayer();
        paused=false;ended=false;resumeOnFocus=false;
        title=songs.get(index).name;artist="";
        session.setActive(true);publishMetadata();updateNotification();
        MainActivity.Entry song=songs.get(index);
        CIFSContext source=context;
        worker.execute(()->{
            SmbPlaybackServer bridge=null;
            try{
                if(song.url.startsWith("smb://")){
                    if(source==null)throw new IOException("SMB unavailable");
                    bridge=new SmbPlaybackServer(song.url,source,getCacheDir());
                }
                SmbPlaybackServer ready=bridge;
                ui.post(()->{
                    if(destroyed||token!=generation){
                        if(ready!=null){
                            if(worker.isShutdown())new Thread(ready::stop,"music-cleanup").start();
                            else worker.execute(ready::stop);
                        }
                        return;
                    }
                    server=ready;
                    try{
                        engine=new LibVLC(getApplicationContext(),new ArrayList<>(Arrays.asList("--no-video","--network-caching=3500")));
                        player=new MediaPlayer(engine);
                        Media media;
                        if(ready!=null)media=new Media(engine,Uri.parse(ready.url()));
                        else{
                            descriptor=getContentResolver().openAssetFileDescriptor(Uri.parse(song.url),"r");
                            if(descriptor==null)throw new IOException("Music unavailable");
                            media=new Media(engine,descriptor);
                        }
                        try{media.addOption(":no-video");player.setMedia(media);}finally{media.release();}
                        player.setEventListener(event->{
                            if(destroyed||token!=generation)return;
                            if(event.type==MediaPlayer.Event.EndReached){change(1,true);return;}
                            if(event.type==MediaPlayer.Event.EncounteredError){paused=true;releaseWake();abandonFocus();}
                            if(event.type==MediaPlayer.Event.Playing&&paused)player.pause();
                            if(listener!=null)listener.onEvent(event);
                            publishState();publishMetadata();updateNotification();
                        });
                        if(listener!=null)listener.onTrack();
                        setPaused(paused);
                    }catch(IOException|RuntimeException error){paused=true;releasePlayer();updateNotification();}
                });
            }catch(IOException|RuntimeException error){
                if(bridge!=null)bridge.stop();
                ui.post(()->{if(token==generation){paused=true;updateNotification();}});
            }
        });
    }

    void setPaused(boolean value){
        if(!value&&ended){open();return;}
        resumeOnFocus=false;
        if(value){pausePlayback(true);return;}
        if(player==null){paused=false;return;}
        if(!requestFocus()){paused=true;publishState();updateNotification();return;}
        paused=false;
        if(!wake.isHeld())wake.acquire();
        player.setVolume(100);player.play();
        publishState();publishMetadata();updateNotification();
    }
    private void pausePlayback(boolean abandon){
        paused=true;
        if(player!=null)player.pause();
        releaseWake();
        if(abandon)abandonFocus();
        publishState();updateNotification();
    }
    void seek(long position){
        if(player!=null&&player.isSeekable()){
            long target=Math.max(0,position);
            if(player.getLength()>0)target=Math.min(target,player.getLength()-1);
            if(server!=null)server.prepareSeek(target);
            player.setTime(target);publishState();
        }
    }
    InputStream openAuxiliary(MainActivity.Entry entry)throws IOException{
        if(!entry.url.startsWith("smb://")){
            InputStream in=getContentResolver().openInputStream(Uri.parse(entry.url));
            if(in==null)throw new IOException("Music unavailable");
            return in;
        }
        if(context==null)throw new IOException("SMB unavailable");
        jcifs.smb.SmbFile file=new jcifs.smb.SmbFile(entry.url,context);
        try{
            return new FilterInputStream(file.openInputStream()){
                @Override public void close()throws IOException{try{super.close();}finally{file.close();}}
            };
        }catch(IOException|RuntimeException error){file.close();throw error;}
    }
    void metadata(String name,String author){
        if(name!=null&&!name.isEmpty())title=name;
        if(author!=null&&!author.isEmpty())artist=author;
        publishMetadata();updateNotification();
    }

    private boolean requestFocus(){
        if(focusHeld)return true;
        int result;
        if(Build.VERSION.SDK_INT>=26){
            focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focus,ui).build();
            result=audio.requestAudioFocus(focusRequest);
        }else result=audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        focusHeld=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }
    private void abandonFocus(){
        if(!focusHeld)return;
        focusHeld=false;
        if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(focus);
    }
    private void releaseWake(){if(wake!=null&&wake.isHeld())wake.release();}
    private void publishState(){
        if(session==null)return;
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE
                |PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SKIP_TO_NEXT
                |PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO)
                .setState(paused?PlaybackState.STATE_PAUSED:player==null?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PLAYING,
                        player==null?0:Math.max(0,player.getTime()),paused?0:1).build());
    }
    private void publishMetadata(){
        if(session==null)return;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,privatePlayback?"私密播放":title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,privatePlayback?"":artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,player==null?0:Math.max(0,player.getLength())).build());
    }
    private PendingIntent control(String action){
        return PendingIntent.getService(this,action.hashCode(),new Intent(this,MusicPlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    // Active media-session notifications are exempt from POST_NOTIFICATIONS.
    @android.annotation.SuppressLint("NotificationPermission")
    private void updateNotification(){
        if(destroyed)return;
        Intent open=new Intent(this,MainActivity.class).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notice=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        notice.setSmallIcon(R.drawable.ic_music_notification).setContentTitle(privatePlayback?"私密播放":title)
                .setContentText(privatePlayback?"":artist).setContentIntent(content).setOnlyAlertOnce(true)
                .setVisibility(privatePlayback?Notification.VISIBILITY_PRIVATE:Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(!paused).setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous,"上一首",control("previous"))
                .addAction(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,paused?"播放":"暂停",control(paused?"play":"pause"))
                .addAction(android.R.drawable.ic_media_next,"下一首",control("next"))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止",control("stop"))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        if(!foreground){
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notice.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION,notice.build());
            foreground=true;
        }else ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notice.build());
    }
    void stopPlayback(){
        generation++;releasePlayer();abandonFocus();
        CIFSContext old=context;context=null;
        if(old!=null)worker.execute(()->{try{old.close();}catch(Exception ignored){}});
        paused=true;resumeOnFocus=false;preparingContext=false;
        songs.clear();sidecars.clear();session.setActive(false);
        if(listener!=null)listener.onStopped();
        stopForeground(true);foreground=false;stopSelf();
    }
    private void releasePlayer(){
        releaseWake();
        if(player!=null){player.setEventListener(null);player.stop();player.release();player=null;}
        if(engine!=null){engine.release();engine=null;}
        if(descriptor!=null){try{descriptor.close();}catch(IOException ignored){}descriptor=null;}
        SmbPlaybackServer old=server;server=null;
        if(old!=null)worker.execute(old::stop);
    }
    @Override public void onDestroy(){
        destroyed=true;current=null;listener=null;generation++;
        ui.removeCallbacks(tick);
        releasePlayer();abandonFocus();session.release();unregisterReceiver(noisy);
        CIFSContext old=context;context=null;
        if(old!=null)worker.execute(()->{try{old.close();}catch(Exception ignored){}});
        worker.shutdown();super.onDestroy();
    }
}
