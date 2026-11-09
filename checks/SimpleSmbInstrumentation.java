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
public final class SimpleSmbInstrumentation extends Instrumentation {
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
    void waitPrepared()throws Exception{for(int i=0;i<160;i++){boolean[] b={false};ui(()->b[0]=(boolean)get("prepared"));if(b[0])return;SystemClock.sleep(250);}throw new AssertionError("Not prepared");}
    void tapVideo()throws Exception{float[] p=new float[2];ui(()->{View v=(View)get("videoLayout");int[] at=new int[2];v.getLocationOnScreen(at);p[0]=at[0]+v.getWidth()/2f;p[1]=at[1]+v.getHeight()/4f;});pointer(p[0],p[1],MotionEvent.ACTION_DOWN);pointer(p[0],p[1],MotionEvent.ACTION_UP);SystemClock.sleep(350);waitForIdleSync();}
    void pointer(float x,float y,int action){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)pointerDownAt=now;MotionEvent e=MotionEvent.obtain(pointerDownAt,now,action,x,y,0);sendPointerSync(e);e.recycle();}
    public void onCreate(Bundle args){super.onCreate(args);testHost=args.getString("smbHost", "");testPort=args.getString("smbPort", "445");testSubdir=args.getString("smbSubdir", "");if(testHost.isEmpty()||testSubdir.startsWith("/")||testSubdir.contains(".."))throw new IllegalArgumentException("Explicit smbHost and relative smbSubdir required");start();}
    public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);
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
                for(Object item:(List<?>)get("entries")){if((boolean)field(item,"folder")){dirs++;String child=(String)field(item,"url");if(((String)field(item,"name")).equalsIgnoreCase("youtube"))children.add(0,child);else children.add(child);}else if((boolean)call("isVideo",new Class<?>[]{String.class},(String)field(item,"name"))){files++;if(video[0]==null)video[0]=item;}}
                for(String child:children)if(child.startsWith(target[0]))folders.addLast(child);report("SMB authorized subdirectory: "+dirs+" folders, "+files+" videos");});
        }
        ui(()->{check(video[0]!=null,"No videos in authorized test subtree");call("openVideo",new Class<?>[]{video[0].getClass()},video[0]);});
        waitPrepared();SystemClock.sleep(2800);ui(()->{check(playing()&&time()>0,"SMB playback did not advance");Object p=get("player");Object stats=call("videoStats",new Class<?>[]{p.getClass()},p);check(stats!=null&&((Number)field(stats,"displayedPictures")).intValue()>0,"SMB has no displayed pictures");report("SMB custom-port playback advanced, displayed pictures="+field(stats,"displayedPictures"));call("leaveVideo",new Class<?>[]{String.class},"");});
        result.putString("stream","SMB playback regression passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));result.putString("stream",sw.toString());finish(Activity.RESULT_CANCELED,result);}}
}
