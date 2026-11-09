// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Conservative time coverage derived from ISO BMFF sample tables and cached chunk bytes. */
final class Mp4BufferIndex implements MediaBufferIndex {
    interface Reader extends MediaBufferIndex.Reader {}
    private static final int MAX_METADATA = 8 * 1024 * 1024;
    private final List<Track> tracks;

    private Mp4BufferIndex(List<Track> tracks) { this.tracks = tracks; }

    static Mp4BufferIndex read(MediaBufferIndex.Reader source) throws IOException {
        byte[] header = new byte[16];
        long cursor = 0;
        for (int atoms = 0; atoms < 10000 && cursor + 8 <= source.length(); atoms++) {
            source.read(cursor, header, 0, 8);
            long size = uint(header, 0);
            int width = 8;
            if (size == 1) { source.read(cursor + 8, header, 8, 8); size = ulong(header, 8); width = 16; }
            if (size == 0) size = source.length() - cursor;
            require(size >= width && size <= source.length() - cursor);
            if (word(header, 4) == four("moov")) {
                require(size - width <= MAX_METADATA);
                byte[] metadata = new byte[(int) size - width];
                source.read(cursor + width, metadata, 0, metadata.length);
                Box movie = new Box(metadata, 0, metadata.length);
                require(movie.find("mvex") == null);
                Box mvhd = movie.find("mvhd");
                require(mvhd != null);
                long scale = mediaScale(mvhd);
                Budget budget = new Budget();
                List<Track> tracks = new ArrayList<>();
                for (Box child : movie.children()) {
                    if (!child.type.equals("trak")) continue;
                    Box mdia = child.find("mdia");
                    if (mdia == null) continue;
                    Box handler = mdia.find("hdlr");
                    require(handler != null && handler.length() >= 12);
                    int kind = handler.intAt(8);
                    if (kind != four("vide") && kind != four("soun")) continue;
                    require(tracks.size() < 16);
                    tracks.add(new Track(child, mdia, scale, source.length(), kind == four("vide"), budget));
                }
                require(!tracks.isEmpty());
                return new Mp4BufferIndex(tracks);
            }
            cursor += size;
        }
        throw new IOException("No supported MP4 index");
    }

    List<long[]> ranges(List<long[]> bytes, long durationMs) throws IOException {
        if (durationMs <= 0 || bytes.isEmpty()) return Collections.emptyList();
        List<long[]> result = null;
        for (Track track : tracks) {
            List<long[]> next = track.ranges(bytes, durationMs);
            if (result == null) result = next;
            else {
                List<long[]> overlap = new ArrayList<>();
                int a = 0, b = 0;
                while (a < result.size() && b < next.size()) {
                    long[] one = result.get(a), two = next.get(b);
                    append(overlap, Math.max(one[0], two[0]), Math.min(one[1], two[1]));
                    if (one[1] < two[1]) a++; else b++;
                }
                result = overlap;
            }
            if (result.isEmpty()) break;
        }
        return result == null ? Collections.emptyList() : result;
    }

    @Override public List<long[]> ranges(PlaybackByteCache cache, long durationMs) throws IOException {
        return ranges(cache.ranges(), durationMs);
    }

    @Override public long byteOffset(long timeMs)throws IOException{
        long result=Long.MAX_VALUE;
        for(Track track:tracks)result=Math.min(result,track.offsetAt(timeMs));
        return result==Long.MAX_VALUE?-1:result;
    }

    private static final class Budget {
        long bytes;
        void take(long amount) throws IOException { bytes += amount; require(bytes <= MAX_METADATA); }
    }

