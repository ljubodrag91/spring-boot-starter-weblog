package com.eventhorizon.weblog.controller;

import com.eventhorizon.weblog.LogFileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * The starter's log-file I/O layer: file discovery, backward tailing, gzip streaming, and the
 * bounded newest-first / forward-streaming walks. Deliberately parser-agnostic — it deals only in
 * raw {@code String} lines and a caller-supplied parser {@link Function}, so it has no knowledge of
 * the {@code LogEntry} model. {@link LogViewerController} composes it with the parsing layer.
 *
 * <p>Bounded by construction: {@link #readByPrefix} keeps at most {@code maxLines} parsed items and
 * opens one file at a time; {@code .gz} tailing is guarded by {@code maxCompressedBytes}.
 */
final class LogFileReader {

    private static final Logger log = LoggerFactory.getLogger(LogFileReader.class);

    /** Streams a file's lines top→bottom; return {@code false} from {@link #line} to stop early. */
    @FunctionalInterface
    interface LineHandler { boolean line(String line); }

    private final String logDir;
    private final long maxCompressedBytes;

    LogFileReader(String logDir, long maxCompressedBytes) {
        this.logDir = logDir;
        this.maxCompressedBytes = maxCompressedBytes;
    }

    /**
     * Unified newest-first walk shared by every log type. Discovers {@code <prefix>} files
     * (plain {@code .log} and compressed {@code .log.gz}), then reads them most-recent-first until
     * {@code maxLines} parsed items are collected. Plain files are tailed with a backward seek;
     * compressed files stream through a bounded sliding window, so heap stays bounded to
     * {@code maxLines} items regardless of a file's size, and only one file is open at a time.
     */
    <T> List<T> readByPrefix(String prefix, int maxLines, Function<List<String>, List<T>> parser) {
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Log directory not found: {}", logDir);
            return Collections.emptyList();
        }
        File[] files = findLogFiles(dir, prefix); // newest-first
        List<T> result = new ArrayList<>();
        for (File f : files) {
            if (result.size() >= maxLines) break;
            // Re-check existence: AccessLogCompressionTask renames/deletes plain .log files at
            // 00:10, so a file listed a moment ago may already be gone.
            if (!f.exists()) {
                log.debug("readByPrefix: file disappeared (likely just compressed), skipping: {}", f.getName());
                continue;
            }
            int need = maxLines - result.size();
            result.addAll(newestEntries(f, need, parser));
        }
        return result;
    }

    /**
     * Returns the newest {@code need} parsed items from a single file, newest-first, backfilling
     * for any raw lines the parser dropped so "last N" means N parsed items whenever the file holds
     * them. Grows the read window geometrically (not by the exact shortfall): each pass re-reads
     * (and for {@code .gz} re-decompresses) the whole file, so additive growth would be quadratic on
     * a file dominated by droppable lines; doubling bounds it to O(log) passes and linear total
     * bytes. With no dropped lines (the common case) it does exactly one read.
     */
    <T> List<T> newestEntries(File f, int need, Function<List<String>, List<T>> parser) {
        boolean gz = f.getName().endsWith(".gz");
        int rawWanted = need;
        while (true) {
            List<String> lines = gz ? tailLinesGzip(f, rawWanted) : tailLines(f, rawWanted);
            List<T> entries = parser.apply(lines);
            boolean exhausted = lines.size() < rawWanted; // file had no more lines to give
            if (entries.size() >= need || exhausted) {
                return entries.size() > need ? new ArrayList<>(entries.subList(0, need)) : entries;
            }
            rawWanted = rawWanted > (Integer.MAX_VALUE / 2)
                    ? Integer.MAX_VALUE
                    : Math.max(rawWanted * 2, rawWanted + (need - entries.size()));
        }
    }

    /**
     * Files for {@code prefix} whose embedded date is within {@code [from,to]} (inclusive),
     * newest-first. A null bound is unbounded on that side; with both null, every file is returned.
     * Undated legacy files are kept only when the range is fully unbounded — a date-scoped query
     * excludes them, since they carry no date to place.
     */
    File[] selectFiles(String prefix, LocalDate from, LocalDate to) {
        File dir = new File(logDir);
        if (!dir.exists() || !dir.isDirectory()) return new File[0];
        File[] all = findLogFiles(dir, prefix); // newest-first
        if (from == null && to == null) return all;
        List<File> sel = new ArrayList<>();
        for (File f : all) {
            LocalDate d = LogFileNames.dateFromName(f.getName(), prefix);
            if (d == null) continue;
            if (from != null && d.isBefore(from)) continue;
            if (to   != null && d.isAfter(to))    continue;
            sel.add(f);
        }
        return sel.toArray(new File[0]);
    }

    /** Position of the file named {@code name} within {@code sel}, or -1. Also the guard that keeps
     *  a client-supplied cursor file from being treated as a path — we only ever open {@code sel}. */
    static int indexOfFile(File[] sel, String name) {
        for (int i = 0; i < sel.length; i++) {
            if (sel[i].getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * Finds log files whose names match {@code <prefix>.log} (fixed-name legacy format) or
     * {@code <prefix>.*.log} / {@code <prefix>.*.log.gz} (dated format). Returns files sorted
     * newest-first; lexicographic order on the date-bearing names is chronological.
     */
    File[] findLogFiles(File dir, String prefix) {
        File[] files = dir.listFiles(f -> {
            String n = f.getName();
            return n.equals(prefix + ".log")
                    || (n.startsWith(prefix + ".")
                        && (n.endsWith(".log") || n.endsWith(".log.gz")));
        });
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /** Streams a file's lines top→bottom (plain or gzip), stopping early if the handler returns
     *  false. Unlike {@link #tailLinesGzip} there is no compressed-size cap — a paged/date request
     *  is explicit, and memory is bounded by the caller keeping only a page-sized window. */
    void streamLines(File f, LineHandler h) throws IOException {
        if (f.getName().endsWith(".gz")) {
            try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(f));
                 BufferedReader   br  = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {
                for (String line; (line = br.readLine()) != null; ) if (!h.line(line)) return;
            }
        } else {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                for (String line; (line = br.readLine()) != null; ) if (!h.line(line)) return;
            }
        }
    }

    /**
     * Reads up to {@code maxLines} lines from a gzip-compressed file, newest-first. Gzip requires
     * sequential access, so the file is streamed; memory stays bounded to {@code maxLines} via a
     * sliding window. A coarse compressed-size guard short-circuits pathologically large files.
     */
    List<String> tailLinesGzip(File file, int maxLines) {
        if (file.length() > maxCompressedBytes) {
            log.warn("tailLinesGzip: skipping oversized compressed file ({} bytes): {}",
                    file.length(), file.getPath());
            return Collections.emptyList();
        }
        if (maxLines <= 0) return Collections.emptyList();
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(file));
             BufferedReader   br  = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                tail.addLast(line);
                if (tail.size() > maxLines) tail.removeFirst();
            }
        } catch (IOException e) {
            log.warn("Failed to read gzip log file: {}", file.getPath(), e);
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(tail);
        Collections.reverse(result);
        return result;
    }

    /** Reads the last {@code maxLines} lines of a plain file, newest-first, seeking backwards in 8 KB chunks. */
    List<String> tailLines(File file, int maxLines) {
        if (file == null || !file.exists() || !file.canRead()) {
            log.warn("Log file not found or not readable: {}", file == null ? null : file.getPath());
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long pos = raf.length();
            if (pos == 0) return result;

            while (pos > 0 && result.size() < maxLines) {
                int chunk = (int) Math.min(buf.length, pos);
                pos -= chunk;
                raf.seek(pos);
                raf.readFully(buf, 0, chunk);

                for (int i = chunk - 1; i >= 0; i--) {
                    byte b = buf[i];
                    if (b == '\n') {
                        if (lineBytes.size() > 0) {
                            result.add(decodeReversed(lineBytes));
                            lineBytes.reset();
                            if (result.size() >= maxLines) break;
                        }
                    } else {
                        lineBytes.write(b);
                    }
                }
            }
            if (lineBytes.size() > 0 && result.size() < maxLines) {
                result.add(decodeReversed(lineBytes));
            }
        } catch (IOException e) {
            // Can happen when AccessLogCompressionTask deletes a .log file mid-read.
            log.warn("Failed to tail log file (may have been rotated mid-read): {}", file.getPath(), e);
        }
        return result;
    }

    /** Reverses the bytes accumulated while scanning a line backwards, strips a trailing CR, and decodes UTF-8. */
    static String decodeReversed(ByteArrayOutputStream baos) {
        byte[] bytes = baos.toByteArray();
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte tmp = bytes[i]; bytes[i] = bytes[j]; bytes[j] = tmp;
        }
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
