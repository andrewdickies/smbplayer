// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded disk cache. Coverage describes bytes actually written, never requested bytes. */
final class PlaybackByteCache implements Closeable {
    private final File file;
    private final RandomAccessFile data;
    private final int blockSize, slots;
    private final LinkedHashMap<Long, Block> blocks = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Boolean> readStarts = new LinkedHashMap<>(16, .75f, true);
    private long revision;
    private boolean closed;
    private long protectedStart=-1, protectedEnd=-1;

    private static final class Block {
        final int slot;
        final List<long[]> coverage = new ArrayList<>();
        Block(int slot) { this.slot = slot; }
    }

    PlaybackByteCache(File directory, int blockSize, int slots) throws IOException {
        if (blockSize <= 0 || slots <= 0) throw new IllegalArgumentException();
        this.blockSize = blockSize;
        this.slots = slots;
        file = File.createTempFile("smb-stream-", ".cache", directory);
        RandomAccessFile opened = null;
        try {
            opened = new RandomAccessFile(file, "rw");
            opened.setLength((long) blockSize * slots);
            data = opened;
        } catch (IOException e) {
            if (opened != null) opened.close();
            file.delete();
            throw e;
        }
    }

    synchronized int readAt(long position, byte[] buffer, int offset, int count) throws IOException {
        return read(position, buffer, offset, count, true);
    }

    synchronized int readIndexAt(long position, byte[] buffer, int offset, int count) throws IOException {
        return read(position, buffer, offset, count, false);
    }

    private int read(long position, byte[] buffer, int offset, int count, boolean touch) throws IOException {
        if (closed || position < 0) return -1;
        if (count == 0) return 0;
        Block block = blocks.get(position / blockSize);
        if (block == null) return -1;
        if (touch) { blocks.remove(position / blockSize); blocks.put(position / blockSize, block); }
        long within = position % blockSize;
        for (long[] range : block.coverage) {
            if (within < range[0]) return -1;
            if (within < range[1]) {
                int length = (int) Math.min(count, range[1] - within);
                data.seek((long) block.slot * blockSize + within);
                data.readFully(buffer, offset, length);
                return length;
            }
        }
        return -1;
    }

    synchronized void writeAt(long position, byte[] buffer, int offset, int count) throws IOException {
        if (closed || position < 0) return;
        while (count > 0) {
            long key = position / blockSize;
            Block block = blocks.get(key);
            if (block != null) { blocks.remove(key); blocks.put(key, block); }
            if (block == null) {
                int slot = blocks.size();
                if (slot == slots) {
                    Long victim=null;
                    for(Long candidate:blocks.keySet()) {
                        long start=candidate*blockSize;
                        if(start+blockSize<=protectedStart || start>=protectedEnd){victim=candidate;break;}
                    }
                    if(victim==null)victim=blocks.keySet().iterator().next();
                    slot=blocks.remove(victim).slot;
                }
                block = new Block(slot);
                blocks.put(key, block);
            }
            int within = (int) (position % blockSize);
            int length = Math.min(count, blockSize - within);
            data.seek((long) block.slot * blockSize + within);
            data.write(buffer, offset, length);
            merge(block.coverage, within, (long) within + length);
            if (block.coverage.size() > 64) {
                block.coverage.clear();
                block.coverage.add(new long[] {within, (long) within + length});
            }
            revision++;
            position += length;
            offset += length;
            count -= length;
        }
    }

    synchronized boolean isOpen(){return !closed;}
    synchronized long capacity(){return (long)blockSize*slots;}
    synchronized void protect(long start,long end){protectedStart=Math.max(0,start);protectedEnd=Math.max(protectedStart,end);}
    synchronized long contiguousEnd(long position){
        if(closed||position<0)return position;
        long cursor=position;
        while(true){
            Block block=blocks.get(cursor/blockSize);if(block==null)return cursor;
            long within=cursor%blockSize,next=within;
            for(long[] span:block.coverage){if(span[0]>within)break;if(span[1]>within){next=span[1];break;}}
            if(next==within)return cursor;
            cursor+=next-within;if(next<blockSize)return cursor;
        }
    }

    synchronized long revision() { return revision; }

    synchronized void noteReadStart(long position) {
        if (closed || position < 0) return;
        if (readStarts.get(position) == null) { readStarts.put(position, true); revision++; }
        if (readStarts.size() > 512) readStarts.remove(readStarts.keySet().iterator().next());
    }

    synchronized List<Long> readStarts() { return new ArrayList<>(readStarts.keySet()); }

    synchronized List<long[]> ranges() {
        List<long[]> all = new ArrayList<>();
        if (closed) return all;
        for (Map.Entry<Long, Block> entry : blocks.entrySet()) {
            long start = entry.getKey() * blockSize;
            for (long[] span : entry.getValue().coverage) all.add(new long[] {start + span[0], start + span[1]});
        }
        all.sort(Comparator.comparingLong(span -> span[0]));
        List<long[]> result = new ArrayList<>();
        for (long[] span : all) {
            if (!result.isEmpty() && result.get(result.size() - 1)[1] >= span[0])
                result.get(result.size() - 1)[1] = Math.max(result.get(result.size() - 1)[1], span[1]);
            else result.add(span);
        }
        return result;
    }

    private static void merge(List<long[]> spans, long start, long end) {
        int at = 0;
        while (at < spans.size() && spans.get(at)[1] < start) at++;
        while (at < spans.size() && spans.get(at)[0] <= end) {
            long[] previous = spans.remove(at);
            start = Math.min(start, previous[0]);
            end = Math.max(end, previous[1]);
        }
        spans.add(at, new long[] {start, end});
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        revision++;
        blocks.clear();
        readStarts.clear();
        try { data.close(); } catch (IOException ignored) {}
        file.delete();
    }
}
