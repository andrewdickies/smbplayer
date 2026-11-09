// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

/** Remote hold acceleration; discrete press always keeps the configured short step. */
final class TvSeekPolicy {
    private TvSeekPolicy() {}

    static int stepMillis(int shortStep, int repeat) {
        if (repeat >= 120) return 600000;
        if (repeat >= 60) return 300000;
        if (repeat >= 20) return 60000;
        return shortStep;
    }
}