    private static final class Runs {
        final Box box;
        final int[] counts;
        final long[] weighted;
        final boolean signed;
        final long minimum, maximum;
        Runs(Box box, boolean signed, Budget budget) throws IOException {
            this.box = box;
            this.signed = signed;
            int count = box.count(4, 8, 8);
            require(count > 0);
            budget.take((long) (count + 1) * 12);
            counts = new int[count + 1]; weighted = new long[count + 1];
            long low = Long.MAX_VALUE, high = Long.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                long n = box.uintAt(8 + i * 8), value = value(i);
                require(n > 0 && n <= 2000000L - counts[i]);
                counts[i + 1] = counts[i] + (int) n;
                weighted[i + 1] = weighted[i] + n * value;
                require(Math.abs(weighted[i + 1]) < Long.MAX_VALUE / 2000);
                low = Math.min(low, value); high = Math.max(high, value);
            }
            minimum = low; maximum = high;
        }
        long value(int group) throws IOException { return signed ? box.intAt(12 + group * 8) : box.uintAt(12 + group * 8); }
        int group(int sample) {
            int lo = 0, hi = counts.length - 1;
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (counts[mid] <= sample) lo = mid; else hi = mid; }
            return lo;
        }
        long time(int sample) throws IOException {
            if (sample == counts[counts.length - 1]) return weighted[weighted.length - 1];
            int group = group(sample);
            return weighted[group] + (sample - counts[group]) * value(group);
        }
        int samples() { return counts[counts.length - 1]; }
    }

    private static final class Track {
        final Box sizes, offsets, mapping, sync;
        final boolean video, wide;
        final int samples, chunks, groups;
        final long fixedSize, scale, fileLength;
        final long[] samplePrefix;
        final long[] bytePrefix;
        final Runs times, composition;
        long mediaStart, emptyStartMs, emptyEndMs, editEndMs = Long.MAX_VALUE;

        Track(Box trak, Box mdia, long movieScale, long fileLength, boolean video, Budget budget) throws IOException {
            this.video = video; this.fileLength = fileLength;
            Box mdhd = mdia.find("mdhd"), minf = mdia.find("minf");
            require(mdhd != null && minf != null);
            scale = mediaScale(mdhd);
            Box stbl = minf.find("stbl"); require(stbl != null);
            Box dinf = minf.find("dinf"); require(dinf != null);
            Box dref = dinf.find("dref"); require(dref != null && dref.uintAt(4) == 1);
            List<Box> references = new Box(dref.bytes, dref.start + 8, dref.end).children();
            require(references.size() == 1 && "url ".equals(references.get(0).type)
                    && (references.get(0).intAt(0) & 0xffffff) == 1);
            sizes = stbl.find("stsz"); mapping = stbl.find("stsc");
            Box descriptions = stbl.find("stsd"); require(descriptions != null);
            int descriptionCount = descriptions.count(4, 8, 8);
            List<Box> formats = new Box(descriptions.bytes, descriptions.start + 8, descriptions.end).children();
            require(descriptionCount == formats.size() && descriptionCount > 0);
            for (Box format : formats) {
                require(format.length() >= 12 && (format.intAt(4) & 65535) == 1);
                if (video) require(" avc1 avc3 hvc1 hev1 av01 vp09 mp4v dvhe dvh1 ".contains(" " + format.type + " "));
                else require(" mp4a ac-3 ec-3 alac Opus ".contains(" " + format.type + " ") && format.intAt(8) >>> 16 == 0);
            }
            Box narrow = stbl.find("stco"); offsets = narrow == null ? stbl.find("co64") : narrow;
            require(sizes != null && offsets != null && mapping != null && sizes.length() >= 12);
            require(sizes.version() == 0 && offsets.version() == 0 && mapping.version() == 0);
            fixedSize = sizes.uintAt(4);
            long sampleCount = sizes.uintAt(8); require(sampleCount > 0 && sampleCount <= 2000000);
            samples = (int) sampleCount;
            if (fixedSize == 0) require(12L + samples * 4L <= sizes.length());
            if (fixedSize == 0) {
                int blocks = (samples + 255) / 256;
                budget.take((long) (blocks + 1) * 8);
                bytePrefix = new long[blocks + 1];
                long bytes = 0;
                for (int i = 0; i < samples; i++) {
                    if (i % 256 == 0) bytePrefix[i / 256] = bytes;
                    bytes += sizes.uintAt(12 + i * 4);
                    require(bytes <= fileLength);
                }
                bytePrefix[blocks] = bytes;
            } else { require(fixedSize <= fileLength / samples); bytePrefix = null; }
            wide = narrow == null;
            chunks = offsets.count(4, 8, wide ? 8 : 4); require(chunks > 0);
            groups = mapping.count(4, 8, 12); require(groups > 0);
            budget.take((long) groups * 8); samplePrefix = new long[groups];
            long mapped = 0;
            for (int i = 0; i < groups; i++) {
                long first = firstChunk(i), next = i + 1 < groups ? firstChunk(i + 1) : (long) chunks + 1;
                require(first >= 1 && first <= chunks && next > first && next <= (long) chunks + 1);
                if (i == 0) require(first == 1);
                long perChunk = samplesPerChunk(i); require(perChunk > 0 && perChunk <= samples);
                long description = mapping.uintAt(16 + i * 12);
                require(description >= 1 && description <= descriptionCount);
                samplePrefix[i] = mapped;
                mapped += (next - first) * perChunk;
                require(mapped <= 4000000);
            }
            require(mapped >= samples && mapped - samples < samplesPerChunk(groups - 1));
            long previous = -1;
            for (int i = 0; i < chunks; i++) { long at = chunkOffset(i); require(at > previous && at < fileLength); previous = at; }
            Box stts = stbl.find("stts"); require(stts != null);
            require(stts.version() == 0);
            times = new Runs(stts, false, budget); require(times.samples() == samples && times.time(samples) > 0);
            Box ctts = stbl.find("ctts");
            if (ctts != null) { require(ctts.version() <= 1); composition = new Runs(ctts, ctts.version() == 1, budget); require(composition.samples() == samples); }
            else composition = null;
            sync = video ? stbl.find("stss") : null;
            if (sync != null) {
                require(sync.version() == 0);
                int n = sync.count(4, 8, 4); long prior = 0;
                for (int i = 0; i < n; i++) { long current = sync.uintAt(8 + i * 4); require(current > prior && current <= samples); prior = current; }
            }
            Box edits = trak.find("edts");
            if (edits != null && edits.find("elst") != null) parseEdits(edits.find("elst"), movieScale);
        }

        void parseEdits(Box edits, long movieScale) throws IOException {
            int version = edits.version(); require(version <= 1);
            int width = version == 1 ? 20 : 12;
            int count = edits.count(4, 8, width); require(count >= 1 && count <= 2);
            boolean active = false;
            for (int i = 0; i < count; i++) {
                int at = 8 + i * width;
                long duration = version == 1 ? edits.ulongAt(at) : edits.uintAt(at);
                long mediaTime = version == 1 ? edits.longAt(at + 8) : edits.intAt(at + 4);
                require(duration < Long.MAX_VALUE / 2000 && edits.intAt(at + width - 4) == 65536);
                if (mediaTime == -1 && !active && i == 0) {
                    emptyStartMs = ceil(duration * 1000, movieScale);
                    emptyEndMs = duration * 1000 / movieScale;
                } else {
                    require(!active && duration > 0 && mediaTime >= 0 && mediaTime < Long.MAX_VALUE / 2000);
                    mediaStart = mediaTime; editEndMs = emptyEndMs + duration * 1000 / movieScale; active = true;
                }
            }
            require(active);
        }

        long firstChunk(int group) throws IOException { return mapping.uintAt(8 + group * 12); }
        long samplesPerChunk(int group) throws IOException { return mapping.uintAt(12 + group * 12); }
        long chunkOffset(int chunk) throws IOException { return wide ? offsets.ulongAt(8 + chunk * 8) : offsets.uintAt(8 + chunk * 4); }
        int firstSample(int chunk) throws IOException {
            int lo = 0, hi = groups;
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (firstChunk(mid) <= chunk + 1L) lo = mid; else hi = mid; }
            long first = samplePrefix[lo] + (chunk + 1L - firstChunk(lo)) * samplesPerChunk(lo);
            require(first < samples); return (int) first;
        }
        int nextSample(int chunk) throws IOException { return chunk + 1 == chunks ? samples : firstSample(chunk + 1); }
        long sampleBytes(int sample) throws IOException {
            if (fixedSize != 0) return fixedSize * sample;
            int block = sample / 256;
            long bytes = bytePrefix[block];
            for (int i = block * 256; i < sample; i++) bytes += sizes.uintAt(12 + i * 4);
            return bytes;
        }
        long offsetAt(long timeMs)throws IOException{
            long mediaTime=mediaStart+(long)(Math.max(0,timeMs-emptyStartMs)*(double)scale/1000);
            int lo=0,hi=samples;while(lo+1<hi){int mid=(lo+hi)>>>1;if(times.time(mid)<=mediaTime)lo=mid;else hi=mid;}
            int sample=lo;
            if(video&&sync!=null){int n=sync.count(4,8,4),a=0,b=n;while(a<b){int mid=(a+b)>>>1;if(sync.uintAt(8+mid*4)<=sample+1L)a=mid+1;else b=mid;}sample=a==0?0:(int)sync.uintAt(8+(a-1)*4)-1;}
            lo=0;hi=chunks;while(lo+1<hi){int mid=(lo+hi)>>>1;if(firstSample(mid)<=sample)lo=mid;else hi=mid;}
            return chunkOffset(lo)+sampleBytes(sample)-sampleBytes(firstSample(lo));
        }
        int firstSync(int start, int end) throws IOException {
            if (!video || sync == null) return start;
            int lo = 0, hi = sync.count(4, 8, 4);
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (sync.uintAt(8 + mid * 4) < start + 1L) lo = mid + 1; else hi = mid; }
            if (lo == sync.count(4, 8, 4)) return -1;
            int found = (int) sync.uintAt(8 + lo * 4) - 1;
            return found < end ? found : -1;
        }
        int lowerChunk(long byteOffset) throws IOException {
            int lo = 0, hi = chunks;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (chunkOffset(mid) < byteOffset) lo = mid + 1; else hi = mid; }
            return lo;
        }

        List<long[]> ranges(List<long[]> cached, long movieEnd) throws IOException {
            List<long[]> result = new ArrayList<>();
            int previousEnd = -1;
            long start = -1, end = -1;
            long minCts = composition == null ? 0 : composition.minimum;
            long maxCts = composition == null ? 0 : composition.maximum;
            for (long[] bytes : cached) {
                for (int chunk = Math.max(0, lowerChunk(bytes[0]) - 1); chunk < chunks; chunk++) {
                    long offset = chunkOffset(chunk); if (offset >= bytes[1]) break;
                    int chunkFirst = firstSample(chunk), chunkEnd = nextSample(chunk);
                    long base = sampleBytes(chunkFirst), size = sampleBytes(chunkEnd) - base;
                    require(size <= fileLength - offset);
                    int lo = chunkFirst, hi = chunkEnd;
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        if (offset + sampleBytes(mid) - base < bytes[0]) lo = mid + 1; else hi = mid;
                    }
                    int first = lo;
                    lo = first; hi = chunkEnd + 1;
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        if (offset + sampleBytes(mid) - base <= bytes[1]) lo = mid + 1; else hi = mid;
                    }
                    int next = lo - 1;
                    if (next <= first) continue;
                    if (first != previousEnd) { append(result, start, end); start = end = -1; }
                    if (start < 0) {
                        int syncSample = firstSync(first, next);
                        if (syncSample < 0) { previousEnd = next; continue; }
                        start = first == 0 && syncSample == 0 ? 0
                                : Math.max(0, ceil((times.time(syncSample) + maxCts - mediaStart) * 1000, scale) + emptyStartMs);
                    }
                    end = Math.max(0, Math.floorDiv((times.time(next) + minCts - mediaStart) * 1000, scale) + emptyEndMs);
                    if (next == samples || end >= editEndMs) end = movieEnd;
                    end = Math.min(end, movieEnd);
                    previousEnd = next;
                }
            }
            append(result, start, end);
            return result;
        }
    }

    static final class Box {
        final byte[] bytes;
        final int start, end;
        String type = "";
        Box(byte[] bytes, int start, int end) { this.bytes = bytes; this.start = start; this.end = end; }
        int length() { return end - start; }
        int version() throws IOException { require(length() >= 4); return bytes[start] & 255; }
        int intAt(int at) throws IOException { require(at >= 0 && at <= length() - 4); return word(bytes, start + at); }
        long uintAt(int at) throws IOException { return intAt(at) & 0xffffffffL; }
        long longAt(int at) throws IOException { require(at >= 0 && at <= length() - 8); return ((long) word(bytes, start + at) << 32) | uint(bytes, start + at + 4); }
        long ulongAt(int at) throws IOException { long result = longAt(at); require(result >= 0); return result; }
        int count(int countAt, int tableAt, int width) throws IOException {
            long count = uintAt(countAt); require(count <= Integer.MAX_VALUE && tableAt + count * width <= length()); return (int) count;
        }
        List<Box> children() throws IOException {
            List<Box> result = new ArrayList<>();
            int at = start;
            while (at < end) {
                require(at <= end - 8 && result.size() < 10000);
                long length = uint(bytes, at); int header = 8;
                if (length == 1) { require(at <= end - 16); length = ulong(bytes, at + 8); header = 16; }
                if (length == 0) length = end - at;
                require(length >= header && length <= end - at);
                Box child = new Box(bytes, at + header, at + (int) length);
                child.type = new String(new char[] {(char) bytes[at + 4], (char) bytes[at + 5], (char) bytes[at + 6], (char) bytes[at + 7]});
                result.add(child); at += (int) length;
            }
            return result;
        }
        Box find(String type) throws IOException { for (Box box : children()) if (type.equals(box.type)) return box; return null; }
    }

    private static long mediaScale(Box header) throws IOException {
        int version = header.version(); require(version <= 1);
        long scale = header.uintAt(version == 1 ? 20 : 12); require(scale > 0); return scale;
    }
    private static int word(byte[] b, int at) { return (b[at] & 255) << 24 | (b[at + 1] & 255) << 16 | (b[at + 2] & 255) << 8 | b[at + 3] & 255; }
    private static long uint(byte[] b, int at) { return word(b, at) & 0xffffffffL; }
    private static long ulong(byte[] b, int at) throws IOException { long result = (long) word(b, at) << 32 | uint(b, at + 4); require(result >= 0); return result; }
    private static int four(String value) { return value.charAt(0) << 24 | value.charAt(1) << 16 | value.charAt(2) << 8 | value.charAt(3); }
    private static long ceil(long value, long divisor) { return -Math.floorDiv(-value, divisor); }
    private static void require(boolean valid) throws IOException { if (!valid) throw new IOException("Unsupported or malformed MP4 sample index"); }
    private static void append(List<long[]> spans, long start, long end) {
        if (start < 0 || end <= start) return;
        if (!spans.isEmpty() && spans.get(spans.size() - 1)[1] >= start) spans.get(spans.size() - 1)[1] = Math.max(end, spans.get(spans.size() - 1)[1]);
        else spans.add(new long[] {start, end});
    }
}
