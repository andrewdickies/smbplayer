package com.smbplayer.tv;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.*;

final class PhotoViewer implements AutoCloseable {
    private static final int[] SECONDS = {3, 5, 10, 15, 30};
    private final MainActivity host;
    private final List<MainActivity.Entry> photos;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout root, slideStage;
    private final PhotoCache cache;
    private final ExecutorService prefetch = Executors.newSingleThreadExecutor();
    private Future<?> prefetchJob;
    private final java.util.Random effects = new java.util.Random();
    private int slideCount=1;
    private long slideDue, slideRemaining;
    private FrameLayout slidePage;
    private final Runnable advance = this::advanceSlide;
    private final ImageView image;
    private final HorizontalScrollView toolbar;
    private final LinearLayout actions, header;
    private final TextView status, photoTitle;
    private final Button back;
    private final ProgressBar loadingIndicator;
    private final Button retry;
    private final android.util.LruCache<String, Bitmap> thumbnails = new android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final ExecutorService thumbnailWorker = new ThreadPoolExecutor(
            1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(12),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private int failedIndex;
    private long exitAt;
    private Runnable loadTimeout;
    private final Matrix matrix = new Matrix();
    private Bitmap bitmap;
    private int index, displayedIndex = -1, generation, seconds = 5, effect = 2;
    private boolean cover, slideshow, paused, loading, pinch, dragged;
    private volatile boolean closed;
    private float zoom = 1, x, y, touchX, touchY, lastX, lastY;
    private Future<?> job;
    private final java.util.Set<Integer> heldKeys=new java.util.HashSet<>();
    private boolean touchActive;
    private final Runnable hideToolbar=this::hideToolbarWhenIdle;

    private void hideToolbarWhenIdle(){
        if(closed||!toolbar.isShown()||touchActive||!heldKeys.isEmpty())return;
        if(host.hasPlaybackDialog()){ui.postDelayed(this.hideToolbar,500);return;}
        setToolbarVisible(false);
    }

    void touch(boolean active){
        touchActive=active;
        scheduleToolbarHide();
    }

    private void scheduleToolbarHide(){
        ui.removeCallbacks(hideToolbar);
        if(!closed&&toolbar!=null&&toolbar.isShown()&&!touchActive&&heldKeys.isEmpty())
            ui.postDelayed(hideToolbar,4000);
    }
    private ObjectAnimator animationX, animationY;

    PhotoViewer(MainActivity host, List<MainActivity.Entry> photos, int index) {
        this.host = host;
        this.photos = photos;
        this.index = index;
        cache = new PhotoCache(host.getCacheDir());
        root = new FrameLayout(host);
        root.setBackgroundColor(0xff000000);
        image = new ImageView(host);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(image, new FrameLayout.LayoutParams(-1,-1));
        slideStage = new FrameLayout(host);
        slideStage.setVisibility(View.GONE);
        root.addView(slideStage,new FrameLayout.LayoutParams(-1,-1));
        status = host.text("",22);
        status.setVisibility(View.GONE);
        status.setShadowLayer(host.dp(4),0,host.dp(1),Color.BLACK);
        status.setPadding(host.dp(16),host.dp(9),host.dp(16),host.dp(9));
        LinearLayout loadingPanel = new LinearLayout(host);
        loadingPanel.setOrientation(LinearLayout.VERTICAL);
        loadingPanel.setGravity(Gravity.CENTER);
        loadingIndicator=new ProgressBar(host);
        loadingIndicator.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff69e1f3));
        loadingIndicator.setContentDescription("正在打开图片");
        loadingIndicator.setVisibility(View.GONE);
        loadingPanel.addView(loadingIndicator,new LinearLayout.LayoutParams(host.dp(42),host.dp(42)));
        loadingPanel.addView(status);
        retry = host.iconButton("repeat","重试图片", () -> load(failedIndex));
        retry.setVisibility(View.GONE);
        loadingPanel.addView(retry);
        root.addView(loadingPanel,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        toolbar = new HorizontalScrollView(host);
        toolbar.setFocusable(false);
        toolbar.setFillViewport(true);
        toolbar.setHorizontalScrollBarEnabled(false);
        toolbar.setBackground(host.playbackScrim(false));
        actions = new LinearLayout(host);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(host.dp(16),host.dp(14),host.dp(16),host.dp(12));
        toolbar.addView(actions);
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        toolbar.setVisibility(View.GONE);
        header=new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(host.dp(24),host.dp(12),host.dp(24),host.dp(14));
        header.setBackground(host.playbackScrim(true));
        back=host.iconButton("back","返回目录",host::closeMediaViewer);
        header.addView(back);
        back.setNextFocusLeftId(back.getId());
        back.setNextFocusRightId(back.getId());
        back.setNextFocusUpId(back.getId());
        photoTitle=host.text("",28);
        photoTitle.setSingleLine(true);
        photoTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        photoTitle.setTypeface(Typeface.DEFAULT_BOLD);
        photoTitle.setShadowLayer(host.dp(4),0,host.dp(1),Color.BLACK);
        LinearLayout.LayoutParams titleSpace=new LinearLayout.LayoutParams(0,-2,1);
        titleSpace.setMarginStart(host.dp(16));
        header.addView(photoTitle,titleSpace);
        root.addView(header,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        header.setVisibility(View.GONE);
        buildActions();
        image.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutImage());
        installGestures();
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        host.setContentView(root);
        load(index);
    }

