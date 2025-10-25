package eu.Divish.tabSBS.util;

import java.time.Duration;

public final class TimeSpec {
    private TimeSpec() {}

    /** Přijme 2 | 1.2 | "1.75s" → vrátí Duration (zaokrouhleno nahoru na ms, min 50 ms). */
    public static Duration secondsToDuration(Object val, double defSeconds) {
        double secs = defSeconds;
        if (val instanceof Number n) {
            secs = n.doubleValue();
        } else if (val instanceof String s) {
            s = s.trim().toLowerCase();
            if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
            try { secs = Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        long ms = (long) Math.ceil(secs * 1000.0);
        if (ms < 50) ms = 50;
        return Duration.ofMillis(ms);
    }

    /** Duration → ticks (ceil), min 1 tick. */
    public static long toTicksCeil(Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }
}
