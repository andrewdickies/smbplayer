package com.smbplayer.tv;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** Independently verifies the virtual header field, including split HTTP reads. */
public final class FragmentDurationCheck {
    private static long uint(byte[] bytes, int at) {
        long value = 0; for (int i = 0; i < 4; i++) value = value << 8 | bytes[at + i] & 255; return value;
    }
    private static int find(byte[] bytes, int start, int end, String type) {
        for (int at = start; at + 8 <= end;) {
            long size = uint(bytes, at); if (size < 8 || size > end - at) break;
            String name = new String(bytes, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (name.equals(type)) return at + 8; at += (int) size;
        }
        throw new AssertionError("Missing " + type);
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i += 2) {
            File path = new File(args[i]); double expectedMs = Double.parseDouble(args[i + 1]);
            try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
                FragmentedMp4BufferIndex index = new FragmentedMp4BufferIndex(new MediaBufferIndex.Reader() {
                    public long length() { return path.length(); }
                    public void read(long at, byte[] bytes, int offset, int count) throws java.io.IOException {
                        file.seek(at); file.readFully(bytes, offset, count);
                    }
                });
                byte[] header = new byte[(int) Math.min(path.length(), 65536)]; file.seek(0); file.readFully(header);
                byte[] normalized = header.clone(); index.normalizeDuration(0, normalized, 0, normalized.length);
                int movie = find(header, 0, header.length, "moov");
                int end = movie - 8 + (int) uint(header, movie - 8);
                int mvhd = find(header, movie, end, "mvhd");
                if (header[mvhd] != 0) throw new AssertionError("Fixture must use v0 mvhd");
                int field = mvhd + 16; double actualMs = uint(normalized, field) * 1000.0 / uint(header, mvhd + 12);
                if (Math.abs(actualMs - expectedMs) > 100) throw new AssertionError("Wrong duration " + actualMs);
                for (int at = 0; at < header.length; at++) {
                    if (at < field || at >= field + 4) {
                        if (header[at] != normalized[at]) throw new AssertionError("Unrelated header byte changed");
                    }
                    byte[] single = {header[at]}; index.normalizeDuration(at, single, 0, 1);
                    if (single[0] != normalized[at]) throw new AssertionError("Split read differs");
                }
                byte[] data = new byte[4096]; file.seek(path.length() / 2); file.readFully(data);
                byte[] copy = data.clone(); index.normalizeDuration(path.length() / 2, copy, 0, copy.length);
                if (!Arrays.equals(copy, data)) throw new AssertionError("Media payload changed");
                FragmentedMp4BufferIndex existing = new FragmentedMp4BufferIndex(new MediaBufferIndex.Reader() {
                    public long length() { return path.length(); }
                    public void read(long at, byte[] bytes, int offset, int count) throws java.io.IOException {
                        file.seek(at); file.readFully(bytes, offset, count); index.normalizeDuration(at, bytes, offset, count);
                    }
                });
                byte[] preserved = normalized.clone(); existing.normalizeDuration(0, preserved, 0, preserved.length);
                if (!Arrays.equals(preserved, normalized)) throw new AssertionError("Existing declared duration changed");
                FragmentedMp4BufferIndex noFooter = new FragmentedMp4BufferIndex(new MediaBufferIndex.Reader() {
                    public long length() { return path.length() - 16; }
                    public void read(long at, byte[] bytes, int offset, int count) throws java.io.IOException {
                        file.seek(at); file.readFully(bytes, offset, count);
                    }
                });
                byte[] untouched = header.clone(); noFooter.normalizeDuration(0, untouched, 0, untouched.length);
                if (!Arrays.equals(untouched, header)) throw new AssertionError("Unindexed duration was guessed");
                System.out.println(path.getName() + ": virtual duration " + actualMs + " ms; other header and payload bytes unchanged");
            }
        }
    }
}