    private void buildActions() {
        int focused = actions.indexOfChild(actions.getFocusedChild());
        actions.removeAllViews();
        add("previous","上一张",()->change(-1,true));
        add("next","下一张",()->change(1,true));
        add("minus","缩小",()->scale(1/1.4f,image.getWidth()/2f,image.getHeight()/2f));
        add("plus","放大",()->scale(1.4f,image.getWidth()/2f,image.getHeight()/2f));
        add("fit",cover?"适应屏幕":"充满屏幕",()->{if(slideshow)endShow();cover=!cover;resetTransform();buildActions();});
        add(slideshow&&!paused?"pause":"play",slideshow&&!paused?"暂停幻灯片":"播放幻灯片",this::toggleSlide);
        add("more","幻灯片设置",this::options);
        add("list","照片列表",this::showPhotos);
        TextView page=host.text((index+1)+" / "+photos.size(),20);
        page.setPadding(host.dp(12),0,0,0);
        page.setShadowLayer(host.dp(3),0,host.dp(1),android.graphics.Color.BLACK);
        actions.addView(page);
        for(int i=0;i<actions.getChildCount()-1;i++){
            View button=actions.getChildAt(i);
            button.setNextFocusUpId(back.getId());
            button.setNextFocusLeftId(actions.getChildAt(Math.max(0,i-1)).getId());
            button.setNextFocusRightId(actions.getChildAt(Math.min(actions.getChildCount()-2,i+1)).getId());
            button.setNextFocusDownId(button.getId());
        }
        back.setNextFocusDownId(actions.getChildAt(0).getId());
        updatePhotoTitle();
        if(focused>=0 && focused<actions.getChildCount()-1) actions.getChildAt(focused).requestFocus();
        scheduleToolbarHide();
    }

    private void add(String icon,String label,Runnable run) {
        Button button=host.iconButton(icon,label,run);
        LinearLayout.LayoutParams size=(LinearLayout.LayoutParams)button.getLayoutParams();
        size.setMargins(host.dp(3),0,host.dp(3),0);
        actions.addView(button);
    }

