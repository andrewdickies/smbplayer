// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.app.Application;
import java.io.File;

public final class TvApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // A new process has no active stream. Remove temporary caches left by a killed process.
        File[] abandoned = getCacheDir().listFiles(file -> file.isFile()
                && file.getName().startsWith("smb-stream-") && file.getName().endsWith(".cache"));
        if (abandoned != null) for (File file : abandoned) file.delete();
    }
}
