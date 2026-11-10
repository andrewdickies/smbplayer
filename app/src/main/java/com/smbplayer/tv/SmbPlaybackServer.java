// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.io.IOException;
import java.io.File;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

final class SmbPlaybackServer extends NanoHTTPD {
    private final String token = "/" + UUID.randomUUID();
    private final String smbUrl;
    private final CIFSContext context;
    private final long size;

    private PlaybackByteCache cache;
    private volatile RangeReadAhead readAhead;
    private volatile ForwardCache forward;
    private final AtomicLong streamIds=new AtomicLong();
    private volatile long playbackTime,latestDemand,seekFloor,seekTime=-1,seekDeadline;
    private volatile boolean indexedPosition;

    private ScheduledExecutorService indexing;
    private volatile SmbFileInputStream indexReader;
    private volatile MediaBufferIndex index;
    private final java.util.concurrent.CountDownLatch indexReady = new java.util.concurrent.CountDownLatch(1);
    private MediaBufferIndex lastComputedIndex;
    private volatile List<long[]> buffered = Collections.emptyList();
    private volatile long duration;
    private volatile boolean stopped;
    private volatile String indexState = "pending";
    private long indexedRevision = -1, indexedDuration = -1;
    private final AtomicLong remoteBytes = new AtomicLong(), reusedBytes = new AtomicLong();

    SmbPlaybackServer(String smbUrl, CIFSContext context, File directory) throws IOException {
        this(smbUrl, context, directory, true);
    }

