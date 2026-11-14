package com.smbplayer.tv;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

final class Lyrics {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    static final class Line {
        final long millis;
        final String text;
        Line(long millis, String text) { this.millis = millis; this.text = text; }
    }
    static List<Line> parse(byte[] bytes) {
        Charset encoding = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 255 && bytes[1] == (byte) 254)
                || (bytes[0] == (byte) 254 && bytes[1] == (byte) 255))) encoding = StandardCharsets.UTF_16;
        String text = new String(bytes, encoding);
        if (encoding == StandardCharsets.UTF_8 && text.indexOf('\ufffd') >= 0) text = new String(bytes, Charset.forName("GB18030"));
        long offset = 0;
        Matcher shift = Pattern.compile("\\[offset:([+-]?\\d{1,8})\\]", Pattern.CASE_INSENSITIVE).matcher(text);
        if (shift.find()) offset = Long.parseLong(shift.group(1));
        List<Line> result = new ArrayList<>();
        for (String row : text.split("\\r?\\n")) {
            Matcher time = TIME.matcher(row);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (time.find()) {
                String fraction = time.group(3);
                long ms = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
                stamps.add(60000L * Long.parseLong(time.group(1)) + 1000L * Long.parseLong(time.group(2)) + ms);
                end = time.end();
            }
            String words = row.substring(end).trim();
            for (long stamp : stamps) if (!words.isEmpty() && result.size() < 10000)
                result.add(new Line(Math.max(0, stamp + offset), words));
        }
        result.sort(Comparator.comparingLong(line -> line.millis));
        return result;
    }
}
