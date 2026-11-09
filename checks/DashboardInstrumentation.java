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
public final class DashboardInstrumentation extends Instrumentation {
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
        report("Launching five-entry dashboard checks");
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<40;i++){boolean[] b={false};ui(()->b[0]=activity.hasWindowFocus());if(b[0])break;SystemClock.sleep(100);}
        ui(()->{
            check((boolean)get("dashboard"),"Startup did not show dashboard");
            List<Button> cards=buttons(activity.getWindow().getDecorView());check(cards.size()==5,"Dashboard has extra controls");
            for(String title:new String[]{"本地存储","SMB 网络","M3U / M3U8","网络串流","设置"}){Button b=button(title);check(b!=null&&b.getCompoundDrawables()[1]!=null,"Missing icon/text entry: "+title);}
            check("smbplayer".equals(getTargetContext().getApplicationInfo().loadLabel(getTargetContext().getPackageManager()).toString()),"App label not renamed");
            snapshot(activity.getWindow().getDecorView(),"dashboard-qa.png");
        });
        for(int code:new int[]{22,22,20,21,19,21,21,19,20,22,22,20}){key(code);ui(()->{check((boolean)get("dashboard")&&!(boolean)get("browsing"),"Focus opened a category without confirmation");visibleFocus();});}
        String[] kinds={"local","nas","m3u","stream"},add={"选择文件夹","添加 SMB 服务器","添加 M3U / M3U8","添加网络串流"};
        for(int i=0;i<kinds.length;i++){
            String kind=kinds[i],label=add[i];ui(()->((Map<String,Button>)get("sourceTabs")).get(kind).requestFocusFromTouch());key(KeyEvent.KEYCODE_DPAD_CENTER);
            ui(()->{check(!(boolean)get("dashboard")&&!(boolean)get("browsing"),"Source detail not opened correctly");check(button(label)!=null,"Missing source add/list action");});
            key(KeyEvent.KEYCODE_BACK);ui(()->{check((boolean)get("dashboard"),"Back did not return to dashboard");check(activity.getCurrentFocus()==((Map<?,?>)get("sourceTabs")).get(kind),"Dashboard category focus not restored");});
        }
        ui(()->((Map<String,Button>)get("sourceTabs")).get("settings").requestFocusFromTouch());key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->check(get("playbackDialog")!=null,"Settings entry failed"));key(KeyEvent.KEYCODE_BACK);
        report("Exactly five icon/text entries, confirm-only navigation, Back and focus checks passed");
        File fixture=new File(getTargetContext().getFilesDir(),"dashboard-fixture.mp4");check(fixture.isFile(),"Missing fixture");
        File beta=new File(fixture.getParentFile(),"dashboard-beta.mp4");try(FileInputStream in=new FileInputStream(fixture);FileOutputStream out=new FileOutputStream(beta)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)out.write(b,0,n);}
        List<Object> entries=new ArrayList<>();Object[] movie={null};
        ui(()->{
            SharedPreferences s=(SharedPreferences)get("settings");s.edit().putInt("resumeMode",0).putInt("loop",0).putInt("browserSort",1).putBoolean("browserDescending",false).putBoolean("gridView",false).commit();
            Class<?> c=Class.forName("com.smbplayer.tv.MainActivity$Entry",true,getTargetContext().getClassLoader());Constructor<?> ctor=c.getDeclaredConstructor(String.class,String.class,boolean.class,long.class,long.class,long.class);ctor.setAccessible(true);
            entries.add(ctor.newInstance("Films/","content://fixture/folder/",true,1794000000000L,0L,-1L));
            movie[0]=ctor.newInstance("Alpha.mp4",android.net.Uri.fromFile(fixture).toString(),false,1794000000000L,60000L,512L);entries.add(movie[0]);
            entries.add(ctor.newInstance("Beta.mp4",android.net.Uri.fromFile(beta).toString(),false,1794000000000L,60000L,2097152L));
            set("sourceKind","local");call("setPlaylist",new Class<?>[]{List.class,String.class},entries,"File info fixture");
        });SystemClock.sleep(350);
        ui(()->{
            ListView l=find(activity.getWindow().getDecorView(),ListView.class);View folder=l.getAdapter().getView(0,null,l);
            ImageView icon=(ImageView)field(folder,"folderGlyph");check(icon.getDrawable() instanceof android.graphics.drawable.VectorDrawable,"Folder is not vector-rendered");
            for(int i=1;i<=2;i++){View tile=l.getAdapter().getView(i,null,l);String text=((TextView)field(tile,"fileDetails")).getText().toString();check(text.contains("2026-")&&text.contains(i==1?"1 kB":"2.0 MB"),"Missing file metadata: "+text);}
            snapshot(activity.getWindow().getDecorView(),"file-list-qa.png");
            ((SharedPreferences)get("settings")).edit().putBoolean("gridView",true).commit();call("renderBrowser",new Class<?>[]{String.class,boolean.class},"",true);
        });SystemClock.sleep(300);
        ui(()->{GridView grid=find(activity.getWindow().getDecorView(),GridView.class);snapshot(activity.getWindow().getDecorView(),"file-grid-qa.png");for(int i=1;i<=2;i++){View tile=grid.getChildAt(i);TextView info=(TextView)field(tile,"fileDetails");Rect r=new Rect();check(info.getGlobalVisibleRect(r)&&r.height()>=info.getHeight()-2,"Grid metadata clipped: visible="+r.height()+" height="+info.getHeight()+" grid="+grid.getHeight()+" tile="+tile.getHeight());}call("openVideo",new Class<?>[]{movie[0].getClass()},movie[0]);});
        waitPrepared();SystemClock.sleep(500);ui(()->{bars(true);check(playing(),"Fixture not playing");activity.onBackPressed();bars(false);});
        tapVideo();ui(()->{bars(true);check(playing()&&!(boolean)get("pausedPlayback"),"Screen tap paused playback");check(activity.getCurrentFocus()==get("pause"),"Screen tap did not focus pause");});
        tapVideo();ui(()->check(playing(),"Second screen tap toggled playback"));key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_DPAD_UP);
        ui(()->{bars(true);check(get("playbackDialog")==null&&playing(),"Up opened a list or paused playback");check(activity.getCurrentFocus()==get("pause"),"Up reveal lost pause focus");});
        key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->check((boolean)get("pausedPlayback"),"Confirm did not pause"));key(KeyEvent.KEYCODE_BACK);
        tapVideo();ui(()->{bars(true);check((boolean)get("pausedPlayback")&&!playing(),"Screen tap resumed a paused video");});key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->{bars(false);check(!(boolean)get("pausedPlayback"),"Hidden confirm did not resume");});
        key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->{bars(false);check((boolean)get("pausedPlayback"),"Hidden confirm did not pause");});
        key(KeyEvent.KEYCODE_DPAD_UP);
        float[] seek=new float[3];ui(()->{bars(true);SeekBar bar=(SeekBar)get("timeline");int[] p=new int[2];bar.getLocationOnScreen(p);seek[0]=p[0]+bar.getPaddingLeft();seek[1]=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();seek[2]=p[1]+bar.getHeight()/2f;});
        pointer(seek[0]+seek[1]*.2f,seek[2],MotionEvent.ACTION_DOWN);pointer(seek[0]+seek[1]*.6f,seek[2],MotionEvent.ACTION_MOVE);pointer(seek[0]+seek[1]*.6f,seek[2],MotionEvent.ACTION_UP);SystemClock.sleep(850);
        ui(()->{check(Math.abs(time()-36000)<2500&&(boolean)get("pausedPlayback"),"Touch seek or pause preservation regressed");});key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_DPAD_DOWN);
        ui(()->{bars(false);AlertDialog d=(AlertDialog)get("playbackDialog");check(d!=null,"Down did not open playlist");ListView l=find(d.getWindow().getDecorView(),ListView.class);View row=l.getAdapter().getView(0,null,l);check(((TextView)((ViewGroup)row).getChildAt(1)).getText().toString().contains("2026-"),"Playlist missing file metadata");snapshot(d.getWindow().getDecorView(),"playlist-info-qa.png");});
        key(KeyEvent.KEYCODE_BACK);ui(()->{bars(false);call("leaveVideo",new Class<?>[]{String.class},"");});
        report("Vector folder, file metadata, grid layout, touch/Up reveal, confirm toggle, touch seek and Down playlist passed");
        result.putString("stream","Dashboard and player interaction checks passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));result.putString("stream",s.toString());finish(Activity.RESULT_CANCELED,result);}}
}
