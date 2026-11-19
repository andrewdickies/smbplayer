package com.smbplayer.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-only cache of display-sized photos. Keys are queue indices, never media names. */
final class PhotoCache implements AutoCloseable {
    private static final long DISK_BUDGET = 192L * 1024 * 1024;
    private static final long MIN_FREE = 1024L * 1024 * 1024;
    private final File directory;
    private final LruCache<Integer, Bitmap> memory = new LruCache<Integer, Bitmap>(40 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Map<Integer, File> disk = new LinkedHashMap<>(16, .75f, true);
    private long diskBytes;
    private boolean closed;

    PhotoCache(File parent) {
        directory = new File(parent, "photo-session-" + java.util.UUID.randomUUID());
    }

    Bitmap get(int key) {
        File file;
        synchronized (this) {
            if (closed) return null;
            Bitmap cached = memory.get(key);
            if (cached != null) return cached;
            file = disk.get(key);
        }
        if (file == null) return null;
        Bitmap cached = BitmapFactory.decodeFile(file.getPath());
        synchronized (this) {
            if (closed || cached == null) return null;
            memory.put(key, cached);
            return cached;
        }
    }

    void put(int key, Bitmap bitmap) {
        synchronized (this) {
            if (closed) return;
            memory.put(key, bitmap);
            if (disk.containsKey(key)) return;
            if ((!directory.isDirectory() && !directory.mkdir()) || directory.getUsableSpace() < MIN_FREE) return;
        }
        File file;
        try {
            file = File.createTempFile("frame-", ".cache", directory);
        } catch (IOException e) { return; }
        boolean saved = false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            Bitmap.CompressFormat format = bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            saved = bitmap.compress(format, 93, out);
        } catch (IOException | RuntimeException ignored) {}
        synchronized (this) {
            if (!saved || closed || disk.containsKey(key)) {
                file.delete();
                if (closed) directory.delete();
                return;
            }
            disk.put(key, file);
            diskBytes += file.length();
            while (diskBytes > DISK_BUDGET && !disk.isEmpty()) {
                Integer oldest = disk.keySet().iterator().next();
                File evicted = disk.remove(oldest);
                diskBytes -= evicted.length();
                evicted.delete();
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        memory.evictAll();
        disk.clear();
        clear(directory);
    }

    static void clear(File directory) {
        File[] frames = directory.listFiles();
        if (frames != null) for (File frame : frames)
            if (frame.isFile() && frame.getName().startsWith("frame-") && frame.getName().endsWith(".cache")) frame.delete();
        directory.delete();
    }
}
