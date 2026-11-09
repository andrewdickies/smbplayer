package com.smbplayer.tv;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Properties;
import java.security.MessageDigest;

/** Read-only bounded probe. Credentials arrive on stdin, never in args or logs. */
public final class SmbThroughputProbe {
    static String findVideo(String root, CIFSContext context) throws Exception {
        String chosen = null;
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(); pending.add(root);
        for (int visited = 0; chosen == null && !pending.isEmpty() && visited < 20; visited++) {
            try (SmbFile folder = new SmbFile(pending.remove(), context)) {
                for (SmbFile file : folder.listFiles()) try {
                    if (file.isDirectory()) pending.add(file.getCanonicalPath());
                    else if (chosen == null && file.getName().toLowerCase().matches(".*\\.(mp4|m4v|mkv|ts|m2ts|avi|mov|flv|webm|mpg|mpeg|wmv|3gp)$") && file.length() >= 1024 * 1024)
                        chosen = file.getCanonicalPath();
                } finally { file.close(); }
            }
        }
        return chosen;
    }

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String root = input.readLine(), user = input.readLine(), password = input.readLine();
        String chosen = null;
        for (int setting : new int[] {0, 2, 4, 8, 0}) {
            Properties p = new Properties();
            p.setProperty("jcifs.smb.client.minVersion", "SMB202");
            p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            p.setProperty("jcifs.smb.client.connTimeout", "5000");
            p.setProperty("jcifs.smb.client.soTimeout", "8000");
            p.setProperty("jcifs.smb.client.responseTimeout", "8000");
            if (setting == 1) {
                p.setProperty("jcifs.smb.client.rcv_buf_size", "1048576");
                p.setProperty("jcifs.smb.client.snd_buf_size", "1048576");
                p.setProperty("jcifs.smb.client.transaction_buf_size", "1052672");
                p.setProperty("jcifs.smb.client.tcpNoDelay", "true");
            }
            if (setting == 2) p.setProperty("jcifs.smb.client.tcpNoDelay", "true");
            CIFSContext context = new BaseContext(new PropertyConfiguration(p))
                    .withCredentials(new NtlmPasswordAuthenticator("", user, password));
            try {
                if (chosen == null) {
                    chosen = findVideo(root, context);
                }

                if (chosen == null) throw new IllegalStateException("No video fixture");
                if (setting >= 4) {
                    final String path = chosen; final long limit;
                    try (SmbFile file = new SmbFile(path, context)) { limit = Math.min(file.length(), 4L * 1024 * 1024); }
                    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(setting);
                    java.util.List<java.util.concurrent.Future<byte[]>> jobs = new java.util.ArrayList<>();
                    long started = System.nanoTime();
                    try {
                        for (int part = 0; part < setting; part++) {
                            final long start = limit * part / setting, end = limit * (part + 1) / setting;
                            jobs.add(pool.submit(() -> {
                                byte[] data = new byte[(int) (end - start)];
                                try (SmbFile f = new SmbFile(path, context); SmbFileInputStream stream = f.openInputStream()) {
                                    if (stream.skip(start) != start) throw new java.io.IOException("Seek failed");
                                    int at = 0;
                                    while (at < data.length) { int n = stream.read(data, at, Math.min(256 * 1024, data.length - at)); if (n < 0) throw new java.io.IOException("Short read"); at += n; }
                                }
                                return data;
                            }));
                        }
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        for (java.util.concurrent.Future<byte[]> job : jobs) digest.update(job.get());
                        double seconds = (System.nanoTime() - started) / 1e9;
                        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
                        System.out.printf(java.util.Locale.ROOT, "mode=parallel-%d bytes=%d seconds=%.3f MiB/s=%.3f Mbps=%.3f sha256=%s%n",setting,limit,seconds,limit/seconds/1048576,limit*8/seconds/1e6,hash);
                    } finally { pool.shutdownNow(); }
                    continue;
                }
                try (SmbFile file = new SmbFile(chosen, context); SmbFileInputStream stream = file.openInputStream()) {
                    java.lang.reflect.Field field = SmbFileInputStream.class.getDeclaredField("readSizeFile"); field.setAccessible(true);
                    long limit = Math.min(file.length(), 4L * 1024 * 1024), count = 0;
                    byte[] buffer = new byte[256 * 1024]; MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long started = System.nanoTime();
                    while (count < limit) {
                        int n = stream.read(buffer, 0, (int) Math.min(buffer.length, limit - count));
                        if (n < 0) break; digest.update(buffer, 0, n); count += n;
                    }
                    double seconds = (System.nanoTime() - started) / 1e9;
                    StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
                    System.out.printf(java.util.Locale.ROOT, "mode=%s negotiatedRead=%d bytes=%d seconds=%.3f MiB/s=%.3f Mbps=%.3f sha256=%s%n",
                            setting == 0 ? "default" : setting == 2 ? "tcp-nodelay" : "large-buffer", field.getInt(stream), count, seconds, count / seconds / 1048576, count * 8 / seconds / 1e6, hash);
                }
            } finally { context.close(); }
        }
    }
}
