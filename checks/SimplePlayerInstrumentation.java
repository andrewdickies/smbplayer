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
public final class SimplePlayerInstrumentation extends Instrumentation {
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
    public void onCreate(Bundle args){super.onCreate(args);start();}
    String originalSources;
    void click(String text)throws Exception{ui(()->{Button b=button(text);check(b!=null,"Missing button: "+text);b.performClick();});SystemClock.sleep(150);}
    void choose(int index)throws Exception{ui(()->{AlertDialog d=(AlertDialog)get("playbackDialog");check(d!=null,"No selection menu");d.getListView().performItemClick(null,index,index);});SystemClock.sleep(170);}
    void waitBrowser()throws Exception{for(int i=0;i<160;i++){boolean[] ready={false};ui(()->ready[0]=(boolean)get("browsing")&&((List<?>)get("entries")).size()>0);if(ready[0]){SystemClock.sleep(250);return;}SystemClock.sleep(100);}throw new AssertionError("Browser did not load");}
    void addFolder(String id)throws Exception{ui(()->{android.net.Uri uri=DocumentsContract.buildTreeDocumentUri("com.smbplayer.tv.qa.documents",id);call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},10,Activity.RESULT_OK,new Intent().setData(uri));});SystemClock.sleep(220);}
    void listClick(int position)throws Exception{ui(()->{ListView list=find(activity.getWindow().getDecorView(),ListView.class);list.setSelection(position);list.performItemClick(null,position,position);});SystemClock.sleep(200);}
    public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(600);
        ui(()->{originalSources=call("homeSources",new Class<?>[]{}).toString();check(buttons(activity.getWindow().getDecorView()).size()==5,"Home has extra entries");});
        click("设置");ui(()->{check(get("playbackDialog")==null&&(boolean)get("settingsPage"),"Settings became a dialog");check(buttons(activity.getWindow().getDecorView()).size()==7,"Settings has extra options");snapshot(activity.getWindow().getDecorView(),"v26-settings-qa.png");});
        click("兼容性设置");ui(()->check(buttons(activity.getWindow().getDecorView()).size()==4,"Compatibility has extra entries"));key(4);click("默认字幕大小");click("超大");key(4);ui(()->check((boolean)get("dashboard"),"Settings did not return home"));
        ui(()->{((SharedPreferences)get("settings")).edit().putString("homeSources","[]").putInt("resumeMode",0).putInt("loop",0).putBoolean("gridView",false).commit();});
        click("本地存储");addFolder("one");addFolder("two");ui(()->{check(button("Folder One")!=null&&button("Folder Two")!=null,"Multiple local folders missing");Button add=button("添加");check(add.getLeft()+((View)add.getParent()).getLeft()>activity.getWindow().getDecorView().getWidth()/2,"Add action is not aligned right");check(button("主页")==null&&button("上一级")==null,"Duplicate navigation remains");button("添加").requestFocusFromTouch();});key(20);ui(()->check(activity.getCurrentFocus()==button("Folder One"),"Down skipped first folder"));key(20);ui(()->check(activity.getCurrentFocus()==button("Folder Two"),"Down skipped second folder"));key(19);
        click("Folder One");waitBrowser();ui(()->{check(((List<?>)get("entries")).size()==13,"SAF folder not read");ListView l=find(activity.getWindow().getDecorView(),ListView.class);View tile=l.getAdapter().getView(1,null,l);check(((TextView)field(tile,"fileDetails")).getText().toString().contains("2.0 MB"),"Size missing");snapshot(activity.getWindow().getDecorView(),"v26-browser-qa.png");});
        click("排序");choose(1);ui(()->check(((SharedPreferences)get("settings")).getBoolean("browserDescending",false),"Sort direction failed"));click("排序");choose(0);
        click("视图");choose(1);ui(()->check(find(activity.getWindow().getDecorView(),GridView.class)!=null,"Grid switch failed"));click("视图");choose(0);
        listClick(0);waitBrowser();key(4);waitBrowser();ui(()->check(((String)get("currentUrl")).contains("one"),"Folder Back failed"));
        report("Five home entries, concise settings, multi-folder SAF, right actions, sequential focus, sorting and view passed");
        listClick(6);waitPrepared();SystemClock.sleep(650);ui(()->{check(buttons((View)get("controls")).size()==5,"Bottom controls not reduced to five");check(((TextView)get("speedButton")).getText().toString().equals("1×"),"New video did not start at 1x");snapshot(activity.getWindow().getDecorView(),"v26-player-qa.png");call("showControls",new Class<?>[]{});});
        tapVideo();ui(()->{bars(false);check(playing(),"Tap paused playback");});tapVideo();ui(()->bars(true));key(23);ui(()->check((boolean)get("pausedPlayback"),"OK did not pause"));
        ui(()->((Button)get("speedButton")).performClick());choose(3);ui(()->check(((SharedPreferences)get("settings")).getInt("speedSteps",0)==40,"Speed choice failed"));
        ui(()->((Button)get("aspectButton")).performClick());choose(3);ui(()->check(((SharedPreferences)get("settings")).getInt("scale",0)==3,"Aspect choice failed"));
        ui(()->((Button)get("more")).performClick());ui(()->{bars(false);ListView l=((AlertDialog)get("playbackDialog")).getListView();check(l.getCount()==4,"More must contain four entries");check(l.getItemAtPosition(2).toString().contains("旋转"),"Rotation removed");});choose(2);choose(4);waitPrepared();SystemClock.sleep(600);
        ui(()->{check(((SharedPreferences)get("settings")).getInt("rotation",0)==4,"Flip not applied");check((boolean)get("pausedPlayback"),"Rotation rebuild lost pause");check(((SharedPreferences)get("settings")).getInt("speedSteps",0)==40,"Rebuild lost speed");});
        ui(()->call("showDelay",new Class<?>[]{boolean.class},true));SystemClock.sleep(250);key(22);ui(()->{check(((SharedPreferences)get("settings")).getLong("subtitleDelay",0)==100000,"Subtitle slider did not update");AlertDialog d=(AlertDialog)get("playbackDialog");check(d.getButton(-1)==null||d.getButton(-1).getVisibility()!=View.VISIBLE,"Slider has Apply");});key(4);
        key(19);float[] seek=new float[3];ui(()->{SeekBar bar=(SeekBar)get("timeline");int[] point=new int[2];bar.getLocationOnScreen(point);seek[0]=point[0]+bar.getPaddingLeft();seek[1]=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();seek[2]=point[1]+bar.getHeight()/2f;});pointer(seek[0]+seek[1]*.2f,seek[2],MotionEvent.ACTION_DOWN);pointer(seek[0]+seek[1]*.4f,seek[2],MotionEvent.ACTION_MOVE);pointer(seek[0]+seek[1]*.4f,seek[2],MotionEvent.ACTION_UP);SystemClock.sleep(600);ui(()->check(Math.abs(time()-24000)<2500&&(boolean)get("pausedPlayback"),"Touch seek/pause regressed"));key(4);
        key(20);ui(()->{bars(false);AlertDialog d=(AlertDialog)get("playbackDialog");check(d!=null,"Down did not open episode list");check(buttons(d.getWindow().getDecorView()).size()==1,"Episode list has redundant tools");snapshot(d.getWindow().getDecorView(),"v26-playlist-qa.png");Button mode=buttons(d.getWindow().getDecorView()).get(0);mode.performClick();check((int)get("playbackLoop")==1,"Loop cycle failed");mode.performClick();check((int)get("playbackLoop")==2,"List loop failed");});
        ui(()->{AlertDialog d=(AlertDialog)get("playbackDialog");ListView l=find(d.getWindow().getDecorView(),ListView.class);l.performItemClick(null,1,1);});waitPrepared();SystemClock.sleep(450);
        ui(()->{bars(false);SharedPreferences prefs=(SharedPreferences)get("settings");check(prefs.getInt("speedSteps",0)==20&&prefs.getInt("rotation",0)==0&&prefs.getInt("scale",-1)==0,"New episode kept old adjustments");check((int)get("playbackLoop")==2,"Playlist loop did not survive episode change");call("showControls",new Class<?>[]{});((Button)get("playerBack")).performClick();check(!(boolean)get("playing"),"Top return did not exit");});
        key(4);ui(()->{check(button("Folder One")!=null&&button("Folder Two")!=null,"Back lost saved folders");button("Folder Two").performLongClick();});choose(0);ui(()->check(button("Folder Two")==null&&button("Folder One")!=null,"Folder removal failed"));
        report("Five playback controls, tap/OK, aspect/speed, flip rebuild, live subtitle delay, concise episode list and folder removal passed");
        ui(()->{((SharedPreferences)get("settings")).edit().putString("homeSources",originalSources).commit();call("showHome",new Class<?>[]{String.class,String.class},"nas","");});
        result.putString("stream","Simple player regression passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));result.putString("stream",sw.toString());finish(Activity.RESULT_CANCELED,result);}}
}
