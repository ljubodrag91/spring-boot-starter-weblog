package com.eventhorizon.weblog.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses previous days' rolled log files. <b>Compression only — archives are never
 * deleted.</b>
 *
 * <p>Covers every log family the starter manages — Tomcat's native access log plus the
 * Logback-written {@code catalina}/{@code error}/{@code exclusions}/{@code slow} logs — via one
 * nightly directory sweep. The set of filename prefixes is configurable through
 * {@code log-viewer.compression-log-prefixes} (default {@code access_log, catalina, error,
 * exclusions, slow}).
 *
 * <p>Why a sweep rather than relying on each writer's own rollover: neither Tomcat's
 * {@code AccessLogValve} nor Logback compresses reliably on an intermittently-run app.
 * Tomcat's valve rotates daily but never compresses, and its {@code maxDays} cleanup cannot
 * match a {@code .gz} name once renamed. Logback compresses only <i>at the instant</i> a
 * file's own rollover boundary is crossed while the JVM is running; miss that instant (app
 * down over midnight) and the file is orphaned as plain {@code .log} forever. So the
 * starter's Logback appenders are configured to roll to plain {@code .log} (no {@code .gz})
 * and this task gzips every past-day {@code <prefix>.<date>[.<i>].log}.
 *
 * <p><b>No retention:</b> this task never removes {@code .log.gz} archives — they are kept
 * indefinitely. Bounding disk usage is left to the operator (external log rotation, disk
 * monitoring, or manual cleanup).
 *
 * <p>Because the sweep re-scans the directory on every run, one run catches up the entire
 * backlog that accumulated on nights the app was down — not just the previous day.
 *
 * <p>Runs at {@code log-viewer.compression-cron} (default 00:10) — after the midnight
 * rotation — to give the OS time to release the file handle on the just-closed file before
 * the plain source is deleted (Windows holds handles briefly after {@code close()}).
 *
 * <p>The log viewer's readers handle both {@code .log} and {@code .log.gz} files, so
 * historical data remains visible after compression.
 *
 * <p><b>Note:</b> {@code @EnableScheduling} is activated by
 * {@link com.eventhorizon.weblog.WebLogAutoConfiguration}, so the consumer app does not
 * need to declare it separately.
 */
@Slf4j
public class AccessLogCompressionTask {

    private static final int BUFFER = 65_536;

    @Value("${log-viewer.access-log-directory:logs}")
    private String tomcatLogDir;

    /**
     * Filename prefixes of the log families this task compresses and prunes. Each is swept
     * independently. The Java default mirrors the log streams the starter manages; override
     * via {@code log-viewer.compression-log-prefixes} (comma-separated). The default is also
     * set as a field initializer so the task behaves correctly when constructed outside a
     * Spring context (e.g. unit tests) where {@code @Value} is not applied.
     */
    @Value("${log-viewer.compression-log-prefixes:access_log,catalina,error,exclusions,slow}")
    private List<String> logPrefixes = List.of("access_log", "catalina", "error", "exclusions", "slow");

    // Default mirrors WebLogProperties.compressionCron; a property placeholder is required here
    // because @Scheduled needs a constant expression resolvable at bean-init time. "-" disables.
    @Scheduled(cron = "${log-viewer.compression-cron:0 10 0 * * *}")
    public void compressOldAccessLogs() {
        File dir = new File(tomcatLogDir);
        if (!dir.exists() || !dir.isDirectory()) return;
        for (String prefix : logPrefixes) {
            compressPreviousDays(dir, prefix);
        }
    }

    // ── compression ─────────────────────────────────────────────────────────

    private void compressPreviousDays(File dir, String prefix) {
        LocalDate today = LocalDate.now();
        File[] candidates = dir.listFiles(f -> {
            String n = f.getName();
            if (!n.startsWith(prefix + ".") || !n.endsWith(".log")) return false;
            // Compress only past-day files. A parseable date strictly before today excludes
            // today's active file (and any same-day size-rolled index still being caught up
            // tomorrow) and skips undated legacy names like "catalina.log".
            LocalDate d = com.eventhorizon.weblog.LogFileNames.dateFromName(n, prefix);
            return d != null && d.isBefore(today);
        });
        if (candidates == null) return;

        for (File src : candidates) {
            File gz = new File(src.getParent(), src.getName() + ".gz");

            if (gz.exists()) {
                // A .gz already exists — but a JVM kill mid-compress could have left a
                // truncated archive next to the good source. Only drop the source if the
                // existing archive is a complete, readable gzip; otherwise recompress.
                if (isCompleteGzip(gz)) {
                    if (src.delete()) {
                        log.info("AccessLog compress: removed duplicate plain file {}", src.getName());
                    }
                } else {
                    log.warn("AccessLog compress: existing archive {} is incomplete/corrupt — recompressing",
                            gz.getName());
                    if (compress(src, gz)) deleteSource(src, gz);
                }
                continue;
            }

            if (compress(src, gz)) deleteSource(src, gz);
        }
    }

    /**
     * Compresses {@code src} into {@code gz} by writing to a temp file first and moving it
     * into place on success — so an interrupted run never leaves a partial file at the final
     * {@code .gz} name (which a later run would mistake for a valid archive).
     */
    private boolean compress(File src, File gz) {
        File tmp = new File(gz.getParentFile(), gz.getName() + ".tmp");
        try {
            gzip(src, tmp);
            Files.move(tmp.toPath(), gz.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.error("AccessLog compress: failed to compress {}", src.getName(), e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete(); // remove partial output
            return false;
        }
    }

    private void deleteSource(File src, File gz) {
        if (src.delete()) {
            log.info("AccessLog compress: {} → {}", src.getName(), gz.getName());
        } else {
            log.warn("AccessLog compress: compressed {} but could not delete source", src.getName());
            // Leave both; next run will validate the .gz and clean up.
        }
    }

    // ── gzip helpers ──────────────────────────────────────────────────────────

    private static void gzip(File src, File dest) throws IOException {
        byte[] buf = new byte[BUFFER];
        // Each stream in its own try-with-resources: if a wrapping constructor throws, the
        // already-opened underlying stream is still closed (a leaked handle would block the
        // subsequent atomic move/replace on Windows).
        try (FileInputStream  in  = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest);
             GZIPOutputStream out = new GZIPOutputStream(fos, BUFFER)) {
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * Streams the whole archive through {@link GZIPInputStream}; a truncated or corrupt file
     * throws before EOF (gzip validates its CRC32/ISIZE trailer), so completing the read means
     * the archive is intact. Only invoked on the rare path where a source survived a prior run.
     */
    private static boolean isCompleteGzip(File gz) {
        byte[] buf = new byte[BUFFER];
        // FileInputStream in its own try-with-resources: GZIPInputStream's constructor reads and
        // validates the header and can throw on a corrupt file — if it does, the FileInputStream
        // arg would otherwise leak an open handle, and on Windows that blocks the recompress from
        // replacing this very file.
        try (FileInputStream fis = new FileInputStream(gz);
             GZIPInputStream  in  = new GZIPInputStream(fis, BUFFER)) {
            while (in.read(buf) != -1) { /* consume to trailer */ }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
