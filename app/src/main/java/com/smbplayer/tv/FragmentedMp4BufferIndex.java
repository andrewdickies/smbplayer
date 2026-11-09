// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import static com.smbplayer.tv.BufferIndexSupport.*;
import com.smbplayer.tv.Mp4BufferIndex.Box;

/** Fragment addresses come from tfra/sidx or structurally consecutive cached boxes. */
final class FragmentedMp4BufferIndex implements MediaBufferIndex {
    private final long length;
    private final Map<Long, Track> tracks = new LinkedHashMap<>();
    private final TreeSet<Long> anchors = new TreeSet<>();
    private long durationOffset = -1;
    private byte[] durationBytes;
    private final LinkedHashMap<Long, Unit> parsed = new LinkedHashMap<>(16, .75f, true);
    private static final class Track {
        long id, scale, duration, size, flags, mediaStart;
        double emptyMs;
        boolean video;
    }
    private static final class Span {
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY, sync = Double.POSITIVE_INFINITY;
        void add(double from, double to, boolean key) { start = Math.min(start, from); end = Math.max(end, to); if (key) sync = Math.min(sync, from); }
        void merge(Span other) { start = Math.min(start, other.start); end = Math.max(end, other.end); sync = Math.min(sync, other.sync); }
    }
    private static final class Unit {
        long start, end;
        final Map<Long, Span> spans = new LinkedHashMap<>();
    }
    private static final class Atom {
        long start, body, end; String type;
    }

