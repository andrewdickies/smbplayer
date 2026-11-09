package com.smbplayer.checks;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Temporary test-build instrumentation, never included in the delivered APK. */
public final class NavigationInstrumentation extends Instrumentation {
    private Activity activity;
    private Object get(Object o,String name) throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private void set(Object o,String name,Object value) throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
    private Object call(Object o,String name,Class<?>[] types,Object... args) throws Exception { Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args); }
    interface Work { void run() throws Exception; }
    private void ui(Work w) throws Exception { Throwable[] error={null};runOnMainSync(()->{try{w.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new Exception(error[0]); }
    private void check(boolean b,String message) {if(!b)throw new AssertionError(message);}
    private void report(String message) {Bundle b=new Bundle();b.putString("stream",message+"\n");sendStatus(0,b);}
    private void key(int key) throws Exception {sendKeyDownUpSync(key);SystemClock.sleep(180);waitForIdleSync();}
    private Button button(View root,String label) {
        if(root instanceof Button && label.equals(((Button)root).getText().toString()))return (Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button b=button(((ViewGroup)root).getChildAt(i),label);if(b!=null)return b;}
        return null;
    }
    private ListView list(View root) {
        if(root instanceof ListView)return (ListView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){ListView b=list(((ViewGroup)root).getChildAt(i));if(b!=null)return b;}
        return null;
    }
    private void focused() throws Exception {
        View v=activity.getCurrentFocus();check(v!=null&&v.isShown()&&v.isEnabled(),"Focus disappeared");
        check(v instanceof Button||v instanceof SeekBar||v instanceof AbsListView,"Focus entered an invisible/noninteractive container: "+v.getClass());
        android.graphics.Rect visible=new android.graphics.Rect();check(v.getGlobalVisibleRect(visible)&&!visible.isEmpty(),"Focused control is outside the screen");
        if(v instanceof AbsListView)check(((AbsListView)v).getSelectedItemPosition()>=0,"List focus has no selected row");
    }
    private void bars(boolean visible) throws Exception {
        check((boolean)get(activity,"controlsShown")==visible,"Controls visibility flag incorrect");
        for(String field:new String[]{"controls","header"})check(((View)get(activity,field)).getVisibility()==(visible?View.VISIBLE:View.GONE),"Unexpected visible "+field);
    }
    private void waitPrepared() throws Exception {
        for(int i=0;i<160;i++){boolean[] ready={false};ui(()->ready[0]=(boolean)get(activity,"prepared"));if(ready[0])return;SystemClock.sleep(250);}
        throw new AssertionError("Playback not prepared");
    }
    private List<String> names(ListView list) throws Exception {
        List<String> result=new ArrayList<>();for(int i=0;i<list.getCount();i++)result.add((String)get(list.getAdapter().getItem(i),"name"));return result;
    }
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        report("Launching TV navigation checks");
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int attempt=0;attempt<40;attempt++){
            boolean[] window={false};ui(()->window[0]=activity.hasWindowFocus());if(window[0])break;SystemClock.sleep(100);
        }
        ui(()->check(activity.hasWindowFocus(),"Test activity has not received window input focus"));
        ui(()->{
            SharedPreferences p=(SharedPreferences)get(activity,"settings");p.edit().putInt("resumeMode",0).putInt("loop",0).putInt("playlistSort",0).putBoolean("playlistDescending",false).putInt("browserSort",1).putBoolean("browserDescending",false).putBoolean("gridView",false).commit();
            call(activity,"showHome",new Class<?>[]{String.class,String.class},"local","");
            ((Map<String,Button>)get(activity,"sourceTabs")).get("local").requestFocusFromTouch();
        });
        for(String expected:new String[]{"nas","m3u","stream"}){
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            for(int attempt=0;attempt<15;attempt++){
                boolean[] changed={false};ui(()->changed[0]=expected.equals(get(activity,"sourceKind")));if(changed[0])break;SystemClock.sleep(100);
            }
            ui(()->{check(expected.equals(get(activity,"sourceKind")),"Category did not switch on focus: "+expected);check(!(boolean)get(activity,"browsing"),"Source opened without confirmation");check(activity.getCurrentFocus()==((Map<?,?>)get(activity,"sourceTabs")).get(expected),"Category switch stole focus");focused();});
        }
        for(int code:new int[]{19,22,20,20,20,20,21,21,19,19,19,21,21,21,19,20,20,22,19,22,22,22}){
            key(code);ui(this::focused);
        }
        report("Category focus auto-switch and home directional boundaries passed");
        File fixture=new File(getTargetContext().getFilesDir(),"navigation-fixture.mp4");check(fixture.isFile(),"Missing local fixture");
        File beta=new File(fixture.getParentFile(),"navigation-beta.mp4"),gamma=new File(fixture.getParentFile(),"navigation-gamma.mp4");
        for(File target:new File[]{beta,gamma})try(FileInputStream in=new FileInputStream(fixture);FileOutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);
        }
        Object[] entry={null};List<Object> entries=new ArrayList<>();
        ui(()->{
            Class<?> c=Class.forName("com.smbplayer.tv.MainActivity$Entry",true,getTargetContext().getClassLoader());Constructor<?> ctor=c.getDeclaredConstructor(String.class,String.class,boolean.class,long.class,long.class,long.class);ctor.setAccessible(true);
            entries.add(ctor.newInstance("Alpha.mp4",android.net.Uri.fromFile(fixture).toString(),false,300L,60000L,100L));
            entries.add(ctor.newInstance("Beta.mp4",android.net.Uri.fromFile(beta).toString(),false,100L,60000L,200L));
            entries.add(ctor.newInstance("Gamma.mp4",android.net.Uri.fromFile(gamma).toString(),false,200L,60000L,300L));
            entry[0]=entries.get(0);set(activity,"sourceKind","stream");call(activity,"setPlaylist",new Class<?>[]{List.class,String.class},entries,"Navigation fixture");
            button(activity.getWindow().getDecorView(),"排序：名称").performClick();
            check(((SharedPreferences)get(activity,"settings")).getInt("browserSort",-1)==2,"Browser modification-date mode not selected");
            check(names(list(activity.getWindow().getDecorView())).equals(Arrays.asList("Beta.mp4","Gamma.mp4","Alpha.mp4")),"Browser modification date order incorrect");
            check(activity.getCurrentFocus() instanceof Button&&((Button)activity.getCurrentFocus()).getText().toString().startsWith("排序："),"Browser sort button lost focus");
            button(activity.getWindow().getDecorView(),"正序").performClick();
            check(names(list(activity.getWindow().getDecorView())).equals(Arrays.asList("Alpha.mp4","Gamma.mp4","Beta.mp4")),"Browser reverse incorrect");
        });
        ui(()->{
            SharedPreferences p=(SharedPreferences)get(activity,"settings");p.edit().putBoolean("gridView",true).commit();
            call(activity,"renderBrowser",new Class<?>[]{String.class,boolean.class},"",true);
        });
        for(int code:new int[]{22,22,22,22,20,20,21,21,21,19,19,20}){key(code);ui(this::focused);}
        ui(()->{
            ((SharedPreferences)get(activity,"settings")).edit().putBoolean("gridView",false).commit();
            call(activity,"setPlaylist",new Class<?>[]{List.class,String.class},entries,"Navigation fixture");
            call(activity,"openVideo",new Class<?>[]{entry[0].getClass()},entry[0]);
        });
        waitPrepared();SystemClock.sleep(700);
        ui(()->{bars(true);check(activity.getCurrentFocus()==get(activity,"pause"),"Initial controls not focused on pause");activity.onBackPressed();bars(false);});
        key(KeyEvent.KEYCODE_DPAD_UP);
        ui(()->{bars(false);AlertDialog d=(AlertDialog)get(activity,"playbackDialog");check(d!=null,"Up did not open playlist");ListView l=list(d.getWindow().getDecorView());check(l.hasFocus()&&l.getSelectedItemPosition()==0,"Playlist initial focus incorrect");check(button(d.getWindow().getDecorView(),"不循环")!=null,"Loop is not in toolbar");});
        ui(()->{
            View decor=((AlertDialog)get(activity,"playbackDialog")).getWindow().getDecorView();
            android.graphics.Bitmap screenshot=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(screenshot));
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"navigation-playlist.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();
        });
        key(KeyEvent.KEYCODE_BACK);SystemClock.sleep(400);ui(()->{bars(false);check(get(activity,"playbackDialog")==null,"Playlist did not dismiss");});
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        ui(()->{
            bars(false);AlertDialog d=(AlertDialog)get(activity,"playbackDialog");View root=d.getWindow().getDecorView();ListView l=list(root);
            check(names(l).equals(Arrays.asList("Alpha.mp4","Beta.mp4","Gamma.mp4")),"Playlist file-size order incorrect");
            Button loop=button(root,"不循环");loop.requestFocusFromTouch();loop.performClick();check(button(root,"单曲循环")!=null&&get(activity,"playbackDialog")==d,"Loop opened another dialog");
            Button sort=button(root,"大小");sort.requestFocusFromTouch();sort.performClick();check(names(l).equals(Arrays.asList("Alpha.mp4","Beta.mp4","Gamma.mp4")),"Playlist name order incorrect");
            sort.performClick();check(names(l).equals(Arrays.asList("Beta.mp4","Gamma.mp4","Alpha.mp4")),"Playlist modification date order incorrect");
            sort.performClick();List<String> random=names(l);Button reverse=button(root,"正序");reverse.requestFocusFromTouch();reverse.performClick();Collections.reverse(random);check(names(l).equals(random),"Random reverse changed random ordering");
            sort.requestFocusFromTouch();sort.performClick();check(names(l).equals(Arrays.asList("Gamma.mp4","Beta.mp4","Alpha.mp4")),"Size descending incorrect");
            bars(false);check(sort.hasFocus(),"Sorting stole toolbar focus");
        });
        key(KeyEvent.KEYCODE_BACK);ui(()->bars(false));
        ui(()->{((View)get(activity,"videoLayout")).performClick();bars(true);check(activity.getCurrentFocus()==get(activity,"pause"),"Screen click reveal did not focus pause");});
        SystemClock.sleep(400);key(KeyEvent.KEYCODE_BACK);ui(()->bars(false));
        ui(()->{((View)get(activity,"videoLayout")).performClick();bars(true);});
        SystemClock.sleep(400);key(KeyEvent.KEYCODE_BACK);ui(()->bars(false));
        key(KeyEvent.KEYCODE_DPAD_CENTER);SystemClock.sleep(400);ui(()->{bars(true);check(activity.getCurrentFocus()==get(activity,"pause"),"Confirm reveal did not focus pause");check((boolean)get(activity,"pausedPlayback"),"Hidden confirm did not pause");});
        key(KeyEvent.KEYCODE_DPAD_CENTER);SystemClock.sleep(250);
        for(int code:new int[]{22,22,22,22,22,22,20,20,19,19,19,20,21,21,21,21,21,20,20}){key(code);ui(()->{bars(true);focused();View focus=activity.getCurrentFocus();check(Arrays.asList(get(activity,"timeline"),get(activity,"pause"),get(activity,"previousVideo"),get(activity,"nextVideo"),get(activity,"playlist"),get(activity,"more")).contains(focus),"Playback focus escaped");});}
        ui(()->{((View)get(activity,"pause")).requestFocusFromTouch();activity.onBackPressed();bars(false);});
        key(KeyEvent.KEYCODE_DPAD_UP);
        ui(()->{AlertDialog d=(AlertDialog)get(activity,"playbackDialog");ListView l=list(d.getWindow().getDecorView());l.requestFocusFromTouch();l.setSelection(1);});
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitPrepared();SystemClock.sleep(450);ui(()->bars(false));
        key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->{bars(true);check(activity.getCurrentFocus()==get(activity,"pause"),"New movie reveal lost pause focus");});
        int[] location=new int[2];float[] track=new float[3];
        ui(()->{SeekBar bar=(SeekBar)get(activity,"timeline");bar.getLocationOnScreen(location);track[0]=location[0]+bar.getPaddingLeft();track[1]=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();track[2]=location[1]+bar.getHeight()/2f;check((boolean)get(activity,"pausedPlayback"),"Touch test must start paused");});
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<3;i++){
            int action=i==0?android.view.MotionEvent.ACTION_DOWN:i==2?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
            android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,track[0]+track[1]*(i==0?.2f:.7f),track[2],0);sendPointerSync(event);event.recycle();SystemClock.sleep(100);
            if(i==1)ui(()->{check((boolean)get(activity,"dragging"),"Touch drag did not start");bars(true);});
        }
        SystemClock.sleep(900);
        long[] position={0};
        ui(()->{Object p=get(activity,"player");long t=(long)p.getClass().getMethod("getTime").invoke(p);position[0]=t;check(Math.abs(t-42000)<2500,"Touch drag did not seek to the target: "+t);check((boolean)get(activity,"pausedPlayback"),"Touch seek resumed paused playback");});
        down=SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_CANCEL}){
            android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,track[0]+track[1]*.3f,track[2],0);sendPointerSync(event);event.recycle();SystemClock.sleep(80);
        }
        SystemClock.sleep(400);
        ui(()->{Object p=get(activity,"player");long t=(long)p.getClass().getMethod("getTime").invoke(p);check(Math.abs(t-position[0])<1000,"Cancelled drag unexpectedly committed a seek");check(!(boolean)get(activity,"dragging"),"Cancelled drag left controls stuck");});
        report("Touch seek, target time, pause preservation and cancelled-drag checks passed");
        ui(()->call(activity,"leaveVideo",new Class<?>[]{String.class},""));
        report("Playlist isolation, top controls, sorting, reverse, pause focus and playback boundaries passed");
        result.putString("stream","TV navigation integration checks passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));result.putString("stream",s.toString());finish(Activity.RESULT_CANCELED,result);}}
}
