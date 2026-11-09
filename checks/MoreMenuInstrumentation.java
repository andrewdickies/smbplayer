package com.smbplayer.checks;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Temporary test build only; checks menu visibility through the actual player. */
public final class MoreMenuInstrumentation extends Instrumentation {
    Activity activity;
    Object get(String name)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    interface Work{void run()throws Exception;}
    void ui(Work w)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{w.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    void check(boolean b,String text){if(!b)throw new AssertionError(text);}
    void bars(boolean visible)throws Exception{
        check((boolean)get("controlsShown")==visible,"Controls flag incorrect");
        for(String key:new String[]{"header","controls"})check(((View)get(key)).getVisibility()==(visible?View.VISIBLE:View.GONE),"Unexpected visible "+key);
    }
    void key(int code)throws Exception{sendKeyDownUpSync(code);SystemClock.sleep(250);waitForIdleSync();}
    void report(String text){Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b);}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        report("Launching More menu visibility checks");
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui(()->{
            ((SharedPreferences)get("settings")).edit().putInt("resumeMode",0).putInt("loop",0).putInt("shortcut.82",1).commit();
            Class<?> c=Class.forName("com.smbplayer.tv.MainActivity$Entry",true,getTargetContext().getClassLoader());Constructor<?> constructor=c.getDeclaredConstructor(String.class,String.class,boolean.class);constructor.setAccessible(true);
            File file=new File(getTargetContext().getFilesDir(),"more-fixture.mp4");check(file.isFile(),"Missing fixture");
            Object entry=constructor.newInstance("More menu fixture",android.net.Uri.fromFile(file).toString(),false);
            call("setPlaylist",new Class<?>[]{List.class,String.class},Collections.singletonList(entry),"More menu fixture");
            call("openVideo",new Class<?>[]{c},entry);
        });
        boolean prepared=false;for(int i=0;i<160;i++){boolean[] ready={false};ui(()->ready[0]=(boolean)get("prepared"));if(ready[0]){prepared=true;break;}SystemClock.sleep(250);}
        check(prepared,"Player not prepared");SystemClock.sleep(500);
        ui(()->{bars(true);((View)get("more")).performClick();bars(false);check(get("playbackDialog")!=null,"More did not open");});
        SystemClock.sleep(700);ui(()->bars(false));key(KeyEvent.KEYCODE_BACK);ui(()->{bars(false);check(get("playbackDialog")==null,"More not dismissed");});
        key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->{bars(true);check(activity.getCurrentFocus()==get("pause"),"Pause focus was not restored on reveal");});
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        ui(()->{
            call("dispatchKeyEvent",new Class<?>[]{KeyEvent.class},new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            call("dispatchKeyEvent",new Class<?>[]{KeyEvent.class},new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU));
            bars(false);AlertDialog more=(AlertDialog)get("playbackDialog");check(more!=null,"Menu shortcut did not open More");
            more.getListView().performItemClick(more.getListView().getChildAt(0),0,0);
            check(get("playbackDialog")!=more&&get("playbackDialog")!=null,"Nested subtitle menu not opened");bars(false);
        });
        key(KeyEvent.KEYCODE_BACK);ui(()->bars(false));
        ui(()->{((View)get("videoLayout")).performClick();bars(true);check(activity.getCurrentFocus()==get("pause"),"Screen reveal did not focus pause");});
        ui(()->call("leaveVideo",new Class<?>[]{String.class},""));
        result.putString("stream","More button, menu shortcut, nested menu, Back and pause-focus checks passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter out=new StringWriter();e.printStackTrace(new PrintWriter(out));result.putString("stream",out.toString());finish(Activity.RESULT_CANCELED,result);}}
}
