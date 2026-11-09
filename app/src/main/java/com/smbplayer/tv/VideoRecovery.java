// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

/** Watches output continuously, including seeks and pause/resume without Buffering events. */
final class VideoRecovery {
    enum Action { NONE, STALLED, RECOVERED, RESYNC, REOPEN, FAILED, UNAVAILABLE }
    private boolean active,ready,watching;
    private int attempts,pictures,progressPictures,audio;
    private long startedAt,readyAt,lastProgressAt,position;
    void begin(){begin(System.nanoTime()/1000000);}
    void begin(long now){if(!active){attempts=0;startedAt=now;}active=true;ready=false;}
    void expect(long now,int displayed,int playedAudio,long current){cancel();begin(now);ready(now,displayed,playedAudio,current);}
    boolean active(){return active;}
    boolean awaitingFrames(){return active&&ready;}
    long frozenPosition(){return position;}
    void ready(long now,int displayed,int playedAudio,long current){
        if(!active||ready)return;
        ready=true;readyAt=lastProgressAt=now;pictures=displayed;audio=playedAudio;position=current;progressPictures=0;watching=true;
    }
    Action sample(long now,int displayed,int playedAudio,long current,boolean seekable,boolean paused){
        if(paused){cancel();return Action.NONE;}
        if(!watching){baseline(now,displayed,playedAudio,current);return Action.NONE;}
        if(!active){
            if(displayed!=pictures||playedAudio<audio||current<position-1500){baseline(now,displayed,playedAudio,current);return Action.NONE;}
            if(now-lastProgressAt>=1500&&(playedAudio>audio+2||current>position+750)){
                active=ready=true;attempts=0;startedAt=readyAt=now;progressPictures=0;return Action.STALLED;
            }
            return Action.NONE;
        }
        if(!ready){return now-startedAt>=45000?Action.FAILED:Action.NONE;}
        if(displayed<pictures||playedAudio<audio||current<position-1500){
            pictures=displayed;audio=playedAudio;position=current;progressPictures=0;readyAt=lastProgressAt=now;
        }
        if(displayed>pictures){progressPictures+=displayed-pictures;pictures=displayed;lastProgressAt=now;
            if(progressPictures>=2&&now-readyAt>=200){active=ready=false;baseline(now,displayed,playedAudio,current);return Action.RECOVERED;}
        }else if(now-lastProgressAt>750)progressPictures=0;
        if(now-lastProgressAt>=(attempts==0?2000:4000)){
            attempts++;ready=false;
            if(!seekable)return attempts==1?Action.REOPEN:Action.FAILED;
            return attempts==1?Action.RESYNC:attempts==2?Action.REOPEN:Action.FAILED;
        }
        return now-startedAt>=20000?Action.FAILED:Action.NONE;
    }
    private void baseline(long now,int displayed,int playedAudio,long current){watching=true;pictures=displayed;audio=playedAudio;position=current;lastProgressAt=now;}
    void cancel(){active=ready=watching=false;attempts=progressPictures=0;}
}
