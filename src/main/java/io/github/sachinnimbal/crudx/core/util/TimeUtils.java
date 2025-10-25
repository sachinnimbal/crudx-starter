package io.github.sachinnimbal.crudx.core.util;

public class TimeUtils {

    private TimeUtils() {}

    public static String formatExecutionTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + " ms";
        } else if (milliseconds < 60000) {
            double seconds = milliseconds / 1000.0;
            return String.format("%.2fs (%d ms)", seconds, milliseconds);
        } else if (milliseconds < 3600000) {
            long minutes = milliseconds / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dm %ds (%d ms)", minutes, seconds, milliseconds);
        } else {
            long hours = milliseconds / 3600000;
            long minutes = (milliseconds % 3600000) / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dh %dm %ds (%d ms)", hours, minutes, seconds, milliseconds);
        }
    }
}
