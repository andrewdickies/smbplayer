// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;

/** Conservative automatic passthrough; forced modes remain available for TV firmware quirks. */
final class AudioOutput {
    static boolean enabled(Context context,int mode) {
        if(mode==1)return false;
        if(mode==2)return true;
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(manager==null)return false;
        try {
            boolean hdmi=false,compressed=false,otherExternal=false;
            for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type=device.getType();
                if(type==AudioDeviceInfo.TYPE_HDMI || type==AudioDeviceInfo.TYPE_HDMI_ARC || type==AudioDeviceInfo.TYPE_HDMI_EARC) {
                    hdmi=true;
                    for(int encoding:device.getEncodings())if(encoding==AudioFormat.ENCODING_AC3 || encoding==AudioFormat.ENCODING_E_AC3 || encoding==AudioFormat.ENCODING_DTS || encoding==AudioFormat.ENCODING_DTS_HD)compressed=true;
                } else if(type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type==AudioDeviceInfo.TYPE_WIRED_HEADSET || type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type==AudioDeviceInfo.TYPE_USB_HEADSET || type==AudioDeviceInfo.TYPE_BLE_HEADSET || type==AudioDeviceInfo.TYPE_BLE_SPEAKER)otherExternal=true;
            }
            if(!hdmi||!compressed)return false;
            if(Build.VERSION.SDK_INT>=29) {
                AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
                for(int encoding:new int[]{AudioFormat.ENCODING_AC3,AudioFormat.ENCODING_E_AC3,AudioFormat.ENCODING_DTS}) {
                    AudioFormat format=new AudioFormat.Builder().setEncoding(encoding).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();
                    if(android.media.AudioTrack.isDirectPlaybackSupported(format,attributes))return true;
                }
                return false;
            }
            return !otherExternal;
        }catch(RuntimeException unsupported){return false;}
    }
}
