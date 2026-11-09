// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class BufferIndexSupport {
    private BufferIndexSupport() {}
    static void require(boolean valid) throws IOException { if (!valid) throw new IOException("Unsupported container index"); }
    static boolean covered(List<long[]> ranges, long start, long end) {
        for (long[] range : ranges) { if (range[0] > start) return false; if (range[0] <= start && range[1] >= end) return true; }
        return false;
    }
    static MediaBufferIndex.Reader cached(PlaybackByteCache cache, long length) {
        return new MediaBufferIndex.Reader() {
            public long length() { return length; }
            public void read(long position, byte[] bytes, int offset, int count) throws IOException {
                require(position >= 0 && count <= length - position);
                while (count > 0) {
                    int n = cache.readIndexAt(position, bytes, offset, count);
                    if (n <= 0) throw new IOException("Index bytes not cached yet");
                    position += n; offset += n; count -= n;
                }
            }
        };
    }
    static void append(List<long[]> out, long start, long end) {
        if (start < 0 || end <= start) return;
        if (!out.isEmpty() && out.get(out.size() - 1)[1] >= start)
            out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], end);
        else out.add(new long[] {start, end});
    }
    static List<long[]> intersect(List<long[]> left, List<long[]> right) {
        List<long[]> result = new ArrayList<>(); int a = 0, b = 0;
        while (a < left.size() && b < right.size()) {
            long[] one = left.get(a), two = right.get(b);
            append(result, Math.max(one[0], two[0]), Math.min(one[1], two[1]));
            if (one[1] < two[1]) a++; else b++;
        }
        return result;
    }
}
