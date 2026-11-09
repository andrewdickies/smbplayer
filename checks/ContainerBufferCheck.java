package com.smbplayer.tv;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;

public final class ContainerBufferCheck {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void copy(RandomAccessFile input, PlaybackByteCache cache, long start, long count) throws Exception {
        cache.noteReadStart(start);
        byte[] bytes = new byte[65536]; input.seek(start);
        while (count > 0) {
            int n = (int) Math.min(count, bytes.length); input.readFully(bytes, 0, n); cache.writeAt(start, bytes, 0, n);
            start += n; count -= n;
        }
    }
    private static long[] fragmentLayout(RandomAccessFile file, long minimum) throws Exception {
        long aligned = -1, hasMfra = 0;
        for (long at = 0; at + 8 <= file.length();) {
            file.seek(at); long size = file.readInt() & 0xffffffffL; int type = file.readInt();
            if (size == 1) size = file.readLong(); if (size == 0) size = file.length() - at;
            check(size >= 8 && size <= file.length() - at, "Malformed test fixture");
            if (type == 0x6d6f6f66 && at >= minimum && aligned < 0) aligned = at;
            if (type == 0x6d667261) hasMfra = 1;
            at += size;
        }
        return new long[] {aligned, hasMfra};
    }
    private static String show(List<long[]> ranges) {
        StringBuilder text = new StringBuilder(); for (long[] span : ranges) text.append('[').append(span[0]).append(',').append(span[1]).append("] "); return text.toString();
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i += 2) {
            File file = new File(args[i]); long duration = Long.parseLong(args[i + 1]);
            File folder = Files.createTempDirectory("container-buffer-check").toFile();
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                MediaBufferIndex index = MediaBufferIndex.open(new MediaBufferIndex.Reader() {
                    public long length() { return file.length(); }
                    public void read(long position, byte[] bytes, int offset, int length) throws java.io.IOException {
                        input.seek(position); input.readFully(bytes, offset, length);
                    }
                });
                try (PlaybackByteCache cache = new PlaybackByteCache(folder, 65536, 256)) {
                    check(index.ranges(cache, duration).isEmpty(), "Uncached file has a time interval");
                    copy(input, cache, 0, 32);
                    check(index.ranges(cache, duration).isEmpty(), "Magic bytes became a playable interval");
                    long part = Math.min(file.length() / 3, 3 * 1024 * 1024);
                    copy(input, cache, 0, part);
                    List<long[]> prefix = index.ranges(cache, duration);
                    System.out.println(file.getName() + " " + index.getClass().getSimpleName() + " prefix: " + show(prefix));
                    check(!prefix.isEmpty() && prefix.get(0)[0] <= 200 && prefix.get(prefix.size() - 1)[1] < duration,
                            "Expected bounded prefix coverage for " + file.getName());
                    copy(input, cache, file.length() - part, part);
                    List<long[]> separated = index.ranges(cache, duration);
                    System.out.println("Separated: " + show(separated));
                    if (separated.size() < 2 && index instanceof FragmentedMp4BufferIndex) {
                        long[] layout = fragmentLayout(input, file.length() - part);
                        check(layout[1] == 0 && layout[0] >= 0, "Indexed tail was not recognized");
                        try (PlaybackByteCache aligned = new PlaybackByteCache(folder, 65536, 256)) {
                            copy(input, aligned, 0, part);
                            copy(input, aligned, layout[0], file.length() - layout[0]);
                            List<long[]> recognized = index.ranges(aligned, duration);
                            System.out.println("Aligned DASH seek: " + show(recognized));
                            check(recognized.size() >= 2, "Fragment-boundary seek did not expose cached tail");
                        }
                    } else check(separated.size() >= 2, "A cache hole incorrectly became buffered time");
                    copy(input, cache, 0, file.length());
                    List<long[]> full = index.ranges(cache, duration);
                    System.out.println("Full: " + show(full));
                    check(full.size() == 1 && full.get(0)[0] <= 200 && full.get(0)[1] >= duration - 200,
                            "Complete container data not recognized");
                }
                try (PlaybackByteCache cache = new PlaybackByteCache(folder, 65536, 8)) {
                    copy(input, cache, 0, file.length());
                    List<long[]> evicted = index.ranges(cache, duration);
                    for (long[] span : evicted) check(span[0] > 0, "Evicted prefix still advertised as buffered");
                }
            }
            check(folder.list().length == 0, "Temporary cache leaked"); folder.delete();
        }
        System.out.println("Container cache coverage, holes and eviction checks passed");
    }
}
