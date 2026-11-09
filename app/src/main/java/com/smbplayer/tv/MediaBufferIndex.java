// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.IOException;
import java.util.List;

interface MediaBufferIndex {
    interface Reader {
        long length();
        void read(long position, byte[] bytes, int offset, int length) throws IOException;
    }
    List<long[]> ranges(PlaybackByteCache cache, long durationMs) throws IOException;
    /** Real indexed byte position, or -1 when this container cannot map the timestamp. */
    default long byteOffset(long timeMs) throws IOException {return -1;}


    static MediaBufferIndex open(Reader source) throws IOException {
        byte[] magic = new byte[4]; source.read(0, magic, 0, 4);
        if ((magic[0] & 255) == 0x1a && (magic[1] & 255) == 0x45
                && (magic[2] & 255) == 0xdf && (magic[3] & 255) == 0xa3)
            return new MatroskaBufferIndex(source);
        try { return Mp4BufferIndex.read(source); }
        catch (IOException unsupported) { return new FragmentedMp4BufferIndex(source); }
    }
}
