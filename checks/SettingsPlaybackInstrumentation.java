package com.smbplayer.checks;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Temporary test-build entry point. No test runner ships in the APK. */
public final class SettingsPlaybackInstrumentation extends Instrumentation {
    Activity activity;
    long pointerDownAt;
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    Object get(String name)throws Exception{return field(activity,name);}
    void set(String name,Object value)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    interface Work{void run()throws Exception;}
    void ui(Work w)throws Exception{Throwable[] e={null};runOnMainSync(()->{try{w.run();}catch(Throwable x){e[0]=x;}});if(e[0]!=null)throw new Exception(e[0]);}
    void check(boolean b,String text){if(!b)throw new AssertionError(text);}
    void report(String text){Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b);}
    void key(int code)throws Exception{sendKeyDownUpSync(code);SystemClock.sleep(230);waitForIdleSync();}
    void bars(boolean visible)throws Exception{check((boolean)get("controlsShown")==visible,"Wrong bars state");for(String n:new String[]{"header","controls"})check(((View)get(n)).getVisibility()==(visible?View.VISIBLE:View.GONE),"Wrong "+n+" visibility");}
    List<Button> buttons(View root){List<Button> out=new ArrayList<>();if(root instanceof Button)out.add((Button)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(buttons(((ViewGroup)root).getChildAt(i)));return out;}
    Button button(String text){for(Button b:buttons(activity.getWindow().getDecorView()))if(text.equals(b.getText().toString()))return b;return null;}
    <T> T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    void snapshot(View root,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    void visibleFocus(){View v=activity.getCurrentFocus();Rect r=new Rect();check(v!=null&&v.isEnabled()&&v.isShown()&&v.getGlobalVisibleRect(r)&&!r.isEmpty(),"Focus became invisible");}
    boolean playing()throws Exception{Object player=get("player");return (boolean)player.getClass().getMethod("isPlaying").invoke(player);}
    long time()throws Exception{Object player=get("player");return (long)player.getClass().getMethod("getTime").invoke(player);}
    void waitPrepared()throws Exception{for(int i=0;i<160;i++){boolean[] b={false};ui(()->b[0]=(boolean)get("prepared"));if(b[0])return;SystemClock.sleep(250);}throw new AssertionError("Not prepared");}
    void tapVideo()throws Exception{float[] p=new float[2];ui(()->{View v=(View)get("videoLayout");int[] at=new int[2];v.getLocationOnScreen(at);p[0]=at[0]+v.getWidth()/2f;p[1]=at[1]+v.getHeight()/4f;});pointer(p[0],p[1],MotionEvent.ACTION_DOWN);pointer(p[0],p[1],MotionEvent.ACTION_UP);SystemClock.sleep(350);waitForIdleSync();}
    void pointer(float x,float y,int action){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)pointerDownAt=now;MotionEvent e=MotionEvent.obtain(pointerDownAt,now,action,x,y,0);sendPointerSync(e);e.recycle();}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(650);
        ui(()->button("设置").performClick());SystemClock.sleep(200);
        ui(()->{check((boolean)get("settingsPage")&&get("playbackDialog")==null,"Settings must be a page");button("播放默认值").performClick();});
        ui(()->button("默认倍速").performClick());
        ui(()->{check(button("2.0×")!=null,"Speed defaults missing");button("2.0×").performClick();check(button("默认倍速")!=null,"Choice did not return one level");});
        key(4);ui(()->check(button("播放默认值")!=null,"Back did not return settings root"));key(4);ui(()->check((boolean)get("dashboard"),"Back did not return home"));
        ui(()->{
            SharedPreferences p=(SharedPreferences)get("settings");p.edit().putInt("speedSteps",20).putInt("resumeMode",0).putInt("loop",0).putBoolean("gridView",false).putInt("zoomSteps",0).putInt("rotation",0).putInt("picturePreset",0).putBoolean("customPicture",false).putBoolean("softwareDecode",false).commit();
            org.json.JSONArray sources=new org.json.JSONArray();for(int i=0;i<3;i++)sources.put(new org.json.JSONObject().put("id","qa"+i).put("kind","stream").put("name","QA source "+i).put("url","https://example.invalid/"+i));
            p.edit().putString("homeSources",sources.toString()).commit();set("homeFocusId","qa2");call("showHome",new Class<?>[]{String.class,String.class},"stream","");button("添加网络串流").requestFocusFromTouch();
        });key(20);ui(()->check(activity.getCurrentFocus()==button("QA source 0"),"Down skipped first source"));key(20);ui(()->check(activity.getCurrentFocus()==button("QA source 1"),"Down skipped second source"));key(20);ui(()->check(activity.getCurrentFocus()==button("QA source 2"),"Down skipped third source"));
        report("Hierarchical settings/back and first-row sequential focus passed");
        File fixture=new File(getTargetContext().getFilesDir(),"dashboard-fixture.mp4");List<Object> entries=new ArrayList<>();Object[] movie={null};
        ui(()->{Class<?> c=Class.forName("com.smbplayer.tv.MainActivity$Entry",true,getTargetContext().getClassLoader());Constructor<?> ctor=c.getDeclaredConstructor(String.class,String.class,boolean.class,long.class,long.class,long.class);ctor.setAccessible(true);movie[0]=ctor.newInstance("QA.mp4",android.net.Uri.fromFile(fixture).toString(),false,1794000000000L,60000L,512L);entries.add(movie[0]);set("sourceKind","local");call("setPlaylist",new Class<?>[]{List.class,String.class},entries,"QA");call("openVideo",new Class<?>[]{c},movie[0]);});
        waitPrepared();SystemClock.sleep(900);ui(()->{call("showControls",new Class<?>[]{});snapshot(activity.getWindow().getDecorView(),"v25-player-qa.png");});report("Before tap bars="+get("controlsShown"));tapVideo();report("After tap bars="+get("controlsShown"));ui(()->{bars(false);check(playing(),"Tap paused playback");});tapVideo();ui(()->{bars(true);check(playing(),"Tap reveal paused playback");check(activity.getCurrentFocus()==get("pause"),"Pause focus missing");});
        key(23);ui(()->check((boolean)get("pausedPlayback"),"OK did not pause"));tapVideo();ui(()->bars(false));tapVideo();ui(()->{bars(true);check((boolean)get("pausedPlayback"),"Tap resumed playback");});
        ui(()->((Button)get("speedButton")).performClick());ui(()->{AlertDialog d=(AlertDialog)get("playbackDialog");check(d.getListView().getCount()==6,"Wrong speed presets");d.getListView().performItemClick(null,3,3);});
        ui(()->{check(((SharedPreferences)get("settings")).getInt("speedSteps",0)==40,"Session speed not set");check(getTargetContext().getSharedPreferences("server",0).getInt("speedSteps",0)==20,"Session speed leaked to global");});
        ui(()->{Object player=get("player");player.getClass().getMethod("setTime",long.class,boolean.class).invoke(player,10000L,false);});SystemClock.sleep(600);
        ui(()->((Button)get("repeatButton")).performClick());ui(()->check((long)get("pointA")>=0&&(long)get("pointB")==-1,"A not set"));
        ui(()->{Object player=get("player");player.getClass().getMethod("setTime",long.class,boolean.class).invoke(player,16000L,false);});SystemClock.sleep(600);ui(()->((Button)get("repeatButton")).performClick());ui(()->check((long)get("pointB")>(long)get("pointA"),"B not set"));ui(()->((Button)get("repeatButton")).performClick());ui(()->check((long)get("pointA")==-1&&(long)get("pointB")==-1,"AB not off"));
        ui(()->{((SharedPreferences)get("settings")).edit().putLong("audioDelay",0).apply();call("showDelay",new Class<?>[]{boolean.class},false);AlertDialog d=(AlertDialog)get("playbackDialog");check(d.getButton(-1)==null||d.getButton(-1).getVisibility()!=View.VISIBLE,"Slider still has Apply");check(d.getButton(-2)==null||d.getButton(-2).getVisibility()!=View.VISIBLE,"Slider still has Cancel");SeekBar bar=find(d.getWindow().getDecorView(),SeekBar.class);bar.requestFocusFromTouch();});SystemClock.sleep(250);ui(()->{AlertDialog d=(AlertDialog)get("playbackDialog");SeekBar b=find(d.getWindow().getDecorView(),SeekBar.class);report("Slider initial="+b.getProgress()+" focus="+b.hasFocus()+" window="+d.getWindow().getDecorView().hasWindowFocus());snapshot(d.getWindow().getDecorView(),"v25-slider-qa.png");});key(22);ui(()->{SeekBar b=find(((AlertDialog)get("playbackDialog")).getWindow().getDecorView(),SeekBar.class);report("Slider after="+b.getProgress()+" focus="+b.hasFocus());});report("Delay value="+((SharedPreferences)get("settings")).getLong("audioDelay",0));
        ui(()->{check(((SharedPreferences)get("settings")).getLong("audioDelay",0)==100000,"Delay not applied in real time");});key(4);
        ui(()->{call("showFinePicture",new Class<?>[]{});AlertDialog d=(AlertDialog)get("playbackDialog");d.getListView().performItemClick(null,0,0);});SystemClock.sleep(200);ui(()->find(((AlertDialog)get("playbackDialog")).getWindow().getDecorView(),SeekBar.class).requestFocusFromTouch());key(22);SystemClock.sleep(700);waitPrepared();
        ui(()->{check(get("playbackDialog")!=null&&((AlertDialog)get("playbackDialog")).isShowing(),"Live picture slider closed during rebuild");check(((SharedPreferences)get("settings")).getFloat("pictureBrightness",1)>1,"Picture slider not applied");});key(4);
        ui(()->{((Button)get("more")).performClick();bars(false);AlertDialog d=(AlertDialog)get("playbackDialog");ListView l=d.getListView();for(int i=0;i<l.getCount();i++){String label=l.getItemAtPosition(i).toString();check(!label.equals("倍速")&&!label.equals("返回目录")&&!label.equals("播放设置"),"Duplicate/global More option: "+label);}});key(4);
        ui(()->{call("showControls",new Class<?>[]{});((Button)get("playerBack")).performClick();check(!(boolean)get("playing")&&(boolean)get("browsing"),"Header return did not go to directory");check(((SharedPreferences)get("settings")).getInt("speedSteps",0)==20,"Next video did not inherit defaults");});
        ui(()->{call("showDashboard",new Class<?>[]{});button("设置").performClick();button("画面默认值").performClick();button("自定义缩放").performClick();check(get("playbackDialog")==null,"Global slider became dialog");});key(4);key(4);key(4);ui(()->{check((boolean)get("dashboard"),"Slider Back hierarchy failed");button("设置").performClick();});
        SystemClock.sleep(300);ui(()->snapshot(activity.getWindow().getDecorView(),"v25-settings-qa.png"));
        report("Tap toggle, OK pause, speed presets, AB cycle, live slider, scoped settings and header return passed");
        result.putString("stream","Settings and playback interaction checks passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));result.putString("stream",sw.toString());finish(Activity.RESULT_CANCELED,result);}}
}
