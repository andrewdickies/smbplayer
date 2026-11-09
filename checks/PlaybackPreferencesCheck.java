package com.smbplayer.tv;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
public final class PlaybackPreferencesCheck {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static SharedPreferences backing(Map<String,Object> data){return (SharedPreferences)Proxy.newProxyInstance(PlaybackPreferencesCheck.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(o,m,a)->{
        String n=m.getName();if(n.equals("getAll"))return new HashMap<>(data);if(n.equals("contains"))return data.containsKey(a[0]);
        if(n.startsWith("get"))return data.containsKey(a[0])?data.get(a[0]):a[1];
        if(n.equals("edit")){Map<String,Object> changes=new HashMap<>();Set<String> removed=new HashSet<>();boolean[] clear={false};return Proxy.newProxyInstance(PlaybackPreferencesCheck.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(e,em,ea)->{
            String en=em.getName();if(en.startsWith("put")){changes.put((String)ea[0],ea[1]);return e;}if(en.equals("remove")){removed.add((String)ea[0]);return e;}if(en.equals("clear")){clear[0]=true;return e;}
            if(en.equals("apply")||en.equals("commit")){if(clear[0])data.clear();for(String k:removed)data.remove(k);data.putAll(changes);return en.equals("commit")?true:null;}return null;
        });}return null;
    });}
    public static void main(String[] args){
        Map<String,Object> data=new HashMap<>();data.put("homeSources","encrypted source data");data.put("subtitleSize",14);data.put("speedSteps",50);data.put("rotation",2);data.put("audioGain",6);data.put("gridView",true);data.put("passthrough",false);data.put("resumeMode",2);
        PlayerDefaults.migrate(backing(data));
        check(data.get("homeSources").equals("encrypted source data")&&data.get("resumeMode").equals(2),"Migration changed sources or resume choice");
        check(!data.containsKey("audioGain")&&!data.containsKey("rotation")&&!data.containsKey("speedSteps")&&data.get("gridView").equals(false),"Removed options remain active");
        PlaybackPreferences p=new PlaybackPreferences(backing(data));p.begin("a");
        check(p.getInt("speedSteps",0)==20&&p.getInt("scale",-1)==0&&p.getInt("rotation",-1)==0,"New video did not use simple defaults");
        p.edit().putInt("speedSteps",40).putLong("audioDelay",100000).putInt("rotation",4).putInt("subtitleSize",11).apply();
        check(p.getInt("speedSteps",0)==40&&!data.containsKey("speedSteps")&&data.get("subtitleSize").equals(14),"Current video wrote global preferences");
        p.begin("a");check(p.getInt("rotation",0)==4&&p.getLong("audioDelay",0)==100000,"Rebuild lost current overrides");
        p.begin("b");check(p.getInt("speedSteps",0)==20&&p.getInt("rotation",0)==0&&p.getLong("audioDelay",0)==0&&p.getInt("subtitleSize",0)==14,"Next video inherited previous overrides");
        p.end();p.edit().putInt("subtitleSize",11).apply();p.begin("c");check(p.getInt("subtitleSize",0)==11,"Global subtitle default not inherited");
        data.put("gridView",true);PlayerDefaults.migrate(backing(data));check(data.get("gridView").equals(true),"Migration repeated and overwrote user preference");
        System.out.println("Simple-default migration, source preservation, session isolation, rebuild and next-video reset passed");
    }
}