    private void showPhotos() {
        pauseShow();
        ListView list = new ListView(host);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        host.styleListFocus(list,0x2268c9dd);
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle("照片列表").setView(list).create();
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return photos.size(); }
            @Override public Object getItem(int position) { return photos.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                LinearLayout row = new LinearLayout(host);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(host.dp(12),host.dp(6),host.dp(12),host.dp(6));
                ImageView thumb = new ImageView(host);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(thumb,new LinearLayout.LayoutParams(host.dp(82),host.dp(62)));
                MainActivity.Entry item = photos.get(position);
                TextView caption = host.text((position==index?"✓  ":"")+item.name,20);
                caption.setSingleLine(true);
                caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
                Bitmap cached = thumbnails.get(item.url);
                if(cached!=null)thumb.setImageBitmap(cached);
                else {
                    thumb.setImageDrawable(new ActionIcon("photo"));
                    thumb.post(() -> thumbnailWorker.execute(() -> {
                        if(closed||!dialog.isShowing()||!thumb.isAttachedToWindow())return;
                        try {
                            Bitmap result = decode(host,item,220,150);
                            ui.post(() -> {
                                if(closed){result.recycle();return;}
                                thumbnails.put(item.url,result);
                                if(dialog.isShowing()&&thumb.isAttachedToWindow())thumb.setImageBitmap(result);
                            });
                        } catch(IOException|RuntimeException|OutOfMemoryError ignored) {}
                    }));
                }
                return row;
            }
        });
        list.setOnItemClickListener((parent,view,position,id) -> {
            dialog.dismiss();
            if(position!=index||bitmap==null)load(position);
        });
        host.showSidePanel(dialog,true);
        list.setItemChecked(index,true);
        list.setSelection(index);
        list.requestFocus();
    }

    private void options() {
        pauseShow();
        host.showItems("幻灯片设置",new String[]{"每页 "+seconds+" 秒","结束幻灯片"},choice->{
            if(choice==0)host.showItems("每页停留",new String[]{"3 秒","5 秒","10 秒","15 秒","30 秒"},i->{
                seconds=SECONDS[i];
                slideRemaining=seconds*1000L;
                host.mediaSettings().edit().putInt("slideSeconds",seconds).apply();
            });
            if(choice==1)endShow();
        });
    }

    private void installGestures() {
        GestureDetector tap=new GestureDetector(host,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){if(!dragged&&!pinch)toggleToolbar();return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(zoom>1){pauseShow();resetTransform();}
                else scale(2,e.getX(),e.getY());
                return true;
            }
        });
        ScaleGestureDetector magnify=new ScaleGestureDetector(host,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){pinch=true;scale(d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}
        });
        View.OnTouchListener gesture=(v,e)->{
            magnify.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=lastX=e.getX();touchY=lastY=e.getY();pinch=false;dragged=false;}
            if(e.getPointerCount()>1)pinch=true;
            if(!pinch)tap.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && !pinch){
                float dx=e.getX()-touchX,dy=e.getY()-touchY;
                if(Math.abs(dx)>host.dp(10)||Math.abs(dy)>host.dp(10)) dragged=true;
                if(dragged){
                    pauseShow();
                    if(!slideshow&&canPan()){x+=e.getX()-lastX;y+=e.getY()-lastY;layoutImage();}
                    else if(!slideshow&&Math.abs(dx)>Math.abs(dy))image.setTranslationX(dx*.55f);
                }
                lastX=e.getX();lastY=e.getY();
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                image.setTranslationX(0);
                float dx=e.getX()-touchX,dy=e.getY()-touchY;
                if(!pinch&&dragged&&(slideshow||!canPan())&&e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)>host.dp(55)&&Math.abs(dx)>Math.abs(dy))
                    change(dx<0?1:-1,true);
            }
            return true;
        };
        image.setOnTouchListener(gesture);
        slideStage.setOnTouchListener(gesture);
    }

    private void toggleToolbar() {
        exitAt=0;
        boolean show=toolbar.getVisibility()!=View.VISIBLE;
        setToolbarVisible(show);
        if(show) actions.getChildAt(0).requestFocusFromTouch();
        scheduleToolbarHide();
    }

    private void setToolbarVisible(boolean visible){
        int state=visible?View.VISIBLE:View.GONE;
        toolbar.setVisibility(state);
        header.setVisibility(state);
    }

    private void updatePhotoTitle(){
        int shown=displayedIndex>=0?displayedIndex:index;
        photoTitle.setText(photos.get(shown).name);
    }

    boolean key(KeyEvent event) {
        int code=event.getKeyCode();
        boolean ok=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_BUTTON_A;
        if(ok||code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_RIGHT){
            if(event.getAction()==KeyEvent.ACTION_DOWN)heldKeys.add(code);
            else if(event.getAction()==KeyEvent.ACTION_UP)heldKeys.remove(code);
            scheduleToolbarHide();
        }
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggleSlide();return true;}
        if(toolbar.isShown())return false;
        if(!loading && retry.isShown() && ok){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)retry.performClick();
            return true;
        }
        if(!ok && (code<KeyEvent.KEYCODE_DPAD_UP||code>KeyEvent.KEYCODE_DPAD_RIGHT))return false;
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
        exitAt=0;
        if(ok){if(event.getRepeatCount()==0){if(slideshow)toggleSlide();else toggleToolbar();}return true;}
        if(code==KeyEvent.KEYCODE_DPAD_UP&&slideshow&&zoom<=1){toggleToolbar();return true;}
        if(zoom>1){
            pauseShow();
            int step=host.dp(46);
            if(code==KeyEvent.KEYCODE_DPAD_LEFT)x+=step;
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT)x-=step;
            if(code==KeyEvent.KEYCODE_DPAD_UP)y+=step;
            if(code==KeyEvent.KEYCODE_DPAD_DOWN)y-=step;
            layoutImage();
        } else if(code==KeyEvent.KEYCODE_DPAD_UP)toggleToolbar();
        else if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)
            change(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,true);
        return true;
    }

    void back(){
        if(toolbar.isShown()){toggleToolbar();return;}
        if(slideshow){endShow();exitAt=0;return;}
        if(zoom>1||cover){cover=false;resetTransform();buildActions();exitAt=0;return;}
        long now=SystemClock.elapsedRealtime();
        if(exitAt>0&&now-exitAt<=2400){host.closeMediaViewer();return;}
        exitAt=now;
        Toast.makeText(host,"再次按返回退出图片浏览",Toast.LENGTH_SHORT).show();
    }

    void suspend(){heldKeys.clear();touchActive=false;pauseShow();scheduleToolbarHide();}
    String currentUrl(){return photos.get(index).url;}
    private void toggleSlide(){
        if(!slideshow){
            slideshow=true;paused=false;
            seconds=host.mediaSettings().getInt("slideSeconds",5);
            resetTransform();
            load(displayedIndex>=0?displayedIndex:index);
            return;
        }
        if(!paused){pauseShow();message("Ⅱ");return;}
        paused=false;
        status.setVisibility(View.GONE);
        if(animationX!=null&&animationX.isPaused())animationX.resume();
        if(animationY!=null&&animationY.isPaused())animationY.resume();
        if(slidePage==null&&!loading)load(index);
        else schedule(slideRemaining);
        buildActions();
    }

    private void message(String text){
        status.setText(text);status.setVisibility(View.VISIBLE);
        ui.postDelayed(()->{if(!loading && status.getText().toString().equals(text))status.setVisibility(View.GONE);},1100);
    }

    private void pauseShow(){
        if(!slideshow||paused)return;
        paused=true;
        slideRemaining=Math.max(300,slideDue-SystemClock.uptimeMillis());
        ui.removeCallbacks(advance);
        if(loading&&displayedIndex>=0&&index!=displayedIndex){
            generation++;
            if(job!=null)job.cancel(true);
            if(loadTimeout!=null)ui.removeCallbacks(loadTimeout);
            index=displayedIndex;
            loading=false;
            loadingIndicator.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
        }
        if(animationX!=null&&animationX.isStarted())animationX.pause();
        if(animationY!=null&&animationY.isStarted())animationY.pause();
        buildActions();
    }

    private void endShow(){
        slideshow=false;paused=false;stopMotion(true);
        generation++;
        if(loadTimeout!=null)ui.removeCallbacks(loadTimeout);
        if(job!=null)job.cancel(true);
        loading=false;loadingIndicator.setVisibility(View.GONE);status.setVisibility(View.GONE);retry.setVisibility(View.GONE);
        slideStage.removeAllViews();slideStage.setVisibility(View.GONE);slidePage=null;
        image.setVisibility(View.VISIBLE);
        if(displayedIndex>=0)index=displayedIndex;
        resetTransform();buildActions();
        if(bitmap==null)load(index);
    }

    private void schedule(long delay){
        ui.removeCallbacks(advance);
        if(slideshow&&!paused&&!loading){
            slideDue=SystemClock.uptimeMillis()+Math.max(300,delay);
            ui.postAtTime(advance,slideDue);
        }
    }

    private void advanceSlide(){
        if(!slideshow||paused||loading)return;
        int next=(index+slideCount)%photos.size();
        if(next==index){schedule(seconds*1000L);return;}
        load(next);
    }

    private void change(int delta,boolean manual){
        if(manual)pauseShow();
        int target=Math.floorMod(index+delta,photos.size());
        if(target!=index)load(target);
    }

    private Bitmap cachedPhoto(int position)throws IOException {
        MainActivity.Entry item=photos.get(position);
        synchronized(item){
            if(Thread.currentThread().isInterrupted()||closed)throw new InterruptedIOException();
            Bitmap ready=cache.get(position);
            if(ready!=null){
                android.util.Log.d("SmbPlayer","Photo session cache hit");
                return ready;
            }
            int width=Math.max(720,root.getResources().getDisplayMetrics().widthPixels);
            int height=Math.max(480,root.getResources().getDisplayMetrics().heightPixels);
            ready=decode(host,item,width,height);
            if(Thread.currentThread().isInterrupted()||closed){ready.recycle();throw new InterruptedIOException();}
            cache.put(position,ready);
            return ready;
        }
    }

    private void prefetchAround(int position) {
        if(prefetchJob!=null)prefetchJob.cancel(true);
        prefetchJob=prefetch.submit(()->{
            for(int distance=1;distance<=5;distance++){
                for(int candidate:new int[]{position+distance,position-distance}){
                    if(closed||Thread.currentThread().isInterrupted())return;
                    if(candidate<0||candidate>=photos.size())continue;
                    try{cachedPhoto(candidate);}catch(IOException|RuntimeException|OutOfMemoryError ignored){}
                }
            }
        });
    }

    private void load(int target){
        index=target;exitAt=0;retry.setVisibility(View.GONE);
        int token=++generation;
        loading=true;
        ui.removeCallbacks(advance);
        buildActions();
        status.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.VISIBLE);
        if(job!=null)job.cancel(true);
        if(prefetchJob!=null)prefetchJob.cancel(true);
        if(loadTimeout!=null)ui.removeCallbacks(loadTimeout);
        loadTimeout=()->{
            if(closed||token!=generation)return;
            generation++;
            job.cancel(true);
            showLoadFailure(target,"图片读取超时");
        };
        ui.postDelayed(loadTimeout,photos.get(target).url.startsWith("smb://")?30000:14000);
        boolean pairAllowed=slideshow;
        long started=SystemClock.elapsedRealtime();
        job=worker.submit(()->{
            Bitmap first=null,second=null;
            String error="无法打开图片";
            try{
                first=cachedPhoto(target);
                if(pairAllowed&&first.getHeight()>first.getWidth()&&target+1<photos.size()){
                    try{
                        Bitmap candidate=cachedPhoto(target+1);
                        if(candidate.getHeight()>candidate.getWidth())second=candidate;
                    }catch(IOException|RuntimeException|OutOfMemoryError ignored){}
                }
            }catch(EOFException incomplete){
                error="图片读取不完整";
            }catch(IOException|RuntimeException|OutOfMemoryError ignored){}
            Bitmap result=first,paired=second;
            String failure=error;
            ui.post(()->{
                if(closed||token!=generation)return;
                ui.removeCallbacks(loadTimeout);
                loading=false;
                loadingIndicator.setVisibility(View.GONE);
                if(result==null){showLoadFailure(target,failure);return;}
                android.util.Log.d("SmbPlayer","Photo ready in "+(SystemClock.elapsedRealtime()-started)+" ms");
                stopMotion(true);
                bitmap=result;displayedIndex=target;
                updatePhotoTitle();
                resetTransform();image.setImageBitmap(bitmap);layoutImage();
                status.setVisibility(View.GONE);
                if(slideshow)renderSlide(result,paired);
                prefetchAround(target);
            });
        });
    }

    private void renderSlide(Bitmap first,Bitmap second){
        slideCount=second==null?1:2;
        int nextEffect=effects.nextInt(2);
        if(nextEffect>=effect)nextEffect++;
        effect=nextEffect;
        FrameLayout page=new FrameLayout(host);
        page.setBackgroundColor(Color.BLACK);
        LinearLayout row=new LinearLayout(host);
        page.addView(row,new FrameLayout.LayoutParams(-1,-1));
        addSlideImage(row,first);
        if(second!=null)addSlideImage(row,second);
        FrameLayout previous=slidePage;
        slidePage=page;
        slideStage.setVisibility(View.VISIBLE);
        slideStage.addView(page,new FrameLayout.LayoutParams(-1,-1));
        image.setVisibility(View.INVISIBLE);
        page.setAlpha(previous==null?1f:0f);
        if(previous!=null)page.animate().alpha(1).setDuration(420)
                .withEndAction(()->slideStage.removeView(previous)).start();
        slideRemaining=seconds*1000L;
        if(!paused){motion();schedule(slideRemaining);}
    }

    private void addSlideImage(LinearLayout row,Bitmap source){
        FrameLayout frame=new FrameLayout(host);
        frame.setClipChildren(true);
        row.addView(frame,new LinearLayout.LayoutParams(0,-1,1));
        boolean portrait=source.getHeight()>source.getWidth();
        if(portrait){
            ImageView background=new ImageView(host);
            background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            background.setImageBitmap(Bitmap.createScaledBitmap(source,20,28,true));
            background.setColorFilter(0x66000000);
            frame.addView(background,new FrameLayout.LayoutParams(-1,-1));
        }
        ImageView front=new ImageView(host);
        front.setScaleType(portrait?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
        front.setImageBitmap(source);
        frame.addView(front,new FrameLayout.LayoutParams(-1,-1));
    }

    private void showLoadFailure(int target,String message) {
        loading=false;
        loadingIndicator.setVisibility(View.GONE);
        failedIndex=target;
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
        if(!toolbar.isShown())retry.requestFocusFromTouch();
        if(displayedIndex>=0){index=displayedIndex;buildActions();}
        pauseShow();
    }

    static Bitmap decode(MainActivity host,MainActivity.Entry entry,int width,int height)throws IOException {
        File source=File.createTempFile("photo-input-",".tmp",host.getCacheDir());
        try {
            long received=0;
            long expected=entry.url.startsWith("smb://")&&entry.size>0?entry.size:Long.MAX_VALUE;
            try(InputStream input=host.openAuxiliary(entry);FileOutputStream output=new FileOutputStream(source)){
                byte[] bytes=new byte[64*1024];
                while(received<expected){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    int count=input.read(bytes,0,(int)Math.min(bytes.length,expected-received));
                    if(count<0)break;
                    output.write(bytes,0,count);
                    received+=count;
                }
            }
            if(received==0||entry.size>0&&received<entry.size)throw new EOFException("Incomplete image transfer");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            if(Build.VERSION.SDK_INT>=28)return decodeComplete(source,width,height);
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(source.getPath(),options);
            if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Invalid image");
            boolean jpeg="image/jpeg".equals(options.outMimeType);
            if(jpeg)verifyJpegEnd(source);
            options.inJustDecodeBounds=false;
            options.inSampleSize=sampleSize(options.outWidth,options.outHeight,width,height);
            int orientation=1;
            if(jpeg){
                try{orientation=new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}
                catch(IOException|RuntimeException ignored){}
            }
            return orient(BitmapFactory.decodeFile(source.getPath(),options),orientation);
        } finally {
            source.delete();
        }
    }

    @android.annotation.TargetApi(28)
    private static Bitmap decodeComplete(File source,int width,int height)throws IOException{
        try{
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,ignored)->{
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(sampleSize(info.getSize().getWidth(),info.getSize().getHeight(),width,height));
                decoder.setOnPartialImageListener(error->false);
            });
        }catch(ImageDecoder.DecodeException error){
            if(error.getError()==ImageDecoder.DecodeException.SOURCE_INCOMPLETE)
                throw new EOFException("Incomplete image");
            throw error;
        }
    }

    private static int sampleSize(int w,int h,int width,int height){
        int sample=1;
        while((long)(w/sample)*(h/sample)>6_000_000L||w/sample>width*2||h/sample>height*2)sample*=2;
        return sample;
    }

    // Older decoders may accept partial JPEG scan data. Skip metadata segments and require EOI.
    private static void verifyJpegEnd(File source)throws IOException{
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(source),64*1024))){
            if(input.readUnsignedShort()!=0xffd8)throw new IOException("Invalid JPEG header");
            boolean scanning=false;
            while(true){
                int prefix=input.readUnsignedByte();
                if(prefix!=0xff){
                    if(!scanning)throw new IOException("Invalid JPEG marker");
                    continue;
                }
                int marker;
                do{marker=input.readUnsignedByte();}while(marker==0xff);
                if(scanning&&(marker==0||marker>=0xd0&&marker<=0xd7))continue;
                if(marker==0xd9)return;
                if(marker==1)continue;
                int size=input.readUnsignedShort()-2;
                if(size<0||input.skipBytes(size)!=size)throw new EOFException("Incomplete JPEG segment");
                scanning=marker==0xda||(scanning&&marker==0xdc);
            }
        }
    }

    private static Bitmap orient(Bitmap bitmap,int orientation)throws IOException{
        if(bitmap==null)throw new IOException("Invalid image");
        Matrix matrix=new Matrix();
        if(orientation==2||orientation==5||orientation==7)matrix.postScale(-1,1);
        if(orientation==3)matrix.postRotate(180);
        if(orientation==4)matrix.postScale(1,-1);
        if(orientation==5||orientation==6)matrix.postRotate(90);
        if(orientation==7||orientation==8)matrix.postRotate(270);
        if(matrix.isIdentity())return bitmap;
        Bitmap result=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
        if(result!=bitmap)bitmap.recycle();
        return result;
    }

    private float baseScale(){
        float w=(float)image.getWidth()/bitmap.getWidth(),h=(float)image.getHeight()/bitmap.getHeight();
        return cover?Math.max(w,h):Math.min(w,h);
    }
    private boolean canPan(){
        if(bitmap==null)return false;
        float scale=baseScale()*zoom;
        return zoom>1||bitmap.getWidth()*scale>image.getWidth()+1||bitmap.getHeight()*scale>image.getHeight()+1;
    }
    private void resetTransform(){zoom=1;x=y=0;layoutImage();}
    private void scale(float factor,float fx,float fy){
        if(slideshow)endShow();
        stopMotion(true);
        float old=zoom;zoom=Math.max(1,Math.min(5,zoom*factor));
        float cx=fx-image.getWidth()/2f,cy=fy-image.getHeight()/2f;
        x=cx+(x-cx)*zoom/old;y=cy+(y-cy)*zoom/old;
        layoutImage();
    }
    private void layoutImage(){
        if(bitmap==null||image.getWidth()==0||image.getHeight()==0)return;
        float s=baseScale()*zoom,w=bitmap.getWidth()*s,h=bitmap.getHeight()*s;
        float maxX=Math.max(0,(w-image.getWidth())/2),maxY=Math.max(0,(h-image.getHeight())/2);
        x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));
        matrix.setScale(s,s);matrix.postTranslate((image.getWidth()-w)/2+x,(image.getHeight()-h)/2+y);
        image.setImageMatrix(matrix);
    }
    private void stopMotion(boolean reset){
        ui.removeCallbacks(advance);
        if(animationX!=null)animationX.cancel();
        if(animationY!=null)animationY.cancel();
        if(reset&&slidePage!=null){slidePage.setScaleX(1);slidePage.setScaleY(1);}
    }

    private void motion(){
        if(!slideshow||paused||loading||effect==0||slidePage==null)return;
        float[] values=effect==1?new float[]{1,1.045f}:new float[]{1.045f,1};
        animationX=ObjectAnimator.ofFloat(slidePage,View.SCALE_X,values);
        animationY=ObjectAnimator.ofFloat(slidePage,View.SCALE_Y,values);
        for(ObjectAnimator animator:new ObjectAnimator[]{animationX,animationY}){
            animator.setInterpolator(new LinearInterpolator());
            animator.setDuration(seconds*1000L);
            animator.start();
        }
    }

    @Override public void close(){
        closed=true;generation++;stopMotion(true);ui.removeCallbacksAndMessages(null);
        if(job!=null)job.cancel(true);
        if(prefetchJob!=null)prefetchJob.cancel(true);
        worker.shutdownNow();prefetch.shutdownNow();thumbnailWorker.shutdownNow();
        thumbnails.evictAll();cache.close();
        slideStage.removeAllViews();slidePage=null;
        image.setImageDrawable(null);bitmap=null;
    }
}
