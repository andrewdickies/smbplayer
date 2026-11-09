// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.io.IOException;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff121212;
    private static final int ACCENT = 0xff68c9dd;
    private static final int SECONDARY = 0xffaaaaaa;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(12),
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.util.LruCache<String, Preview> previewCache = new android.util.LruCache<String, Preview>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Preview preview) {
            return 128 + (preview.image == null ? 0 : preview.image.getAllocationByteCount());
        }
    };
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences settings;
    private final java.util.ArrayDeque<Runnable> settingsParents = new java.util.ArrayDeque<>();
    private Runnable settingsRenderer;
    private boolean settingsPage;
    private Button aspectButton, speedButton, playerBack;

    private EditText host, port, share, user, password;
    private String passwordForSession = "";
    private CIFSContext connection;
    private String connectionUser = "";
    private String connectionRoot;
    private String rootUrl;
    private String currentUrl;
    private String focusedUrl;
    private String sourceKind = "nas";
    private final java.util.Map<String, View> sourceTabs = new java.util.LinkedHashMap<>();
    private boolean buildingPage;
    private String browserActionFocus;
    private View pageRoot, pagePreferredFocus;
    private LinearLayout moduleHeading;
    private int playbackLoop=-1;
    private boolean dashboard;
    private String dashboardFocusKind = "local";
    private final java.util.Map<String,String> folderLabels=new java.util.HashMap<>();
    private Uri localTree;
    private boolean nfsBrowsing;
    private LibVLC directoryVlc;
    private org.videolan.libvlc.util.MediaBrowser nfsBrowser;
    private Runnable nfsTimeout;
    private final java.util.ArrayDeque<String> localParents = new java.util.ArrayDeque<>();
    private List<Entry> subtitlesInFolder = new ArrayList<>();
    private volatile int metadataGeneration;
    private volatile boolean previewsPaused;
    private volatile SmbPreviewSource activePreviewSource;
    private final List<java.io.File> waitingSubtitles = new ArrayList<>();
    private boolean playlistSource;
    private Runnable returnFromPlaylist;
    private String playlistTitle = "网络播放列表";
    private boolean pickingSubtitle;
    private final android.media.AudioDeviceCallback audioRoutes=new android.media.AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] devices){refreshAudioOutput();}
        @Override public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] devices){refreshAudioOutput();}
    };
    private void refreshAudioOutput(){digitalOutput=AudioOutput.enabled(this,settings.getInt("passthroughMode",0));if(player!=null)player.setAudioDigitalOutputEnabled(digitalOutput);}
    private boolean screenOff;
    private final android.content.BroadcastReceiver deviceEvents = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOff = true;
                if (prepared) requestPause(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOff = false;
            } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                if (playing) flashNotice("检测到新存储设备");
                else android.widget.Toast.makeText(MainActivity.this, "检测到新存储设备", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                if (localTree != null && browsing && currentUrl != null) checkLocalStorage();
            }
        }
    };
    private List<Entry> entries = new ArrayList<>();
    private int request;
    private boolean browsing;
    private boolean playing;
    private boolean prepared;
    private MediaPlayer player;
    private android.media.session.MediaSession mediaSession;
    private LibVLC vlc;
    private SmbPlaybackServer playbackServer;
    private int playbackRequest;
    private LinearLayout controls;
    private LinearLayout header;
    private LinearLayout loadingOverlay;
    private TextView loadingText;
    private VLCVideoLayout videoLayout;
    private AssetFileDescriptor playerFd;
    private TextView playbackStatus;
    private Button pause;
    private SeekBar timeline;
    private List<long[]> cachedTimeline = java.util.Collections.emptyList();
    private Button more;
    private Button playlist;
    private TextView playbackNotice;
    private AlertDialog playbackDialog;
    private View playbackDialogOrigin;
    private SharedPreferences history;
    private String videoKey;
    private Entry playingEntry;
    private final List<java.io.File> subtitleFiles = new ArrayList<>();
    private long resumeAt;
    private long observedPosition, observedDuration;
    private boolean resumeWithoutPrompt;
    private boolean restorePaused;
    private boolean pausedPlayback;
    private boolean pauseRequested,frameCheckPending,digitalOutput;
    private long frameCheckPosition,frameCheckStarted,loadingDue;
    private int recoveryRebuilds;
    private boolean retainingRecovery;
    private MediaPlayer gatedPlayer;
    private int gatedVolume=-1,gatedTrack=Integer.MIN_VALUE;
    private final DownloadRate downloadRate=new DownloadRate();
    private Object rateSource;
    private long nativeReadEpoch,lastNativeBytes;
    private double networkRate;
    private long lastFramePoll;
    private final Runnable loadingTick=new Runnable(){@Override public void run(){
        if(!playing)return;
        updateNetworkRate();
        if(buffering&&!pauseRequested&&!dragging&&android.os.SystemClock.elapsedRealtime()>=loadingDue){loadingText.setText(bufferingLabel());loadingOverlay.setVisibility(View.VISIBLE);}
        ui.postDelayed(this,500);
    }};

    private boolean buffering;
    private final VideoRecovery videoRecovery = new VideoRecovery();
    private boolean currentSoftwareRetry;
    private boolean retainedSubtitleSession;
    private int restoreAudioTrack = Integer.MIN_VALUE, restoreSubtitleTrack = Integer.MIN_VALUE;
    private Runnable openingTimeout;
    private long pendingSeek = -1;
    private boolean dragging;
    private boolean seekTouchCancelled,dragResumeDesired;
    private boolean displayedCounterSeen,decodedCounterFallback,videoTrackKnown;
    private int revealKey = -1;
    private final Runnable sleepPlayback = () -> {
        if (playing) leaveVideo("定时停止播放");
    };
    private final Runnable hideNotice = () -> {
        if (playbackNotice != null) playbackNotice.setVisibility(View.GONE);
    };
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
            MediaPlayer.ScaleType.SURFACE_FILL, MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL
    };
    private boolean controlsShown;
    private boolean keepControlsHidden;
    private boolean startWithChromeHidden;
    private long exitPromptAt;
    private final Runnable hideControls = () -> {
        keepControlsHidden = true;
        controlsShown = false;
        header.animate().cancel(); controls.animate().cancel();
        header.setVisibility(View.GONE); controls.setVisibility(View.GONE);
        controls.setTranslationY(0);
    };
    private final Runnable applySeek = () -> {
        if (prepared && pendingSeek >= 0 && player.isSeekable()) {
            android.util.Log.d("SmbPlayer", "VLC seek " + player.getTime() + " -> " + pendingSeek);
            performSeek(pendingSeek);
        }
        pendingSeek = -1;
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!prepared) return;
            long position = player.getTime();
            if(playbackServer!=null)playbackServer.updatePlayback(position,player.getLength());
            if(monitorVideoRecovery(position))return;
            if (!dragging && pendingSeek < 0) updateTimeline(videoRecovery.active()?frameCheckPosition:position);
            cachedTimeline = playbackServer == null ? java.util.Collections.emptyList()
                    : playbackServer.bufferedRanges(player.getLength());
            timeline.invalidate();
            ui.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        SharedPreferences defaults=getSharedPreferences("server",MODE_PRIVATE);
        PlayerDefaults.migrate(defaults);
        settings = new PlaybackPreferences(defaults);
        history = getSharedPreferences("playback", MODE_PRIVATE);
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio!=null)audio.registerAudioDeviceCallback(audioRoutes,ui);
        mediaSession = new android.media.session.MediaSession(this, "SmbPlayer");
        android.content.IntentFilter screenEvents = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        android.content.IntentFilter storageEvents = new android.content.IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        storageEvents.addAction(Intent.ACTION_MEDIA_REMOVED);
        storageEvents.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        storageEvents.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        storageEvents.addDataScheme("file");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(deviceEvents, screenEvents, RECEIVER_NOT_EXPORTED);
            registerReceiver(deviceEvents, storageEvents, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(deviceEvents, screenEvents);
            registerReceiver(deviceEvents, storageEvents);
        }
        mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() {
                requestPause(false);
            }
            @Override public void onPause() {
                if (prepared) requestPause(true);
            }
            @Override public void onStop() {
                if (playing) leaveVideo("");
            }
            @Override public void onSkipToNext() {
                if (playing) stepVideo(1, false);
            }
            @Override public void onSkipToPrevious() {
                if (playing) stepVideo(-1, false);
            }
            @Override public void onSeekTo(long position) {
                if (prepared && player.isSeekable()) performSeek(position);
            }
            @Override public void onFastForward() { if (playing) seek(seekStep()); }
            @Override public void onRewind() { if (playing) seek(-seekStep()); }
        });
        showDashboard();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(Math.max(20, size));
        view.setTextColor(Color.WHITE);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this) {
            @Override public boolean onTouchEvent(android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && isEnabled()) requestFocusFromTouch();
                return super.onTouchEvent(event);
            }
        };
        view.setId(View.generateViewId());
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(24);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setMinHeight(dp(52));
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        android.graphics.drawable.StateListDrawable backgrounds = new android.graphics.drawable.StateListDrawable();
        backgrounds.addState(new int[] {-android.R.attr.state_enabled}, buttonBackground(0xff181818, 0xff333333));
        backgrounds.addState(new int[] {android.R.attr.state_focused}, buttonBackground(0xff263134, ACCENT));
        backgrounds.addState(new int[] {}, buttonBackground(0xff292929, 0xff464646));
        view.setBackground(backgrounds);
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        view.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xff666666, Color.WHITE}));
        view.setOnFocusChangeListener((focusedView, focused) -> {
            focusedView.animate().cancel();
            boolean animated = false;
            float scale = animated && focused ? 1.025f : 1f;
            if (animated) focusedView.animate().scaleX(scale).scaleY(scale).setDuration(220).start();
            else {
                focusedView.setScaleX(1f);
                focusedView.setScaleY(1f);
            }
        });
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void setPlaybackButton(boolean active) {
        pause.setText(active ? "暂停" : "继续");
        setControlIcon(pause, active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private void setControlIcon(Button button, int id) {
        android.graphics.drawable.Drawable icon = getDrawable(id).mutate();
        icon.setBounds(0, 0, dp(24), dp(24));
        icon.setTintList(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xff666666, Color.WHITE}));
        button.setCompoundDrawablePadding(dp(4));
        button.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private android.graphics.drawable.LayerDrawable tvProgressTrack() {
        android.graphics.drawable.GradientDrawable remaining = buttonBackground(0xff464646, 0xff464646);
        android.graphics.drawable.ClipDrawable played = new android.graphics.drawable.ClipDrawable(
                buttonBackground(ACCENT, ACCENT), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {remaining, played});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            track.setLayerHeight(i, dp(7));
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        return track;
    }

    private android.graphics.drawable.GradientDrawable buttonBackground(int color, int border) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(border == ACCENT ? 4 : 1), border);
        return drawable;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(32), dp(20), dp(32), dp(20));
        layout.setBackgroundColor(BACKGROUND);
        return layout;
    }

    private final java.util.Map<String, String> sourcePasswords = new java.util.HashMap<>();
    private String homeFocusId;

    private String homeKind() {
        return "usb".equals(sourceKind) ? "local"
                : java.util.Arrays.asList("local", "nas", "m3u", "stream").contains(sourceKind) ? sourceKind : "nas";
    }

    private void showSetup(String message){showHome(homeKind(),message);}

    private void showDashboard() {
        beginPage(); dashboard = true; settingsPage = false; settingsParents.clear(); settingsRenderer = null; ((PlaybackPreferences) settings).end();
        ++request; closeNfsBrowser(); browsing = false; playlistSource = false; nfsBrowsing = false;
        returnFromPlaylist = null;
        localTree = null; localParents.clear(); currentUrl = null; focusedUrl = null;
        entries = new ArrayList<>(); subtitlesInFolder = new ArrayList<>();
        LinearLayout page = column(); page.setPadding(dp(32),dp(16),dp(32),dp(16)); pageRoot = page; sourceTabs.clear();
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this); logo.setImageResource(R.mipmap.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text("smbplayer", 32); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2); titleParams.leftMargin = dp(14);
        brand.addView(title, titleParams); page.addView(brand);
        String[] kinds={"local","nas","m3u","stream","settings"};
        String[] labels={"本地存储","SMB 网络","M3U/M3U8","网络串流","设置"};
        int[] icons={R.drawable.ic_local,R.drawable.ic_smb,R.drawable.ic_playlist,R.drawable.ic_stream,R.drawable.ic_settings};
        List<HomeDashboard.Card> cards=new ArrayList<>();
        for(int i=0;i<kinds.length;i++){
            String kind=kinds[i];
            android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();
            background.addState(new int[]{android.R.attr.state_focused},buttonBackground(0xff263134,ACCENT));
            background.addState(new int[]{android.R.attr.state_pressed},buttonBackground(0xff303b3e,ACCENT));
            background.addState(new int[]{},buttonBackground(0xff21272a,0xff464e52));
            HomeDashboard.Card card=new HomeDashboard.Card(this,labels[i],icons[i],background,()->{
                dashboardFocusKind=kind;if(kind.equals("settings"))showAppSettings();else switchSource(kind);
            });
            card.setOnFocusChangeListener((view,focused)->{if(focused)dashboardFocusKind=kind;});
            cards.add(card);sourceTabs.put(kind,card);
        }
        HomeDashboard dashboardContent=new HomeDashboard(this,cards);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this){
            @Override protected void onMeasure(int width,int height){dashboardContent.setViewportHeight(View.MeasureSpec.getSize(height));super.onMeasure(width,height);}
        };
        scroll.setFocusable(false);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(dashboardContent,new ScrollView.LayoutParams(-1,-2));
        LinearLayout.LayoutParams contentSpace=new LinearLayout.LayoutParams(-1,0,1);contentSpace.topMargin=dp(12);page.addView(scroll,contentSpace);
        String selected = dashboardFocusKind;
        setContentView(page);
        pagePreferredFocus = sourceTabs.containsKey(selected) ? sourceTabs.get(selected) : cards.get(0);
        pagePreferredFocus.requestFocusFromTouch(); buildingPage = false;
        pagePreferredFocus.post(this::repairMainFocus);
    }

    private void showHome(String kind,String message) {
        beginPage();++request;closeNfsBrowser();sourceKind=kind;browsing=false;returnFromPlaylist=null;nfsBrowsing=false;playlistSource=false;
        localTree=null;localParents.clear();currentUrl=null;focusedUrl=null;entries=new ArrayList<>();subtitlesInFolder=new ArrayList<>();
        LinearLayout page=column();pageRoot=page;addSourceNavigation(page,kind);
        LinearLayout actions=new LinearLayout(this);
        Button add=button("添加",()->{if("local".equals(kind))pickDocument(Intent.ACTION_OPEN_DOCUMENT_TREE,10);else editHomeSource(kind,null);});
        add.setContentDescription("local".equals(kind)?"添加文件夹":"nas".equals(kind)?"添加 SMB 服务器":"m3u".equals(kind)?"添加 M3U / M3U8":"添加网络串流");actions.addView(add);moduleHeading.addView(actions);
        org.json.JSONArray all=homeSources();List<org.json.JSONObject> items=new ArrayList<>();
        for(int i=0;i<all.length();i++){org.json.JSONObject item=all.optJSONObject(i);if(item!=null&&kind.equals(item.optString("kind")))items.add(item);}
        TextView hint=text(message.isEmpty()?items.isEmpty()?"尚未添加"+("local".equals(kind)?"文件夹":"来源")+"，请选择右上角添加。":"已添加 "+items.size()+" 项 · 长按管理":message,20);hint.setTextColor(SECONDARY);hint.setPadding(0,0,0,dp(14));page.addView(hint);
        ScrollView scroll=new ScrollView(this);scroll.setFocusable(false);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        List<Button> buttons=new ArrayList<>();View preferred=add;
        for(org.json.JSONObject item:items) {
            Button row=button(item.optString("name"),()->openHomeSource(item));row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setMinHeight(dp(66));
            if("local".equals(kind)){android.graphics.drawable.Drawable icon=getDrawable(R.drawable.ic_folder);icon.setBounds(0,0,dp(36),dp(36));row.setCompoundDrawables(icon,null,null,null);row.setCompoundDrawablePadding(dp(14));}
            boolean[] held={false};row.setOnLongClickListener(v->{held[0]=true;manageHomeSource(item);return true;});
            row.setOnKeyListener((v,key,event)->{
                if(key==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)manageHomeSource(item);return true;}
                if(key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER&&key!=KeyEvent.KEYCODE_BUTTON_A)return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0)held[0]=false;else{if(!held[0]){held[0]=true;v.cancelLongPress();v.setPressed(false);manageHomeSource(item);}return true;}}
                else if(held[0]){held[0]=false;v.setPressed(false);return true;}return false;
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);rows.addView(row,lp);buttons.add(row);
            if(buttons.size()==1||item.optString("id").equals(homeFocusId))preferred=row;
        }
        setContentView(page);finishPage(preferred,actions);
        for(int i=0;i<buttons.size();i++){Button row=buttons.get(i);row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId());row.setNextFocusUpId(i==0?add.getId():buttons.get(i-1).getId());row.setNextFocusDownId(buttons.get(Math.min(i+1,buttons.size()-1)).getId());}
        if(!buttons.isEmpty()){add.setNextFocusDownId(buttons.get(0).getId());sourceTabs.get(kind).setNextFocusDownId(buttons.get(0).getId());}
    }

    private org.json.JSONArray homeSources() {
        try {
            if(settings.contains("homeSources"))return includeLegacyLocal(new org.json.JSONArray(settings.getString("homeSources","[]")));
            org.json.JSONArray result = new org.json.JSONArray();
            org.json.JSONArray old = new org.json.JSONArray(settings.getString("savedShares", "[]"));
            if (old.length() == 0 && !settings.getString("host", "").isEmpty()) {
                org.json.JSONObject item = new org.json.JSONObject();
                for (String key : new String[] {"host", "port", "share", "user"}) item.put(key, settings.getString(key, ""));
                old.put(item);
            }
            for (int i = 0; i < old.length(); i++) {
                org.json.JSONObject item = old.getJSONObject(i);
                item.put("id", java.util.UUID.randomUUID().toString()).put("kind", "nas")
                        .put("name", item.optString("host") + ":" + item.optString("port", "445"));
                result.put(item);
            }
            settings.edit().putString("homeSources", result.toString()).apply();
            return includeLegacyLocal(result);
        } catch (org.json.JSONException invalid) { return new org.json.JSONArray(); }
    }

    private org.json.JSONArray includeLegacyLocal(org.json.JSONArray sources) throws org.json.JSONException {
        String old=settings.getString("localFolder","");
        if(!old.isEmpty()) {
            boolean exists=false;for(int i=0;i<sources.length();i++){org.json.JSONObject item=sources.optJSONObject(i);if(item!=null&&"local".equals(item.optString("kind"))&&old.equals(item.optString("url")))exists=true;}
            if(!exists)sources.put(new org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("kind","local").put("url",old).put("name",settings.getString("localFolderName","已选择的文件夹")));
            settings.edit().putString("homeSources",sources.toString()).remove("localFolder").remove("localFolderName").apply();
        }
        return sources;
    }

    private String sourceAddress(org.json.JSONObject item) {
        return "nas".equals(item.optString("kind")) ? "smb://" + item.optString("host") + ":"
                + item.optString("port", "445") + "/" + item.optString("share") : item.optString("url");
    }

    private void putHomeSource(org.json.JSONObject item) {
        org.json.JSONArray all = homeSources(), next = new org.json.JSONArray(); boolean replaced = false;
        for (int i = 0; i < all.length(); i++) {
            org.json.JSONObject old = all.optJSONObject(i);
            if (old == null) continue;
            if (old.optString("id").equals(item.optString("id"))) { next.put(item); replaced = true; }
            else next.put(old);
        }
        if (!replaced) next.put(item);
        settings.edit().putString("homeSources", next.toString()).apply();
        homeFocusId = item.optString("id");
    }

    private void openLocalFolder(Uri uri) {
        sourceKind = "local"; nfsBrowsing = false; returnFromPlaylist = null;
        localTree = uri; localParents.clear(); playlistSource = false; currentUrl = null;
        try {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            folderLabels.put(root.toString(),documentName(uri));
            loadLocal(root.toString(), null, false);
        } catch (RuntimeException invalid) { showHome("local", "文件夹授权已失效，请重新选择。"); }
    }

    private void manageHomeSource(org.json.JSONObject item) {
        if("local".equals(item.optString("kind"))) {showItems(item.optString("name"),new String[]{"移除文件夹"},index->removeSource(item));return;}
        showItems(item.optString("name"), new String[] {"编辑", "复制为新来源", "删除"}, choice -> {
            if (choice == 0) editHomeSource(item.optString("kind"), item);
            else if (choice == 1) {
                try {
                    org.json.JSONObject copy = new org.json.JSONObject(item.toString());
                    copy.put("id", java.util.UUID.randomUUID().toString()).put("name", item.optString("name") + " · 副本");
                    String secret = savedSourcePassword(item);
                    if (secret != null) sourcePasswords.put(copy.optString("id"), secret);
                    putHomeSource(copy); showHome(copy.optString("kind"), "已复制来源");
                } catch (org.json.JSONException ignored) {}
            } else showPlaybackDialog(new AlertDialog.Builder(this).setTitle("删除此来源？")
                    .setMessage(item.optString("name")).setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        removeSource(item);
                    }).create());
        });
    }

    private void removeSource(org.json.JSONObject item) {
        org.json.JSONArray all=homeSources(),kept=new org.json.JSONArray();
        for(int i=0;i<all.length();i++){org.json.JSONObject old=all.optJSONObject(i);if(old!=null&&!old.optString("id").equals(item.optString("id")))kept.put(old);}
        settings.edit().putString("homeSources",kept.toString()).apply();sourcePasswords.remove(item.optString("id"));homeFocusId=null;
        if("local".equals(item.optString("kind")))try{getContentResolver().releasePersistableUriPermission(Uri.parse(item.optString("url")),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(RuntimeException ignored){}
        showHome(item.optString("kind"),"");
    }

    private void editHomeSource(String kind, org.json.JSONObject existing) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        EditText label = field(form, "名称", existing == null ? "" : existing.optString("name"), InputType.TYPE_CLASS_TEXT, false);
        final java.util.Map<String, EditText> inputs = new java.util.HashMap<>();
        if ("nas".equals(kind)) {
            for (String key : new String[] {"host", "port", "share", "user"}) {
                String title = "host".equals(key) ? "服务器地址" : "port".equals(key) ? "端口" : "share".equals(key) ? "共享名 / 子目录" : "用户名（可选）";
                inputs.put(key, field(form, title, existing == null ? ("port".equals(key) ? "445" : "") : existing.optString(key),
                        "port".equals(key) ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT, false));
            }
            inputs.put("password", field(form, "密码（保存到设备）", existing == null ? "" : (savedSourcePassword(existing) == null ? "" : savedSourcePassword(existing)),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false));
        } else inputs.put("url", field(form, "链接", existing == null ? "" : existing.optString("url"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false));
        String typeName = "nas".equals(kind) ? "SMB 服务器" : "m3u".equals(kind) ? "M3U / M3U8" : "网络串流";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle((existing == null ? "添加 " : "编辑 ") + typeName)
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        showPlaybackDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("id", existing == null ? java.util.UUID.randomUUID().toString() : existing.optString("id")).put("kind", kind);
                for (String key : inputs.keySet()) if (!key.equals("password")) item.put(key, inputs.get(key).getText().toString().trim());
                if ("nas".equals(kind)) {
                    int number = Integer.parseInt(item.optString("port").isEmpty() ? "445" : item.optString("port"));
                    String path = item.optString("share").replace('\\', '/').replaceAll("^/+|/+$", "");
                    if (number < 1 || number > 65535 || path.isEmpty()) throw new IllegalArgumentException();
                    for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException();
                    URI uri = new URI("smb", null, item.optString("host"), number, "/" + path + "/", null, null);
                    if (uri.getHost() == null) throw new IllegalArgumentException();
                    item.put("port", String.valueOf(number)).put("share", path);
                } else {
                    String url = item.optString("url");
                    if (!isNetworkMedia(url) || "m3u".equals(kind) && !(url.startsWith("http://") || url.startsWith("https://"))) throw new IllegalArgumentException();
                }
                String name = label.getText().toString().trim();
                item.put("name", name.isEmpty() ? sourceAddress(item) : name);
                if ("nas".equals(kind)) {
                    String secret = inputs.get("password").getText().toString();
                    item.put("credential", SourceCredentials.encrypt(secret));
                    sourcePasswords.put(item.optString("id"), secret);
                }
                putHomeSource(item); dialog.dismiss(); showHome(kind, existing == null ? "已添加来源" : "已保存修改");
            } catch (Exception invalid) {
                EditText invalidField = inputs.get("nas".equals(kind) ? "host" : "url");
                invalidField.setError("nas".equals(kind) ? "请检查地址、端口和共享路径" : "请输入有效的网络链接");
                invalidField.requestFocus();
            }
        });
    }

    private EditText sourceValue(String value) { EditText input = new EditText(this); input.setText(value); return input; }

    private String savedSourcePassword(org.json.JSONObject item) {
        String id = item.optString("id");
        if (sourcePasswords.containsKey(id)) return sourcePasswords.get(id);
        if (!item.has("credential")) return null;
        try { return SourceCredentials.decrypt(item.optString("credential")); }
        catch (Exception unavailable) { return null; }
    }

    private void connectHomeSource(org.json.JSONObject item, String secret) {
        sourceKind = "nas"; host = sourceValue(item.optString("host")); port = sourceValue(item.optString("port", "445"));
        share = sourceValue(item.optString("share")); user = sourceValue(item.optString("user")); password = sourceValue(secret);
        try { item.put("credential", SourceCredentials.encrypt(secret)); putHomeSource(item); }
        catch (Exception failed) { showHome("nas", "密码保存失败，请编辑来源后重试。"); return; }
        sourcePasswords.put(item.optString("id"), secret); connect(text("", 20));
    }

    private void openHomeSource(org.json.JSONObject item) {
        String kind = item.optString("kind"); sourceKind = kind;
        homeFocusId=item.optString("id");
        if("local".equals(kind)) {openLocalFolder(Uri.parse(item.optString("url")));return;}
        if ("nas".equals(kind)) {
            String secret = savedSourcePassword(item);
            if (item.optString("user").isEmpty() || secret != null) connectHomeSource(item, secret == null ? "" : secret);
            else {
                LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(12), dp(20), dp(12));
                form.addView(text(sourceAddress(item), 20));
                EditText secretInput = field(form, "密码（保存到设备）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
                showPlaybackDialog(new AlertDialog.Builder(this).setTitle("连接 · " + item.optString("name"))
                        .setView(form).setNegativeButton("取消", null).setPositiveButton("连接", (dialog, which) -> connectHomeSource(item, secretInput.getText().toString())).create());
            }
        } else if ("m3u".equals(kind)) importPlaylist(Uri.parse(item.optString("url")), java.util.Collections.emptyList(), java.util.Collections.emptyList(), null, item.optString("name"));
        else {
            String url = item.optString("url");
            if (url.startsWith("nfs://") && url.endsWith("/")) { localParents.clear(); localTree = null; loadNfs(url, null); }
            else { Entry entry = new Entry(item.optString("name"), url, false); setPlaylist(java.util.Collections.singletonList(entry), item.optString("name")); openVideo(entry); }
        }
    }

    private EditText field(LinearLayout parent, String label, String value, int type, boolean horizontal) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(text(label, 15));
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        input.setSingleLine();
        input.setInputType(type);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                | android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        input.setText(value);
        input.setTextSize(21);
        android.graphics.drawable.StateListDrawable fieldBackground = new android.graphics.drawable.StateListDrawable();
        fieldBackground.addState(new int[] {android.R.attr.state_focused}, buttonBackground(0xff2a3335, ACCENT));
        fieldBackground.addState(new int[] {}, buttonBackground(0xff181818, 0xff464646));
        input.setBackground(fieldBackground);
        input.setPadding(dp(10), dp(4), dp(10), dp(4));
        input.setMinHeight(dp(42));
        group.addView(input);
        parent.addView(group, horizontal
                ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void connect(TextView hint) {
        String server = host.getText().toString().trim();
        String endpoint = port.getText().toString().trim();
        String path = share.getText().toString().trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        String account = user.getText().toString().trim();
        String secret = password.getText().toString();
        try {
            int number = Integer.parseInt(endpoint.isEmpty() ? "445" : endpoint);
            if (number < 1 || number > 65535 || path.isEmpty()) throw new IllegalArgumentException();
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException();
            }
            URI uri = new URI("smb", null, server, number, "/" + path + "/", null, null);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            Properties config = new Properties();
            config.setProperty("jcifs.smb.client.minVersion", "SMB202");
            config.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            config.setProperty("jcifs.smb.client.connTimeout", "5000");
            config.setProperty("jcifs.smb.client.soTimeout", "8000");
            config.setProperty("jcifs.smb.client.responseTimeout", "8000");
            int separator = account.indexOf('\\');
            String domain = separator < 0 ? "" : account.substring(0, separator);
            String name = account.substring(separator + 1);
            sourceKind = "nas";
            localTree = null;
            localParents.clear();
            playlistSource = false;
            currentUrl = null;
            passwordForSession = secret;
            settings.edit().putString("host", server).putString("port", String.valueOf(number))
                    .putString("share", path).putString("user", account).apply();
            if (hint.getWindowToken() != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(hint.getWindowToken(), 0);
            // jCIFS treats percent escapes literally; retain spaces and Unicode in SMB paths.
            String url = "smb://" + uri.getRawAuthority() + uri.getPath();
            int token = ++request;
            browsing = true;
            renderBrowser("正在连接…", false);
            network.execute(() -> {
                try {
                    BaseContext base = new BaseContext(new PropertyConfiguration(config));
                    CIFSContext next = account.isEmpty() ? base.withAnonymousCredentials()
                            : base.withCredentials(new NtlmPasswordAuthenticator(domain, name, secret));
                    ui.post(() -> {
                        if (token != request) {
                            if (!network.isShutdown()) network.execute(() -> closeContext(next));
                            return;
                        }
                        CIFSContext previous = connection;
                        connection = next;
                        connectionUser = account;
                        connectionRoot = url;
                        if (previous != null) network.execute(() -> closeContext(previous));
                        rootUrl = url;
                        load(url, null);
                    });
                } catch (Exception e) {
                    android.util.Log.w("SmbPlayer", "SMB initialization failed", e);
                    ui.post(() -> {
                        if (token == request) showSetup("无法初始化 SMB 连接。");
                    });
                }
            });
        } catch (Exception e) {
            android.util.Log.w("SmbPlayer", "SMB setup failed", e);
            showHome("nas", "请检查服务器地址、共享名和端口（1–65535）。");
        }
    }

    private static void closeContext(CIFSContext context) {
        try {
            context.close();
        } catch (Exception ignored) {
        }
    }

    private void load(String url, String restoreFocus) {
        int token = ++request;
        CIFSContext context = connection;
        browsing = true;
        renderBrowser("正在读取目录…", false);
        network.execute(() -> {
            try (SmbFile directory = new SmbFile(url, context)) {
                List<Entry> result = new ArrayList<>();
                List<Entry> subtitles = new ArrayList<>();
                for (SmbFile child : directory.listFiles()) {
                    try (SmbFile item = child) {
                        String name = item.getName();
                        boolean folder = name.endsWith("/");
                        String urlForEntry = item.getURL().toExternalForm();
                        if (folder || isVideo(name) || isPlaylist(name)) {
                            long modified = 0;
                            try { modified = item.lastModified(); } catch (jcifs.CIFSException ignored) {}
                            long bytes = -1;
                            if (!folder) try { bytes = item.length(); } catch (jcifs.CIFSException ignored) {}
                            result.add(new Entry(name, urlForEntry, folder, modified, 0, bytes));
                        } else if (isSubtitle(name)) subtitles.add(new Entry(name, urlForEntry, false));
                    }
                }
                sortEntries(result);
                ui.post(() -> {
                    if (token != request) return;
                    currentUrl = url;

                    entries = result;
                    subtitlesInFolder = subtitles;
                    focusedUrl = restoreFocus;
                    renderBrowser(entries.isEmpty() ? "目录中没有可显示的视频" : "", true);
                });
            } catch (Exception e) {
                android.util.Log.w("SmbPlayer", "SMB directory request failed", e);
                ui.post(() -> {
                    if (token != request) return;
                    String error = "无法读取目录 · " + e.getClass().getSimpleName() + "。请检查地址、端口和共享权限。";
                    if (currentUrl == null) { sourcePasswords.remove(homeFocusId); showSetup(error); }
                    else renderBrowser(error, true);
                });
            }
        });
    }

    private void sortEntries(List<Entry> files) {
        List<Entry> sorted = PlaylistOrder.sort(files, browserSortMode(), browserDescending(),
                settings.getLong("browserSortSeed", 0), entry -> entry.size, entry -> entry.modified, entry -> entry.name, entry -> entry.url);
        sorted.sort(Comparator.comparing(entry -> !entry.folder));
        files.clear(); files.addAll(sorted);
    }

    private int browserSortMode() { return Math.floorMod(settings.getInt("browserSort", settings.getBoolean("sortRecent", false) ? 2 : 1), 4); }
    private boolean browserDescending() { return settings.getBoolean("browserDescending", settings.getBoolean("sortRecent", false)); }
    private String sortLabel(int mode) { return new String[] {"大小", "名称", "修改时间", "随机"}[Math.floorMod(mode, 4)]; }

    private void addSourceNavigation(LinearLayout page,String active) {
        sourceTabs.clear(); moduleHeading=new LinearLayout(this);moduleHeading.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("返回",this::onBackPressed);sourceTabs.put(active,back);
        moduleHeading.addView(back,new LinearLayout.LayoutParams(-2,dp(52)));
        String label="local".equals(active)?"本地存储":"nas".equals(active)?"SMB 网络":"m3u".equals(active)?"M3U / M3U8":"网络串流";
        if(browsing){if(playlistSource)label=playlistTitle;else if(currentUrl!=null){String known=folderLabels.get(currentUrl);if(known!=null)label=known;else {String path=Uri.parse(currentUrl).getPath();if(path!=null){path=path.replaceAll("/+$","");int split=Math.max(path.lastIndexOf('/'),path.lastIndexOf(':'));label=path.substring(split+1);}}}}
        TextView title=text(label,30);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.setMargins(dp(18),0,dp(20),0);moduleHeading.addView(title,titleParams);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);page.addView(moduleHeading,lp);
    }

    private void switchSource(String kind) { showHome(kind, ""); }

    private void beginPage() {
        buildingPage = true; dashboard = false; settingsPage = false;
    }

    private List<View> interactiveChildren(android.view.ViewGroup group) {
        List<View> result = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && child.isEnabled() && child.getVisibility() == View.VISIBLE) result.add(child);
        }
        return result;
    }

    private void finishPage(View content,LinearLayout actions) {
        pagePreferredFocus=content;List<View> top=new ArrayList<>(sourceTabs.values());top.addAll(interactiveChildren(actions));
        for(int i=0;i<top.size();i++) {View action=top.get(i);action.setNextFocusLeftId(top.get(Math.max(0,i-1)).getId());action.setNextFocusRightId(top.get(Math.min(top.size()-1,i+1)).getId());action.setNextFocusUpId(action.getId());
            action.setNextFocusDownId(top.contains(content)?action.getId():content.getId());
            if(content instanceof android.widget.AbsListView)action.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_DOWN)return false;if(event.getAction()==KeyEvent.ACTION_DOWN){content.requestFocusFromTouch();((android.widget.AbsListView)content).setSelection(0);}return true;});
        }
        View preferred=content;if(browserActionFocus!=null)for(View v:top)if(browserActionFocus.equals(v.getTag()))preferred=v;browserActionFocus=null;
        if(preferred!=null)preferred.requestFocusFromTouch();buildingPage=false;View rendered=pageRoot;content.post(()->{if(!playing&&rendered==pageRoot)repairMainFocus();});
    }

    private boolean interactiveFocus(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown() && view.isEnabled()
                && (view instanceof HomeDashboard.Card || view instanceof Button || view instanceof EditText || view instanceof SeekBar
                    || view instanceof android.widget.AbsListView && ((android.widget.AbsListView) view).getCount() > 0);
    }

    private void repairMainFocus() {
        if (buildingPage || playbackDialog != null || playing && !controlsShown) return;
        View focus = getCurrentFocus();
        if (!interactiveFocus(focus)) {
            View fallback = playing ? pause : pagePreferredFocus;
            if (!interactiveFocus(fallback)) fallback = sourceTabs.get(sourceKind);
            if (interactiveFocus(fallback)) { fallback.requestFocusFromTouch(); focus = fallback; }
        }
        if (focus instanceof android.widget.AbsListView) {
            android.widget.AbsListView list = (android.widget.AbsListView) focus;
            if (list.getCount() > 0 && list.getSelectedItemPosition() < 0) {
                list.requestFocusFromTouch(); list.setSelection(0);
            }
        }
    }

    private void renderBrowser(String message,boolean showEntries) {
        beginPage();if(!playing){previewsPaused=false;((PlaybackPreferences)settings).end();}
        LinearLayout page=column();pageRoot=page;addSourceNavigation(page,sourceKind);
        LinearLayout actions=new LinearLayout(this);moduleHeading.addView(actions);
        if(showEntries) {
            Button sort=button("排序",this::showBrowserSort);sort.setTag("sort");actions.addView(sort);
            Button view=button("视图",()->showChoices("视图",new String[]{"列表","网格"},settings.getBoolean("gridView",false)?1:0,i->{rememberBrowserScroll();settings.edit().putBoolean("gridView",i==1).apply();browserActionFocus="view";renderBrowser("",true);}));view.setTag("view");actions.addView(view);
        }
        spaceActions(actions);
        String path=browserLocation();
        TextView location=text("位置："+(path==null?"":path),20);location.setTextColor(SECONDARY);location.setSingleLine(true);location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);location.setPadding(0,0,0,dp(14));page.addView(location);
        if(!message.isEmpty()){TextView hint=text(message,22);hint.setPadding(0,0,0,dp(12));page.addView(hint);}
        setContentView(page);
        if(!showEntries) {
            android.widget.ProgressBar loading=new android.widget.ProgressBar(this);loading.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));page.addView(loading,new LinearLayout.LayoutParams(dp(48),dp(48)));finishPage(sourceTabs.get(sourceKind),actions);return;
        }
        if(entries.isEmpty()){finishPage(actions.getChildAt(0),actions);return;}
        if(settings.getBoolean("gridView",false))showGrid(page,actions);else showFileList(page,actions);
    }

    private String browserLocation(){
        if(playlistSource)return playlistTitle;
        if(currentUrl==null)return "";
        if(localTree!=null){List<String> names=new ArrayList<>();java.util.Iterator<String> parents=localParents.descendingIterator();while(parents.hasNext()){String label=folderLabels.get(parents.next());if(label!=null)names.add(label);}String current=folderLabels.get(currentUrl);if(current!=null)names.add(current);return "本地 / "+android.text.TextUtils.join(" / ",names);}
        String path=Uri.parse(currentUrl).getPath();return path==null?"":path.replaceAll("^/+|/+$","").replace("/"," / ");
    }

    private void showBrowserSort() {
        String[] labels={"名称 · 正序","名称 · 倒序","修改时间 · 最新在前","修改时间 · 最早在前","文件大小 · 从小到大","文件大小 · 从大到小","随机"};
        int mode=browserSortMode();int current=mode==3?6:(mode==1?0:mode==2?2:4)+(browserDescending()?1:0);
        if(mode==2)current=browserDescending()?2:3;
        showChoices("排序",labels,current,index->{
            int sort=index==6?3:index<2?1:index<4?2:0;boolean descending=index==1||index==2||index==5;
            SharedPreferences.Editor edit=settings.edit().putInt("browserSort",sort).putBoolean("browserDescending",descending);
            if(sort==3)edit.putLong("browserSortSeed",new java.util.Random().nextLong());edit.apply();sortEntries(entries);fileScroll.remove(browserKey());browserActionFocus="sort";renderBrowser("",true);
        });
    }

    private final java.util.Map<String,ScrollAnchor> fileScroll=new java.util.HashMap<>();
    private android.widget.AbsListView fileView;
    private static final class ScrollAnchor {final String url;final int top;ScrollAnchor(String url,int top){this.url=url;this.top=top;}}
    private String browserKey(){return sourceKind+":"+(currentUrl==null?playlistTitle:currentUrl);}
    private void rememberBrowserScroll(){
        if(fileView==null||!fileView.isAttachedToWindow())return;int selected=fileView.getSelectedItemPosition();
        if(selected<0||selected>=fileView.getCount())return;View child=fileView.getChildAt(selected-fileView.getFirstVisiblePosition());
        Object item=fileView.getItemAtPosition(selected);if(item instanceof Entry&&child!=null)fileScroll.put(browserKey(),new ScrollAnchor(((Entry)item).url,child.getTop()));
    }

    private void showFileList(LinearLayout page, LinearLayout actions) {
        List<Entry> items = new ArrayList<>(entries);
        android.widget.ListView list = new android.widget.ListView(this);
        fileView=list;
        list.setFocusableInTouchMode(true);
        list.setId(View.generateViewId());
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setSelector(buttonBackground(0x2268c9dd, ACCENT));
        list.setDrawSelectorOnTop(true);
        list.setItemsCanFocus(false);
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Entry getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View view, android.view.ViewGroup parent) {
                MediaTile row = view instanceof MediaTile ? (MediaTile) view : new MediaTile(true);
                row.bind(getItem(position));
                return row;
            }
        });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        final int[] selected = {0};
        list.setOnItemClickListener((parent, view, position, id) -> openEntry(items.get(position)));
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selected[0] = position;
                focusedUrl = items.get(position).url;
                if (view != null && list.hasFocus()) {
                    int offset = Math.max(0, (list.getHeight() - view.getHeight()) / 2);
                    list.smoothScrollToPositionFromTop(position, offset,
                            true ? 200 : 0);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        list.setOnKeyListener((view,key,event)->fileListBoundary(list,actions,key,event,1));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).url.equals(focusedUrl)) {
                selected[0] = i;
                list.setSelection(i);
                break;
            }
        }
        list.setNextFocusLeftId(list.getId()); list.setNextFocusRightId(list.getId());
        list.setNextFocusDownId(list.getId());
        list.setNextFocusUpId(actions.getChildAt(0).getId());
        finishPage(list, actions);
        list.setSelection(selected[0]);
        ScrollAnchor anchor=fileScroll.get(browserKey());int selectedIndex=selected[0];
        if(anchor!=null&&items.get(selectedIndex).url.equals(anchor.url))list.post(()->list.setSelectionFromTop(selectedIndex,anchor.top));
    }

    private void openEntry(Entry entry) {
        rememberBrowserScroll();
        focusedUrl = entry.url;
        if(entry.folder)folderLabels.put(entry.url,entry.name);
        if (entry.folder && nfsBrowsing) {
            localParents.push(currentUrl);
            loadNfs(entry.url, null);
        } else if (entry.folder && localTree != null) {
            localParents.push(currentUrl);
            loadLocal(entry.url, null, true);
        } else if (entry.folder) load(entry.url, null);
        else if (!playlistSource && isPlaylist(entry.name)) importFolderPlaylist(entry);
        else openVideo(entry);
    }

    private void spaceActions(LinearLayout actions) {
        for (int i = 0; i < actions.getChildCount(); i++) {
            View child = actions.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.setMarginEnd(i + 1 < actions.getChildCount() ? dp(7) : 0);
            params.bottomMargin = dp(8);
            child.setLayoutParams(params);
        }
    }

    private void showGrid(LinearLayout page, LinearLayout actions) {
        List<Entry> items = new ArrayList<>(entries);
        android.widget.GridView grid = new android.widget.GridView(this);
        fileView=grid;
        grid.setId(View.generateViewId());
        grid.setFocusableInTouchMode(true);
        grid.setNumColumns(getResources().getConfiguration().screenWidthDp >= 1000 ? 4 : 3);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(buttonBackground(Color.TRANSPARENT, ACCENT));
        grid.setDrawSelectorOnTop(true);
        grid.setClipChildren(false);
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Entry getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View view, android.view.ViewGroup parent) {
                MediaTile tile = view instanceof MediaTile ? (MediaTile) view : new MediaTile();
                tile.bind(getItem(position));
                return tile;
            }
        });
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.setOnItemClickListener((parent, view, position, id) -> openEntry(items.get(position)));
        final int[] selected = {0};
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selected[0] = position;
                focusedUrl = items.get(position).url;
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        grid.setOnKeyListener((view,key,event)->fileListBoundary(grid,actions,key,event,grid.getNumColumns()));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).url.equals(focusedUrl)) {
                selected[0] = i;
                grid.setSelection(i);
                break;
            }
        }
        grid.setNextFocusLeftId(grid.getId()); grid.setNextFocusRightId(grid.getId());
        grid.setNextFocusDownId(grid.getId()); grid.setNextFocusUpId(actions.getChildAt(0).getId());
        finishPage(grid, actions);
        grid.setSelection(selected[0]);
    }

    private boolean fileListBoundary(android.widget.AbsListView list, LinearLayout actions, int key, KeyEvent event, int columns) {
        int selected = Math.max(0, list.getSelectedItemPosition()), count = list.getCount();
        columns = Math.max(1, columns);
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP && selected < columns;
        boolean edge = key == KeyEvent.KEYCODE_DPAD_DOWN && selected + columns >= count
                || key == KeyEvent.KEYCODE_DPAD_LEFT && (columns == 1 || selected % columns == 0)
                || key == KeyEvent.KEYCODE_DPAD_RIGHT && (columns == 1 || selected % columns == columns - 1 || selected == count - 1);
        if (up && event.getAction() == KeyEvent.ACTION_DOWN) actions.getChildAt(0).requestFocusFromTouch();
        return up || edge;
    }

    private CIFSContext previewContextFor(Entry entry) {return entry.url.startsWith("smb://")?connection:null;}

    private final class MediaTile extends LinearLayout {
        private final android.widget.ImageView image;
        private final TextView name;
        private final TextView duration;
        private final android.widget.ImageView folderGlyph;
        private final TextView fileDetails;
        private final boolean listMode;
        private volatile String boundUrl;

        MediaTile() { this(false); }

        MediaTile(boolean listRow) {
            super(MainActivity.this);
            listMode = listRow;
            setOrientation(listRow ? HORIZONTAL : VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(2), dp(2), dp(2), dp(2));
            setBackground(buttonBackground(0xff212121, 0xff464646));
            FrameLayout poster = new FrameLayout(MainActivity.this);
            int posterHeight = listRow ? 90 : Math.max(64, Math.min(172, getResources().getConfiguration().screenHeightDp - 320));
            addView(poster, new LinearLayout.LayoutParams(listRow ? dp(144) : -1, dp(posterHeight)));
            image = new android.widget.ImageView(MainActivity.this);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            poster.addView(image, new FrameLayout.LayoutParams(-1, -1));
            folderGlyph = new android.widget.ImageView(MainActivity.this);
            folderGlyph.setImageResource(R.drawable.ic_folder);
            folderGlyph.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            folderGlyph.setContentDescription("文件夹");
            int folderIconSize = listRow ? 64 : Math.min(80, posterHeight - 8);
            poster.addView(folderGlyph, new FrameLayout.LayoutParams(dp(folderIconSize), dp(folderIconSize), Gravity.CENTER));
            LinearLayout captions = new LinearLayout(MainActivity.this);
            captions.setOrientation(VERTICAL);
            captions.setPadding(dp(10), dp(listRow ? 5 : 0), dp(10), dp(listRow ? 6 : 0));
            captions.setBackgroundColor(0xc9121212);
            if (listRow) addView(captions, new LinearLayout.LayoutParams(0, -2, 1));
            else addView(captions, new LinearLayout.LayoutParams(-1, -2));
            name = text("", 24);
            name.setSingleLine();
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            captions.addView(name);
            duration = text("", 20);
            duration.setSingleLine();
            duration.setEllipsize(android.text.TextUtils.TruncateAt.END);
            duration.setTextColor(SECONDARY);
            captions.addView(duration);
            fileDetails = text("", 20); fileDetails.setTextColor(SECONDARY);
            fileDetails.setSingleLine(listRow); fileDetails.setMaxLines(listRow ? 1 : 2);
            fileDetails.setMinLines(listRow ? 1 : 2);
            fileDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
            captions.addView(fileDetails);
        }

        @Override public void setSelected(boolean selected) {
            boolean changed = isSelected() != selected;
            super.setSelected(selected);
            if (!changed) return;
            animate().cancel();
            boolean animated = false;
            float scale = animated && selected ? 1.025f : 1f;
            if (animated) animate().scaleX(scale).scaleY(scale).setDuration(220).start();
            else {
                setScaleX(1f);
                setScaleY(1f);
            }
        }

        void bind(Entry entry) {
            boundUrl = entry.url;
            name.setText(entry.name);
            folderGlyph.setVisibility(entry.folder ? View.VISIBLE : View.GONE);
            image.setVisibility(entry.folder ? View.GONE : View.VISIBLE);
            fileDetails.setVisibility(entry.folder ? listMode ? View.GONE : View.INVISIBLE : View.VISIBLE);
            fileDetails.setText(listMode ? FileDisplay.details(entry.modified, entry.size)
                    : FileDisplay.modified(entry.modified) + "\n" + FileDisplay.size(entry.size));
            fileDetails.setContentDescription("修改时间：" + FileDisplay.modified(entry.modified) + "；文件大小：" + FileDisplay.size(entry.size));
            duration.setText(entry.folder ? "目录" : isPlaylist(entry.name) && !playlistSource ? "播放列表"
                    : entry.duration > 0 ? time(entry.duration) : "视频");
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            image.setImageResource(R.drawable.ic_stream);
            CIFSContext previewContext = previewContextFor(entry);
            if (entry.folder || isPlaylist(entry.name) && !playlistSource
                    || (!entry.url.startsWith("content://") && previewContext == null)) return;
            Preview cached = previewCache.get(entry.url);
            if (cached != null) {
                applyPreview(cached);
                return;
            }
            int generation = metadataGeneration;
            previews.execute(() -> {
                if (previewsPaused || !entry.url.equals(boundUrl) || generation != metadataGeneration) return;
                Preview result = previewCache.get(entry.url);
                if (result == null) {
                    result = readPreview(entry.url, previewContext, true);
                    if (generation != metadataGeneration) return;
                    previewCache.put(entry.url, result);
                }
                Preview ready = result;
                ui.post(() -> {
                    if (entry.url.equals(boundUrl) && generation == metadataGeneration) applyPreview(ready);
                });
            });
        }

        private void applyPreview(Preview preview) {
            if (preview.image != null) {
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(preview.image);
            }
            String info = preview.duration > 0 ? time(preview.duration) : "视频";
            duration.setText(info);
        }
    }

    private Preview readPreview(String url, CIFSContext context, boolean includeFrame) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        SmbPreviewSource source = null;
        long duration = 0;
        try {
            if (previewsPaused) return new Preview(null, 0);
            if (context != null) {
                source = new SmbPreviewSource(url, context);
                activePreviewSource = source;
                if (previewsPaused) source.cancel();
                retriever.setDataSource(source);
            } else retriever.setDataSource(this, Uri.parse(url));
            String length = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration = length == null ? 0 : Long.parseLong(length);
            android.graphics.Bitmap frame = includeFrame && android.os.Build.VERSION.SDK_INT >= 27
                    ? retriever.getScaledFrameAtTime(context == null ? 1000000 : 0,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) : null;
            return new Preview(frame, duration);
        } catch (IOException | RuntimeException e) {
            return new Preview(null, duration);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
            if (activePreviewSource == source) activePreviewSource = null;
            if (source != null) {
                try { source.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static final class Preview {
        final android.graphics.Bitmap image;
        final long duration;
        Preview(android.graphics.Bitmap image,long duration) {this.image=image;this.duration=duration;}
    }

    private void up() {
        if (returnFromPlaylist != null) {
            Runnable restore = returnFromPlaylist;
            returnFromPlaylist = null;
            restore.run();
            return;
        }
        if (nfsBrowsing) {
            if (localParents.isEmpty()) {
                ++request;
                showSetup("");
            } else loadNfs(localParents.pop(), currentUrl);
            return;
        }
        if (localTree != null && !localParents.isEmpty()) {
            loadLocal(localParents.pop(), currentUrl, false);
            return;
        }
        if (localTree != null || playlistSource) {
            ++request;
            localTree = null;
            playlistSource = false;
            showSetup("");
            return;
        }
        if (currentUrl == null || currentUrl.equals(rootUrl)) {
            ++request;
            showSetup("");
            return;
        }
        String parent = currentUrl.substring(0, currentUrl.lastIndexOf('/', currentUrl.length() - 2) + 1);
        load(parent, currentUrl);
    }

    private void openVideo(Entry entry) {
        if(!playing)rememberBrowserScroll();
        openVideo(entry, !entry.url.startsWith("smb://") || settings.getBoolean("nativeSmb", false), false);
    }

    private void openVideo(Entry entry, boolean direct, boolean softwareRetry) {
        ((PlaybackPreferences) settings).begin(entry.url);
        if(playbackLoop<0)playbackLoop=Math.floorMod(settings.getInt("loop",0),3);
        playing = true;
        keepControlsHidden = startWithChromeHidden;
        startWithChromeHidden = false;
        exitPromptAt = 0;
        previewsPaused = true;
        metadataGeneration++;
        ((java.util.concurrent.ThreadPoolExecutor) previews).getQueue().clear();
        SmbPreviewSource preview = activePreviewSource;
        if (preview != null) preview.cancel();
        publishMediaState(android.media.session.PlaybackState.STATE_BUFFERING);
        mediaSession.setActive(true);
        int token = ++playbackRequest;
        playingEntry = entry;
        videoKey = entry.url;
        resumeAt = resumeMode() == 0 ? 0 : history.getLong(videoKey, 0);
        observedPosition = 0;
        observedDuration = 0;
        buffering = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        VLCVideoLayout video = new VLCVideoLayout(this);
        videoLayout = video;
        frame.addView(video, new FrameLayout.LayoutParams(-1, -1));
        video.setOnClickListener(v -> { exitPromptAt = 0; if (controlsShown) { ui.removeCallbacks(hideControls); hideControls.run(); } else { showControls(); pause.requestFocusFromTouch(); } });
        video.setFocusable(false);
        video.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        controls = column();
        controlsShown = !keepControlsHidden;
        controls.setPadding(dp(24), dp(4), dp(24), dp(8));
        controls.setBackgroundColor(0xd1121212);
        TextView title = text(entry.name, 32);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(8), dp(24), dp(8));
        header.setBackgroundColor(0xcc121212);

        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.setMargins(dp(16), 0, 0, 0);
        playerBack = button("返回", () -> leaveVideo(""));
        header.addView(playerBack);
        header.addView(title, titleSpace);
        frame.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setOrientation(LinearLayout.VERTICAL);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setBackground(buttonBackground(0xd1181818, 0xff464646));
        loadingOverlay.setPadding(dp(28), dp(20), dp(28), dp(20));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        loadingOverlay.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        loadingText = text("正在缓冲", 24);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setSingleLine(false);
        loadingText.setEllipsize(null);
        LinearLayout.LayoutParams loadingLabel = new LinearLayout.LayoutParams(-1, -2);
        loadingLabel.topMargin = dp(12);
        loadingOverlay.addView(loadingText, loadingLabel);
        frame.addView(loadingOverlay, new FrameLayout.LayoutParams(
                Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(48)), -2, Gravity.CENTER));
        playbackNotice = text("", 26);
        playbackNotice.setVisibility(View.GONE);
        playbackNotice.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        playbackNotice.setBackground(buttonBackground(0xd1181818, 0xff464646));
        playbackNotice.setPadding(dp(18), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams noticePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        frame.addView(playbackNotice, noticePosition);
        timeline = new SeekBar(this) {
            private final android.graphics.Paint cachePaint = new android.graphics.Paint();

            @Override public boolean onTouchEvent(android.view.MotionEvent event) {
                if (!isEnabled()) return true;
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    seekTouchCancelled = false;
                    requestFocusFromTouch();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == android.view.MotionEvent.ACTION_CANCEL) seekTouchCancelled = true;
                boolean handled = super.onTouchEvent(event);
                if (handled && action == android.view.MotionEvent.ACTION_UP) performClick();
                if ((action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) && getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(false);
                return handled;
            }

            @Override public boolean performClick() { return super.performClick(); }

            @Override protected synchronized void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (player == null || player.getLength() <= 0) return;
                long duration = player.getLength();
                float trackLeft = getPaddingLeft();
                float trackWidth = getWidth() - trackLeft - getPaddingRight();
                float cursor = trackLeft + trackWidth * getProgress() / getMax();
                float thumbRadius = getThumb() == null ? dp(10) : getThumb().getBounds().width() / 2f;
                cachePaint.setColor(SECONDARY);
                for (long[] span : cachedTimeline) {
                    float from = Math.max(cursor + thumbRadius, trackLeft + trackWidth * span[0] / duration);
                    float to = Math.min(trackLeft + trackWidth, trackLeft + trackWidth * span[1] / duration);
                    if (to > from) canvas.drawRect(from, getHeight() / 2f - dp(3), to, getHeight() / 2f + dp(3), cachePaint);
                }

            }
        };
        timeline.setMax(1000);
        timeline.setProgressDrawable(tvProgressTrack());
        timeline.setThumbTintList(ColorStateList.valueOf(ACCENT));
        timeline.setFocusable(true);
        timeline.setFocusableInTouchMode(true);
        timeline.setMinimumHeight(dp(48));
        android.graphics.drawable.StateListDrawable timelineBackground = new android.graphics.drawable.StateListDrawable();
        timelineBackground.addState(new int[] {android.R.attr.state_focused}, buttonBackground(0xff292929, ACCENT));
        timelineBackground.addState(new int[] {}, buttonBackground(Color.TRANSPARENT, Color.TRANSPARENT));
        timeline.setBackground(timelineBackground);
        timeline.setContentDescription("播放进度");
        timeline.setEnabled(false);
        playbackStatus = text("— / —", 20);
        playbackStatus.setTextColor(SECONDARY);
        controls.addView(playbackStatus);
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, dp(48)));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                videoRecovery.cancel();frameCheckPending=false;buffering=false;loadingOverlay.setVisibility(View.GONE);
                dragResumeDesired=!pauseRequested;
                if(prepared&&dragResumeDesired){gateAudio(player);player.pause();}
                ui.removeCallbacks(applySeek); pendingSeek = -1;
                dragging = true;
                ui.removeCallbacks(hideControls);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared && player.isSeekable() && !seekTouchCancelled) {
                    long length = player.getLength();
                    performSeek(Math.max(0, Math.min(length - 1, length * bar.getProgress() / bar.getMax())));
                } else if (prepared) {
                    updateTimeline(player.getTime());
                    ui.removeCallbacks(hideNotice); playbackNotice.setVisibility(View.GONE);
                }
                if(prepared&&dragResumeDesired){if(!videoRecovery.active())beginFrameCheck(Math.max(0,player.getTime()),true);player.play();}
                dragResumeDesired=false;
                showControls();
            }

            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && prepared) {
                    long target = player.getLength() * progress / bar.getMax();
                    showTime(target, player.getLength());
                    if (dragging) flashNotice("定位 " + time(target));
                }
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.HorizontalScrollView controlScroll = new android.widget.HorizontalScrollView(this);
        controlScroll.setFocusable(false); controlScroll.setHorizontalScrollBarEnabled(false);
        controlScroll.addView(row); controls.addView(controlScroll);
        pause = button("暂停", this::togglePlayback);
        setPlaybackButton(true);
        playlist = button("选集", this::showPlaylist);
        more = button("更多", this::showPlaybackOptions);
        aspectButton = button("比例", this::showAspectOptions);
        speedButton = button(speedLabel(), this::showSpeed);
        for (Button action : new Button[] {pause, playlist, aspectButton, speedButton, more}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Math.max(dp(80), (getResources().getDisplayMetrics().widthPixels - dp(88)) / 5), dp(54));
            params.setMargins(dp(4), dp(2), dp(4), 0);
            action.setTextSize(20); action.setPadding(dp(4), dp(4), dp(4), dp(4));
            row.addView(action, params);
        }
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        loadingOverlay.bringToFront();
        downloadRate.reset();rateSource=null;networkRate=0;nativeReadEpoch=lastNativeBytes=0;
        loadingDue=0;ui.removeCallbacks(loadingTick);ui.post(loadingTick);
        timeline.setId(View.generateViewId());
        timeline.setNextFocusUpId(playerBack.getId());
        playerBack.setNextFocusDownId(timeline.getId()); playerBack.setNextFocusUpId(playerBack.getId());
        playerBack.setNextFocusLeftId(playerBack.getId()); playerBack.setNextFocusRightId(playerBack.getId());
        timeline.setNextFocusLeftId(timeline.getId()); timeline.setNextFocusRightId(timeline.getId());
        timeline.setNextFocusDownId(pause.getId());
        for (Button action : new Button[] {pause, playlist, aspectButton, speedButton, more}) {
            action.setNextFocusUpId(timeline.getId());
        }
        Button[] playbackButtons={pause,playlist,aspectButton,speedButton,more};
        for(int i=0;i<playbackButtons.length;i++) {
            Button action=playbackButtons[i];action.setNextFocusLeftId(playbackButtons[Math.max(0,i-1)].getId());action.setNextFocusRightId(playbackButtons[Math.min(playbackButtons.length-1,i+1)].getId());action.setNextFocusDownId(action.getId());
        }
        updateQueueButtons();
        setContentView(frame);
        header.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        controls.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        if (controlsShown) pause.requestFocusFromTouch();

        long openingStarted = android.os.SystemClock.elapsedRealtime();
        long[] lastOpeningRead = {0};
        openingTimeout = () -> {
            if (token != playbackRequest || prepared) return;
            // Fragmented containers may need many remote index reads before Playing.
            // Extend only while the stream is actually progressing, with a hard limit.
            if (!direct && playbackServer != null
                    && android.os.SystemClock.elapsedRealtime() - openingStarted < 90000) {
                long bytes = playbackServer.streamedRemoteBytes();
                if (bytes > lastOpeningRead[0]) {
                    lastOpeningRead[0] = bytes;
                    loadingText.setText(bufferingLabel());
                    ui.postDelayed(openingTimeout, 5000);
                    return;
                }
            }
            if (direct && entry.url.startsWith("smb://") && connection != null) {
                boolean hidden = !controlsShown;
                stopVideo();
                startWithChromeHidden = hidden;
                openVideo(entry, false, softwareRetry);
            } else {
                showPlaybackFailure("等待视频数据超时，请检查网络连接。");
            }
        };
        ui.postDelayed(openingTimeout, direct ? 15000 : 25000);
        if (direct) {
            startVideo(video, entry.url, entry.url.startsWith("smb://") ? entry : null, softwareRetry);
            loadSidecarSubtitles(entry, token);
            return;
        }
        CIFSContext context = connection;
        network.execute(() -> {
            try {
                SmbPlaybackServer server = new SmbPlaybackServer(entry.url, context, getCacheDir(),
                        settings.getBoolean("smbReadAhead", true));
                ui.post(()->{if(token==playbackRequest)playbackServer=server;else server.stop();});
                String lowerName = entry.name.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v") || lowerName.endsWith(".mov"))
                    server.awaitContainerHint();
                ui.post(() -> {
                    if (token != playbackRequest) {
                        server.stop();
                        return;
                    }
                    playbackServer = server;
                    startVideo(video, server.url(), null, softwareRetry);
                    loadSidecarSubtitles(entry, token);
                });
            } catch (Exception e) {
                android.util.Log.w("SmbPlayer", "Cannot open SMB video", e);
                ui.post(() -> {
                    if (token == playbackRequest) showPlaybackFailure("无法打开视频，请检查共享连接和读取权限。");
                });
            }
        });
    }

    private void startVideo(VLCVideoLayout video, String url, Entry directEntry, boolean softwareRetry) {
        try {
            currentSoftwareRetry = softwareRetry;
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=3500");
            options.add("--stats");
            if (android.util.Log.isLoggable("SmbPlayerVlc", android.util.Log.DEBUG)) options.add("--verbose=2");
            options.add("--freetype-rel-fontsize=" + settings.getInt("subtitleSize", 14));
            options.add("--freetype-outline-thickness=6");
            options.add("--freetype-outline-color=0");
            options.add("--freetype-shadow-opacity=120");
            options.add("--freetype-opacity=255");
            options.add("--freetype-color=16777215");
            addVideoFilters(options);
            vlc = new LibVLC(this, options);
            MediaPlayer opened = new MediaPlayer(vlc);
            player = opened;
            opened.setVolume(100);
            opened.attachViews(video,null,true,false);
            opened.setVideoScale(SCALES[settings.getInt("scale", 0)]);
            digitalOutput=AudioOutput.enabled(this,settings.getInt("passthroughMode",0));opened.setAudioDigitalOutputEnabled(digitalOutput);
            opened.setEventListener(event -> {
                if (player != opened) return;
                switch (event.type) {
                    case MediaPlayer.Event.Buffering:
                        if(pauseRequested)break;
                        if(event.getBuffering()<100){
                            if(prepared&&supportsVideoRecovery(opened)){if(!videoRecovery.active()){frameCheckPosition=Math.max(0,opened.getTime());frameCheckStarted=android.os.SystemClock.elapsedRealtime();recoveryRebuilds=0;}videoRecovery.begin(android.os.SystemClock.elapsedRealtime());gateAudio(opened);}
                            showBuffering(false);
                        }else if(buffering){
                            if(prepared&&supportsVideoRecovery(opened)){if(!videoRecovery.active())videoRecovery.begin(android.os.SystemClock.elapsedRealtime());awaitVideoFrames(opened);}
                            else if(prepared){buffering=false;loadingOverlay.setVisibility(View.GONE);releaseAudioGate();}
                        }
                        break;
                    case MediaPlayer.Event.Playing:
                        pausedPlayback=pauseRequested;
                        observedDuration = Math.max(observedDuration, opened.getLength());
                        ui.removeCallbacks(openingTimeout);
                        if (!prepared) {
                            prepared = true;
                            opened.setRate(settings.getInt("speedSteps", 20) / 20f);
                            opened.setAudioDelay(settings.getLong("audioDelay", 0));
                            opened.setSpuDelay(settings.getLong("subtitleDelay", 0));
                            android.util.Log.d("SmbPlayer", directEntry != null ? "VLC SMB playback started" : "VLC playback started");
                            for (java.io.File subtitle : waitingSubtitles) addSubtitleToPlayer(subtitle);
                            waitingSubtitles.clear();
                            timeline.invalidate();
                            ui.post(tick);
                        }
                        timeline.setEnabled(opened.isSeekable());
                        restorePlayback();
                        restoreTrackSelection(opened);
                        if(pauseRequested){opened.pause();setPlaybackButton(false);break;}
                        if(supportsVideoRecovery(opened)){
                            if(!videoRecovery.active()&&frameCheckPending)beginFrameCheck(Math.max(0,opened.getTime()),true);
                            else if(videoRecovery.active())awaitVideoFrames(opened);
                        }else{buffering=false;loadingOverlay.setVisibility(View.GONE);releaseAudioGate();}
                        setPlaybackButton(true);
                        publishMediaState(buffering?android.media.session.PlaybackState.STATE_BUFFERING:android.media.session.PlaybackState.STATE_PLAYING);
                        if(!buffering&&!keepControlsHidden)showControls();
                        break;
                    case MediaPlayer.Event.Vout:
                        break;
                    case MediaPlayer.Event.ESAdded:
                        if (prepared) {restoreTrackSelection(opened);if(frameCheckPending&&!pauseRequested&&supportsVideoRecovery(opened))beginFrameCheck(Math.max(0,opened.getTime()),true);}
                        break;
                    case MediaPlayer.Event.SeekableChanged:
                        timeline.setEnabled(prepared && opened.isSeekable());
                        restorePlayback();
                        break;
                    case MediaPlayer.Event.Paused:
                        if(!pauseRequested&&!restorePaused)break;
                        videoRecovery.cancel();
                        pausedPlayback = true;
                        buffering = false;
                        loadingOverlay.setVisibility(View.GONE);
                        setPlaybackButton(false);
                        publishMediaState(android.media.session.PlaybackState.STATE_PAUSED);
                        if (!keepControlsHidden) showControls();
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        if (event.getTimeChanged() >= 0) observedPosition = event.getTimeChanged();
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        if (event.getLengthChanged() > 0) observedDuration = event.getLengthChanged();
                        break;
                    case MediaPlayer.Event.EndReached:
                        long endTolerance = Math.max(2500, Math.min(10000, observedDuration / 20));
                        if ((directEntry != null || playbackServer != null) && observedDuration > endTolerance
                                && Math.max(observedPosition, opened.getTime()) < observedDuration - endTolerance) {
                            if (directEntry != null && connection != null) {
                                long position = Math.max(observedPosition, resumeAt);
                                boolean paused = restorePaused || pausedPlayback;
                                android.util.Log.d("SmbPlayer", "SMB ended early; switching to compatible transport");
                                reopenVideo(directEntry, false, softwareRetry, position, paused, false);
                            } else showPlaybackFailure("共享连接提前中断，请重试。");
                            break;
                        }
                        history.edit().remove(videoKey).apply();
                        videoKey = null;
                        stepVideo(1, true);
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        if (directEntry != null && connection != null && !prepared) {
                            boolean hidden = !controlsShown;
                            stopVideo();
                            startWithChromeHidden = hidden;
                            openVideo(directEntry, false, softwareRetry);
                        } else if (!softwareRetry && !settings.getBoolean("softwareDecode", false) && playingEntry != null) {
                            Entry retry = playingEntry;
                            boolean direct = playbackServer == null;
                            long position = Math.max(resumeAt, observedPosition);
                            boolean ask = resumeMode() == 2 && !resumeWithoutPrompt && resumeAt > 0;
                            reopenVideo(retry, direct, true, position, !ask && (restorePaused || pausedPlayback), ask);
                        } else {
                            showPlaybackFailure("视频无法继续播放。请检查连接或切换软件解码。");
                        }
                        break;
                    default:
                        break;
                }
            });
            Media media = openMedia(url);
            try {
                configureVideo(media, softwareRetry);
                if (directEntry != null) {
                    String account = connectionUser;
                    int separator = account.indexOf('\\');
                    if (separator >= 0) media.addOption(":smb-domain=" + account.substring(0, separator));
                    if (!account.isEmpty()) {
                        media.addOption(":smb-user=" + account.substring(separator + 1));
                        media.addOption(":smb-pwd=" + passwordForSession);
                    }
                }
                opened.setMedia(media);
            } finally {
                media.release();
            }
            frameCheckPending=true;gateAudio(opened);opened.play();
        } catch (IOException | RuntimeException | LinkageError e) {
            android.util.Log.w("SmbPlayer", "Cannot initialize VLC", e);
            showPlaybackFailure("无法初始化 VLC 播放引擎。");
        }
    }

    private boolean supportsVideoRecovery(MediaPlayer opened) {
        if(playingEntry==null)return false;
        org.videolan.libvlc.interfaces.IMedia.VideoTrack track = opened.getCurrentVideoTrack();
        // Do not mistake still images, very low frame-rate slideshows, or audio-only media for a frozen decoder.
        if(track==null)return videoTrackKnown;
        boolean supported=track.frameRateDen<=0||track.frameRateNum<=0||track.frameRateNum/(double)track.frameRateDen>=2;
        if(supported)videoTrackKnown=true;return supported;
    }

    private void restoreTrackSelection(MediaPlayer opened) {
        if(restoreAudioTrack!=Integer.MIN_VALUE){if(gatedPlayer==opened&&digitalOutput){gatedTrack=restoreAudioTrack;restoreAudioTrack=Integer.MIN_VALUE;}else if(opened.setAudioTrack(restoreAudioTrack))restoreAudioTrack=Integer.MIN_VALUE;}
        // External subtitle streams may only become selectable after addSlave has completed.
        if (restoreSubtitleTrack != Integer.MIN_VALUE && opened.setSpuTrack(restoreSubtitleTrack))
            restoreSubtitleTrack = Integer.MIN_VALUE;
    }

    private org.videolan.libvlc.interfaces.IMedia.Stats videoStats(MediaPlayer opened) {
        org.videolan.libvlc.interfaces.IMedia media = opened.getMedia();
        if (media == null) return null;
        try { return media.getStats(); } finally { media.release(); }
    }

    private String bufferingLabel(){
        boolean remote=playingEntry!=null&&(playingEntry.url.startsWith("smb://")||isNetworkMedia(playingEntry.url));
        return remote?"正在缓冲 · "+DownloadRate.format(networkRate):"正在缓冲";
    }
    private void updateNetworkRate(){
        Object source=playbackServer!=null?playbackServer:player;long bytes=0;
        if(source!=rateSource){rateSource=source;downloadRate.reset();nativeReadEpoch=lastNativeBytes=0;}
        if(playbackServer!=null)bytes=playbackServer.streamedRemoteBytes();
        else if(prepared&&player!=null){org.videolan.libvlc.interfaces.IMedia.Stats stats=videoStats(player);if(stats!=null){long current=Integer.toUnsignedLong(stats.readBytes);if(current<lastNativeBytes&&lastNativeBytes-current>0x80000000L)nativeReadEpoch+=0x100000000L;lastNativeBytes=current;bytes=nativeReadEpoch+current;}}
        networkRate=downloadRate.sample(bytes,android.os.SystemClock.elapsedRealtime());
    }
    private void gateAudio(MediaPlayer opened){
        if(gatedPlayer==opened){if(digitalOutput&&opened.getAudioTrack()>=0){if(gatedTrack==Integer.MIN_VALUE)gatedTrack=opened.getAudioTrack();opened.setAudioTrack(-1);}return;}
        gatedPlayer=opened;gatedVolume=opened.getVolume();if(gatedVolume<=0)gatedVolume=100;gatedTrack=Integer.MIN_VALUE;
        if(digitalOutput&&opened.getAudioTrack()>=0){gatedTrack=opened.getAudioTrack();opened.setAudioTrack(-1);}
        opened.setVolume(0);
    }
    private void releaseAudioGate(){
        MediaPlayer owner=gatedPlayer;int volume=gatedVolume,track=gatedTrack;
        gatedPlayer=null;gatedVolume=-1;gatedTrack=Integer.MIN_VALUE;
        if(owner!=null&&owner==player){if(track!=Integer.MIN_VALUE)owner.setAudioTrack(track);if(volume>=0)owner.setVolume(volume);}
    }
    private void showBuffering(boolean delayed){
        boolean wasWaiting=buffering;buffering=true;ui.removeCallbacks(hideControls);
        if(!wasWaiting)loadingDue=android.os.SystemClock.elapsedRealtime()+(delayed?350:0);
        if(!delayed){loadingDue=0;loadingText.setText(bufferingLabel());loadingOverlay.setVisibility(View.VISIBLE);}
    }
    private void beginFrameCheck(long target,boolean delayed){
        if(!prepared||player==null||pauseRequested)return;
        org.videolan.libvlc.interfaces.IMedia.Stats stats=videoStats(player);
        if(stats==null){frameCheckPending=false;releaseAudioGate();return;}
        long now=android.os.SystemClock.elapsedRealtime();frameCheckPosition=Math.max(0,target);frameCheckStarted=now;frameCheckPending=false;
        if(!retainingRecovery)recoveryRebuilds=0;retainingRecovery=false;
        gateAudio(player);videoRecovery.expect(now,outputPictures(stats,now),stats.playedAbuffers,player.getTime());
        showBuffering(delayed);
    }
    private int outputPictures(org.videolan.libvlc.interfaces.IMedia.Stats stats,long now){
        if(stats.displayedPictures>0)displayedCounterSeen=true;
        if(!displayedCounterSeen&&!decodedCounterFallback&&stats.decodedVideo>=4&&now-frameCheckStarted>=2500){decodedCounterFallback=true;android.util.Log.i("SmbPlayer","Renderer counter unavailable; using decoded frame progress");}
        return displayedCounterSeen||!decodedCounterFallback?stats.displayedPictures:stats.decodedVideo;
    }
    private void awaitVideoFrames(MediaPlayer opened){
        org.videolan.libvlc.interfaces.IMedia.Stats stats=videoStats(opened);if(stats==null){cancelVideoRecovery();return;}
        videoRecovery.ready(android.os.SystemClock.elapsedRealtime(),outputPictures(stats,android.os.SystemClock.elapsedRealtime()),stats.playedAbuffers,opened.getTime());
    }
    private void cancelVideoRecovery(){videoRecovery.cancel();frameCheckPending=false;buffering=false;if(loadingOverlay!=null)loadingOverlay.setVisibility(View.GONE);if(!pauseRequested)releaseAudioGate();}
    private void performSeek(long target){performSeek(target,true);}
    private void performSeek(long target,boolean newRequest){
        if(!prepared||player==null||!player.isSeekable())return;
        target=Math.max(0,Math.min(Math.max(0,player.getLength()-1),target));
        cancelVideoRecovery();frameCheckPosition=target;frameCheckPending=true;
        if(newRequest){recoveryRebuilds=0;retainingRecovery=false;}else retainingRecovery=true;
        if(playbackServer!=null)playbackServer.prepareSeek(target);
        if(!pauseRequested)gateAudio(player);
        player.setTime(target,false);
        if(!pauseRequested&&supportsVideoRecovery(player))beginFrameCheck(target,true);
    }
    private void requestPause(boolean paused){
        pauseRequested=paused;pausedPlayback=paused;
        if(!prepared||player==null){restorePaused=paused;return;}
        if(paused){player.pause();videoRecovery.cancel();frameCheckPending=true;buffering=false;loadingOverlay.setVisibility(View.GONE);}
        else{frameCheckPending=true;if(supportsVideoRecovery(player))beginFrameCheck(Math.max(0,player.getTime()),true);player.play();}
        setPlaybackButton(!paused);
    }
    /** Returns true only when a new player replaced the old tick owner. */
    private boolean monitorVideoRecovery(long position){
        if(player==null||!prepared)return false;
        if(pauseRequested||dragging||pendingSeek>=0){videoRecovery.cancel();return false;}
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastFramePoll<200)return false;lastFramePoll=now;
        if(!supportsVideoRecovery(player)){if(gatedPlayer==player){buffering=false;loadingOverlay.setVisibility(View.GONE);releaseAudioGate();}return false;}
        MediaPlayer opened=player;org.videolan.libvlc.interfaces.IMedia.Stats stats=videoStats(opened);
        if(stats==null){if(videoRecovery.active()&&now-frameCheckStarted>10000)cancelVideoRecovery();return false;}
        VideoRecovery.Action action=videoRecovery.sample(now,outputPictures(stats,now),stats.playedAbuffers,position,opened.isSeekable(),false);
        if(action==VideoRecovery.Action.STALLED){frameCheckPosition=Math.max(0,videoRecovery.frozenPosition());frameCheckStarted=now;recoveryRebuilds=0;gateAudio(opened);showBuffering(false);}
        else if(action==VideoRecovery.Action.RECOVERED){buffering=false;loadingOverlay.setVisibility(View.GONE);releaseAudioGate();publishMediaState(android.media.session.PlaybackState.STATE_PLAYING);if(controlsShown)showControls();}
        else if(action==VideoRecovery.Action.RESYNC){if(playbackServer!=null)playbackServer.prepareSeek(frameCheckPosition);opened.setTime(frameCheckPosition,false);awaitVideoFrames(opened);}
        else if(action==VideoRecovery.Action.REOPEN){
            if(recoveryRebuilds>=1){showPlaybackFailure("画面未能恢复，请重试或在兼容性设置中切换解码方式。");return true;}
            int rebuilds=recoveryRebuilds+1,audio=gatedTrack!=Integer.MIN_VALUE?gatedTrack:opened.getAudioTrack(),subtitle=opened.getSpuTrack();Entry entry=playingEntry;
            boolean direct=playbackServer==null,software=currentSoftwareRetry;long target=opened.isSeekable()?frameCheckPosition:0;
            reopenVideo(entry,direct,software,target,false,false,new ArrayList<>(subtitleFiles));
            recoveryRebuilds=rebuilds;retainingRecovery=true;restoreAudioTrack=audio;restoreSubtitleTrack=subtitle;return true;
        }else if(action==VideoRecovery.Action.FAILED){showPlaybackFailure("等待视频画面超时，请检查连接后重试。");return true;}
        return false;
    }

    private void publishMediaState(int state) {
        long actions = android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                | android.media.session.PlaybackState.ACTION_PLAY
                | android.media.session.PlaybackState.ACTION_PAUSE
                | android.media.session.PlaybackState.ACTION_STOP
                | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | android.media.session.PlaybackState.ACTION_FAST_FORWARD
                | android.media.session.PlaybackState.ACTION_REWIND
                | android.media.session.PlaybackState.ACTION_SEEK_TO;
        long position = prepared && player != null ? Math.max(0, player.getTime()) : 0;
        float speed = state == android.media.session.PlaybackState.STATE_PLAYING && player != null
                ? player.getRate() : 0;
        mediaSession.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(actions).setState(state, position, speed).build());
    }

    private void showPlaybackFailure(String message) {
        Entry failed = playingEntry;
        leaveVideo(message);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("播放失败").setMessage(message)
                .setNegativeButton("返回目录", null)
                .setPositiveButton("重试", (ignored, choice) -> {
                    if (failed != null) openVideo(failed);
                }).create();
        showPlaybackDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void restorePlayback() {
        if (playbackDialog != null || !prepared) return;
        if(resumeAt<=0&&restorePaused){restorePaused=false;requestPause(true);}
        if (resumeAt <= 0 || !player.isSeekable() || player.getLength() <= 0) return;
        long requestedPosition = resumeAt;
        resumeAt = 0;
        if (requestedPosition >= player.getLength() - 5000 && !resumeWithoutPrompt) return;
        final long position = Math.min(requestedPosition, Math.max(0, player.getLength() - 1));
        if (resumeMode() == 2 && !resumeWithoutPrompt) {
            resumeAt = position;
            requestPause(true);
            int token = playbackRequest;
            AlertDialog prompt = new AlertDialog.Builder(this).setTitle("继续上次播放？")
                    .setMessage("上次播放到 " + time(position))
                    .setNegativeButton("从头播放", (dialog, which) -> {
                        if (token == playbackRequest && prepared) {
                            resumeAt = 0;
                            history.edit().remove(videoKey).apply();
                            requestPause(false);
                        }
                    }).setPositiveButton("继续播放", (dialog, which) -> {
                        if (token == playbackRequest && prepared) {
                            resumeAt = 0;
                            performSeek(position,false);
                            requestPause(false);
                        }
                    }).create();
            prompt.setOnCancelListener(dialog -> {
                if (token == playbackRequest && prepared) leaveVideo("");
            });
            showPlaybackDialog(prompt);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        } else {
            performSeek(position,false);
            updateTimeline(position);
            if(restorePaused){restorePaused=false;requestPause(true);}
        }
    }

    private int resumeMode() {
        return settings.getInt("resumeMode", settings.getBoolean("resume", true) ? 2 : 0);
    }

    private void savePlayback() {
        if (resumeMode() == 0 || videoKey == null || !prepared || player == null || resumeAt > 0) return;
        long position = player.getTime();
        long duration = player.getLength();
        if (position < 0 || duration <= 0) return;
        SharedPreferences.Editor edit = history.edit();
        if (position < 5000 || position >= duration - 5000) edit.remove(videoKey);
        else {
            edit.putLong(videoKey, position);
            List<String> recent = new ArrayList<>();
            recent.add(videoKey);
            try {
                org.json.JSONArray stored = new org.json.JSONArray(history.getString("recentOrder", "[]"));
                for (int i = 0; i < stored.length(); i++) {
                    String key = stored.getString(i);
                    if (!key.equals(videoKey)) recent.add(key);
                }
            } catch (org.json.JSONException ignored) {}
            int limit = 100;
            org.json.JSONArray kept = new org.json.JSONArray();
            for (int i = 0; i < recent.size(); i++) {
                if (i < limit) kept.put(recent.get(i));
                else edit.remove(recent.get(i));
            }
            edit.putString("recentOrder", kept.toString());
        }
        edit.apply();
    }

    private List<Entry> videoEntries() {
        List<Entry> videos=new ArrayList<>();
        for(Entry entry:entries)if(!entry.folder && (playlistSource || !isPlaylist(entry.name)))videos.add(entry);
        return videos;
    }

    private int videoIndex(List<Entry> videos) {
        for (int i = 0; i < videos.size(); i++) {
            if (videos.get(i).url.equals(playingEntry == null ? focusedUrl : playingEntry.url)) return i;
        }
        return -1;
    }

    private void playEntry(Entry entry) {
        ((PlaybackPreferences) settings).end();
        boolean hidden = playing && !controlsShown;
        stopVideo();
        startWithChromeHidden = hidden;
        focusedUrl = entry.url;
        openVideo(entry);
    }

    private void restartVideo(){
        Entry entry=playingEntry;if(entry==null)return;
        long position=prepared&&player!=null?Math.max(0,player.getTime()):Math.max(0,resumeAt);boolean paused=pausedPlayback||restorePaused;
        int audio=prepared?player.getAudioTrack():Integer.MIN_VALUE,subtitle=prepared?player.getSpuTrack():Integer.MIN_VALUE;
        reopenVideo(entry,!entry.url.startsWith("smb://")||settings.getBoolean("nativeSmb",false),false,position,paused,false,new ArrayList<>(subtitleFiles));
        restoreAudioTrack=audio;restoreSubtitleTrack=subtitle;
    }

    private void reopenVideo(Entry entry, boolean direct, boolean software, long position, boolean paused, boolean ask) {
        reopenVideo(entry, direct, software, position, paused, ask, java.util.Collections.emptyList());
    }

    private void reopenVideo(Entry entry, boolean direct, boolean software, long position, boolean paused, boolean ask,
                             List<java.io.File> retainedSubtitles) {
        boolean hidden = !controlsShown;
        // Transfer ownership before stopVideo schedules deletion of the old player's temporary files.
        subtitleFiles.removeAll(retainedSubtitles);
        stopVideo();
        retainedSubtitleSession = !retainedSubtitles.isEmpty();
        subtitleFiles.addAll(retainedSubtitles);
        waitingSubtitles.addAll(retainedSubtitles);
        resumeWithoutPrompt = !ask;
        startWithChromeHidden = hidden;
        openVideo(entry, direct, software);
        if (playing && playingEntry == entry) {
            resumeAt = Math.max(0, position);
            restorePaused=paused;pauseRequested=paused;pausedPlayback=paused;
        }
    }

    private void stepVideo(int direction, boolean completed) {
        List<Entry> videos = videoEntries();
        int index = videoIndex(videos);
        int mode = playbackLoop;
        int target = index + direction;
        if (completed && mode == 1) target = index;
        else if (mode == 2 && !videos.isEmpty()) target = (target + videos.size()) % videos.size();
        if (target >= 0 && target < videos.size()) playEntry(videos.get(target));
        else if (completed) leaveVideo("");
    }

    private void showPlaylist() {
        if (playbackDialog != null) return;
        hideControls.run();
        List<Entry> videos = videoEntries();
        int current = videoIndex(videos);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), dp(16));
        LinearLayout toolbar = new LinearLayout(this);
        Button loop = button(loopLabel(), () -> {});
        Button[] tools = {loop};
        for (int i = 0; i < tools.length; i++) {
            tools[i].setMinWidth(0); tools[i].setMinimumWidth(0);
            toolbar.addView(tools[i], new LinearLayout.LayoutParams(0, dp(56), i == 0 ? 1.5f : i == 1 ? 1.5f : 1f));
        }
        spaceActions(toolbar); panel.addView(toolbar);
        android.widget.ListView list = new android.widget.ListView(this);
        list.setId(View.generateViewId()); list.setFocusableInTouchMode(true);
        list.setItemsCanFocus(false); list.setDrawSelectorOnTop(true);
        list.setSelector(buttonBackground(0x2268c9dd, ACCENT));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(8));
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.BaseAdapter rows = new android.widget.BaseAdapter() {
            @Override public int getCount() { return videos.size(); }
            @Override public Entry getItem(int position) { return videos.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : null;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(14), dp(5), dp(14), dp(5));
                    row.setMinimumHeight(dp(64));
                    LinearLayout firstLine = new LinearLayout(MainActivity.this); firstLine.setGravity(Gravity.CENTER_VERTICAL);
                    TextView title = text("", 24);
                    title.setId(android.R.id.text1);
                    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    title.setSingleLine();
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    firstLine.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView duration = text("", 20);
                    duration.setTextColor(SECONDARY);
                    duration.setPadding(dp(8), 0, 0, 0);
                    firstLine.addView(duration); row.addView(firstLine);
                }
                Entry entry = getItem(position);
                LinearLayout firstLine = (LinearLayout) row.getChildAt(0);
                TextView title = (TextView) firstLine.getChildAt(0);
                title.setText(entry.name);
                boolean active = playingEntry != null && entry.url.equals(playingEntry.url);
                title.setTextColor(active ? ACCENT : Color.WHITE);
                long length = entry.duration;
                Preview preview = previewCache.get(entry.url);
                if (length <= 0 && preview != null) length = preview.duration;
                if (length <= 0 && active) length = observedDuration;
                TextView duration = (TextView) firstLine.getChildAt(1);
                duration.setVisibility(length > 0 ? View.VISIBLE : View.GONE);
                if (length > 0) {
                    String formatted = time(length);
                    duration.setText(length < 3600000 ? formatted.substring(3) : formatted);
                }
                return row;
            }
        };
        list.setAdapter(rows);
        AlertDialog playlistPanel = new AlertDialog.Builder(this).setTitle("选集").setView(panel).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = videos.get(position); playlistPanel.dismiss(); playEntry(entry);
        });
        View[] lastTool = {loop};
        for (int i = 0; i < tools.length; i++) {
            Button tool = tools[i]; View.OnFocusChangeListener visual = tool.getOnFocusChangeListener();
            tool.setOnFocusChangeListener((view, focused) -> { if (visual != null) visual.onFocusChange(view, focused); if (focused) lastTool[0] = view; });
            tool.setNextFocusLeftId(tools[Math.max(0, i - 1)].getId());
            tool.setNextFocusRightId(tools[Math.min(tools.length - 1, i + 1)].getId());
            tool.setNextFocusUpId(tool.getId()); tool.setNextFocusDownId(videos.isEmpty() ? tool.getId() : list.getId());
        }
        loop.setContentDescription("循环模式：" + loopLabel());
        loop.setOnClickListener(view -> { showLoopOptions(); loop.setText(loopLabel()); loop.setContentDescription("循环模式：" + loopLabel()); });
        list.setOnKeyListener((view, key, event) -> {
            boolean up = key == KeyEvent.KEYCODE_DPAD_UP && list.getSelectedItemPosition() <= 0;
            boolean edge = key == KeyEvent.KEYCODE_DPAD_DOWN && list.getSelectedItemPosition() >= videos.size() - 1
                    || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (up && event.getAction() == KeyEvent.ACTION_DOWN) lastTool[0].requestFocusFromTouch();
            return up || edge;
        });
        list.setNextFocusUpId(loop.getId()); list.setNextFocusDownId(list.getId());
        list.setNextFocusLeftId(list.getId()); list.setNextFocusRightId(list.getId());
        showSidePanel(playlistPanel, true);
        if (playlistPanel.getWindow() != null) {
            int width = getResources().getDisplayMetrics().widthPixels;
            playlistPanel.getWindow().setLayout(Math.min(width * 45 / 100, Math.max(dp(280), width * 35 / 100)), -1);
        }
        Runnable focusList = () -> {
            if (!playlistPanel.isShowing() || playbackDialog != playlistPanel) return;
            if (videos.isEmpty()) loop.requestFocusFromTouch();
            else { list.requestFocusFromTouch(); list.setSelection(Math.max(0, current)); }
        };
        View root = playlistPanel.getWindow() == null ? null : playlistPanel.getWindow().getDecorView();
        if (root == null || root.hasWindowFocus()) list.post(focusList);
        else root.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean focused) {
                if (!focused) return;
                root.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                list.post(focusList);
            }
        });
    }

    private void updateQueueButtons() {
        if(pause==null || playlist==null || aspectButton==null || speedButton==null || more==null)return;
        Button[] buttons={pause,playlist,aspectButton,speedButton,more};
        for(int i=0;i<buttons.length;i++) {Button b=buttons[i];b.setNextFocusLeftId(buttons[Math.max(0,i-1)].getId());b.setNextFocusRightId(buttons[Math.min(buttons.length-1,i+1)].getId());b.setNextFocusDownId(b.getId());}
    }

    private String loopLabel(){return new String[]{"顺序播放","单集循环","列表循环"}[playing?Math.max(0,playbackLoop):Math.floorMod(settings.getInt("loop",0),3)];}

    private void showLoopOptions(){playbackLoop=(Math.max(0,playbackLoop)+1)%3;flashNotice(loopLabel());}

    private int seekStep() {
        return settings.getInt("seekStep", 10) * 1000;
    }

    private void showJumpOptions() {
        int shortStep = seekStep() / 1000;
        String[] names = {"后退 " + shortStep + " 秒", "前进 " + shortStep + " 秒",
                "后退 1 分钟", "前进 1 分钟", "后退 5 分钟", "前进 5 分钟", "后退 10 分钟", "前进 10 分钟"};
        int[] seconds = {-shortStep, shortStep, -60, 60, -300, 300, -600, 600};
        showPlaybackDialog(new AlertDialog.Builder(this).setTitle("跳转进度")
                .setItems(names, (dialog, choice) -> seek(seconds[choice] * 1000)).create());
    }

    private void showAppSettings() {
        settingsParents.clear(); settingsRenderer=null; settingsPage=true;
        showItems("设置",new String[]{"续播方式","快进 / 快退步长","默认字幕大小","播放结束","兼容性设置","关于"},choice->{
            switch(choice) {
                case 0:showChoices("续播方式",new String[]{"关闭","自动继续","询问是否继续"},resumeMode(),i->settings.edit().putInt("resumeMode",i).apply());break;
                case 1:showChoices("快进 / 快退步长",new String[]{"10 秒","30 秒"},seekStep()==30000?1:0,i->settings.edit().putInt("seekStep",i==1?30:10).apply());break;
                case 2:showSubtitleSize();break;
                case 3:showChoices("播放结束",new String[]{"顺序播放","单集循环","列表循环"},Math.floorMod(settings.getInt("loop",0),3),i->settings.edit().putInt("loop",i).apply());break;
                case 4:showCompatibilitySettings();break;
                case 5:showSettingsInfo("关于","smbplayer 0.28 · 32 位 ARM\n\nLibVLC 3.6.5 · jcifs-ng 2.1.10 · NanoHTTPD 2.3.1\n使用的开源组件按各自许可证分发。");break;
                default:break;
            }
        });
    }

    private void showCompatibilitySettings() {
        showItems("兼容性设置",new String[]{"解码方式","音频透传","SMB 传输"},choice->{
            if(choice==0)showDecoderOptions();
            else if(choice==1)showChoices("音频透传",new String[]{"自动（推荐）","关闭","开启"},settings.getInt("passthroughMode",0),i->settings.edit().putInt("passthroughMode",i).apply());
            else showChoices("SMB 传输",new String[]{"缓存通道（推荐）","原生直连","串行兼容"},settings.getBoolean("nativeSmb",false)?1:settings.getBoolean("smbReadAhead",true)?0:2,i->settings.edit().putBoolean("nativeSmb",i==1).putBoolean("smbReadAhead",i!=2).apply());
        });
    }

    private void showSubtitleSize() {
        int[] sizes={16,14,11};int checked=0;for(int i=0;i<sizes.length;i++)if(settings.getInt("subtitleSize",14)==sizes[i])checked=i;
        showChoices("字幕大小",new String[]{"中","大","超大"},checked,i->{settings.edit().putInt("subtitleSize",sizes[i]).apply();if(playing)restartVideo();});
    }

    private void settingsBack() {
        if (settingsParents.isEmpty()) { showDashboard(); return; }
        Runnable parent = settingsParents.pop(); settingsRenderer = parent; parent.run();
    }

    private LinearLayout settingsSurface(String title) {
        beginPage(); settingsPage = true; browsing = false; sourceTabs.clear();
        LinearLayout page = column(); pageRoot = page;
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回", this::settingsBack); heading.addView(back);
        TextView label = text(title,32); label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,-2,1); labelParams.leftMargin = dp(20);
        heading.addView(label,labelParams); page.addView(heading);
        TextView hint = text("全局默认值 · 新打开的视频使用这些设置",20); hint.setTextColor(SECONDARY);
        hint.setPadding(0,dp(12),0,dp(18)); page.addView(hint);
        setContentView(page); buildingPage=false; return page;
    }

    private void pushSettingsPage(Runnable render) {
        if (settingsRenderer != null) settingsParents.push(settingsRenderer);
        settingsRenderer=render; render.run();
    }

    private void showSettingsList(String title, String[] labels, int current, java.util.function.IntConsumer action, boolean leaf) {
        final int[] focused={Math.max(0,current)};
        pushSettingsPage(() -> {
            LinearLayout page=settingsSurface(title);
            Button back=(Button)((LinearLayout)page.getChildAt(0)).getChildAt(0);
            ScrollView scroll=new ScrollView(this); scroll.setFocusable(false);
            LinearLayout rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
            page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            List<Button> buttons=new ArrayList<>();
            for(int i=0;i<labels.length;i++) {
                final int index=i;
                Button row=button((i==current?"✓  ":"")+labels[i],()->{ action.accept(index); if(leaf) settingsBack(); });
                row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                View.OnFocusChangeListener visual=row.getOnFocusChangeListener();
                row.setOnFocusChangeListener((v,f)->{if(visual!=null)visual.onFocusChange(v,f);if(f)focused[0]=index;});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(60)); lp.bottomMargin=dp(10); rows.addView(row,lp);buttons.add(row);
            }
            for(int i=0;i<buttons.size();i++) { Button row=buttons.get(i);row.setNextFocusUpId(i==0?back.getId():buttons.get(i-1).getId());row.setNextFocusDownId(buttons.get(Math.min(i+1,buttons.size()-1)).getId());row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId()); }
            if(!buttons.isEmpty()){back.setNextFocusDownId(buttons.get(0).getId());pagePreferredFocus=buttons.get(Math.min(focused[0],buttons.size()-1));pagePreferredFocus.requestFocusFromTouch();}else pagePreferredFocus=back;
        });
    }

    private void showSettingsInfo(String title,String message) {
        pushSettingsPage(()->{LinearLayout page=settingsSurface(title); ScrollView scroll=new ScrollView(this);TextView info=text(message,24);info.setPadding(0,dp(12),0,dp(12));scroll.addView(info);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));pagePreferredFocus=((LinearLayout)page.getChildAt(0)).getChildAt(0);pagePreferredFocus.requestFocusFromTouch();});
    }

    private void showPlaybackOptions() {
        if(playing)hideControls.run();
        showItems("更多",new String[]{"字幕","音轨","旋转 / 翻转","定时停止"},choice->{
            if(choice==0)showSubtitleOptions();else if(choice==1)showAudioOptions();else if(choice==2)showRotation();else showSleepOptions();
        });
    }

    private void showRotation() {
        showChoices("旋转 / 翻转",new String[]{"原始方向","旋转 90°","旋转 180°","旋转 270°","水平翻转","垂直翻转"},settings.getInt("rotation",0),i->{
            settings.edit().putInt("rotation",i).apply();restartVideo();
        });
    }

    private void showItems(String title, String[] labels, java.util.function.IntConsumer action) {
        if (settingsPage && !playing) { showSettingsList(title,labels,-1,action,false); return; }
        showPlaybackDialog(new AlertDialog.Builder(this).setTitle(title)
                .setItems(labels, (dialog, index) -> action.accept(index)).create());
    }

    private void showChoices(String title, String[] labels, int current, java.util.function.IntConsumer action) {
        if (settingsPage && !playing) { showSettingsList(title,labels,current,action,true); return; }
        showPlaybackDialog(new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, current, (dialog, index) -> {
                    dialog.dismiss();
                    action.accept(index);
                }).create());
    }

    private void showSlider(String title, int count, int initial,
                            java.util.function.IntFunction<String> format, java.util.function.IntConsumer action) {
        LinearLayout content = column();
        content.setBackgroundColor(Color.TRANSPARENT);
        TextView value = text(format.apply(initial), 29);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setFocusable(true); slider.setFocusableInTouchMode(true);
        slider.setMax(count);
        slider.setKeyProgressIncrement(1);
        slider.setProgress(initial);
        slider.setId(View.generateViewId());
        slider.setContentDescription(title);
        android.graphics.drawable.StateListDrawable sliderFocus = new android.graphics.drawable.StateListDrawable();
        sliderFocus.addState(new int[] {android.R.attr.state_focused}, buttonBackground(0xff292929, ACCENT));
        sliderFocus.addState(new int[] {}, buttonBackground(Color.TRANSPARENT, Color.TRANSPARENT));
        slider.setBackground(sliderFocus);
        slider.setPadding(dp(12), dp(6), dp(12), dp(6));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                value.setText(format.apply(progress));
                if (user) {
                    action.accept(progress);
                }
            }
        });
        content.addView(slider, new LinearLayout.LayoutParams(-1, dp(54)));
        if(settingsPage && !playing) {
            pushSettingsPage(()->{LinearLayout page=settingsSurface(title);if(content.getParent()!=null)((android.view.ViewGroup)content.getParent()).removeView(content);page.addView(content);pagePreferredFocus=slider;slider.requestFocusFromTouch();});
        } else {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(content).create();
            showPlaybackDialog(dialog); slider.requestFocusFromTouch();
            slider.setNextFocusUpId(slider.getId());slider.setNextFocusDownId(slider.getId());
        }
    }

    private String speedLabel() { return String.format(Locale.ROOT,"%s×",new java.text.DecimalFormat("0.##").format(settings.getInt("speedSteps",20)/20f)); }

    private void showSpeed() {
        int[] steps={15,20,30,40,50,60}; int selected=-1;for(int i=0;i<steps.length;i++)if(steps[i]==settings.getInt("speedSteps",20))selected=i;
        showChoices("倍速",new String[]{"0.75×","1×（默认）","1.5×","2.0×","2.5×","3.0×"},selected,index->{
            settings.edit().putInt("speedSteps",steps[index]).apply();if(prepared)player.setRate(steps[index]/20f);
            if(speedButton!=null)speedButton.setText(speedLabel());flashNotice(speedLabel());
        });
    }

    private void showAspectOptions() {
        showChoices("画面比例",new String[]{"适应屏幕","裁剪填充","拉伸铺满","16:9","4:3"},settings.getInt("scale",0),index->{settings.edit().putInt("scale",index).apply();if(player!=null)player.setVideoScale(SCALES[index]);flashNotice(new String[]{"适应屏幕","裁剪填充","拉伸铺满","16:9","4:3"}[index]);});
    }

    private void showSubtitleOptions() {
        showItems("字幕",new String[]{"选择 / 关闭字幕","加载外挂字幕","字幕大小","字幕时间偏移"},choice->{
            if(choice==0)showTracks(true);
            else if(choice==1) {pickingSubtitle=true;pickDocument(Intent.ACTION_OPEN_DOCUMENT,12);}
            else if(choice==2)showSubtitleSize();else showDelay(true);
        });
    }

    private void showAudioOptions() {
        showItems("音轨",new String[]{"选择音轨","音频时间偏移"},i->{if(i==0)showTracks(false);else showDelay(false);});
    }

    private void showDelay(boolean subtitles) {
        String key = subtitles ? "subtitleDelay" : "audioDelay";
        showSlider(subtitles ? "字幕时间偏移" : "音频延迟", 40,
                (int) (settings.getLong(key, 0) / 100000) + 20,
                step -> String.format(Locale.ROOT, "%+.1f 秒", (step - 20) / 10f), step -> {
                    long delay = (step - 20) * 100000L;
                    settings.edit().putLong(key, delay).apply();
                    if (prepared) {
                        if (subtitles) player.setSpuDelay(delay);
                        else player.setAudioDelay(delay);
                    }
                });
    }

    private void configureVideo(Media media,boolean softwareRetry) {
        media.setHWDecoderEnabled(!softwareRetry && !settings.getBoolean("softwareDecode",false) && settings.getInt("rotation",0)==0,false);
    }

    private void addVideoFilters(List<String> options) {
        int rotation=settings.getInt("rotation",0);
        if(rotation>0&&rotation<6) {options.add("--video-filter=transform");options.add("--transform-type="+new String[]{"","90","180","270","hflip","vflip"}[rotation]);}
    }

    private void showSleepOptions() {
        int[] minutes = {0, 15, 30, 60, 90};
        showItems("定时停止", new String[] {"关闭", "15 分钟", "30 分钟", "60 分钟", "90 分钟"}, choice -> {
            ui.removeCallbacks(sleepPlayback);
            if (choice != 0 && playing) ui.postDelayed(sleepPlayback, minutes[choice] * 60000L);
            flashNotice(choice == 0 ? "已关闭定时停止" : minutes[choice] + " 分钟后停止播放");
        });
    }

    private void flashNotice(String message) {
        if (!playing) return;
        playbackNotice.setText(message);
        playbackNotice.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNotice);
        ui.postDelayed(hideNotice, 2400);
    }

    private void showDecoderOptions() {
        showChoices("解码方式",new String[]{"自动（优先硬件解码）","软件解码"},settings.getBoolean("softwareDecode",false)?1:0,index->{
            if(settings.getBoolean("softwareDecode",false)==(index==1))return;
            settings.edit().putBoolean("softwareDecode",index==1).apply();restartVideo();
        });
    }

    private void showTracks(boolean subtitles) {
        MediaPlayer.TrackDescription[] tracks = !prepared ? null
                : subtitles ? player.getSpuTracks() : player.getAudioTracks();
        String title = subtitles ? "字幕" : "音轨";
        if (tracks == null || tracks.length == 0) {
            showPlaybackDialog(new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(subtitles ? "当前视频没有内嵌字幕。" : "当前没有可切换的音轨。")
                    .setPositiveButton("确定", null).create());
            return;
        }
        String[] labels = new String[tracks.length];
        int active = subtitles ? player.getSpuTrack() : player.getAudioTrack();
        int checked = -1;
        for (int index = 0; index < tracks.length; index++) {
            labels[index] = tracks[index].id == -1 ? "关闭" : tracks[index].name;
            if (tracks[index].id == active) checked = index;
        }
        showSidePanel(new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, choice) -> {
                    if (prepared) {
                        if (subtitles) player.setSpuTrack(tracks[choice].id);
                        else if(gatedPlayer==player&&digitalOutput){gatedTrack=tracks[choice].id;player.setAudioTrack(-1);}else player.setAudioTrack(tracks[choice].id);
                    }
                    dialog.dismiss();
                }).create());
    }

    private void showSidePanel(AlertDialog dialog) {
        showSidePanel(dialog, false);
    }

    private void showSidePanel(AlertDialog dialog, boolean keepHiddenAfterDismiss) {
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setWindowAnimations(true ? R.style.PanelMotion : 0);
        showPlaybackDialog(dialog, keepHiddenAfterDismiss);
        if (window == null) return;
        window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        window.setDimAmount(0.15f);
        window.setLayout(Math.max(dp(260), getResources().getDisplayMetrics().widthPixels * 35 / 100), -1);
        if (playing) hideControls.run();
    }

    private void showPlaybackDialog(AlertDialog dialog) {
        showPlaybackDialog(dialog, false);
    }

    private void showPlaybackDialog(AlertDialog dialog, boolean keepHiddenAfterDismiss) {
        View priorFocus = playbackDialog != null && playbackDialogOrigin != null
                ? playbackDialogOrigin : getCurrentFocus();
        boolean restoreChrome = playing && controlsShown && !keepHiddenAfterDismiss;
        playbackDialogOrigin = priorFocus;
        playbackDialog = dialog;
        ui.removeCallbacks(hideControls);
        dialog.setOnDismissListener(dismissed -> {
            if (playbackDialog != dialog) return;
            playbackDialog = null;
                    playbackDialogOrigin = null;
            if (playing) {
                if (restoreChrome) showControls();
                else hideControls.run();
            }
            if (priorFocus != null) ui.post(() -> {
                if (playbackDialog == null && priorFocus.isAttachedToWindow()
                        && priorFocus.isShown() && priorFocus.isEnabled()) priorFocus.requestFocusFromTouch();
            });
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable surface = buttonBackground(0xd1181818, 0xff464646);
            surface.setCornerRadius(dp(18));
            dialog.getWindow().setBackgroundDrawable(surface);
            if (playing) dialog.getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility());
        }
        android.widget.ListView list = dialog.getListView();
        if (list != null) {
            list.setFocusableInTouchMode(true);
            list.setSelector(buttonBackground(0xff303839, ACCENT));
            list.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { styleDialogRow(child); }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            for (int i = 0; i < list.getChildCount(); i++) styleDialogRow(list.getChildAt(i));
            list.requestFocusFromTouch();
            if (list.getCount() > 0) list.setSelection(Math.max(0, list.getCheckedItemPosition()));
            // The window can gain focus after show() returns and restore touch mode at that point.
            // Apply the initial TV selection after that transition, once, instead of on every key.
            View dialogRoot = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
            Runnable initialFocus = () -> {
                if (!dialog.isShowing() || playbackDialog != dialog) return;
                Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (primary != null && primary.getVisibility() == View.VISIBLE) return;
                list.requestFocusFromTouch();
                if (list.getCount() > 0) list.setSelection(Math.max(0, list.getCheckedItemPosition()));
            };
            if (dialogRoot != null) {
                if (dialogRoot.hasWindowFocus()) list.post(initialFocus);
                else dialogRoot.getViewTreeObserver().addOnWindowFocusChangeListener(
                        new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                            @Override public void onWindowFocusChanged(boolean focused) {
                                if (!focused) return;
                                dialogRoot.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                                list.post(initialFocus);
                            }
                        });
            }
        }
        for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button action = dialog.getButton(which);
            if (action != null) {
                action.setTextSize(24);
                action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                action.setTextColor(Color.WHITE);
                action.setAllCaps(false);
                action.setMinHeight(dp(52));
                action.setFocusableInTouchMode(true);
                action.setOnTouchListener((view, event) -> {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && view.isEnabled()) view.requestFocus();
                    return false;
                });
                action.setBackground(getDrawable(R.drawable.tv_dialog_button));
                android.view.ViewGroup.LayoutParams raw = action.getLayoutParams();
                if (raw instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams spacing = (android.view.ViewGroup.MarginLayoutParams) raw;
                    spacing.setMarginStart(dp(8));
                    spacing.setMarginEnd(dp(8));
                    action.setLayoutParams(spacing);
                }
            }
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(24);
            message.setTextColor(Color.WHITE);
        }
        Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (primary != null && primary.getVisibility() == View.VISIBLE && primary.isEnabled()) primary.requestFocus();
    }

    private void styleDialogRow(View row) {
        row.setMinimumHeight(dp(52));
        TextView label = row instanceof TextView ? (TextView) row : row.findViewById(android.R.id.text1);
        if (label != null) {
            label.setTextSize(24);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private void showControls() {
        keepControlsHidden = false;
        if (!controlsShown) {
            controlsShown = true;
            controls.animate().cancel();
            controls.setVisibility(View.VISIBLE);
            header.setVisibility(View.VISIBLE);
            controls.setTranslationY(0);
            pause.requestFocusFromTouch();
        }
        header.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        if (prepared && player.isPlaying() && !dragging && !buffering && playbackDialog == null) {
            ui.postDelayed(hideControls, 4 * 1000L);
        }
    }

    private void togglePlayback() {
        boolean reveal = controlsShown;
        if (prepared) {
            // Buffering can make native isPlaying() false while playback is still requested.
            // Toggle the requested pause state so a confirm/tap cannot accidentally resume it.
            requestPause(!pauseRequested);
        } else {restorePaused=!restorePaused;pauseRequested=restorePaused;pausedPlayback=restorePaused;}
        exitPromptAt = 0;
        if (reveal) { showControls(); pause.requestFocusFromTouch(); }
        else {
            keepControlsHidden = true;
            flashNotice((prepared ? pausedPlayback : restorePaused) ? "已暂停" : "继续播放");
        }
    }

    private void seek(int delta) { seek(delta, true); }

    private void seek(int delta, boolean reveal) {
        videoRecovery.cancel();
        if (reveal) showControls();
        if (!prepared || !player.isSeekable() || player.getLength() <= 0) return;
        long current = pendingSeek >= 0 ? pendingSeek : player.getTime();
        pendingSeek = Math.max(0, Math.min(player.getLength(), current + delta));
        updateTimeline(pendingSeek);
        ui.removeCallbacks(applySeek);
        ui.postDelayed(applySeek, 220);
    }

    private void updateTimeline(long position) {
        long duration = player.getLength();
        timeline.setProgress(duration > 0 ? (int) (Math.max(0, position) * 1000 / duration) : 0);
        showTime(position, duration);
    }

    private void showTime(long position, long duration) {
        String end = duration <= 0 ? "—" : false
                ? "−" + time(duration - position) : time(duration);
        String value = time(position) + " / " + end;
        if (!value.contentEquals(playbackStatus.getText())) playbackStatus.setText(value);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private void stopVideo() {
        ++playbackRequest;
        mediaSession.setActive(false);
        savePlayback();
        videoKey = null;
        playingEntry = null;
        resumeAt = 0;
        resumeWithoutPrompt = false;
        restorePaused = false;
        pausedPlayback=false;pauseRequested=false;frameCheckPending=false;retainingRecovery=false;recoveryRebuilds=0;
        gatedPlayer=null;gatedVolume=-1;gatedTrack=Integer.MIN_VALUE;downloadRate.reset();rateSource=null;
        displayedCounterSeen=decodedCounterFallback=videoTrackKnown=false;dragResumeDesired=false;
        ui.removeCallbacks(loadingTick);
        buffering = false;
        videoRecovery.cancel();
        retainedSubtitleSession = false;
        restoreAudioTrack = restoreSubtitleTrack = Integer.MIN_VALUE;
        if (openingTimeout != null) ui.removeCallbacks(openingTimeout);
        openingTimeout = null;
        AlertDialog dialog = playbackDialog;
        playbackDialog = null;
        playbackDialogOrigin = null;
        if (dialog != null) dialog.dismiss();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideControls);
        if (controls != null) controls.animate().cancel();
        ui.removeCallbacks(applySeek);
        pendingSeek = -1;
        revealKey = -1;
        dragging = false;
        seekTouchCancelled = false;
        ui.removeCallbacks(hideNotice);
        MediaPlayer previous = player;
        LibVLC previousVlc = vlc;
        SmbPlaybackServer server = playbackServer;
        AssetFileDescriptor oldFd = playerFd;
        playerFd = null;
        cachedTimeline = java.util.Collections.emptyList();
        videoLayout = null;
        List<java.io.File> oldSubtitles = new ArrayList<>(subtitleFiles);
        subtitleFiles.clear();
        waitingSubtitles.clear();
        player = null;
        vlc = null;
        playbackServer = null;
        if (previous != null) {
            previous.setVolume(0);
            if(digitalOutput)previous.setAudioTrack(-1);
            previous.setEventListener(null);
            previous.detachViews();
        }
        if (server != null || previousVlc != null) {
            network.execute(() -> {
                if (server != null) server.stop();
                if (previous != null) {
                    previous.stop();
                    previous.release();
                }
                if (previousVlc != null) previousVlc.release();
                if (oldFd != null) {
                    try { oldFd.close(); } catch (IOException ignored) {}
                }
                for (java.io.File subtitle : oldSubtitles) subtitle.delete();
            });
        }
        playing = false;
        prepared = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void leaveVideo(String message) {
        stopVideo(); playbackLoop=-1;
        ((PlaybackPreferences)settings).end();ui.removeCallbacks(sleepPlayback);renderBrowser(message,true);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = event.getRepeatCount() == 0;
        boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!playing) {
            if (down && direction) repairMainFocus();
            boolean handled = super.dispatchKeyEvent(event);
            if (down && direction) ui.post(this::repairMainFocus);
            return handled;
        }
        boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_BUTTON_A;
        if (down && key != KeyEvent.KEYCODE_BACK) exitPromptAt = 0;
        if ((direction || confirm) && !controlsShown) {
            if (confirm) {
                if (down && first) {
                    togglePlayback();
                }
            } else if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (down && first) { revealKey = key; showControls(); }
            } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && event.getRepeatCount() % 4 == 0) {
                    int step = TvSeekPolicy.stepMillis(seekStep(), event.getRepeatCount());
                    seek(key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step, false);
                    flashNotice((key == KeyEvent.KEYCODE_DPAD_LEFT ? "后退 " : "前进 ")
                            + (step >= 60000 ? step / 60000 + " 分钟" : step / 1000 + " 秒")
                            + " · " + time(Math.max(0, pendingSeek >= 0 ? pendingSeek : player == null ? 0 : player.getTime())));
                }
            } else if (down && first) { showPlaylist(); }
            return true;
        }
        if (key == revealKey) {
            if (!down) revealKey = -1;
            return true;
        }
        if (direction || confirm) {
            repairMainFocus();
            if (down) showControls();
            if (timeline.hasFocus() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (down && event.getRepeatCount() % 4 == 0) {
                    int step = TvSeekPolicy.stepMillis(seekStep(), event.getRepeatCount());
                    seek(key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step);
                    flashNotice((key == KeyEvent.KEYCODE_DPAD_LEFT ? "后退 " : "前进 ")
                            + (step >= 60000 ? step / 60000 + " 分钟" : step / 1000 + " 秒"));
                }
                return true;
            }
            if (confirm && timeline.hasFocus()) {
                if (down && first) showJumpOptions();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A && getCurrentFocus() instanceof Button) {
                if (!down) getCurrentFocus().performClick();
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_SPACE) {
            if (down && first) togglePlayback();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (down && first) {
                if (key == KeyEvent.KEYCODE_MEDIA_STOP) leaveVideo("");
                else if(key==KeyEvent.KEYCODE_MEDIA_PLAY)requestPause(false);
                else if(key==KeyEvent.KEYCODE_MEDIA_PAUSE)requestPause(true);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (down && first) stepVideo(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1, false);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (down && event.getRepeatCount() % 4 == 0) seek(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? seekStep() : -seekStep());
            return true;
        }
        if (key==KeyEvent.KEYCODE_MENU) {if(down&&first)showPlaybackOptions();return true;}
        boolean handled = super.dispatchKeyEvent(event);
        if (down && direction) ui.post(this::repairMainFocus);
        return handled;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ui.post(this::repairMainFocus);
    }

    @Override public void onBackPressed() {
        if (playing) {
            if (controlsShown) {
                ui.removeCallbacks(hideControls);
                hideControls.run();
                exitPromptAt = 0;
            } else {
                long now = android.os.SystemClock.elapsedRealtime();
                if (exitPromptAt > 0 && now - exitPromptAt <= 2400) leaveVideo("");
                else { exitPromptAt = now; flashNotice("再次按返回键退出播放"); }
            }
        } else if (settingsPage) settingsBack();
        else if (browsing) up();
        else if (!dashboard) showDashboard();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        super.onStop();
        if (playing && !screenOff && !pickingSubtitle) leaveVideo("");
    }

    @Override protected void onDestroy() {
        ++request;
        closeNfsBrowser();
        ui.removeCallbacks(sleepPlayback);
        stopVideo();
        if (connection != null) {
            CIFSContext previous = connection;
            network.execute(() -> closeContext(previous));
        }
        network.shutdown();
        previews.shutdownNow();
        mediaSession.release();
        unregisterReceiver(deviceEvents);
        android.media.AudioManager audio=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio!=null)audio.unregisterAudioDeviceCallback(audioRoutes);
        super.onDestroy();
    }

    private static boolean isVideo(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm|ts|m2ts|mov|avi|mpg|mpeg|3gp|wmv|flv|m3u8)$");
    }

    private static boolean isPlaylist(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".m3u");
    }

    private static boolean isSubtitle(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(srt|ass|ssa|vtt)$");
    }

    private void pickDocument(String action, int code) {
        Intent intent = new Intent(action);
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(intent, code);
        } catch (android.content.ActivityNotFoundException e) {
            pickingSubtitle = false;
            if (playing) flashNotice("此设备没有系统文件选择器");
            else if (code == 10) showHome("local", "此设备没有系统文件选择器。");
            else showSetup("此设备没有系统文件选择器。");
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 12) pickingSubtitle = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (code == 10) {
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            try {
                org.json.JSONObject item=null;org.json.JSONArray sources=homeSources();
                for(int i=0;i<sources.length();i++){org.json.JSONObject existing=sources.optJSONObject(i);if(existing!=null&&"local".equals(existing.optString("kind"))&&uri.toString().equals(existing.optString("url")))item=existing;}
                if(item==null)item=new org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("kind","local");
                item.put("url",uri.toString()).put("name",documentName(uri));putHomeSource(item);showHome("local","");
            }catch(org.json.JSONException invalid){showHome("local","无法保存文件夹，请重试。");}
        } else if (code == 11) {
            importPlaylist(uri);
        } else if (code == 12 && playing) {
            int token = playbackRequest;
            CIFSContext context = connection;
            network.execute(() -> attachSubtitle(new Entry(documentName(uri), uri.toString(), false), token, context));
        }
    }

    private String documentName(Uri uri) {
        List<String> segments = uri.getPathSegments();
        boolean folder = segments.size() == 2 && "tree".equals(segments.get(0));
        Uri document = folder ? DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) : uri;
        try (Cursor cursor = getContentResolver().query(document, new String[] {"_display_name"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        return folder ? "已选择的文件夹" : "字幕.srt";
    }

    private void checkLocalStorage() {
        String url = currentUrl;
        Uri tree = localTree;
        network.execute(() -> {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getDocumentId(Uri.parse(url)));
            try (Cursor cursor = getContentResolver().query(children, new String[] {"document_id"}, null, null, null)) {
                if (cursor != null && !cursor.isClosed()) return;
            } catch (RuntimeException ignored) {}
            ui.post(() -> {
                if (!tree.equals(localTree)) return;
                if (playing) stopVideo();
                localTree = null;
                localParents.clear();
                ++request;
                showSetup("存储设备已移除。");
            });
        });
    }

    private void loadLocal(String url, String restore, boolean enteredChild) {
        int token = ++request;
        Uri tree = localTree;
        browsing = true;
        renderBrowser("正在读取存储设备…", false);
        network.execute(() -> {
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getDocumentId(Uri.parse(url)));
                String[] columns = {"document_id", "_display_name", "mime_type", "last_modified", "_size"};
                List<Entry> files = new ArrayList<>();
                List<Entry> subtitles = new ArrayList<>();
                try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
                    if (cursor == null) throw new IOException("Cannot read device");
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                        Uri item = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                        Entry entry = new Entry(name, item.toString(), folder, cursor.getLong(3), 0,
                                cursor.isNull(4) ? -1 : cursor.getLong(4));
                        if (folder || isVideo(name) || isPlaylist(name) || mime != null && mime.startsWith("video/")) files.add(entry);
                        else if (isSubtitle(name)) subtitles.add(entry);
                    }
                }
                sortEntries(files);
                ui.post(() -> {
                    if (request != token) return;
                    entries = files;
                    subtitlesInFolder = subtitles;
                            currentUrl = url;
                    focusedUrl = restore;
                    renderBrowser(files.isEmpty() ? "目录中没有视频" : "", true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (request != token) return;
                    if (enteredChild && !localParents.isEmpty()) localParents.pop();
                    localTree = null;
                    localParents.clear();
                    showSetup("存储设备已移除或目录无法读取。");
                });
            }
        });
    }

    private void rememberShare() {
        try {
            org.json.JSONArray old = new org.json.JSONArray(settings.getString("savedShares", "[]"));
            org.json.JSONObject current = new org.json.JSONObject();
            for (String key : new String[] {"host", "port", "share", "user"}) current.put(key, settings.getString(key, ""));
            org.json.JSONArray recent = new org.json.JSONArray();
            recent.put(current);
            for (int i = 0; i < old.length() && recent.length() < 8; i++) {
                org.json.JSONObject item = old.getJSONObject(i);
                boolean same = true;
                for (String key : new String[] {"host", "port", "share", "user"}) {
                    if (!current.optString(key).equals(item.optString(key))) same = false;
                }
                if (!same) recent.put(item);
            }
            settings.edit().putString("savedShares", recent.toString()).apply();
        } catch (org.json.JSONException ignored) {}
    }

    private void closeNfsBrowser() {
        if (nfsTimeout != null) ui.removeCallbacks(nfsTimeout);
        nfsTimeout = null;
        if (nfsBrowser != null) nfsBrowser.release();
        if (directoryVlc != null) directoryVlc.release();
        nfsBrowser = null;
        directoryVlc = null;
    }

    private void loadNfs(String url, String restore) {
        sourceKind = "stream";
        closeNfsBrowser();
        int token = ++request;
        nfsBrowsing = true;
        browsing = true;
        renderBrowser("正在读取 NFS 目录…", false);
        List<Entry> files = new ArrayList<>();
        try {
            directoryVlc = new LibVLC(this);
            nfsBrowser = new org.videolan.libvlc.util.MediaBrowser(directoryVlc,
                    new org.videolan.libvlc.util.MediaBrowser.EventListener() {
                        @Override public void onMediaAdded(int index, org.videolan.libvlc.interfaces.IMedia media) {
                            if (request != token || media.getUri() == null) return;
                            String name = media.getMeta(org.videolan.libvlc.interfaces.IMedia.Meta.Title);
                            if (name == null || name.isEmpty()) name = media.getUri().getLastPathSegment();
                            boolean folder = media.getType() == org.videolan.libvlc.interfaces.IMedia.Type.Directory;
                            if (name != null && (folder || isVideo(name))) {
                                files.add(new Entry(name, media.getUri().toString(), folder));
                            }
                        }

                        @Override public void onMediaRemoved(int index, org.videolan.libvlc.interfaces.IMedia media) {}

                        @Override public void onBrowseEnd() {
                            ui.post(() -> {
                                if (request != token) return;
                                closeNfsBrowser();
                                sortEntries(files);
                                entries = files;
                                subtitlesInFolder = new ArrayList<>();
                                                        currentUrl = url;
                                focusedUrl = restore;
                                renderBrowser(files.isEmpty() ? "目录中没有可显示的视频，或服务器拒绝访问。" : "", true);
                                        });
                        }
                    }, ui);
            nfsTimeout = () -> {
                if (request != token) return;
                ++request;
                showSetup("NFS 目录读取超时。");
            };
            ui.postDelayed(nfsTimeout, 12000);
            nfsBrowser.browse(Uri.parse(url), org.videolan.libvlc.util.MediaBrowser.Flag.NoSlavesAutodetect);
        } catch (RuntimeException | LinkageError e) {
            ++request;
            showSetup("无法读取 NFS 目录。");
        }
    }

    private static boolean isNetworkMedia(String value) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme) || "nfs".equals(scheme)
                || "rtsp".equals(scheme) || "rtmp".equals(scheme) || "rtp".equals(scheme) || "udp".equals(scheme))
                && uri.getHost() != null;
    }

    private void importFolderPlaylist(Entry playlist) {
        List<Entry> siblings = entries;
        List<Entry> subtitles = subtitlesInFolder;
        Uri tree = localTree;
        String folder = currentUrl;
        java.util.ArrayDeque<String> parents = new java.util.ArrayDeque<>(localParents);
        Runnable restore = () -> {
            playlistSource = false;
            entries = siblings;
            subtitlesInFolder = subtitles;
            localTree = tree;
            localParents.clear();
            localParents.addAll(parents);
            currentUrl = folder;
            focusedUrl = playlist.url;
            renderBrowser("", true);
        };
        importPlaylist(Uri.parse(playlist.url), siblings, subtitles, restore, playlist.name);
    }

    private void importPlaylist(Uri uri) {
        importPlaylist(uri, java.util.Collections.emptyList(), java.util.Collections.emptyList(), null, "网络播放列表");
    }

    private void importPlaylist(Uri uri, List<Entry> siblings, List<Entry> subtitles, Runnable restore, String title) {
        int token = ++request;
        CIFSContext context = connection;
        renderBrowser("正在读取播放列表…", false);
        network.execute(() -> {
            List<Entry> items;
            try {
                if ("smb".equals(uri.getScheme())) {
                    try (SmbFile file = new SmbFile(uri.toString(), context)) {
                        long size = file.length();
                        if (size < 0 || size > 2 * 1024 * 1024) throw new IOException("Playlist size limit");
                        byte[] bytes = new byte[(int) size];
                        try (java.io.DataInputStream input = new java.io.DataInputStream(file.openInputStream())) {
                            input.readFully(bytes);
                        }
                        items = readPlaylist(new java.io.ByteArrayInputStream(bytes), siblings);
                    }
                } else if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    items = readLinkedPlaylist(uri, title);
                } else {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        items = readPlaylist(input, siblings);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("SmbPlayer", "Playlist read failed", e);
                ui.post(() -> {
                    if (request != token) return;
                    if (restore == null) showSetup("无法读取 M3U 文件。");
                    else renderBrowser("无法读取 M3U 文件。", true);
                });
                return;
            }
            ui.post(() -> {
                if (request != token) return;
                if (items.isEmpty()) {
                    if (restore == null) showSetup("M3U 中没有可播放的网络地址。");
                    else renderBrowser("M3U 中没有可播放的同目录视频或网络地址。", true);
                } else {
                    returnFromPlaylist = restore;
                    setPlaylist(items, title);
                    subtitlesInFolder = subtitles;
                }
            });
        });
    }

    private static List<Entry> readLinkedPlaylist(Uri uri, String title) throws IOException {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        try {
            try (InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("Playlist size limit");
                    bytes.write(buffer, 0, count);
                }
                String content = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                if (content.startsWith("\ufeff")) content = content.substring(1);
                if (content.contains("#EXT-X-")) return java.util.Collections.singletonList(new Entry(title, uri.toString(), false));
                StringBuilder resolved = new StringBuilder();
                URI base = URI.create(connection.getURL().toString());
                for (String line : content.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        try { line = base.resolve(trimmed.replace(" ", "%20")).toString(); }
                        catch (IllegalArgumentException invalid) { throw new IOException("Invalid playlist address", invalid); }
                    }
                    resolved.append(line).append('\n');
                }
                return readPlaylist(new java.io.ByteArrayInputStream(resolved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.util.Collections.emptyList());
            }
        } finally { connection.disconnect(); }
    }

    private static List<Entry> readPlaylist(InputStream input, List<Entry> siblings) throws IOException {
        if (input == null) throw new IOException("Cannot open playlist");
        java.util.Map<String, Entry> files = new java.util.HashMap<>();
        for (Entry entry : siblings) {
            if (!entry.folder && !isPlaylist(entry.name)) files.put(entry.name, entry);
        }
        List<Entry> items = new ArrayList<>();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                input, java.nio.charset.StandardCharsets.UTF_8));
        String line;
        String name = null;
        long length = 0;
        for (int i = 0; i < 20000 && items.size() < 2000 && (line = reader.readLine()) != null; i++) {
            line = line.trim();
            if (line.startsWith("\ufeff")) line = line.substring(1);
            if (line.startsWith("#EXTINF:") && line.indexOf(',') >= 0) {
                int comma = line.indexOf(',');
                name = line.substring(comma + 1);
                try { length = Math.max(0, (long) (Double.parseDouble(line.substring(8, comma)) * 1000)); }
                catch (NumberFormatException ignored) { length = 0; }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String relative = line.startsWith("./") || line.startsWith(".\\") ? line.substring(2) : line;
                Entry file = files.get(relative);
                if (file != null) items.add(new Entry(file.name, file.url, false, file.modified,
                        length > 0 ? length : file.duration, file.size));
                else if (isNetworkMedia(line)) items.add(new Entry(name == null || name.isEmpty() ? line : name,
                        line, false, 0, length));
                name = null;
                length = 0;
            }
        }
        return items;
    }

    private void setPlaylist(List<Entry> items) {
        setPlaylist(items, "网络播放列表");
    }

    private void setPlaylist(List<Entry> items, String title) {
        setPlaylist(items, title, null);
    }

    private void setPlaylist(List<Entry> items, String title, String restoreFocus) {
        playlistTitle = title;
        browsing = true;
        playlistSource = true;
        nfsBrowsing = false;
        localTree = null;
        localParents.clear();
        currentUrl = null;
        focusedUrl = restoreFocus;
        entries = new ArrayList<>(items);
        sortEntries(entries);
        subtitlesInFolder = new ArrayList<>();
        renderBrowser(items.isEmpty() ? "暂无可播放影片" : "", true);
    }

    private Media openMedia(String url) throws IOException {
        Uri uri = Uri.parse(url);
        if ("content".equals(uri.getScheme())) {
            playerFd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (playerFd == null) throw new IOException("Cannot open document");
            return new Media(vlc, playerFd);
        }
        return new Media(vlc, uri);
    }

    private void loadSidecarSubtitles(Entry video, int token) {
        if (retainedSubtitleSession) return;
        int dot = video.name.lastIndexOf('.');
        if (dot <= 0) return;
        String base = video.name.substring(0, dot).toLowerCase(Locale.ROOT);
        List<Entry> matches = new ArrayList<>();
        for (Entry subtitle : subtitlesInFolder) {
            String name = subtitle.name.toLowerCase(Locale.ROOT);
            if (name.startsWith(base + ".") && matches.size() < 8) matches.add(subtitle);
        }
        if (matches.isEmpty()) return;
        CIFSContext context = connection;
        network.execute(() -> {
            for (Entry subtitle : matches) attachSubtitle(subtitle, token, context);
        });
    }

    private void attachSubtitle(Entry subtitle, int token, CIFSContext context) {
        if (!isSubtitle(subtitle.name)) {
            ui.post(() -> {
                if (token == playbackRequest) flashNotice("请选择 SRT、ASS 或 VTT 字幕");
            });
            return;
        }
        java.io.File copy = null;
        try {
            java.io.File directory = new java.io.File(getCacheDir(), "subtitles");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create subtitle cache");
            int extension = subtitle.name.lastIndexOf('.');
            int language = subtitle.name.lastIndexOf('.', extension - 1);
            int suffix = language >= 0 && subtitle.name.substring(language + 1, extension)
                    .matches("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?") ? language : extension;
            copy = java.io.File.createTempFile("sub-", subtitle.name.substring(suffix), directory);
            if (subtitle.url.startsWith("smb://")) {
                try (SmbFile file = new SmbFile(subtitle.url, context);
                     InputStream input = file.openInputStream()) {
                    copySubtitle(input, copy);
                }
            } else {
                try (InputStream input = getContentResolver().openInputStream(Uri.parse(subtitle.url))) {
                    copySubtitle(input, copy);
                }
            }
            java.io.File ready = copy;
            ui.post(() -> {
                if (token != playbackRequest || player == null) {
                    ready.delete();
                    return;
                }
                subtitleFiles.add(ready);
                if (prepared) addSubtitleToPlayer(ready);
                else waitingSubtitles.add(ready);
            });
        } catch (Exception e) {
            if (copy != null) copy.delete();
            ui.post(() -> {
                if (token == playbackRequest) flashNotice("外挂字幕读取失败");
            });
        }
    }

    private void addSubtitleToPlayer(java.io.File subtitle) {
        boolean added = player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(subtitle), false);
        android.util.Log.d("SmbPlayer", added ? "External subtitle attached" : "External subtitle rejected");
    }

    private static void copySubtitle(InputStream input, java.io.File copy) throws IOException {
        if (input == null) throw new IOException("Cannot open subtitle");
        try (java.io.OutputStream output = new java.io.FileOutputStream(copy)) {
            byte[] bytes = new byte[16384];
            int total = 0;
            int read;
            while ((read = input.read(bytes)) > 0) {
                total += read;
                if (total > 8 * 1024 * 1024) throw new IOException("Subtitle too large");
                output.write(bytes, 0, read);
            }
        }
    }

    private static final class Entry {
        final String name;
        final String url;
        final boolean folder;
        final long modified;
        final long duration;
        final long size;

        Entry(String name, String url, boolean folder) {
            this(name, url, folder, 0);
        }

        Entry(String name, String url, boolean folder, long modified) {
            this(name, url, folder, modified, 0);
        }

        Entry(String name, String url, boolean folder, long modified, long duration) {
            this(name, url, folder, modified, duration, -1);
        }

        Entry(String name, String url, boolean folder, long modified, long duration, long size) {
            this.modified = modified;
            this.duration = duration;
            this.size = size;
            this.name = folder ? name.replaceAll("[/\\\\]+$", "") : name;
            this.url = url;
            this.folder = folder;
        }
    }
}
