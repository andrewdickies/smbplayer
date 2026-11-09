package com.smbplayer.tv;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class SmbReadAheadServerCheck {
    static byte[] expected(String path, long at, int count) throws Exception {
        byte[] bytes = new byte[count];
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) { f.seek(at); f.readFully(bytes); }
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties(); p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        CIFSContext context = new BaseContext(new PropertyConfiguration(p)).withAnonymousCredentials();
        File cache = Files.createTempDirectory("smb-read-ahead-check").toFile();
        SmbPlaybackServer server = new SmbPlaybackServer(args[0], context, cache, true);
        ExecutorService clients = Executors.newFixedThreadPool(3);
        try {
            server.awaitContainerHint();
            List<Future<?>> checks = new ArrayList<>();
            for (long at : new long[] {0, 3L * 1024 * 1024, 6L * 1024 * 1024}) checks.add(clients.submit(() -> {
                try {
                    byte[] raw = expected(args[1], at, 1024 * 1024);
                    server.normalizeDuration(at, raw, 0, raw.length);
                    if (!Arrays.equals(raw, SmbPipelineProbe.get(server, at, raw.length))) throw new AssertionError("Concurrent range bytes differ");
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
            for (Future<?> check : checks) check.get();
        } finally { clients.shutdownNow(); server.stop(); }
        if (cache.list().length != 0) throw new AssertionError("Parallel cache not cleaned up");
        SmbPlaybackServer fallback = new SmbPlaybackServer(args[0], context, cache, false);
        RangeReadAhead failed = new RangeReadAhead(() -> new RangeReadAhead.Reader() {
            public void read(long p, byte[] b, int n) throws IOException { throw new IOException("Fixture server handle limit"); }
            public void close() {}
        });
        java.lang.reflect.Field field = SmbPlaybackServer.class.getDeclaredField("readAhead"); field.setAccessible(true); field.set(fallback, failed);
        try {
            fallback.awaitContainerHint();
            byte[] raw = expected(args[1], 0, 1024 * 1024); fallback.normalizeDuration(0, raw, 0, raw.length);
            if (!Arrays.equals(raw, SmbPipelineProbe.get(fallback, 0, raw.length))) throw new AssertionError("Serial fallback data differs");
            if (field.get(fallback) != null || failed.activeRanges() != 0 || failed.activeReaders() != 0) throw new AssertionError("Failed prefetch engine not released");
        } finally { fallback.stop(); context.close(); }
        if (cache.list().length != 0) throw new AssertionError("Fallback cache not cleaned up");
        cache.delete();
        System.out.println("Three concurrent ranges, consumer limit fallback, injected read failure, byte integrity and cleanup passed");
    }
}
