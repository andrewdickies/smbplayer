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
    private final FrameLayout root;
    private final ImageView image;
    private final HorizontalScrollView toolbar;
    private final LinearLayout actions;
    private final TextView status;
    private final Matrix matrix = new Matrix();
    private Bitmap bitmap;
    private int index, displayedIndex = -1, generation, seconds = 5, effect = 1;
    private boolean cover, slideshow, paused, loading, closed, pinch, dragged;
    private float zoom = 1, x, y, touchX, touchY, lastX, lastY;
    private Future<?> job;
    private ObjectAnimator animationX, animationY;
    private final Runnable advance = () -> change(1, false);

    PhotoViewer(MainActivity host, List<MainActivity.Entry> photos, int index) {
        this.host = host;
        this.photos = photos;
        this.index = index;
        root = new FrameLayout(host);
        root.setBackgroundColor(0xff000000);
        image = new ImageView(host);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(image, new FrameLayout.LayoutParams(-1,-1));
        status = host.text("正在读取图片…", 22);
        status.setBackgroundColor(0xb0181818);
        status.setPadding(host.dp(16),host.dp(9),host.dp(16),host.dp(9));
        root.addView(status,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        toolbar = new HorizontalScrollView(host);
        toolbar.setFocusable(false);
        toolbar.setHorizontalScrollBarEnabled(false);
        toolbar.setBackgroundColor(0xe0181818);
        actions = new LinearLayout(host);
        actions.setPadding(host.dp(16),host.dp(10),host.dp(16),host.dp(10));
        toolbar.addView(actions);
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        toolbar.setVisibility(View.GONE);
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
        add("fit",cover?"适应屏幕":"充满屏幕",()->{pauseShow();cover=!cover;resetTransform();buildActions();});
        add(slideshow&&!paused?"pause":"play",slideshow&&!paused?"暂停幻灯片":"播放幻灯片",this::toggleSlide);
        add("more","幻灯片设置",this::options);
        TextView page=host.text((index+1)+" / "+photos.size(),20);
        actions.addView(page);
        if(focused>=0 && focused<actions.getChildCount()-1) actions.getChildAt(focused).requestFocus();
    }

    private void add(String icon,String label,Runnable run) {
        Button button=host.iconButton(icon,label,run);
        actions.addView(button,new LinearLayout.LayoutParams(host.dp(66),host.dp(58)));
    }

    private void options() {
        pauseShow();
        host.showItems("幻灯片设置",new String[]{"每张 "+seconds+" 秒","切换方式","结束幻灯片"},choice->{
            if(choice==0)host.showItems("每张停留",new String[]{"3 秒","5 秒","10 秒","15 秒","30 秒"},i->{
                seconds=SECONDS[i];host.mediaSettings().edit().putInt("slideSeconds",seconds).apply();
            });
            if(choice==1)host.showItems("切换方式",new String[]{"直接切换","淡入淡出","缓慢推进","缓慢远离"},i->{
                effect=i;host.mediaSettings().edit().putInt("slideEffect",effect).apply();
            });
            if(choice==2)endShow();
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
        image.setOnTouchListener((v,e)->{
            magnify.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=lastX=e.getX();touchY=lastY=e.getY();pinch=false;dragged=false;}
            if(e.getPointerCount()>1)pinch=true;
            if(!pinch)tap.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && !pinch){
                float dx=e.getX()-touchX,dy=e.getY()-touchY;
                if(Math.abs(dx)>host.dp(10)||Math.abs(dy)>host.dp(10)) dragged=true;
                if(dragged){
                    pauseShow();
                    if(canPan()){x+=e.getX()-lastX;y+=e.getY()-lastY;layoutImage();}
                    else if(Math.abs(dx)>Math.abs(dy))image.setTranslationX(dx*.55f);
                }
                lastX=e.getX();lastY=e.getY();
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                image.setTranslationX(0);
                float dx=e.getX()-touchX,dy=e.getY()-touchY;
                if(!pinch&&dragged&&!canPan()&&e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)>host.dp(55)&&Math.abs(dx)>Math.abs(dy))
                    change(dx<0?1:-1,true);
            }
            return true;
        });
    }

    private void toggleToolbar() {
        boolean show=toolbar.getVisibility()!=View.VISIBLE;
        toolbar.setVisibility(show?View.VISIBLE:View.GONE);
        if(show) actions.getChildAt(0).requestFocusFromTouch();
    }

    boolean key(KeyEvent event) {
        int code=event.getKeyCode();
        boolean ok=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_BUTTON_A;
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggleSlide();return true;}
        if(toolbar.isShown())return false;
        if(!ok && (code<KeyEvent.KEYCODE_DPAD_UP||code>KeyEvent.KEYCODE_DPAD_RIGHT))return false;
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
        if(ok){if(event.getRepeatCount()==0){if(slideshow)toggleSlide();else toggleToolbar();}return true;}
        if(code==KeyEvent.KEYCODE_DPAD_UP&&slideshow&&zoom<=1){toggleToolbar();return true;}
        if(canPan()){
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

    boolean back(){
        if(toolbar.isShown()){toggleToolbar();return true;}
        if(slideshow){endShow();return true;}
        if(zoom>1||cover){cover=false;resetTransform();return true;}
        return false;
    }

    void suspend(){pauseShow();}
    String currentUrl(){return photos.get(index).url;}
    private void toggleSlide(){
        if(!slideshow){
            slideshow=true;paused=false;
            seconds=host.mediaSettings().getInt("slideSeconds",5);
            effect=host.mediaSettings().getInt("slideEffect",1);
            resetTransform();
        } else paused=!paused;
        if(paused){stopMotion(false);message("Ⅱ");}
        else {status.setVisibility(View.GONE);motion();schedule();}
        buildActions();
    }
    private void message(String text){
        status.setText(text);status.setVisibility(View.VISIBLE);
        ui.postDelayed(()->{if(!loading && status.getText().toString().equals(text))status.setVisibility(View.GONE);},1100);
    }
    private void pauseShow(){if(slideshow&&!paused){paused=true;stopMotion(false);buildActions();}}
    private void endShow(){slideshow=false;paused=false;stopMotion(true);buildActions();}
    private void schedule(){ui.removeCallbacks(advance);if(slideshow&&!paused&&!loading)ui.postDelayed(advance,seconds*1000L);}
    private void change(int delta,boolean manual){
        if(manual)pauseShow();
        int target=Math.floorMod(index+delta,photos.size());
        if(target!=index)load(target);else schedule();
    }

    private void load(int target){
        index=target;
        int token=++generation;
        loading=true;
        stopMotion(true);
        buildActions();
        status.setText("正在读取图片…");status.setVisibility(View.VISIBLE);
        if(job!=null)job.cancel(true);
        int width=Math.max(720,root.getResources().getDisplayMetrics().widthPixels);
        int height=Math.max(480,root.getResources().getDisplayMetrics().heightPixels);
        MainActivity.Entry item=photos.get(target);
        job=worker.submit(()->{
            Bitmap ready=null;
            try {ready=decode(host,item,width,height);}catch(IOException|RuntimeException|OutOfMemoryError ignored){}
            Bitmap result=ready;
            ui.post(()->{
                if(closed||token!=generation){if(result!=null)result.recycle();return;}
                loading=false;
                if(result==null){
                    status.setText("无法打开图片");
                    if(displayedIndex>=0){index=displayedIndex;buildActions();}
                    pauseShow();
                    return;
                }
                bitmap=result;displayedIndex=target;
                resetTransform();image.setImageBitmap(bitmap);layoutImage();
                status.setVisibility(View.GONE);
                if(slideshow&&!paused&&effect!=0){image.setAlpha(.22f);image.animate().alpha(1).setDuration(360).start();}
                motion();schedule();
            });
        });
    }

    static Bitmap decode(MainActivity host,MainActivity.Entry entry,int width,int height)throws IOException {
        BitmapFactory.Options p=new BitmapFactory.Options();
        p.inJustDecodeBounds=true;
        try(InputStream in=host.openAuxiliary(entry)){BitmapFactory.decodeStream(in,null,p);}
        if(p.outWidth<=0||p.outHeight<=0)throw new IOException("Invalid image");
        p.inJustDecodeBounds=false;p.inSampleSize=1;
        while((long)(p.outWidth/p.inSampleSize)*(p.outHeight/p.inSampleSize)>6_000_000L
                || (p.outWidth/p.inSampleSize>width*2 || p.outHeight/p.inSampleSize>height*2))p.inSampleSize*=2;
        Bitmap b;
        try(InputStream in=host.openAuxiliary(entry)){b=BitmapFactory.decodeStream(in,null,p);}
        if(b==null)throw new IOException("Invalid image");
        if(Build.VERSION.SDK_INT>=24 && entry.name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.jpe?g$")){
            try(InputStream in=host.openAuxiliary(entry)){
                int orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
                Matrix m=new Matrix();
                if(orientation==2||orientation==5||orientation==7)m.postScale(-1,1);
                if(orientation==3)m.postRotate(180);
                if(orientation==4)m.postScale(1,-1);
                if(orientation==5||orientation==6)m.postRotate(90);
                if(orientation==7||orientation==8)m.postRotate(270);
                if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b)b.recycle();b=rotated;}
            }catch(IOException ignored){}
        }
        return b;
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
        pauseShow();stopMotion(true);
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
        if(reset){image.setScaleX(1);image.setScaleY(1);}
    }
    private void motion(){
        if(!slideshow||paused||loading||effect<2)return;
        float[] values=effect==2?new float[]{1,1.09f}:new float[]{1.09f,1};
        animationX=ObjectAnimator.ofFloat(image,View.SCALE_X,values);
        animationY=ObjectAnimator.ofFloat(image,View.SCALE_Y,values);
        for(ObjectAnimator a:new ObjectAnimator[]{animationX,animationY}){a.setInterpolator(new LinearInterpolator());a.setDuration(seconds*1000L);a.start();}
    }
    @Override public void close(){
        closed=true;generation++;stopMotion(true);ui.removeCallbacksAndMessages(null);
        if(job!=null)job.cancel(true);worker.shutdownNow();
        image.setImageDrawable(null);bitmap=null;
    }
}
