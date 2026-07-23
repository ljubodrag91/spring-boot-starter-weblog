package com.eventhorizon.weblog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The one place that knows the starter's rolled-log filename convention
 * {@code <prefix>.<yyyy-MM-dd>[.<i>].log[.gz]}.
 *
 * <p>Both the viewer ({@code LogViewerController}) and the compression task
 * ({@code AccessLogCompressionTask}) need to read the date embedded right after the prefix;
 * keeping that fragile substring logic in a single helper stops the two copies from drifting.
 */
public final class LogFileNames {

    private LogFileNames() {}

    /**
     * Extracts the {@code yyyy-MM-dd} embedded right after {@code <prefix>.} in {@code fileName},
     * or {@code null} if the name has no parseable date at that position (e.g. an undated legacy
     * {@code catalina.log}).
     */
    public static LocalDate dateFromName(String fileName, String prefix) {
        int start = prefix.length() + 1; // skip "<prefix>."
        try {
            return LocalDate.parse(fileName.substring(start, start + 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
