// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded positional prefetch: at most two consumers, eight workers and 4 MiB of ready data. */
final class RangeReadAhead implements Closeable {
    interface Reader extends Closeable { void read(long position, byte[] bytes, int length) throws IOException; }
    interface Factory { Reader open() throws IOException; }
    private static final int CHUNK = 256 * 1024, WINDOW = 8;
    private final Factory factory;
    private final Semaphore consumers = new Semaphore(2);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> { Thread t = new Thread(task, "smb-prefetch"); t.setDaemon(true); return t; });
    private final ThreadLocal<Reader> local = new ThreadLocal<>();
    private final Set<Reader> readers = ConcurrentHashMap.newKeySet();
    private final Set<Range> ranges = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    RangeReadAhead(Factory factory) { this.factory = factory; }

    Range open(long start, long end) {
        if (closed || end < start || !consumers.tryAcquire()) return null;
        Range range = new Range(start, end); ranges.add(range);
        if (closed) { range.close(); return null; }
        return range;
    }

    private byte[] fetch(long start, int length) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Read cancelled");
        Reader reader = local.get();
        if (reader == null) {
            reader = factory.open(); readers.add(reader); local.set(reader);
            if (closed) {
                local.remove(); readers.remove(reader);
                reader.close(); throw new IOException("Read cancelled");
            }
        }
        byte[] result = new byte[length];
        try {
            reader.read(start, result, length);
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Read cancelled");
            return result;
        } catch (IOException | RuntimeException failure) {
            local.remove(); readers.remove(reader);
            try { reader.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }

    final class Range implements Closeable {
        private final long start, end;
        private final TreeMap<Long, Future<byte[]>> pending = new TreeMap<>();
        private boolean cancelled;
        Range(long start, long end) { this.start = start; this.end = end; }

        int read(long position, byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (position > end) return -1;
            if (position < start) throw new IOException("Position before requested range");
            long block = (position - start) / CHUNK;
            Future<byte[]> wanted;
            synchronized (this) {
                if (cancelled || closed) throw new IOException("Read cancelled");
                java.util.Iterator<Map.Entry<Long, Future<byte[]>>> iterator = pending.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, Future<byte[]>> item = iterator.next();
                    if (item.getKey() < block || item.getKey() >= block + WINDOW) { item.getValue().cancel(false); iterator.remove(); }
                }
                workers.purge();
                for (long next = block; next < block + WINDOW; next++) {
                    final long at = start + next * CHUNK;
                    if (at > end || at < start) break;
                    if (!pending.containsKey(next)) {
                        final int length = (int) Math.min(CHUNK, end - at + 1);
                        try { pending.put(next, workers.submit(() -> fetch(at, length))); }
                        catch (RejectedExecutionException unavailable) {
                            if (next == block) throw new IOException("Prefetch queue unavailable", unavailable);
                            break;
                        }
                    }
                }
                wanted = pending.get(block);
            }
            byte[] ready;
            try { ready = wanted.get(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Read interrupted", interrupted); }
            catch (CancellationException cancelledRead) { throw new IOException("Read cancelled", cancelledRead); }
            catch (ExecutionException failed) { throw new IOException("Prefetch failed", failed.getCause()); }
            synchronized (this) {
                if (cancelled || closed) throw new IOException("Read cancelled");
                int at = (int) (position - start - block * CHUNK), n = Math.min(count, ready.length - at);
                System.arraycopy(ready, at, bytes, offset, n);
                if (at + n == ready.length) pending.remove(block);
                return n;
            }
        }

        @Override public synchronized void close() {
            if (cancelled) return;
            cancelled = true;
            for (Future<byte[]> task : pending.values()) task.cancel(false);
            pending.clear(); workers.purge(); ranges.remove(this); consumers.release();
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Range range : ranges) range.close();
        workers.shutdownNow();
        for (Reader reader : readers) try { reader.close(); } catch (IOException ignored) {}
        readers.clear();
    }

    int activeReaders() { return readers.size(); }
    int activeRanges() { return ranges.size(); }
}
