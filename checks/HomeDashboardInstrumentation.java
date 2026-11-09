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
public final class HomeDashboardInstrumentation extends Instrumentation {
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
    @SuppressWarnings("unchecked") List<View> cards()throws Exception{return new ArrayList<>(((Map<String,View>)get("sourceTabs")).values());}
    int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    void layoutCase(int width,int height,boolean single,String screenshot)throws Exception{
        ui(()->{
            View root=(View)get("pageRoot");int w=dp(width),h=dp(height);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
            List<View> cards=cards();check(cards.size()==5,"Wrong card count");Set<Integer> ids=new HashSet<>();for(View card:cards)ids.add(card.getId());
            int top=cards.get(0).getTop();check((cards.get(4).getTop()==top)==single,"Wrong row count at "+width+"x"+height+" root="+root.getWidth()+" parent="+cards.get(0).getParent()+" parentWidth="+((View)cards.get(0).getParent()).getWidth()+" firstCount="+field(cards.get(0).getParent(),"firstCount")+" labelWidth="+((TextView)field(cards.get(2),"label")).getPaint().measureText("M3U/M3U8")+" density="+activity.getResources().getDisplayMetrics().density);
            ViewGroup parent=(ViewGroup)cards.get(0).getParent();
            for(View card:cards){
                View content=(View)field(card,"content");TextView label=(TextView)field(card,"label");ImageView icon=(ImageView)field(card,"icon");
                check(Math.abs(content.getTop()+content.getHeight()/2f-card.getHeight()/2f)<=2,"Icon/text block is not centered");
                check(label.getTop()-icon.getBottom()<=dp(17)&&label.getTop()-icon.getBottom()>=dp(11),"Icon/text gap grew with screen height");
                check(card.getHeight()<=dp(220),"Oversized card at "+width);
                check(card.getLeft()>=0&&card.getRight()<=parent.getWidth()&&card.getTop()>=0&&card.getBottom()<=parent.getHeight(),"Card outside content bounds");
                check(label.getLayout()!=null&&label.getLayout().getEllipsisCount(label.getLineCount()-1)==0,"Label truncated at "+width+": "+label.getText());
                check(ids.contains(card.getNextFocusLeftId())&&ids.contains(card.getNextFocusRightId())&&ids.contains(card.getNextFocusUpId())&&ids.contains(card.getNextFocusDownId()),"Focus escapes home cards");
                if(single)check(card.getNextFocusUpId()==card.getId()&&card.getNextFocusDownId()==card.getId(),"Single-row focus leaves row");
            }
            if(!single){View a=cards.get(3),b=cards.get(4);check(Math.abs((a.getLeft()+b.getRight())/2f-parent.getWidth()/2f)<=2,"Second row not horizontally centered");}
            check(cards.get(0).getHeight()<parent.getHeight()*.85f,"Home cards fill remaining screen height");
            if(screenshot!=null)snapshot(root,screenshot);
        });
        report(width+"x"+height+" dp: "+(single?"five centered cards":"centered 3+2 cards")+", centered content, labels and focus bounds passed");
    }
    void largeFontCase()throws Exception {
        ui(()->{
            android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=2f;
            Context context=activity.createConfigurationContext(config);
            Class<?> cardType=cards().get(0).getClass();Constructor<?> constructor=cardType.getDeclaredConstructor(Context.class,String.class,int.class,android.graphics.drawable.Drawable.class,Runnable.class);constructor.setAccessible(true);
            List<View> enlarged=new ArrayList<>();String[] labels={"本地存储","SMB 网络","M3U/M3U8","网络串流","设置"};
            for(String label:labels)enlarged.add((View)constructor.newInstance(context,label,activity.getResources().getIdentifier("ic_local","drawable",activity.getPackageName()),cards().get(0).getBackground().getConstantState().newDrawable(context.getResources()),(Runnable)()->{}));
            Class<?> gridType=cards().get(0).getParent().getClass();Constructor<?> gridConstructor=gridType.getDeclaredConstructor(Context.class,List.class);gridConstructor.setAccessible(true);View grid=(View)gridConstructor.newInstance(context,enlarged);
            Method viewport=gridType.getDeclaredMethod("setViewportHeight",int.class);viewport.setAccessible(true);viewport.invoke(grid,dp(540));
            grid.measure(View.MeasureSpec.makeMeasureSpec(dp(960),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(540),View.MeasureSpec.EXACTLY));grid.layout(0,0,dp(960),dp(540));
            check(enlarged.get(0).getTop()!=enlarged.get(4).getTop(),"Large font did not switch to two rows");
            for(View card:enlarged){TextView label=(TextView)field(card,"label");View block=(View)field(card,"content");check(label.getLayout().getEllipsisCount(label.getLineCount()-1)==0,"Large font label truncated");check(block.getHeight()<=card.getHeight()-dp(24),"Large font block clipped");}
        });report("200% font: centered 3+2 cards and readable labels passed without changing device font settings");
    }
    public void onStart(){Bundle result=new Bundle();try{
        activity=startActivitySync(new Intent().setClassName("com.smbplayer.tv","com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(600);
        layoutCase(960,540,true,"v27-home-wide.png");layoutCase(1280,720,true,null);layoutCase(1920,1080,true,null);layoutCase(800,480,false,null);layoutCase(640,360,false,"v27-home-compact.png");largeFontCase();
        ui(()->call("showDashboard",new Class<?>[]{}));SystemClock.sleep(350);
        for(int code:new int[]{21,19,22,22,22,22,20,21,19,20,20,21,21,19,19}){key(code);ui(()->{check((boolean)get("dashboard"),"Direction key opened category");check(cards().contains(activity.getCurrentFocus()),"Focus lost from home cards");visibleFocus();});}
        String[] kinds={"local","nas","m3u","stream","settings"};
        for(String kind:kinds){ui(()->((Map<String,View>)get("sourceTabs")).get(kind).requestFocusFromTouch());key(KeyEvent.KEYCODE_DPAD_CENTER);ui(()->check(!(boolean)get("dashboard"),"Confirm did not open "+kind));key(4);ui(()->{check((boolean)get("dashboard"),"Back did not return home");check(activity.getCurrentFocus()==((Map<?,?>)get("sourceTabs")).get(kind),"Return did not restore home card focus");});}
        ui(()->((Map<String,View>)get("sourceTabs")).get("settings").requestFocusFromTouch());key(KeyEvent.KEYCODE_BUTTON_A);ui(()->check((boolean)get("settingsPage"),"Gamepad confirm did not open settings"));key(4);
        float[] tap=new float[2];ui(()->{View card=((Map<String,View>)get("sourceTabs")).get("local");int[] p=new int[2];card.getLocationOnScreen(p);tap[0]=p[0]+card.getWidth()/2f;tap[1]=p[1]+card.getHeight()/2f;});pointer(tap[0],tap[1],MotionEvent.ACTION_DOWN);pointer(tap[0],tap[1],MotionEvent.ACTION_UP);SystemClock.sleep(250);ui(()->check(!(boolean)get("dashboard"),"Touch on card content did not open source"));key(4);
        ui(()->snapshot((View)get("pageRoot"),"v27-home-device.png"));
        result.putString("stream","Responsive home geometry, actual D-pad, confirm/touch/gamepad and Back focus checks passed\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));result.putString("stream",sw.toString());finish(Activity.RESULT_CANCELED,result);}}
}
