// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class FileDisplay {
    private static final long MB = 1024L * 1024;
    static String size(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes < MB) return ((bytes + 1023) / 1024) + " kB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) MB);
    }
    static String modified(long millis) {
        return millis <= 0 ? "修改时间未知" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }
    static String details(long millis, long bytes) { return modified(millis) + " · " + size(bytes); }
}
