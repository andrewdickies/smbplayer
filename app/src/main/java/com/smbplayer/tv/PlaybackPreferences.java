// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.content.SharedPreferences;
import java.util.*;

/** In-memory overrides for one playing item. Persistent defaults remain untouched. */
final class PlaybackPreferences implements SharedPreferences {
    private final SharedPreferences defaults;
    private final Map<String,Object> overrides = new HashMap<>();
    private static final Object REMOVED = new Object();
    private final Set<String> scoped = new HashSet<>(Arrays.asList("scale","speedSteps","rotation","audioDelay","subtitleDelay","subtitleSize"));
    private String video;
    PlaybackPreferences(SharedPreferences defaults) { this.defaults = defaults; }
    void begin(String url) {
        if(!Objects.equals(video,url)) {overrides.clear();overrides.put("scale",0);overrides.put("speedSteps",20);overrides.put("rotation",0);overrides.put("audioDelay",0L);overrides.put("subtitleDelay",0L);}
        video=url;
    }
    void end() { video=null; overrides.clear(); }
    private boolean overridden(String key) { return video!=null && overrides.containsKey(key); }
    private Object local(String key,Object fallback) { Object v=overrides.get(key);return v==REMOVED?fallback:v; }
    @Override public Map<String,?> getAll() { Map<String,Object> out=new HashMap<>(defaults.getAll()); if(video!=null) for(Map.Entry<String,Object> e:overrides.entrySet()) { if(e.getValue()==REMOVED)out.remove(e.getKey());else out.put(e.getKey(),e.getValue()); } return out; }
    @Override public String getString(String k,String d){return overridden(k)?(String)local(k,d):defaults.getString(k,d);}
    @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String k,Set<String>d){Set<String> v=overridden(k)?(Set<String>)local(k,d):defaults.getStringSet(k,d);return v==null?null:new HashSet<>(v);}
    @Override public int getInt(String k,int d){return overridden(k)?(Integer)local(k,d):defaults.getInt(k,d);}
    @Override public long getLong(String k,long d){return overridden(k)?(Long)local(k,d):defaults.getLong(k,d);}
    @Override public float getFloat(String k,float d){return overridden(k)?(Float)local(k,d):defaults.getFloat(k,d);}
    @Override public boolean getBoolean(String k,boolean d){return overridden(k)?(Boolean)local(k,d):defaults.getBoolean(k,d);}
    @Override public boolean contains(String k){return getAll().containsKey(k);}
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){defaults.registerOnSharedPreferenceChangeListener(l);}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){defaults.unregisterOnSharedPreferenceChangeListener(l);}
    private static void put(Editor e,String k,Object v) {
        if(v==REMOVED || v==null)e.remove(k); else if(v instanceof String)e.putString(k,(String)v);
        else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
        else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
        else { @SuppressWarnings("unchecked") Set<String> s=(Set<String>)v;e.putStringSet(k,s); }
    }
    @Override public Editor edit(){return new Editor(){
        final Map<String,Object> changes=new HashMap<>(); boolean clear;
        public Editor putString(String k,String v){changes.put(k,v);return this;}
        public Editor putStringSet(String k,Set<String> v){changes.put(k,v==null?null:new HashSet<>(v));return this;}
        public Editor putInt(String k,int v){changes.put(k,v);return this;}
        public Editor putLong(String k,long v){changes.put(k,v);return this;}
        public Editor putFloat(String k,float v){changes.put(k,v);return this;}
        public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
        public Editor remove(String k){changes.put(k,REMOVED);return this;}
        public Editor clear(){clear=true;return this;}
        public void apply(){write(false);}
        public boolean commit(){return write(true);}
        private boolean write(boolean sync){Editor persistent=defaults.edit();if(clear){if(video==null)persistent.clear();else for(String key:scoped)overrides.put(key,REMOVED);}
            for(Map.Entry<String,Object> e:changes.entrySet()) {if(video!=null&&scoped.contains(e.getKey()))overrides.put(e.getKey(),e.getValue()==null?REMOVED:e.getValue());else put(persistent,e.getKey(),e.getValue());}
            if(sync)return persistent.commit();persistent.apply();return true;}
    };}
}
