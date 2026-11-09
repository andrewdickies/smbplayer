// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.content.SharedPreferences;

/** One-time removal of obsolete preferences without touching sources or playback positions. */
final class PlayerDefaults {
    static void migrate(SharedPreferences preferences) {
        if(preferences.getInt("simpleUiVersion",0)>=26)return;
        SharedPreferences.Editor edit=preferences.edit();
        String[] obsolete={"speedSteps","scale","rotation","zoomSteps","picturePreset","customPicture",
            "pictureBrightness","pictureContrast","pictureSaturation","deinterlace","denoise","sharpness",
            "hdrToSdr","audioGain","equalizer","subtitleEncoding","subtitleYellow","subtitleRaised",
            "subtitleBold","subtitleShadow","subtitleOpacity","audioDelay","subtitleDelay","showRemaining",
            "focusAnimation","uiMotion","hideSeconds","historyLimit","playlistSort","playlistDescending",
            "playlistSortSeed","onlineMovieMetadata","systemMediaKeys"};
        for(String key:obsolete)edit.remove(key);
        for(String key:preferences.getAll().keySet())if(key.startsWith("shortcut."))edit.remove(key);
        if(!preferences.contains("passthroughMode"))edit.putInt("passthroughMode",preferences.getBoolean("passthrough",false)?2:0);
        int size=preferences.getInt("subtitleSize",14);edit.putInt("subtitleSize",size<=11?11:size>=16?16:14);
        edit.remove("passthrough");edit.putInt("loop",Math.floorMod(preferences.getInt("loop",0),3));
        edit.putBoolean("gridView",false).putInt("simpleUiVersion",26).apply();
    }
}
