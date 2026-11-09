package com.smbplayer.checks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;

/** Temporary instrumentation for a test build. Omitted from the delivered APK. */
public final class RecoveryInstrumentation extends Instrumentation {
    private Activity activity;
    private String url;
    public void onCreate(Bundle args) { super.onCreate(args); android.util.Log.i("RecoveryCheck", "onCreate"); url = args.getString("url"); start(); }
    private Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args);
    }
    private Object publicCall(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    interface Work { void run() throws Exception; }
    private void ui(Work work) throws Exception {
        Throwable[] failure = {null}; runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure[0] = e; } });
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    private void report(String text) { Bundle b = new Bundle(); b.putString("stream", text + "\n"); sendStatus(0, b); }
    private void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private Object waitPlayer(Object previous) throws Exception {
        Object[] result = {null};
        for (int i = 0; i < 180; i++) {
            ui(() -> { Object p = field(activity, "player"); if (p != null && p != previous && (boolean) field(activity, "prepared")) result[0] = p; });
            if (result[0] != null) return result[0]; SystemClock.sleep(250);
        }
        throw new AssertionError("Playback did not prepare");
    }
    private Object stats(Object player) throws Exception {
        Object media = publicCall(player, "getMedia");
        try { return publicCall(media, "getStats"); } finally { publicCall(media, "release"); }
    }
    private int stat(Object stats, String name) throws Exception { return stats.getClass().getField(name).getInt(stats); }
    private void freezeSample(Object player, int attempt) throws Exception {
        Object recovery = field(activity, "videoRecovery"), s = stats(player);
        long position = (long) publicCall(player, "getTime");
        call(recovery, "begin", new Class<?>[0]); set(recovery, "attempts", attempt);
        call(recovery, "ready", new Class<?>[] {long.class,int.class,int.class,long.class},
                SystemClock.elapsedRealtime() - (attempt == 0 ? 2200 : 4200), stat(s,"displayedPictures"),
                stat(s,"playedAbuffers") - 10, Math.max(0, position - 1000));
    }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            report("Launching recovery fixture");
            android.util.Log.i("RecoveryCheck", "Starting activity");
            Intent intent = new Intent().setClassName("com.smbplayer.tv", "com.smbplayer.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            report("Activity ready");
            ui(() -> {
                Class<?> entryClass = Class.forName("com.smbplayer.tv.MainActivity$Entry", true, getTargetContext().getClassLoader());
                Constructor<?> ctor = entryClass.getDeclaredConstructor(String.class,String.class,boolean.class); ctor.setAccessible(true);
                Object entry = ctor.newInstance("Recovery integration fixture", url, false);
                call(activity,"setPlaylist",new Class<?>[] {List.class,String.class}, Collections.singletonList(entry),"Recovery integration fixture");
                call(activity,"openVideo",new Class<?>[] {entryClass},entry);
            });
            Object first = waitPlayer(null);
            java.io.File external = new java.io.File(getTargetContext().getFilesDir(), "recovery-fixture.srt");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(external)) {
                f.write("1\n00:00:00,000 --> 00:01:00,000\nRecovery subtitle fixture\n".getBytes("UTF-8"));
            }
            ui(() -> {
                ((List<java.io.File>) field(activity,"subtitleFiles")).add(external);
                call(activity,"addSubtitleToPlayer",new Class<?>[] {java.io.File.class},external);
            });
            SystemClock.sleep(1800);
            int[] selectedSubtitle = {-1};
            ui(() -> {
                for (Object track : (Object[]) publicCall(first,"getSpuTracks")) {
                    int id=track.getClass().getField("id").getInt(track);
                    if (id>=0) { first.getClass().getMethod("setSpuTrack",int.class).invoke(first,id); selectedSubtitle[0]=id; break; }
                }
                check(selectedSubtitle[0]>=0,"External subtitle fixture did not load");
            });
            int[] before = {0}; long[] position = {0};
            ui(() -> { before[0] = stat(stats(first),"displayedPictures"); position[0] = (long) publicCall(first,"getTime"); });
            check(before[0] > 0,"No actual video output statistics");
            ui(() -> { freezeSample(first,0); call(activity,"monitorVideoRecovery",new Class<?>[] {long.class}, position[0]); });
            SystemClock.sleep(1700);
            ui(() -> {
                check(field(activity,"player")==first,"First recovery replaced the input");
                check(stat(stats(first),"displayedPictures")>before[0],"Video did not advance after native resynchronization");
                long now = (long) publicCall(first,"getTime"); check(Math.abs(now-position[0])<4500,"Recovery jumped away from the current position");
            });
            report("Native same-position seek resumed video without replacing player");
            ui(() -> { freezeSample(first,1); position[0] = (long) publicCall(first,"getTime"); check((boolean)call(activity,"monitorVideoRecovery",new Class<?>[] {long.class},position[0]),"Persistent freeze did not recreate player"); });
            Object second = waitPlayer(first); SystemClock.sleep(2200);
            ui(() -> {
                check(stat(stats(second),"displayedPictures")>0,"Recreated decoder did not output video");
                check(external.exists(),"Decoder recreation deleted the active external subtitle");
                check((int)publicCall(second,"getSpuTrack")==selectedSubtitle[0],"External subtitle selection was not restored");
                long now=(long)publicCall(second,"getTime"); check(Math.abs(now-position[0])<5500,"Decoder recreation lost position");
                freezeSample(second,1); check(!(boolean)call(activity,"monitorVideoRecovery",new Class<?>[] {long.class},now),"Cooldown allowed repeated decoder recreation");
            });
            report("Decoder recreation preserved position and external subtitles; cooldown prevented restart loop");
            ui(() -> { freezeSample(second,0); call(activity,"togglePlayback",new Class<?>[0]); });
            SystemClock.sleep(2700);
            ui(() -> { check((boolean)field(activity,"pausedPlayback"),"User pause was overridden"); check(!(boolean)publicCall(second,"isPlaying"),"User-paused player automatically resumed"); });
            report("User pause cancels recovery and stays paused");
            ui(() -> call(activity,"leaveVideo",new Class<?>[] {String.class},""));
            result.putString("stream","Recovery integration checks passed\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            java.io.StringWriter s=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(s));result.putString("stream",s.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
}
