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

/** Uses real cached Cluster payloads, never a byte-to-duration ratio or CueTime interpolation. */
final class MatroskaBufferIndex implements MediaBufferIndex {
    private static final long CLUSTER = 0x1f43b675L, CUES = 0x1c53bb6bL, SEEK = 0x114d9b74L;
    private final long length, segmentStart, segmentEnd, firstCluster;
    private long scale = 1000000;
    private final Map<Long, Track> tracks = new LinkedHashMap<>();
    private final TreeSet<Long> anchors = new TreeSet<>();
    private final java.util.NavigableMap<Long,Long> cueTimes=new java.util.TreeMap<>();
    private final LinkedHashMap<Long, Unit> parsed = new LinkedHashMap<>(16, .75f, true);

    MatroskaBufferIndex(Reader source) throws IOException {
        length = source.length();
        Element ebml = element(source, 0, length); require(ebml.id == 0x1a45dfa3L && !ebml.unknown);
        Element segment = element(source, ebml.end, length); require(segment.id == 0x18538067L);
        segmentStart = segment.body; segmentEnd = segment.end;
        long cuePosition = -1, secondary = -1, first = -1;
        int count = 0;
        for (long at = segment.body; at < segment.end && ++count < 10000;) {
            Element child = element(source, at, segment.end);
            if (child.id == CLUSTER) { first = at; break; }
            require(!child.unknown);
            if (child.id == 0x1549a966L) {
                require(child.end - child.body <= 1024 * 1024);
                for (Element item : children(source, child)) if (item.id == 0x2ad7b1L) scale = unsigned(source, item);
            } else if (child.id == 0x1654ae6bL) readTracks(source, child);
            else if (child.id == SEEK) {
                long[] targets = seekTargets(source, child); cuePosition = targets[0]; secondary = targets[1];
            } else if (child.id == CUES) cuePosition = at;
            at = child.end;
        }
        require(first >= 0 && scale > 0 && !tracks.isEmpty()); firstCluster = first; anchors.add(first);
        if (cuePosition < 0 && secondary >= 0) {
            Element head = element(source, secondary, segmentEnd);
            if (head.id == SEEK) cuePosition = seekTargets(source, head)[0];
        }
        if (cuePosition >= 0) {
            Element cues = element(source, cuePosition, segmentEnd);
            require(cues.id == CUES && !cues.unknown && cues.end - cues.body <= 8 * 1024 * 1024);
            for (Element point : children(source, cues)) if (point.id == 0xbb) {
                long cueTime=-1;for(Element item:children(source,point))if(item.id==0xb3){long value=unsigned(source,item);cueTime=(long)(value*(double)scale/1000000);}
                for (Element positions : children(source, point)) if (positions.id == 0xb7) {
                    for (Element item : children(source, positions)) if (item.id == 0xf1) {
                        long relative = unsigned(source, item); require(relative <= segmentEnd - segmentStart);
                        long offset = segmentStart + relative;
                        require(offset >= firstCluster && offset < segmentEnd && anchors.size() < 100000);
                        anchors.add(offset);if(cueTime>=0)cueTimes.merge(cueTime,offset,Math::min);
                    }
                }
            }
        }
    }

    @Override public long byteOffset(long timeMs){java.util.Map.Entry<Long,Long> cue=cueTimes.floorEntry(Math.max(0,timeMs));return cue==null?firstCluster:cue.getValue();}

    private long[] seekTargets(Reader source, Element head) throws IOException {
        require(head.end - head.body <= 1024 * 1024);
        long cues = -1, secondary = -1;
        for (Element entry : children(source, head)) if (entry.id == 0x4dbb) {
            long id = -1, position = -1;
            for (Element item : children(source, entry)) {
                if (item.id == 0x53ab) id = unsigned(source, item);
                else if (item.id == 0x53ac) position = unsigned(source, item);
            }
            if (position < 0 || position >= segmentEnd - segmentStart) continue;
            if (id == CUES) cues = segmentStart + position;
            else if (id == SEEK) secondary = segmentStart + position;
        }
        return new long[] {cues, secondary};
    }

    private static final class Track {
        long id, type, durationNs, delayNs, preRollNs, offsetNs;
    }
    private void readTracks(Reader source, Element table) throws IOException {
        require(table.end - table.body <= 8 * 1024 * 1024);
        for (Element entry : children(source, table)) if (entry.id == 0xae) {
            Track track = new Track();
            for (Element item : children(source, entry)) {
                if (item.id == 0xd7) track.id = unsigned(source, item);
                else if (item.id == 0x83) track.type = unsigned(source, item);
                else if (item.id == 0x23e383) track.durationNs = unsigned(source, item);
                else if (item.id == 0x56aa) track.delayNs = unsigned(source, item);
                else if (item.id == 0x56bb) track.preRollNs = unsigned(source, item);
                else if (item.id == 0x537f) track.offsetNs = signed(source, item);
                else if (item.id == 0x23314f) require(Math.abs(floating(source, item) - 1) < .00000001);
            }
            if (track.type != 1 && track.type != 2) continue;
            require(track.id > 0 && tracks.size() < 16 && !tracks.containsKey(track.id));
            tracks.put(track.id, track);
        }
    }

