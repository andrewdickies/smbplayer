// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.media.MediaDataSource;
import android.os.SystemClock;

import java.io.IOException;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

/** A bounded SMB reader for thumbnail extraction. Playback has its own streaming path. */
final class SmbPreviewSource extends MediaDataSource {
    private static final int READ_LIMIT = 2 * 1024 * 1024;
    private final long deadline = SystemClock.elapsedRealtime() + 5000;
    private final SmbFile file;
    private final long length;
    private final byte[] cache = new byte[128 * 1024];
    private SmbFileInputStream stream;
    private long cursor;
    private long cacheStart;
    private int cacheSize;
    private int transferred;
    private boolean closed;
    private volatile boolean cancelled;

    void cancel() { cancelled = true; }

    SmbPreviewSource(String url, CIFSContext context) throws IOException {
        file = new SmbFile(url, context);
        try {
            length = file.length();
            stream = file.openInputStream();
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed || cancelled || position < 0) throw new IOException("Preview source unavailable");
        if (size == 0) return 0;
        if (position >= length) return -1;
        if (position < cacheStart || position - cacheStart >= cacheSize) {
            if (SystemClock.elapsedRealtime() >= deadline || transferred >= READ_LIMIT) {
                throw new IOException("Preview read limit");
            }
            cacheSize = 0;
            if (stream == null || position < cursor) {
                if (stream != null) stream.close();
                stream = null;
                stream = file.openInputStream();
                cursor = 0;
            }
            cursor += stream.skip(position - cursor);
            cacheStart = position;
            int count = (int) Math.min(length - position, Math.min(cache.length, READ_LIMIT - transferred));
            int read = stream.read(cache, 0, count);
            if (cancelled) throw new IOException("Preview cancelled for playback");
            if (read <= 0) return -1;
            cursor += read;
            transferred += read;
            cacheSize = read;
        }
        int start = (int) (position - cacheStart);
        int count = Math.min(size, cacheSize - start);
        System.arraycopy(cache, start, buffer, offset, count);
        return count;
    }

    @Override public long getSize() {
        return length;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (stream != null) stream.close();
        } finally {
            file.close();
        }
    }
}