    FragmentedMp4BufferIndex(Reader source) throws IOException {
        length = source.length(); Box movie = null; Atom movieAtom = null;
        List<Atom> segmentIndexes = new ArrayList<>();
        for (long at = 0; at < length;) {
            Atom atom = atom(source, at);
            if (atom.type.equals("sidx")) segmentIndexes.add(atom);
            if (atom.type.equals("moov")) { movie = payload(source, atom); movieAtom = atom; break; }
            require(at < 8L * 1024 * 1024 || atom.type.equals("mdat")); at = atom.end;
        }
        require(movie != null && movie.find("mvex") != null);
        Box mvhd = movie.find("mvhd"); require(mvhd != null);
        long movieScale = scale(mvhd);
        for (Box trak : movie.children()) if (trak.type.equals("trak")) {
            Box mdia = trak.find("mdia"), tkhd = trak.find("tkhd"); require(mdia != null && tkhd != null);
            Box hdlr = mdia.find("hdlr"), mdhd = mdia.find("mdhd"); require(hdlr != null && mdhd != null);
            int kind = hdlr.intAt(8); if (kind != four("vide") && kind != four("soun")) continue;
            Track track = new Track(); track.video = kind == four("vide"); track.scale = scale(mdhd);
            require(tkhd.version() <= 1); track.id = tkhd.uintAt(tkhd.version() == 1 ? 20 : 12);
            Box minf = mdia.find("minf"); require(minf != null);
            Box dinf = minf.find("dinf"); require(dinf != null);
            Box dref = dinf.find("dref"); require(dref != null && dref.uintAt(4) == 1);
            List<Box> refs = new Box(dref.bytes, dref.start + 8, dref.end).children();
            require(refs.size() == 1 && refs.get(0).type.equals("url ") && (refs.get(0).intAt(0) & 0xffffff) == 1);
            Box edts = trak.find("edts");
            if (edts != null && edts.find("elst") != null) edits(track, edts.find("elst"), movieScale);
            require(track.id > 0 && tracks.size() < 16 && !tracks.containsKey(track.id)); tracks.put(track.id, track);
        }
        require(!tracks.isEmpty());
        for (Box trex : movie.find("mvex").children()) if (trex.type.equals("trex")) {
            Track track = tracks.get(trex.uintAt(4)); if (track == null) continue;
            track.duration = trex.uintAt(12); track.size = trex.uintAt(16); track.flags = trex.uintAt(20);
        }
        for (long at = movieAtom.end, count = 0; at < length && count++ < 1000;) {
            Atom next = atom(source, at);
            if (next.type.equals("moof")) { anchors.add(at); break; }
            if (next.type.equals("sidx")) segmentIndexes.add(next);
            at = next.end;
        }
        for (Atom sidx : segmentIndexes) sidx(source, sidx);
        boolean indexedTail = false;
        if (length >= 16) {
            byte[] footer = new byte[16]; source.read(length - 16, footer, 0, 16);
            if (u32(footer, 0) == 16 && word(footer, 4) == four("mfro")) {
                long size = u32(footer, 12); require(size >= 24 && size <= 8 * 1024 * 1024 && size <= length);
                Atom mfra = atom(source, length - size); require(mfra.type.equals("mfra") && mfra.end == length);
                indexedTail = true;
                for (Box tfra : payload(source, mfra).children()) if (tfra.type.equals("tfra")) {
                    require(tfra.version() <= 1); int width = tfra.version() == 1 ? 16 : 8;
                    int sizes = tfra.intAt(8), suffix = ((sizes >>> 4) & 3) + ((sizes >>> 2) & 3) + (sizes & 3) + 3;
                    int count = tfra.count(12, 16, width + suffix); require(count <= 100000);
                    for (int i = 0; i < count; i++) {
                        int at = 16 + i * (width + suffix);
                        long offset = tfra.version() == 1 ? tfra.ulongAt(at + 8) : tfra.uintAt(at + 4);
                        require(offset >= 0 && offset < length && anchors.size() < 100000); anchors.add(offset);
                    }
                }
            }
        }
        require(!anchors.isEmpty());
        // Some muxers leave mvhd duration at zero despite a complete tail index.
        // Supply only that missing field to the local playback view; do not rewrite the NAS.
        int durationAt = mvhd.version() == 1 ? 24 : 16;
        long declared = mvhd.version() == 1 ? mvhd.ulongAt(durationAt) : mvhd.uintAt(durationAt);
        if (indexedTail && declared == 0) {
            try {
                Map<Long, Double> ends = new LinkedHashMap<>();
                int inspected = 0;
                for (long offset : anchors.descendingSet()) {
                    if (++inspected > 16) break;
                    Atom moof = atom(source, offset); require(moof.type.equals("moof"));
                    Atom mdat = atom(source, moof.end); require(mdat.type.equals("mdat"));
                    if (inspected == 1) {
                        Atom footer = atom(source, mdat.end);
                        require(footer.type.equals("mfra") && footer.end == length);
                    }
                    Unit tail = fragment(source, moof, mdat.end,
                            java.util.Collections.singletonList(new long[] {mdat.body, mdat.end}));
                    for (Map.Entry<Long, Span> entry : tail.spans.entrySet())
                        ends.merge(entry.getKey(), entry.getValue().end, Math::max);
                    if (ends.size() == tracks.size()) break;
                }
                require(ends.size() == tracks.size()); double endMs = 0;
                for (double end : ends.values()) endMs = Math.max(endMs, end);
                double ticks = Math.ceil(endMs * movieScale / 1000);
                int width = mvhd.version() == 1 ? 8 : 4;
                require(Double.isFinite(ticks) && ticks > 0 && ticks < Long.MAX_VALUE
                        && (width == 8 || ticks < 0xffffffffL));
                long value = (long) ticks; durationBytes = new byte[width];
                for (int i = width - 1; i >= 0; i--) { durationBytes[i] = (byte) value; value >>>= 8; }
                durationOffset = movieAtom.body + mvhd.start + durationAt;
            } catch (IOException | ArithmeticException unavailableTail) { durationOffset = -1; durationBytes = null; }
        }
    }

    void normalizeDuration(long position, byte[] buffer, int offset, int count) {
        if (durationBytes == null) return;
        long first = Math.max(position, durationOffset), end = Math.min(position + count, durationOffset + durationBytes.length);
        for (long at = first; at < end; at++) buffer[offset + (int) (at - position)] = durationBytes[(int) (at - durationOffset)];
    }

    private void sidx(Reader source, Atom atom) throws IOException {
        Box box = payload(source, atom); require(box.version() <= 1);
        int at = box.version() == 1 ? 28 : 20;
        long first = box.version() == 1 ? box.ulongAt(20) : box.uintAt(16);
        require(first <= length - atom.end); long position = atom.end + first;
        int count = box.intAt(at) & 65535; require((long) at + 4 + count * 12L <= box.length());
        for (int i = 0; i < count; i++) {
            long reference = box.uintAt(at + 4 + i * 12), size = reference & 0x7fffffff;
            require(size > 0 && size <= length - position && anchors.size() < 100000);
            if ((reference & 0x80000000L) == 0) anchors.add(position);
            position += size;
        }
    }