    private static final class Span {
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY, key = Double.POSITIVE_INFINITY;
        void add(double from, double to, boolean sync) {
            start = Math.min(start, from); end = Math.max(end, to); if (sync) key = Math.min(key, from);
        }
        void merge(Span other) { start = Math.min(start, other.start); end = Math.max(end, other.end); key = Math.min(key, other.key); }
    }
    private static final class Unit {
        long start, end;
        final Map<Long, Span> spans = new LinkedHashMap<>();
    }

    @Override public List<long[]> ranges(PlaybackByteCache cache, long duration) throws IOException {
        List<long[]> coverage = cache.ranges(), result = new ArrayList<>();
        Reader source = cached(cache, length);
        // A demuxer can seek to a Cluster not listed in sparse Cues. Only inspect the
        // actual incoming range boundary; never search compressed frame data for IDs.
        List<Long> boundaries = cache.readStarts();
        for (long[] range : coverage) boundaries.add(range[0]);
        for (long boundary : boundaries) if (boundary >= firstCluster && boundary < segmentEnd && anchors.size() < 100000) {
            try { if (element(source, boundary, segmentEnd).id == CLUSTER) anchors.add(boundary); }
            catch (IOException incompleteBoundary) {}
        }
        List<Unit> available = new ArrayList<>();
        TreeSet<Long> examine = new TreeSet<>(anchors);
        int examined = 0;
        while (!examine.isEmpty() && examined++ < 100000) {
            long position = examine.pollFirst();
            if (!covered(coverage, position, Math.min(length, position + 16))) continue;
            try {
                Element cluster = element(source, position, segmentEnd);
                if (cluster.id != CLUSTER) continue;
                if (cluster.unknown) resolveUnknownCluster(source, cluster);
                // Discover consecutive cached clusters even when the file has sparse or no Cues.
                long next = cluster.end;
                for (int skip = 0; skip < 64 && next < segmentEnd && covered(coverage, next, Math.min(segmentEnd, next + 16)); skip++) {
                    Element following = element(source, next, segmentEnd);
                    if (following.id == CLUSTER) {
                        if (anchors.size() < 100000 && anchors.add(next)) examine.add(next);
                        break;
                    }
                    if (following.unknown) break;
                    next = following.end;
                }
                if (!covered(coverage, position, cluster.end)) continue;
                Unit unit = parsed.get(position);
                if (unit == null) {
                    unit = readCluster(source, cluster); parsed.put(position, unit);
                    if (parsed.size() > 2048) parsed.remove(parsed.keySet().iterator().next());
                }
                available.add(unit);
            } catch (IOException notCachedOrUnsupported) { /* An incomplete unit never becomes a time range. */ }
        }
        available.sort(java.util.Comparator.comparingLong(unit -> unit.start));
        Map<Long, Span> run = new LinkedHashMap<>(); long runStart = -1, lastEnd = -1;
        for (Unit unit : available) {
            if (lastEnd != unit.start) { emit(result, run, runStart, duration); run.clear(); runStart = unit.start; }
            for (Map.Entry<Long, Span> entry : unit.spans.entrySet())
                run.computeIfAbsent(entry.getKey(), unused -> new Span()).merge(entry.getValue());
            lastEnd = unit.end;
        }
        emit(result, run, runStart, duration);
        return result;
    }

    private void resolveUnknownCluster(Reader source, Element cluster) throws IOException {
        long at = cluster.body;
        for (int count = 0; at < segmentEnd && count++ < 50000;) {
            Element child = element(source, at, segmentEnd);
            if (child.id == CLUSTER || child.id == CUES || child.id == SEEK || child.id == 0x1254c367L
                    || child.id == 0x1941a469L || child.id == 0x1043a770L || child.id == 0x1654ae6bL || child.id == 0x1549a966L) {
                cluster.end = at; cluster.unknown = false; return;
            }
            require(!child.unknown); at = child.end;
        }
        require(at == segmentEnd); cluster.end = at; cluster.unknown = false;
    }

    private void emit(List<long[]> result, Map<Long, Span> spans, long runStart, long duration) {
        double start = 0, end = duration;
        for (Track track : tracks.values()) {
            Span span = spans.get(track.id); if (span == null) return;
            double from = track.type == 1 ? span.key : span.start;
            if (track.type == 2 && runStart != firstCluster) from += track.preRollNs / 1000000.0;
            start = Math.max(start, from); end = Math.min(end, span.end);
        }
        if (Double.isFinite(start) && Double.isFinite(end)) append(result, (long) Math.ceil(start), (long) Math.floor(end));
    }

