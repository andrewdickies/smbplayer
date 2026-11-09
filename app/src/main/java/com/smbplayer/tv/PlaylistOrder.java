// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 smbplayer contributors
package com.smbplayer.tv;

import java.util.*;
import java.util.function.*;

/** Deterministic queue ordering; missing file size/date stays last in either direction. */
final class PlaylistOrder {
    static <T> List<T> sort(List<T> input, int mode, boolean descending, long seed,
                           ToLongFunction<T> size, ToLongFunction<T> date,
                           Function<T, String> name, Function<T, String> identity) {
        List<T> result = new ArrayList<>(input);
        Comparator<T> tie = Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER).thenComparing(identity);
        if (mode == 3) {
            result.sort(Comparator.comparing(identity));
            Collections.shuffle(result, new Random(seed));
            if (descending) Collections.reverse(result);
        } else if (mode == 1) {
            result.sort(descending ? tie.reversed() : tie);
        } else {
            ToLongFunction<T> value = mode == 2 ? date : size;
            result.sort((a, b) -> {
                long x = value.applyAsLong(a), y = value.applyAsLong(b);
                if (x <= 0 || y <= 0) {
                    if (x <= 0 && y > 0) return 1;
                    if (y <= 0 && x > 0) return -1;
                }
                int compare = x > 0 && y > 0 ? Long.compare(x, y) : 0;
                return compare == 0 ? (descending ? tie.compare(b, a) : tie.compare(a, b)) : descending ? -compare : compare;
            });
        }
        return result;
    }
}
