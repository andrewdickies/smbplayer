package com.smbplayer.tv;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RangeReadAheadCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        AtomicInteger open = new AtomicInteger(), max = new AtomicInteger(), active = new AtomicInteger(), concurrency = new AtomicInteger();
        RangeReadAhead engine = new RangeReadAhead(() -> {
            int count = open.incrementAndGet(); max.accumulateAndGet(count, Math::max);
            return new RangeReadAhead.Reader() {
                boolean closed;
                public void read(long position, byte[] bytes, int length) throws IOException {
                    int count = active.incrementAndGet(); concurrency.accumulateAndGet(count, Math::max);
                    try {
                        try { Thread.sleep(35); } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                        for (int i = 0; i < length; i++) bytes[i] = (byte) ((position + i) * 31 + 7);
                    } finally { active.decrementAndGet(); }
                }
                public synchronized void close() { if (!closed) { closed = true; open.decrementAndGet(); } }
            };
        });
        RangeReadAhead.Range first = engine.open(113, 12L * 1024 * 1024 + 19);
        RangeReadAhead.Range second = engine.open(100, 900000);
        check(engine.open(0, 1000) == null, "More than two prefetch consumers admitted");
        byte[] bytes = new byte[83017];
        for (long start : new long[] {113, 270001, 9000000, 199, 12500000}) {
            long cursor = start;
            for (int round = 0; round < 3; round++) {
                int n = first.read(cursor, bytes, 13, bytes.length - 13);
                for (int i = 0; i < n; i++) check(bytes[i + 13] == (byte) ((cursor + i) * 31 + 7), "Range data changed after seek");
                cursor += n;
            }
        }
        second.close(); first.close();
        check(engine.activeRanges() == 0, "Cancelled ranges retained");
        RangeReadAhead.Range tail = engine.open(50, 97);
        int n = tail.read(91, bytes, 0, 100); check(n == 7, "End bound changed");
        check(tail.read(98, bytes, 0, 1) == -1, "EOF changed"); tail.close();
        check(max.get() <= 8 && concurrency.get() > 1, "Worker bounds or concurrency incorrect");
        engine.close(); check(open.get() == 0 && engine.activeRanges() == 0, "Readers not closed");
        check(engine.open(0, 1) == null, "Closed engine admitted work");
        RangeReadAhead failed = new RangeReadAhead(() -> new RangeReadAhead.Reader() {
            public void read(long p, byte[] b, int len) throws IOException { throw new IOException("fixture failure"); }
            public void close() {}
        });
        try { failed.open(0, 999999).read(0, bytes, 0, 10); throw new AssertionError("Failure swallowed"); }
        catch (IOException expected) {} finally { failed.close(); }
        System.out.println("Positional data, forward/backward seeks, EOF, consumer/worker limits, cancellation and failure checks passed");
    }
}