    SmbPlaybackServer(String smbUrl, CIFSContext context, File directory, boolean parallel) throws IOException {
        super("127.0.0.1", 0);
        this.smbUrl = smbUrl;
        this.context = context;
        try (SmbFile file = new SmbFile(smbUrl, context)) {
            size = file.length();
        }
        if (size <= 0) throw new IOException("Empty video");
        if (parallel) readAhead = new RangeReadAhead(SmbRangeReader::new);
        long capacity=ForwardCache.diskCapacity(size,directory.getUsableSpace());
        if(capacity>0)try{
            cache=new PlaybackByteCache(directory,1024*1024,(int)(capacity/(1024*1024)));
            forward=new ForwardCache(cache,size,SmbRangeReader::new,parallel?8:1);
        }catch(IOException ignored){if(cache!=null)cache.close();cache=null;}
        try {
            start(SOCKET_READ_TIMEOUT, true);
            if (cache != null) {
                indexing = Executors.newSingleThreadScheduledExecutor();
                indexing.execute(this::readIndex);
                indexing.scheduleWithFixedDelay(this::updateBuffered, 0, 750, TimeUnit.MILLISECONDS);
            } else indexReady.countDown();
        } catch (IOException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    String url() {
        return "http://127.0.0.1:" + getListeningPort() + token;
    }

    List<long[]> bufferedRanges(long durationMs) {
        duration = durationMs;
        return buffered;
    }

    long streamedRemoteBytes() { return remoteBytes.get(); }
    long reusedBytes() { return reusedBytes.get(); }
    long cacheCapacity(){return cache==null?0:cache.capacity();}
    long forwardBytes(){ForwardCache f=forward;return f==null?0:f.bufferedAhead();}
    long forwardAnchor(){ForwardCache f=forward;return f==null?-1:f.anchor();}
    private long indexedByte(long position){try{MediaBufferIndex current=index;return current==null?-1:current.byteOffset(Math.max(0,position));}catch(IOException|RuntimeException ignored){return -1;}}
    void prepareSeek(long timeMs){
        seekTime=Math.max(0,timeMs);seekDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);seekFloor=streamIds.get()+1;
        ForwardCache f=forward;if(f!=null){long at=indexedByte(seekTime);if(at>=0)f.reposition(at);else f.suspend();}
    }
    void updatePlayback(long position,long durationMs){
        playbackTime=Math.max(0,position);duration=durationMs;
        long effective=position;
        if(seekTime>=0){if(Math.abs(position-seekTime)<1500||System.nanoTime()>seekDeadline)seekTime=-1;else effective=seekTime;}
        ForwardCache f=forward;if(f==null)return;
        long at=indexedByte(effective);indexedPosition=at>=0;if(at>=0)f.advance(at);
        long ahead=0;for(long[] range:buffered)if(range[0]<=effective+250&&range[1]>effective)ahead=Math.max(ahead,range[1]-effective);
        long bytes=durationMs>0?(long)Math.min(Long.MAX_VALUE/2,Math.max(16L*1024*1024,size*(300000.0/durationMs))):64L*1024*1024;
        // Unsupported indexes use a bounded byte target, never a made-up time coverage bar.
        f.policy(bytes,ahead,index!=null&&at>=0);
    }

    String indexState() { return indexState; }

    void awaitContainerHint() {
        try { indexReady.await(12000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    void normalizeDuration(long position, byte[] bytes, int offset, int count) {
        MediaBufferIndex current = index;
        if (current instanceof FragmentedMp4BufferIndex)
            ((FragmentedMp4BufferIndex) current).normalizeDuration(position, bytes, offset, count);
    }

    private void readIndex() {
        try (MetadataSource source = new MetadataSource()) {
            MediaBufferIndex parsed = MediaBufferIndex.open(source);
            if (!stopped) { index = parsed; indexState = "ready: " + parsed.getClass().getSimpleName(); }
        } catch (IOException | RuntimeException failure) {
            indexState = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally { indexReader = null; indexReady.countDown(); }
    }

    private final class MetadataSource implements MediaBufferIndex.Reader, java.io.Closeable {
        private final SmbFile file;
        private SmbFileInputStream input;
        private long cursor, windowStart = -1, readBytes;
        private int windowLength;
        private final byte[] window = new byte[32 * 1024];
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        MetadataSource() throws IOException {
            file = new SmbFile(smbUrl, context);
            if (file.length() != size) { file.close(); throw new IOException("Shared file changed"); }
        }
        @Override public long length() { return size; }
        @Override public void read(long position, byte[] bytes, int offset, int count) throws IOException {
            if (position < 0 || count < 0 || count > size - position) throw new IOException("Invalid metadata offset");
            while (count > 0) {
                if (stopped || System.nanoTime() > deadline) throw new IOException("Metadata deadline");
                if(cache!=null){int cached=cache.readIndexAt(position,bytes,offset,count);if(cached>0){position+=cached;offset+=cached;count-=cached;continue;}}
                if (position < windowStart || position >= windowStart + windowLength) {
                    if (readBytes >= 16L * 1024 * 1024) throw new IOException("Metadata read limit");
                    if (input == null || position < cursor) {
                        if (input != null) input.close();
                        input = file.openInputStream(); indexReader = input; cursor = 0;
                    }
                    long distance = position - cursor;
                    if (input.skip(distance) != distance) throw new IOException("Cannot seek metadata");
                    cursor = position;
                    int length = (int) Math.min(window.length, Math.min(size - position, 16L * 1024 * 1024 - readBytes));
                    windowLength = input.read(window, 0, length);
                    if (windowLength <= 0) throw new IOException("Incomplete metadata");
                    windowStart = position; cursor += windowLength; readBytes += windowLength;remoteBytes.addAndGet(windowLength);
                    if(cache!=null)try{cache.writeAt(windowStart,window,0,windowLength);}catch(IOException unavailable){cache.close();}
                }
                int at = (int) (position - windowStart), n = Math.min(count, windowLength - at);
                System.arraycopy(window, at, bytes, offset, n); position += n; offset += n; count -= n;
            }
        }
        @Override public void close() throws IOException {
            try { if (input != null) input.close(); } finally { file.close(); }
        }
    }

    private void updateBuffered() {
        if (stopped || cache == null || duration <= 0) return;
        long revision = cache.revision(), length = duration;
        MediaBufferIndex current = index;
        if (revision == indexedRevision && length == indexedDuration && current == lastComputedIndex) return;
        try {
            if (BufferIndexSupport.covered(cache.ranges(), 0, size))
                buffered = Collections.singletonList(new long[] {0, length});
            else buffered = current == null ? Collections.emptyList() : current.ranges(cache, length);
            indexedRevision = revision; indexedDuration = length; lastComputedIndex = current;
        } catch (IOException | RuntimeException failure) {
            indexState = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            index = null; buffered = Collections.emptyList();
        }
    }

    private final class CachedStream extends java.io.InputStream {
        private long position;
        private final long end;
        private SmbFile file;
        private SmbFileInputStream input;
        private long remotePosition;
        private boolean closed;
        private RangeReadAhead.Range prefetched;
        private final long clientId=streamIds.incrementAndGet();
        private final boolean demandStream;
        CachedStream(long start, long end) {
            position = start; this.end = end;
            demandStream=end-start>=256*1024;
            if (cache != null) cache.noteReadStart(start);
            RangeReadAhead engine = readAhead;
            if (forward==null && engine != null && end - start >= 256 * 1024) prefetched = engine.open(start, end);
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (closed || stopped) throw new IOException("Stream closed");
            if (position > end) return -1;
            count = (int) Math.min(count, end - position + 1);
            ForwardCache active=forward;
            boolean tailProbe=position>=size-Math.min(size/8,4L*1024*1024)&&(duration<=0||playbackTime<duration-10000);
            if(active!=null&&!indexedPosition&&demandStream&&!tailProbe&&clientId>=latestDemand&&clientId>=seekFloor){latestDemand=clientId;active.demand(position);}
            int n = -1;
            if (cache != null) {
                try { n = cache.readAt(position, buffer, offset, count); }
                catch (IOException ignored) { cache.close(); }
            }
            if (n > 0) reusedBytes.addAndGet(n);
            else {
                if(active!=null){
                    try{n=active.read(position,buffer,offset,count);}
                    catch(IOException unavailable){active.close();if(forward==active)forward=null;if(stopped||closed)throw unavailable;}
                }
                if (n<=0 && prefetched != null) {
                    try { n = prefetched.read(position, buffer, offset, count); }
                    catch (IOException unavailable) {
                        prefetched.close(); prefetched = null;
                        RangeReadAhead engine = readAhead; readAhead = null;
                        if (engine != null) engine.close();
                        if (stopped || closed) throw unavailable;
                    }
                }
                if (n <= 0) {
                    if (input == null) {
                        file = new SmbFile(smbUrl, context);
                        try { input = file.openInputStream(); }
                        catch (IOException | RuntimeException e) { file.close(); file = null; throw e; }
                    }
                    if (position != remotePosition) {
                        long distance = position - remotePosition;
                        if (distance < 0 || input.skip(distance) != distance) throw new IOException("Cannot seek shared file");
                        remotePosition = position;
                    }
                    n = input.read(buffer, offset, count);
                    if (n <= 0) throw new IOException("Shared file ended prematurely");
                    remotePosition += n;
                    remoteBytes.addAndGet(n);
                }
                if (cache != null) {
                    try { cache.writeAt(position, buffer, offset, n); }
                    catch (IOException ignored) { cache.close(); }
                }
            }
            normalizeDuration(position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (prefetched != null) { prefetched.close(); prefetched = null; }
            try { if (input != null) input.close(); }
            finally { if (file != null) file.close(); }
        }
    }

    @Override public void stop() {
        stopped = true;
        super.stop();
        if (indexing != null) indexing.shutdownNow();
        ForwardCache producer=forward;forward=null;if(producer!=null)producer.close();
        RangeReadAhead engine = readAhead; readAhead = null;
        if (engine != null) engine.close();
        SmbFileInputStream reader = indexReader;
        if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        if (cache != null) cache.close();
        buffered = Collections.emptyList();
    }

    private final class SmbRangeReader implements RangeReadAhead.Reader {
        private final SmbFile file;
        private volatile SmbFileInputStream input;
        private long cursor;
        private volatile boolean closed;
        SmbRangeReader() throws IOException { file = new SmbFile(smbUrl, context); }
        @Override public void read(long position, byte[] bytes, int length) throws IOException {
            if (closed || stopped) throw new IOException("Reader closed");
            if (input == null || position < cursor) {
                if (input != null) input.close();
                input = file.openInputStream(); cursor = 0;
            }
            if (closed || stopped) { input.close(); throw new IOException("Reader closed"); }
            if (input.skip(position - cursor) != position - cursor) throw new IOException("Cannot seek shared file");
            cursor = position;
            for (int at = 0; at < length;) {
                if (closed || stopped || Thread.currentThread().isInterrupted()) throw new IOException("Reader cancelled");
                int n = input.read(bytes, at, length - at);
                if (n <= 0) throw new IOException("Shared file ended prematurely");
                at += n; cursor += n; remoteBytes.addAndGet(n);
            }
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { if (input != null) input.close(); } finally { file.close(); }
        }
    }

    @Override public Response serve(IHTTPSession session) {
        if (!token.equals(session.getUri())) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "");
        }
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "");
        }
        long start = 0;
        long end = size - 1;
        String range = session.getHeaders().get("range");
        if (range != null) {
            try {
                if (!range.startsWith("bytes=")) throw new IllegalArgumentException();
                String[] bounds = range.substring(6).split("-", -1);
                if (bounds.length != 2) throw new IllegalArgumentException();
                if (bounds[0].isEmpty()) {
                    long suffix = Long.parseLong(bounds[1]);
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, size - suffix);
                } else {
                    start = Long.parseLong(bounds[0]);
                    if (!bounds[1].isEmpty()) end = Math.min(end, Long.parseLong(bounds[1]));
                }
                if (start < 0 || start >= size || end < start) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                Response response = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
                response.addHeader("Content-Range", "bytes */" + size);
                return response;
            }
        }
        try {
            java.io.InputStream body = null;
            if (session.getMethod() == Method.GET) {
                body = new java.io.BufferedInputStream(new CachedStream(start, end), 256 * 1024);
            }
            Response response = newFixedLengthResponse(
                    range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                    "application/octet-stream", body, end - start + 1);
            response.addHeader("Accept-Ranges", "bytes");
            if (session.getMethod() == Method.HEAD) response.addHeader("Content-Length", String.valueOf(end - start + 1));
            if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return response;
        } catch (RuntimeException e) {
            android.util.Log.w("SmbPlayer", "SMB stream request failed", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "");
        }
    }
}
