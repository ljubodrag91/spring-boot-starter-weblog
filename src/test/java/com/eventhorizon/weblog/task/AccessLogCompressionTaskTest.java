package com.eventhorizon.weblog.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AccessLogCompressionTask}: compression and the corrupt-archive guard.
 * The task compresses only — it never deletes archives (no retention). It reads its config via
 * {@code @Value} fields, injected here with {@link ReflectionTestUtils} since there is no Spring
 * context.
 */
class AccessLogCompressionTaskTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path dir;

    private AccessLogCompressionTask newTask() {
        AccessLogCompressionTask task = new AccessLogCompressionTask();
        ReflectionTestUtils.setField(task, "tomcatLogDir", dir.toString());
        return task;
    }

    private static String date(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo).format(DATE_FMT);
    }

    @Test
    void compressesPreviousDayAndDeletesSource() throws IOException {
        Path src = dir.resolve("access_log." + date(1) + ".log");
        Files.writeString(src, "line one\nline two\n");

        newTask().compressOldAccessLogs();

        Path gz = dir.resolve("access_log." + date(1) + ".log.gz");
        assertThat(Files.exists(src)).as("plain source removed").isFalse();
        assertThat(Files.exists(gz)).as("gz archive created").isTrue();
        assertThat(gunzip(gz.toFile())).isEqualTo("line one\nline two\n");
    }

    @Test
    void leavesTodaysActiveFileUntouched() throws IOException {
        Path today = dir.resolve("access_log." + date(0) + ".log");
        Files.writeString(today, "in progress");

        newTask().compressOldAccessLogs();

        assertThat(Files.exists(today)).isTrue();
        assertThat(Files.exists(dir.resolve("access_log." + date(0) + ".log.gz"))).isFalse();
    }

    @Test
    void neverDeletesArchives_evenVeryOldOnes() throws IOException {
        // Retention was removed: the task compresses only and must never delete a .log.gz,
        // regardless of age. A year-old archive must still be present after a run.
        Path ancient = writeGz(dir.resolve("access_log." + date(400) + ".log.gz"), "ancient");
        Path recent  = writeGz(dir.resolve("access_log." + date(2)   + ".log.gz"), "recent");

        newTask().compressOldAccessLogs();

        assertThat(Files.exists(ancient)).as("old archive kept — no retention").isTrue();
        assertThat(Files.exists(recent)).as("recent archive kept").isTrue();
    }

    @Test
    void corruptExistingArchiveIsRebuiltFromSourceInsteadOfLosingData() throws IOException {
        String content = "important log data\n";
        Path src = dir.resolve("access_log." + date(1) + ".log");
        Files.writeString(src, content);
        // A truncated/garbage .gz left by an interrupted prior run.
        Path gz = dir.resolve("access_log." + date(1) + ".log.gz");
        Files.write(gz, new byte[]{0x1f, (byte) 0x8b, 0x00, 0x01, 0x02}); // not a valid gzip stream

        newTask().compressOldAccessLogs();

        // The good source must have been used to rebuild a valid archive — no data loss.
        assertThat(Files.exists(gz)).isTrue();
        assertThat(gunzip(gz.toFile())).isEqualTo(content);
    }

    @Test
    void compressesCatalinaErrorAndExclusionsPreviousDay() throws IOException {
        // Logback rolls these to plain, size-indexed names (<prefix>.<date>.<i>.log); the task
        // now owns their compression the same way it does the access log.
        Path catalina   = dir.resolve("catalina."   + date(1) + ".0.log");
        Path error      = dir.resolve("error."      + date(1) + ".0.log");
        Path exclusions = dir.resolve("exclusions." + date(1) + ".0.log");
        Files.writeString(catalina,   "cat\n");
        Files.writeString(error,      "err\n");
        Files.writeString(exclusions, "{\"ts\":\"x\"}\n");

        newTask().compressOldAccessLogs();

        for (String prefix : new String[]{"catalina", "error", "exclusions"}) {
            assertThat(Files.exists(dir.resolve(prefix + "." + date(1) + ".0.log")))
                    .as("%s plain source removed", prefix).isFalse();
            assertThat(Files.exists(dir.resolve(prefix + "." + date(1) + ".0.log.gz")))
                    .as("%s gz archive created", prefix).isTrue();
        }
    }

    @Test
    void leavesUndatedLegacyFileUntouched() throws IOException {
        // e.g. Tomcat's own catalina.log or a fixed-name legacy file — no parseable date,
        // so the task must not attempt to compress it.
        Path legacy = dir.resolve("catalina.log");
        Files.writeString(legacy, "legacy content");

        newTask().compressOldAccessLogs();

        assertThat(Files.exists(legacy)).as("undated legacy file left alone").isTrue();
        assertThat(Files.exists(dir.resolve("catalina.log.gz"))).isFalse();
    }

    @Test
    void oneRunCatchesUpABacklogOfManyDays() throws IOException {
        // Simulates nights the app was down: several past-day plain files accumulate across
        // prefixes. A single sweep must compress the whole backlog, not just yesterday.
        for (int d = 1; d <= 5; d++) {
            Files.writeString(dir.resolve("access_log." + date(d) + ".log"), "a\n");
            Files.writeString(dir.resolve("catalina."   + date(d) + ".0.log"), "c\n");
        }

        newTask().compressOldAccessLogs();

        for (int d = 1; d <= 5; d++) {
            assertThat(Files.exists(dir.resolve("access_log." + date(d) + ".log.gz")))
                    .as("access_log day-%d compressed", d).isTrue();
            assertThat(Files.exists(dir.resolve("catalina." + date(d) + ".0.log.gz")))
                    .as("catalina day-%d compressed", d).isTrue();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Path writeGz(Path path, String content) throws IOException {
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(path.toFile()))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private static String gunzip(java.io.File gz) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(gz))) {
            in.transferTo(bos);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }
}
