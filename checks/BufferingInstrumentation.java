package com.smbplayer.checks;

import android.app.*;
import android.os.*;
import android.content.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Temporary test-build entry point. No test runner ships in the APK. */
public final class BufferingInstrumentation extends Instrumentation {
    Activity activity;
    String testHost, testPort, testSubdir;
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
    List<Button> buttons(View root){List<Button> out=new ArrayList<>();if(root.getVisibility()!=View.VISIBLE)return out;if(root instanceof Button)out.add((Button)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(buttons(((ViewGroup)root).getChildAt(i)));return out;}
    Button button(String text){for(Button b:buttons(activity.getWindow().getDecorView()))if(text.equals(b.getText().toString().replace("✓  ","")))return b;return null;}
    <T> T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    void snapshot(View root,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    void visibleFocus(){View v=activity.getCurrentFocus();Rect r=new Rect();check(v!=null&&v.isEnabled()&&v.isShown()&&v.getGlobalVisibleRect(r)&&!r.isEmpty(),"Focus became invisible");}
    boolean playing()throws Exception{Object player=get("player");return (boolean)player.getClass().getMethod("isPlaying").invoke(player);}
    long time()throws Exception{Object player=get("player");return (long)player.getClass().getMethod("getTime").invoke(player);}
    void waitPrepared()throws Exception{for(int i=0;i<160;i++){boolean[] ready={false};ui(()->{ready[0]=(boolean)get("prepared");if(!(boolean)get("playing"))throw new AssertionError("Playback left before preparation; overlay="+((TextView)get("loadingText")).getText());});if(ready[0])return;SystemClock.sleep(250);}ui(()->{Object p=get("player");throw new AssertionError("Not prepared; state="+(p==null?"null":p.getClass().getMethod("getPlayerState").invoke(p))+" requestedPause="+get("pauseRequested"));});}
    void tapVideo()throws Exception{float[] p=new float[2];ui(()->{View v=(View)get("videoLayout");int[] at=new int[2];v.getLocationOnScreen(at);p[0]=at[0]+v.getWidth()/2f;p[1]=at[1]+v.getHeight()/4f;});pointer(p[0],p[1],MotionEvent.ACTION_DOWN);pointer(p[0],p[1],MotionEvent.ACTION_UP);SystemClock.sleep(350);waitForIdleSync();}
    void pointer(float x,float y,int action){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)pointerDownAt=now;MotionEvent e=MotionEvent.obtain(pointerDownAt,now,action,x,y,0);sendPointerSync(e);e.recycle();}
    public void onCreate(Bundle args){super.onCreate(args);testHost=args.getString("smbHost", "");testPort=args.getString("smbPort", "445");testSubdir=args.getString("smbSubdir", "");if(testHost.isEmpty()||testSubdir.startsWith("/")||testSubdir.contains(".."))throw new IllegalArgumentException("Explicit smbHost and relative smbSubdir required");start();}
    void silent(){android.media.AudioManager a=(android.media.AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);check(a.getStreamVolume(3)==0,"Night test must remain muted");}
    void healthy()throws Exception{for(int i=0;i<160;i++){boolean[] ready={false};ui(()->{check((boolean)get("playing"),"Playback exited unexpectedly");Object p=get("player");ready[0]=(boolean)get("prepared")&&p!=null&&get("gatedPlayer")==null&&!(boolean)get("buffering")&&playing();silent();});if(ready[0])return;SystemClock.sleep(200);}throw new AssertionError("Video did not recover output");}
    long displayed()throws Exception{Object p=get("player");Object stats=call("videoStats",new Class<?>[]{p.getClass()},p);return ((Number)field(stats,"displayedPictures")).longValue();}
    public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);ui(()->{silent();((SharedPreferences)get("settings")).edit().putInt("resumeMode",0).putInt("passthroughMode",1).putBoolean("softwareDecode",false).commit();});
        Object[] fixture={null};ui(()->{Class<?> c=Class.forName("com.smbplayer.tv.MainActivity$Entry",true,getTargetContext().getClassLoader());Constructor<?> ctor=c.getDeclaredConstructor(String.class,String.class,boolean.class,long.class,long.class,long.class);ctor.setAccessible(true);fixture[0]=ctor.newInstance("Night recovery fixture.mp4",android.net.Uri.fromFile(new File(getTargetContext().getFilesDir(),"dashboard-fixture.mp4")).toString(),false,0L,60000L,448218L);call("setPlaylist",new Class<?>[]{List.class,String.class},Collections.singletonList(fixture[0]),"Recovery fixture");call("openVideo",new Class<?>[]{c},fixture[0]);});
        waitPrepared();healthy();ui(()->{check(displayed()>0,"No initial picture");check((int)get("gatedVolume")==-1,"Audio gate leaked after initial output");check((int)get("player").getClass().getMethod("getVolume").invoke(get("player"))==100,"Player volume not restored: "+get("player").getClass().getMethod("getVolume").invoke(get("player")));((Runnable)get("hideControls")).run();});
        ui(()->call("performSeek",new Class<?>[]{long.class},22000L));healthy();ui(()->{bars(false);check(time()>=21000&&time()<28000,"Seek target lost");call("requestPause",new Class<?>[]{boolean.class},true);});SystemClock.sleep(450);
        ui(()->{check((boolean)get("pauseRequested")&&!playing(),"Pause intent lost");call("performSeek",new Class<?>[]{long.class},10000L);});SystemClock.sleep(500);ui(()->{check((boolean)get("pauseRequested")&&!playing(),"Paused seek auto-resumed");call("requestPause",new Class<?>[]{boolean.class},false);});healthy();
        report("Silent startup, seek, paused seek, resume-first-frame and hidden chrome passed");
        Object[] old={null};ui(()->{old[0]=get("player");old[0].getClass().getMethod("detachViews").invoke(old[0]);});
        boolean[] sawWait={false};for(int i=0;i<35;i++){ui(()->{silent();if((boolean)get("buffering")&&get("gatedPlayer")!=null)sawWait[0]=true;});if(sawWait[0])break;SystemClock.sleep(150);}
        ui(()->{check(sawWait[0],"Renderer loss was not detected without Buffering event");check(((TextView)get("loadingText")).getText().toString().startsWith("正在缓冲"),"Wrong recovery wording");check(!((TextView)get("loadingText")).getText().toString().contains("恢复"),"Recovery phase visible");call("requestPause",new Class<?>[]{boolean.class},true);});SystemClock.sleep(600);
        ui(()->{check((boolean)get("pauseRequested")&&!playing(),"Pause during stall did not remain paused");call("requestPause",new Class<?>[]{boolean.class},false);});healthy();
        ui(()->{check(get("player")!=old[0],"Lost surface did not recreate decoder");check(displayed()>0,"Recreated decoder has no pictures");bars(false);call("leaveVideo",new Class<?>[]{String.class},"");});
        report("Actual renderer-loss fault, pause while frozen, resume without Buffering and decoder recreation passed");
        org.json.JSONObject[] source={null};ui(()->{org.json.JSONArray all=(org.json.JSONArray)call("homeSources",new Class<?>[]{});for(int i=0;i<all.length();i++){org.json.JSONObject item=all.optJSONObject(i);if(item!=null&&"nas".equals(item.optString("kind"))&&testHost.equals(item.optString("host"))&&testPort.equals(item.optString("port"))){source[0]=item;break;}}check(source[0]!=null,"No saved SMB source");((SharedPreferences)get("settings")).edit().putInt("resumeMode",0).commit();call("showHome",new Class<?>[]{String.class,String.class},"nas","");call("openHomeSource",new Class<?>[]{org.json.JSONObject.class},source[0]);});
        for(int i=0;i<200;i++){if(get("connection")!=null&&get("currentUrl")!=null)break;SystemClock.sleep(100);}
        String[] target={null};ui(()->{String root=(String)get("connectionRoot");check(root!=null,"No SMB connection");target[0]=root+testSubdir+(testSubdir.isEmpty()||testSubdir.endsWith("/")?"":"/");call("load",new Class<?>[]{String.class,String.class},target[0],null);});
        for(int i=0;i<300;i++){if(target[0].equals(get("currentUrl")))break;SystemClock.sleep(100);}
        Object[] video={null};java.util.ArrayDeque<String> folders=new java.util.ArrayDeque<>();folders.add(target[0]);java.util.Set<String> seen=new java.util.HashSet<>();
        for(int scan=0;scan<8&&!folders.isEmpty()&&video[0]==null;scan++){
            String folder=folders.removeFirst();if(!seen.add(folder))continue;
            ui(()->call("load",new Class<?>[]{String.class,String.class},folder,null));
            for(int i=0;i<300;i++){if(folder.equals(get("currentUrl")))break;SystemClock.sleep(100);}SystemClock.sleep(120);
            ui(()->{check(folder.equals(get("currentUrl")),"SMB directory did not load");int dirs=0,files=0;java.util.List<String> children=new java.util.ArrayList<>();
                for(Object item:(List<?>)get("entries")){if((boolean)field(item,"folder")){dirs++;String child=(String)field(item,"url");if(((String)field(item,"name")).equalsIgnoreCase("youtube"))children.add(0,child);else children.add(child);}else if((boolean)call("isVideo",new Class<?>[]{String.class},(String)field(item,"name"))){files++;if(video[0]==null||((long)field(item,"size")>=64L*1024*1024&&((long)field(video[0],"size")<64L*1024*1024||(long)field(item,"size")<(long)field(video[0],"size"))))video[0]=item;}}
                for(String child:children)if(child.startsWith(target[0]))folders.addLast(child);report("SMB authorized subdirectory: "+dirs+" folders, "+files+" videos");});
        }
        ui(()->{check(video[0]!=null,"No videos in authorized test subtree");call("openVideo",new Class<?>[]{video[0].getClass()},video[0]);});
        waitPrepared();healthy();
        long[] sample=new long[4];ui(()->{silent();Object server=get("playbackServer");check(server!=null,"SMB cache channel not active");sample[0]=(long)callObject(server,"streamedRemoteBytes");sample[1]=SystemClock.elapsedRealtime();call("requestPause",new Class<?>[]{boolean.class},true);});
        SystemClock.sleep(6000);
        ui(()->{silent();Object server=get("playbackServer");long capacity=(long)callObject(server,"cacheCapacity"),ahead=(long)callObject(server,"forwardBytes"),downloaded=(long)callObject(server,"streamedRemoteBytes");check(capacity>64L*1024*1024,"Cache did not grow beyond old cap for selected video");check(ahead>4L*1024*1024,"No independent forward disk fill while paused");check(!playing()&&(boolean)get("pauseRequested"),"Prefill auto-resumed playback");report("Paused SMB: capacity="+capacity/1048576+" MiB, forward="+ahead/1048576+" MiB, downloaded while paused="+(downloaded-sample[0])/1048576+" MiB");call("updateNetworkRate",new Class<?>[]{});String label=(String)call("bufferingLabel",new Class<?>[]{});check(label.contains("正在缓冲")&&label.contains("/s")&&!label.contains("恢复"),"Missing unified buffering/speed label");call("requestPause",new Class<?>[]{boolean.class},false);});healthy();
        ui(()->{long length=((Number)get("observedDuration")).longValue();call("performSeek",new Class<?>[]{long.class},length/2);});healthy();
        ui(()->{silent();check(displayed()>0,"SMB seek resumed audio without pictures");call("leaveVideo",new Class<?>[]{String.class},"");});
        result.putString("stream","Muted forward-cache and video recovery device regression passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));result.putString("stream",sw.toString());finish(Activity.RESULT_CANCELED,result);}}
    Object callObject(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
}