    private Unit readCluster(Reader source, Element cluster) throws IOException {
        List<Element> elements = children(source, cluster); long clock = -1;
        for (Element item : elements) if (item.id == 0xe7) clock = unsigned(source, item);
        require(clock >= 0); Unit unit = new Unit(); unit.start = cluster.start; unit.end = cluster.end;
        for (Element item : elements) {
            if (item.id == 0xa3) block(source, item, clock, true, -1, false, unit);
            else if (item.id == 0xa0) {
                Element block = null; boolean referenced = false; long ticks = -1;
                for (Element part : children(source, item)) {
                    if (part.id == 0xa1) block = part;
                    else if (part.id == 0xfb) referenced = true;
                    else if (part.id == 0x9b) ticks = unsigned(source, part);
                }
                if (block != null) block(source, block, clock, false, ticks, !referenced, unit);
            }
        }
        return unit;
    }

    private void block(Reader source, Element block, long clock, boolean simple, long durationTicks,
                       boolean groupSync, Unit unit) throws IOException {
        byte[] head = new byte[(int) Math.min(16, block.end - block.body)];
        require(head.length >= 4); source.read(block.body, head, 0, head.length);
        int width = vintWidth(head[0] & 255, 8); require(width + 3 <= head.length);
        long number = head[0] & (255 >>> width);
        for (int i = 1; i < width; i++) number = number << 8 | head[i] & 255;
        Track track = tracks.get(number); if (track == null) return;
        int relative = (short) ((head[width] & 255) << 8 | head[width + 1] & 255);
        int flags = head[width + 2] & 255, frames = 1;
        if ((flags & 6) != 0) { require(width + 3 < head.length); frames = (head[width + 3] & 255) + 1; }
        double from = ((clock + relative) * (double) scale + track.offsetNs - track.delayNs) / 1000000.0;
        double length = durationTicks >= 0 ? durationTicks * (double) scale / 1000000.0 : track.durationNs * (double) frames / 1000000.0;
        boolean sync = track.type != 1 || (simple ? (flags & 128) != 0 : groupSync);
        unit.spans.computeIfAbsent(number, unused -> new Span()).add(from, from + length, sync);
    }

    private static final class Element {
        long id, start, body, end; boolean unknown;
    }
    private static int vintWidth(int first, int maximum) throws IOException {
        int mask = 128, width = 1;
        while (width <= maximum && (first & mask) == 0) { width++; mask >>>= 1; }
        require(width <= maximum); return width;
    }
    private static Element element(Reader source, long at, long limit) throws IOException {
        require(at >= 0 && limit <= source.length() && at < limit);
        byte[] head = new byte[(int) Math.min(16, limit - at)]; source.read(at, head, 0, head.length);
        int idWidth = vintWidth(head[0] & 255, 4); require(idWidth < head.length);
        long id = 0; for (int i = 0; i < idWidth; i++) id = id << 8 | head[i] & 255;
        int sizeWidth = vintWidth(head[idWidth] & 255, 8); require(idWidth + sizeWidth <= head.length);
        long size = head[idWidth] & (255 >>> sizeWidth);
        for (int i = 1; i < sizeWidth; i++) size = size << 8 | head[idWidth + i] & 255;
        Element element = new Element(); element.id = id; element.start = at; element.body = at + idWidth + sizeWidth;
        element.unknown = size == (1L << (sizeWidth * 7)) - 1;
        require(element.unknown || size <= limit - element.body);
        element.end = element.unknown ? limit : element.body + size;
        return element;
    }
    private static List<Element> children(Reader source, Element parent) throws IOException {
        List<Element> result = new ArrayList<>();
        for (long at = parent.body; at < parent.end;) {
            require(result.size() < 50000);
            Element child = element(source, at, parent.end); require(!child.unknown);
            result.add(child); at = child.end;
        }
        return result;
    }
    private static byte[] scalar(Reader source, Element item) throws IOException {
        require(item.end > item.body && item.end - item.body <= 8);
        byte[] bytes = new byte[(int) (item.end - item.body)]; source.read(item.body, bytes, 0, bytes.length); return bytes;
    }
    private static long unsigned(Reader source, Element item) throws IOException {
        long value = 0; for (byte b : scalar(source, item)) value = value << 8 | b & 255;
        require(value >= 0); return value;
    }
    private static long signed(Reader source, Element item) throws IOException {
        byte[] bytes = scalar(source, item); long value = bytes[0] < 0 ? -1 : 0;
        for (byte b : bytes) value = value << 8 | b & 255; return value;
    }
    private static double floating(Reader source, Element item) throws IOException {
        byte[] bytes = scalar(source, item); long bits = 0; for (byte b : bytes) bits = bits << 8 | b & 255;
        require(bytes.length == 4 || bytes.length == 8);
        return bytes.length == 4 ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
    }
}