    private static void edits(Track track, Box edits, long movieScale) throws IOException {
        require(edits.version() <= 1); int width = edits.version() == 1 ? 20 : 12;
        int count = edits.count(4, 8, width); require(count >= 1 && count <= 2); boolean active = false;
        for (int i = 0; i < count; i++) {
            int at = 8 + i * width;
            long duration = edits.version() == 1 ? edits.ulongAt(at) : edits.uintAt(at);
            long time = edits.version() == 1 ? edits.longAt(at + 8) : edits.intAt(at + 4);
            require(edits.intAt(at + width - 4) == 65536);
            if (time == -1 && i == 0) track.emptyMs = duration * 1000.0 / movieScale;
            else { require(!active && time >= 0); track.mediaStart = time; active = true; }
        }
        require(active);
    }

    @Override public List<long[]> ranges(PlaybackByteCache cache, long duration) throws IOException {
        List<long[]> coverage = cache.ranges(), result = new ArrayList<>(); Reader source = cached(cache, length);
        List<Long> boundaries = cache.readStarts();
        for (long[] range : coverage) boundaries.add(range[0]);
        for (long boundaryPosition : boundaries) if (anchors.size() < 100000) {
            try {
                Atom boundary = atom(source, boundaryPosition);
                if (boundary.type.equals("moof") || boundary.type.equals("styp")) anchors.add(boundaryPosition);
                else if (boundary.type.equals("sidx")) sidx(source, boundary);
            } catch (IOException incompleteBoundary) {}
        }
        TreeSet<Long> examine = new TreeSet<>(anchors), used = new TreeSet<>(); List<Unit> available = new ArrayList<>();
        for (int count = 0; !examine.isEmpty() && count++ < 100000;) {
            long anchor = examine.pollFirst(); if (!covered(coverage, anchor, Math.min(length, anchor + 16))) continue;
            try {
                Atom fragment = atom(source, anchor);
                for (int skip = 0; !fragment.type.equals("moof") && skip < 64 && fragment.end < length; skip++) fragment = atom(source, fragment.end);
                if (!fragment.type.equals("moof") || !used.add(fragment.start)) continue;
                long end = fragment.end; List<long[]> data = new ArrayList<>();
                for (int boxes = 0; end < length && boxes++ < 64 && covered(coverage, end, Math.min(length, end + 16));) {
                    Atom following = atom(source, end);
                    if (following.type.equals("moof")) {
                        if (anchors.size() < 100000 && anchors.add(end)) examine.add(end); break;
                    }
                    if (following.type.equals("mdat")) { data.add(new long[] {following.body, following.end}); end = following.end; }
                    else if (following.type.equals("free") || following.type.equals("emsg") || following.type.equals("styp") || following.type.equals("sidx")) end = following.end;
                    else break;
                }
                if (data.isEmpty() || !covered(coverage, fragment.start, end)) continue;
                Unit unit = parsed.get(fragment.start);
                if (unit == null) {
                    unit = fragment(source, fragment, end, data); parsed.put(fragment.start, unit);
                    if (parsed.size() > 2048) parsed.remove(parsed.keySet().iterator().next());
                }
                available.add(unit);
            } catch (IOException notCachedOrUnsupported) { /* Wait for complete, verifiable data. */ }
        }
        available.sort(java.util.Comparator.comparingLong(unit -> unit.start));
        Map<Long, Span> run = new LinkedHashMap<>(); long end = -1;
        for (Unit unit : available) {
            if (end != unit.start) { emit(result, run, duration); run.clear(); }
            for (Map.Entry<Long, Span> entry : unit.spans.entrySet()) run.computeIfAbsent(entry.getKey(), key -> new Span()).merge(entry.getValue());
            end = unit.end;
        }
        emit(result, run, duration); return result;
    }

    private void emit(List<long[]> output, Map<Long, Span> spans, long duration) {
        double start = 0, end = duration;
        for (Track track : tracks.values()) {
            Span span = spans.get(track.id); if (span == null) return;
            start = Math.max(start, track.video ? span.sync : span.start); end = Math.min(end, span.end);
        }
        if (Double.isFinite(start) && Double.isFinite(end)) append(output, (long) Math.ceil(start), (long) Math.floor(end));
    }

