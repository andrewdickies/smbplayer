package com.smbplayer.tv;

/** Host-side check: short presses stay configurable; held keys accelerate in bounded stages. */
public final class TvSeekPolicyCheck {
    private static void expect(int actual, int expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        for (int configured : new int[] {10000, 30000}) {
            expect(TvSeekPolicy.stepMillis(configured, 0), configured);
            expect(TvSeekPolicy.stepMillis(configured, 19), configured);
            expect(TvSeekPolicy.stepMillis(configured, 20), 60000);
            expect(TvSeekPolicy.stepMillis(configured, 59), 60000);
            expect(TvSeekPolicy.stepMillis(configured, 60), 300000);
            expect(TvSeekPolicy.stepMillis(configured, 119), 300000);
            expect(TvSeekPolicy.stepMillis(configured, 120), 600000);
            expect(TvSeekPolicy.stepMillis(configured, Integer.MAX_VALUE), 600000);
            int previous = configured;
            for (int repeat = 0; repeat < 200; repeat++) {
                int step = TvSeekPolicy.stepMillis(configured, repeat);
                if (step < previous || step > 600000) throw new AssertionError("Unbounded or decreasing seek step");
                previous = step;
            }
        }
        System.out.println("Remote seek policy checks passed");
    }
}
