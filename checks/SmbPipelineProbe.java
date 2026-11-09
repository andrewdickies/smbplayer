package com.smbplayer.tv;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** Measures the production HTTP/cache bridge; credentials only arrive through stdin. */
public final class SmbPipelineProbe {
    static byte[] get(SmbPlaybackServer server, long start, int length) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server.url()).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
        try {
            if (connection.getResponseCode() != 206) throw new IOException("Range response failed");
            byte[] data = new byte[length]; int at = 0;
            try (InputStream body = connection.getInputStream()) {
                while (at < data.length) { int n = body.read(data, at, data.length - at); if (n < 0) throw new EOFException(); at += n; }
            }
            return data;
        } finally { connection.disconnect(); }
    }
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String root = input.readLine(), user = input.readLine(), password = input.readLine();
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.smb.client.connTimeout", "5000");
        p.setProperty("jcifs.smb.client.soTimeout", "8000");
        p.setProperty("jcifs.smb.client.responseTimeout", "8000");
        CIFSContext context = new BaseContext(new PropertyConfiguration(p))
                .withCredentials(new NtlmPasswordAuthenticator("", user, password));
        String expected = null;
        try {
            String path = SmbThroughputProbe.findVideo(root, context);
            if (path == null) throw new IOException("No video fixture");
            long size; try (jcifs.smb.SmbFile f = new jcifs.smb.SmbFile(path, context)) { size = f.length(); }
            int length = (int) Math.min(size, 4L * 1024 * 1024);
            for (boolean parallel : new boolean[] { false, true, false }) {
                File parent = new File(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"));
                File cache = java.nio.file.Files.createTempDirectory(parent.toPath(), "smb-pipeline-check").toFile();
                SmbPlaybackServer server = new SmbPlaybackServer(path, context, cache, parallel);
                try {
                    server.awaitContainerHint();
                    long started = System.nanoTime(); byte[] bytes = get(server, 0, length);
                    double seconds = (System.nanoTime() - started) / 1e9;
                    StringBuilder hash = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
                    if (expected != null && !expected.equals(hash.toString())) throw new AssertionError("Pipeline bytes differ");
                    expected = hash.toString();
                    System.out.printf(Locale.ROOT, "bridge=%s bytes=%d seconds=%.3f Mbps=%.3f remoteBytes=%d sha256=%s%n",
                            parallel ? "parallel" : "serial", length, seconds, length * 8 / seconds / 1e6, server.streamedRemoteBytes(), expected);
                    long before = server.streamedRemoteBytes();
                    if (!Arrays.equals(Arrays.copyOf(bytes, 65536), get(server, 0, 65536))) throw new AssertionError("Cache bytes differ");
                    if (before != server.streamedRemoteBytes()) throw new AssertionError("Cache reread remote data");
                    if (parallel) {
                        int seekLength = (int) Math.min(size, 1024 * 1024);
                        long at = Math.min(size - seekLength, Math.max(length, size / 2));
                        byte[] sought = get(server, at, seekLength);
                        byte[] raw = new byte[seekLength];
                        try (jcifs.smb.SmbFile f = new jcifs.smb.SmbFile(path, context); jcifs.smb.SmbFileInputStream stream = f.openInputStream()) {
                            if (stream.skip(at) != at) throw new IOException("Seek failed");
                            int n = 0; while (n < raw.length) { int count = stream.read(raw, n, raw.length - n); if (count < 0) throw new EOFException(); n += count; }
                        }
                        server.normalizeDuration(at, raw, 0, raw.length);
                        if (!Arrays.equals(raw, sought)) throw new AssertionError("Seek bytes differ");
                        HttpURLConnection cancel = (HttpURLConnection) new URL(server.url()).openConnection();
                        cancel.setReadTimeout(30000); cancel.setRequestProperty("Range", "bytes=" + at + "-");
                        try (InputStream body = cancel.getInputStream()) { body.read(new byte[1024]); }
                        finally { cancel.disconnect(); }
                        if (!Arrays.equals(Arrays.copyOf(bytes, 65536), get(server, 0, 65536))) throw new AssertionError("Read after cancellation failed");
                        // Force a genuine remote miss after cancellation, then compare against the source.
                        long tail = size - 65536;
                        byte[] tailData = get(server, tail, 65536), tailRaw = new byte[65536];
                        try (jcifs.smb.SmbFile f = new jcifs.smb.SmbFile(path, context); jcifs.smb.SmbFileInputStream stream = f.openInputStream()) {
                            if (stream.skip(tail) != tail) throw new IOException("Tail seek failed");
                            int n = 0; while (n < tailRaw.length) { int count = stream.read(tailRaw, n, tailRaw.length - n); if (count < 0) throw new EOFException(); n += count; }
                        }
                        server.normalizeDuration(tail, tailRaw, 0, tailRaw.length);
                        if (!Arrays.equals(tailData, tailRaw)) throw new AssertionError("Remote recovery bytes differ");
                        System.out.println("Seek, cache and cancelled-response recovery passed");
                    }
                } finally { server.stop(); }
                if (cache.list().length != 0) throw new AssertionError("Cache cleanup failed");
                cache.delete();
            }
        } finally { context.close(); }
        System.out.println("All bridge checks and cache cleanup passed");
        // app_process otherwise remains in AndroidRuntime after the Java entry point returns.
        if (args.length > 0) System.exit(0);
    }
}
