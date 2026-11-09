package com.smbplayer.tv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Synthetic large offsets avoid allocating a multi-gigabyte fixture on the host. */
public final class Mp4EdgeCheck {
    private static final long VIDEO = (1L << 32) + 100, AUDIO = VIDEO + 10000;
    private static byte[] words(int... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        for (int value : values) data.writeInt(value);
        return bytes.toByteArray();
    }
    private static int four(String name) { return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3); }
    private static byte[] atom(String type, byte[]... bodies) throws IOException {
        int length = 8; for (byte[] body : bodies) length += body.length;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes); data.writeInt(length); data.writeInt(four(type));
        for (byte[] body : bodies) data.write(body);
        return bytes.toByteArray();
    }
    private static byte[] track(boolean audio, boolean external, boolean malformed) throws IOException {
        long offset = audio ? AUDIO : VIDEO;
        byte[] stbl = atom("stbl",
                atom("stsd", words(0, 1), atom(audio ? "mp4a" : "avc1", words(0, 1, 0))),
                atom("stsz", words(0, audio ? 10 : 100, 10)),
                atom("stsc", words(0, 1, 1, 10, 1)),
                atom("co64", words(0, 1, (int) (offset >>> 32), (int) offset)),
                atom("stts", words(0, 1, malformed ? 11 : 10, 1000)),
                audio ? new byte[0] : atom("ctts", words(1 << 24, 2, 5, -100, 5, 100)),
                audio ? new byte[0] : atom("stss", words(0, 2, 1, 6)));
        return atom("trak", atom("mdia",
                atom("mdhd", words(0, 0, 0, 1000, 10000)),
                atom("hdlr", words(0, 0, four(audio ? "soun" : "vide"))),
                atom("minf", atom("dinf", atom("dref", words(0, 1), atom("url ", words(external ? 0 : 1)))), stbl)),
                atom("edts", atom("elst", words(1 << 24, 1, 0, 10000, 0, 0, 65536))));
    }
    private static Mp4BufferIndex index(byte[] moov) throws IOException {
        return Mp4BufferIndex.read(new Mp4BufferIndex.Reader() {
            public long length() { return 5L << 30; }
            public void read(long position, byte[] out, int begin, int count) throws IOException {
                if (position < 0 || position + count > moov.length) throw new IOException("Unexpected virtual read");
                System.arraycopy(moov, (int) position, out, begin, count);
            }
        });
    }
    private static void span(List<long[]> actual, long start, long end) {
        if (actual.size() != 1 || actual.get(0)[0] != start || actual.get(0)[1] != end)
            throw new AssertionError("Unexpected interval: " + (actual.isEmpty() ? "empty" : Arrays.toString(actual.get(0))));
    }
    private static void reject(byte[] bytes) throws Exception {
        try { index(bytes); throw new AssertionError("Unsupported index accepted"); }
        catch (IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        byte[] header = atom("mvhd", words(0, 0, 0, 1000, 10000));
        Mp4BufferIndex video = index(atom("moov", header, track(false, false, false)));
        span(video.ranges(Collections.singletonList(new long[] {VIDEO, VIDEO + 1000}), 10000), 0, 10000);
        span(video.ranges(Collections.singletonList(new long[] {VIDEO, VIDEO + 500}), 10000), 0, 4900);
        span(video.ranges(Collections.singletonList(new long[] {VIDEO + 500, VIDEO + 1000}), 10000), 5100, 10000);
        if (!video.ranges(Collections.singletonList(new long[] {VIDEO + 100, VIDEO + 500}), 10000).isEmpty())
            throw new AssertionError("Non-keyframe cache start incorrectly became playable");
        Mp4BufferIndex both = index(atom("moov", header, track(false, false, false), track(true, false, false)));
        span(both.ranges(Arrays.asList(new long[] {VIDEO, VIDEO + 1000}, new long[] {AUDIO, AUDIO + 50}), 10000), 0, 5000);
        reject(atom("moov", header, track(false, true, false)));
        reject(atom("moov", header, track(false, false, true)));
        reject(atom("moov", header, atom("mvex"), track(false, false, false)));
        byte[] zeroEdit = atom("moov", header, track(false, false, false));
        for (int i = 0; i < zeroEdit.length - 20; i++) {
            if (zeroEdit[i] == 'e' && zeroEdit[i + 1] == 'l' && zeroEdit[i + 2] == 's' && zeroEdit[i + 3] == 't') {
                Arrays.fill(zeroEdit, i + 12, i + 20, (byte) 0); break;
            }
        }
        reject(zeroEdit);
        reject(words(16 * 1024 * 1024, four("moov")));
        for (String file : args) {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file));
            try { index(bytes); throw new AssertionError("Fragmented MP4 accepted"); }
            catch (IOException expected) {}
        }
        System.out.println("64-bit offsets, signed composition times, edit lists, audio intersection and rejection checks passed");
    }
}
