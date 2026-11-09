// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;
import java.util.ArrayDeque;
import java.util.Locale;

/** Rolling network bytes, not local-cache bytes or requested ranges. */
final class DownloadRate {
    private final ArrayDeque<long[]> samples=new ArrayDeque<>();
    private long latest=-1;
    void reset(){samples.clear();latest=-1;}
    double sample(long bytes,long now){
        if(bytes<0)return 0;
        if(bytes<latest)samples.clear();latest=bytes;
        if(!samples.isEmpty()&&now<=samples.getLast()[0])return rate(bytes,now);
        samples.addLast(new long[]{now,bytes});
        while(samples.size()>2){long[] first=samples.removeFirst();if(samples.getFirst()[0]>now-2000){samples.addFirst(first);break;}}
        while(samples.size()>12)samples.removeFirst();
        return rate(bytes,now);
    }
    private double rate(long bytes,long now){if(samples.isEmpty())return 0;long[] first=samples.getFirst();return now>first[0]?Math.max(0,(bytes-first[1])*1000.0/(now-first[0])):0;}
    static String format(double perSecond){return perSecond>=1024*1024?String.format(Locale.ROOT,"%.1f MB/s",perSecond/(1024*1024)):String.format(Locale.ROOT,"%.0f kB/s",perSecond/1024);}
}