    private Unit fragment(Reader source, Atom atom, long end, List<long[]> data) throws IOException {
        Box moof = payload(source, atom); Unit unit = new Unit(); unit.start = atom.start; unit.end = end;
        long implicitBase = atom.start;
        for (Box traf : moof.children()) if (traf.type.equals("traf")) {
            Box tfhd = traf.find("tfhd"), tfdt = traf.find("tfdt"); require(tfhd != null && tfdt != null && tfdt.version() <= 1);
            require(tfhd.version() == 0);
            Track track = tracks.get(tfhd.uintAt(4)); if (track == null) continue;
            int flags = tfhd.intAt(0) & 0xffffff, at = 8;
            long base = (flags & 0x20000) != 0 ? atom.start : implicitBase;
            if ((flags & 1) != 0) { base = tfhd.ulongAt(at); at += 8; }
            if ((flags & 2) != 0) at += 4;
            long sampleDuration = track.duration, sampleSize = track.size, sampleFlags = track.flags;
            if ((flags & 8) != 0) { sampleDuration = tfhd.uintAt(at); at += 4; }
            if ((flags & 16) != 0) { sampleSize = tfhd.uintAt(at); at += 4; }
            if ((flags & 32) != 0) sampleFlags = tfhd.uintAt(at);
            long decode = tfdt.version() == 1 ? tfdt.ulongAt(4) : tfdt.uintAt(4), position = base;
            Span span = unit.spans.computeIfAbsent(track.id, key -> new Span());
            for (Box run : traf.children()) if (run.type.equals("trun")) {
                require(run.version() <= 1); int bits = run.intAt(0) & 0xffffff;
                long samples = run.uintAt(4); require(samples > 0 && samples <= 200000); int cursor = 8;
                if ((bits & 1) != 0) { position = Math.addExact(base, run.intAt(cursor)); cursor += 4; }
                long firstFlags = sampleFlags;
                if ((bits & 4) != 0) { firstFlags = run.uintAt(cursor); cursor += 4; }
                int width = Integer.bitCount(bits & 0xf00) * 4; require(cursor + samples * width <= run.length());
                for (int i = 0; i < samples; i++) {
                    long duration = sampleDuration, size = sampleSize, packetFlags = i == 0 ? firstFlags : sampleFlags, composition = 0;
                    if ((bits & 0x100) != 0) { duration = run.uintAt(cursor); cursor += 4; }
                    if ((bits & 0x200) != 0) { size = run.uintAt(cursor); cursor += 4; }
                    if ((bits & 0x400) != 0) { packetFlags = run.uintAt(cursor); cursor += 4; }
                    if ((bits & 0x800) != 0) { composition = run.version() == 1 ? run.intAt(cursor) : run.uintAt(cursor); cursor += 4; }
                    require(duration > 0 && size > 0 && position >= 0 && size <= length - position && covered(data, position, position + size));
                    double time = (decode + (double) composition - track.mediaStart) * 1000 / track.scale + track.emptyMs;
                    boolean sync = !track.video || (packetFlags & 0x10000) == 0 && ((packetFlags >>> 24) & 3) != 1;
                    span.add(time, time + duration * 1000.0 / track.scale, sync);
                    decode = Math.addExact(decode, duration); position += size;
                }
            }
            implicitBase = Math.max(implicitBase, position);
        }
        return unit;
    }

    private static Atom atom(Reader source, long position) throws IOException {
        require(position >= 0 && position <= source.length() - 8);
        byte[] bytes = new byte[(int) Math.min(16, source.length() - position)]; source.read(position, bytes, 0, bytes.length);
        long size = u32(bytes, 0); int header = 8;
        if (size == 1) { require(bytes.length == 16); size = u64(bytes, 8); header = 16; }
        if (size == 0) size = source.length() - position;
        require(size >= header && size <= source.length() - position);
        Atom result = new Atom(); result.start = position; result.body = position + header; result.end = position + size;
        result.type = new String(new char[] {(char) bytes[4], (char) bytes[5], (char) bytes[6], (char) bytes[7]}); return result;
    }
    private static Box payload(Reader source, Atom atom) throws IOException {
        require(atom.end - atom.body <= 8 * 1024 * 1024); byte[] bytes = new byte[(int) (atom.end - atom.body)];
        source.read(atom.body, bytes, 0, bytes.length); return new Box(bytes, 0, bytes.length);
    }
    private static long scale(Box box) throws IOException { require(box.version() <= 1); long scale = box.uintAt(box.version() == 1 ? 20 : 12); require(scale > 0); return scale; }
    private static int word(byte[] b, int at) { return (b[at] & 255) << 24 | (b[at + 1] & 255) << 16 | (b[at + 2] & 255) << 8 | b[at + 3] & 255; }
    private static long u32(byte[] b, int at) { return word(b, at) & 0xffffffffL; }
    private static long u64(byte[] b, int at) throws IOException { long value = (long) word(b, at) << 32 | u32(b, at + 4); require(value >= 0); return value; }
    private static int four(String name) { return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3); }
}
