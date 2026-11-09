package com.smbplayer.tv;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BufferIndexCheck {
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("player-cache-check").toFile();
        byte[] data = new byte[16384];
        new java.util.Random(42).nextBytes(data);
        try (PlaybackByteCache cache = new PlaybackByteCache(directory, 4096, 2)) {
            byte[] actual = new byte[4096];
            cache.writeAt(100, data, 100, 200);
            require(cache.readAt(0, actual, 0, 1) == -1, "Unwritten bytes must not be reused");
            require(cache.readAt(100, actual, 0, 4096) == 200, "Only written bytes should be returned");
            require(Arrays.equals(Arrays.copyOf(actual, 200), Arrays.copyOfRange(data, 100, 300)), "Cache bytes changed");
            cache.writeAt(0, data, 0, 8192);
            List<long[]> covered = cache.ranges();
            require(covered.size() == 1 && covered.get(0)[0] == 0 && covered.get(0)[1] == 8192, "Adjacent blocks did not merge");
            cache.readIndexAt(0, actual, 0, 10);
            cache.writeAt(8200, data, 8200, 20);
            require(cache.readAt(0, actual, 0, 10) == -1, "Evicted block still appears cached");
            require(cache.readAt(8192, actual, 0, 1) == -1, "Old slot content leaked into a new block");
            require(cache.readAt(8200, actual, 0, 20) == 20, "Partial new block missing");
        }
        require(directory.list().length == 0, "Cache file was not deleted on close");
        directory.delete();
        for (int at = 0; at < args.length; at += 2) {
            File path = new File(args[at]);
            long duration = Long.parseLong(args[at + 1]);
            try (RandomAccessFile source = new RandomAccessFile(path, "r")) {
                Mp4BufferIndex index = Mp4BufferIndex.read(new Mp4BufferIndex.Reader() {
                    public long length() { return path.length(); }
                    public void read(long offset, byte[] target, int begin, int length) throws java.io.IOException {
                        source.seek(offset); source.readFully(target, begin, length);
                    }
                });
                require(index.ranges(Collections.emptyList(), duration).isEmpty(), "No bytes must mean no buffered time");
                require(index.ranges(Collections.singletonList(new long[] {0, 32}), duration).isEmpty(), "Container header is not playable data");
                List<long[]> complete = index.ranges(Collections.singletonList(new long[] {0, path.length()}), duration);
                require(complete.size() == 1 && complete.get(0)[0] == 0 && complete.get(0)[1] == duration,
                        "Complete audio/video cache must cover the movie: " + path.getName());
                long part = Math.min(1024 * 1024, path.length() / 4);
                List<long[]> prefix = index.ranges(Collections.singletonList(new long[] {0, part}), duration);
                require(!prefix.isEmpty() && prefix.get(prefix.size() - 1)[1] < duration, "Partial cache incorrectly covers entire movie");
                List<long[]> tail = index.ranges(Collections.singletonList(new long[] {path.length() - part, path.length()}), duration);
                require(!tail.isEmpty() && tail.get(0)[0] > 0 && tail.get(tail.size() - 1)[1] == duration,
                        "Seek cache must retain a separate trailing interval");
                System.out.println(path.getName() + " prefix ends at " + prefix.get(prefix.size() - 1)[1] + " ms; tail starts at " + tail.get(0)[0] + " ms");
            }
        }
        System.out.println("Bounded byte cache and MP4 buffer index checks passed");
    }
}
