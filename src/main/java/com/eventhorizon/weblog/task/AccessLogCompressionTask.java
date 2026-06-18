package com.eventhorizon.weblog.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses Tomcat native access log files from previous days.
 *
 * <p>Tomcat's {@code AccessLogValve} rotates daily but has no compression support.
 * Logback-managed logs (catalina, error, exclusions) compress automatically via
 * {@code SizeAndTimeBasedRollingPolicy}. This task closes the gap so all rolled
 * files in {@code logs/} are consistently {@code .gz}.
 *
 * <p>Runs at 00:10 — ten minutes after Tomcat has rotated at midnight — to give
 * the OS time to release the file handle on the just-closed file before the delete
 * is attempted (Windows holds handles briefly after {@code close()}).
 *
 * <p>The log viewer's {@code readTomcatAccess()} handles both {@code .log} and
 * {@code .log.gz} files, so historical access data remains visible after compression.
 *
 * <p><b>Note:</b> {@code @EnableScheduling} is activated by
 * {@link com.eventhorizon.weblog.WebLogAutoConfiguration}, so the consumer app does not
 * need to declare it separately.
 */
@Slf4j
public class AccessLogCompressionTask {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int               BUFFER   = 65_536;

    @Value("${log-viewer.access-log-directory:logs}")
    private String tomcatLogDir;

    @Scheduled(cron = "0 10 0 * * *")  // 00:10:00 every day
    public void compressOldAccessLogs() {
        String today = LocalDate.now().format(DATE_FMT);
        File   dir   = new File(tomcatLogDir);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] candidates = dir.listFiles(f -> {
            String n = f.getName();
            // e.g. access_log.2026-05-22.log — uncompressed, not today's active file
            return n.startsWith("access_log.")
                && n.endsWith(".log")
                && !n.contains(today);
        });

        if (candidates == null) return;

        for (File src : candidates) {
            File gz = new File(src.getParent(), src.getName() + ".gz");

            if (gz.exists()) {
                // Compressed version already present — clean up the plain copy.
                if (src.delete()) {
                    log.info("AccessLog compress: removed duplicate plain file {}", src.getName());
                }
                continue;
            }

            try {
                gzip(src, gz);
                if (src.delete()) {
                    log.info("AccessLog compress: {} → {}", src.getName(), gz.getName());
                } else {
                    log.warn("AccessLog compress: compressed {} but could not delete source", src.getName());
                    // Leave both; next run will clean up.
                }
            } catch (IOException e) {
                log.error("AccessLog compress: failed to compress {}", src.getName(), e);
                //noinspection ResultOfMethodCallIgnored
                gz.delete(); // remove partial output
            }
        }
    }

    private static void gzip(File src, File dest) throws IOException {
        byte[] buf = new byte[BUFFER];
        try (FileInputStream  in  = new FileInputStream(src);
             GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(dest), BUFFER)) {
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
