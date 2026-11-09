package com.smbplayer.tv;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;

public final class SmbCacheServerCheck {
    private static byte[] get(String url, String range) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
        connection.setRequestProperty("Range", range);
        if (connection.getResponseCode() != 206) throw new AssertionError("Range response failed");
        try (InputStream body = connection.getInputStream()) { return body.readAllBytes(); }
        finally { connection.disconnect(); }
    }
    private static void check(SmbPlaybackServer server, RandomAccessFile original, long first, int length) throws Exception {
        byte[] expected = new byte[length]; original.seek(first); original.readFully(expected);
        server.normalizeDuration(first, expected, 0, length);
        byte[] actual = get(server.url(), "bytes=" + first + "-" + (first + length - 1));
        if (!Arrays.equals(actual, expected)) throw new AssertionError("HTTP range bytes differ");
    }
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        properties.setProperty("jcifs.smb.client.connTimeout", "3000");
        properties.setProperty("jcifs.smb.client.soTimeout", "3000");
        CIFSContext context = new BaseContext(new PropertyConfiguration(properties)).withAnonymousCredentials();
        File cache = Files.createTempDirectory("smb-http-cache-check").toFile();
        SmbPlaybackServer server = new SmbPlaybackServer(args[0], context, cache, false);
        server.awaitContainerHint();
        // Isolate cache-hit behavior from the independent background downloader.
        java.lang.reflect.Field producer=SmbPlaybackServer.class.getDeclaredField("forward");producer.setAccessible(true);
        ForwardCache forward=(ForwardCache)producer.get(server);if(forward!=null)forward.close();producer.set(server,null);
        try (RandomAccessFile original = new RandomAccessFile(args[1], "r")) {
            check(server, original, 0, 65536);
            long firstRead = server.streamedRemoteBytes();
            check(server, original, 0, 65536);
            if (server.streamedRemoteBytes() != firstRead || server.reusedBytes() < 65536)
                throw new AssertionError("Repeated range was not served from the disk cache");
            long beforePartial=server.streamedRemoteBytes();
            check(server, original, 32768, 65536);
            long missing=server.streamedRemoteBytes()-beforePartial;
            if(missing<0||missing>32768)throw new AssertionError("Partial hit reread cached bytes");
            check(server, original, original.length() - 100, 100);
            byte[] suffix = get(server.url(), "bytes=-100");
            byte[] expected = new byte[100]; original.seek(original.length() - 100); original.readFully(expected);
            if (!Arrays.equals(suffix, expected)) throw new AssertionError("Suffix range changed");
            HttpURLConnection head = (HttpURLConnection) new URL(server.url()).openConnection();
            head.setRequestMethod("HEAD"); head.setRequestProperty("Range", "bytes=20-29");
            if (head.getResponseCode() != 206 || head.getContentLengthLong() != 10 || head.getInputStream().read() != -1)
                throw new AssertionError("HEAD semantics changed");
            head.disconnect();
            check(server, original, 0, 1024 * 1024);
            long waitUntil = System.nanoTime() + 5000000000L;
            while (server.bufferedRanges(90000).isEmpty() && System.nanoTime() < waitUntil) Thread.sleep(100);
            if (server.bufferedRanges(90000).isEmpty()) throw new AssertionError("No indexed buffer interval appeared: " + server.indexState());
            System.out.println("Repeated, partial-hit, suffix and HEAD byte checks passed");
            System.out.println("READY_FOR_OUTAGE"); System.out.flush();
            if (args.length > 2 && args[2].equals("outage") && System.in.read() < 0)
                throw new AssertionError("Outage gate closed without an explicit release");
            long before = server.streamedRemoteBytes();
            check(server, original, 0, 65536);
            if (server.streamedRemoteBytes() != before) throw new AssertionError("Cached read accessed SMB during outage");
            System.out.println("Cached range read succeeded without a remote read");
        } finally {
            server.stop(); context.close();
        }
        if (cache.list().length != 0) throw new AssertionError("Streaming cache not cleaned up");
        cache.delete();
        System.out.println("SMB cache HTTP integration checks passed");
    }
}
