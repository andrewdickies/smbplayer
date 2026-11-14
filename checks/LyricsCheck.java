package com.smbplayer.tv;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LyricsCheck {
    public static void main(String[] args) {
        String source="[offset:-250]\n[01:00.5][00:03.25] one\n[00:00.1] first\n[00:15.123] two\n[ar:Band]";
        List<Lyrics.Line> lines=Lyrics.parse(source.getBytes(StandardCharsets.UTF_8));
        long[] times={0,3000,14873,60250};
        if(lines.size()!=times.length)throw new AssertionError("Incorrect tag count");
        for(int i=0;i<times.length;i++)if(lines.get(i).millis!=times[i])throw new AssertionError("Incorrect timestamp or offset");
        if(!lines.get(1).text.equals(lines.get(3).text))throw new AssertionError("Repeated timestamps lost text");
        if(Lyrics.parse("[00:04.00]    ".getBytes(StandardCharsets.UTF_8)).size()!=0)throw new AssertionError("Blank line included");
        System.out.println("LRC timestamps, offsets, duplicate tags and blank lines passed");
    }
}
