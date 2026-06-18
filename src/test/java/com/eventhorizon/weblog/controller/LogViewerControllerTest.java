package com.eventhorizon.weblog.controller;



import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link LogViewerController}.
 *
 * The controller is instantiated manually (no Spring context):
 * - {@code logDir} is set to {@code java.io.tmpdir} — always exists, never has access_log files
 *   → all "read log" paths return empty lists, making HTTP endpoint tests deterministic.
 * - {@code htmlResource} is loaded from the classpath so {@code init()} succeeds.
 *
 * Private utility methods are tested via {@link ReflectionTestUtils#invokeMethod}.
 */
@ExtendWith(MockitoExtension.class)
class LogViewerControllerTest {

    private LogViewerController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LogViewerController();
        // Inject @Value fields that Spring would normally set
        ReflectionTestUtils.setField(controller, "logDir", System.getProperty("java.io.tmpdir"));
        ReflectionTestUtils.setField(controller, "htmlResource",
                new ClassPathResource("views/log-viewer.html"));
        // Trigger @PostConstruct manually
        ReflectionTestUtils.invokeMethod(controller, "init");

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── HTTP endpoints ─────────────────────────────────────────────────────────

    @Test
    void getLogsHtml_noLogFiles_returns200() throws Exception {
        mockMvc.perform(get("/admin/logs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void getLogsData_noLogFiles_returns200WithEmptyArray() throws Exception {
        mockMvc.perform(get("/admin/logs/data"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    // ── decodeReversed ─────────────────────────────────────────────────────────

    @Test
    void decodeReversed_asciiContent_roundTrips() throws Exception {
        assertDecodedEquals("plain ascii log line");
    }

    @Test
    void decodeReversed_utf8MultiByteContent_roundTrips() throws Exception {
        // Serbian multi-byte chars: Š (C5 A0), ć (C4 87), Ž (C5 BD)
        assertDecodedEquals("Šifarnik zdravlja živi Ćirilicom");
    }

    @Test
    void decodeReversed_trailingCarriageReturn_isStripped() throws Exception {
        assertThat(decodeReversed("log line\r")).isEqualTo("log line");
    }

    @Test
    void decodeReversed_noTrailingCr_contentUnchanged() throws Exception {
        assertDecodedEquals("no carriage return");
    }

    // ── tokenize ──────────────────────────────────────────────────────────────

    @Test
    void tokenize_plainSpaceSeparatedTokens_splitCorrectly() {
        assertThat(tokenize("GET HTTP/1.1 200"))
                .containsExactly("GET", "HTTP/1.1", "200");
    }

    @Test
    void tokenize_quotedTokenWithSpaces_treatedAsSingleToken() {
        assertThat(tokenize("\"User Agent 1.0\" 200"))
                .containsExactly("User Agent 1.0", "200");
    }

    @Test
    void tokenize_mixedQuotedAndPlain_parsedInOrder() {
        // Mirrors a fragment of the Tomcat access log format
        String uri = "/api/MTP/DIJAGNOZA";
        assertThat(tokenize("GET \"" + uri + "\" HTTP/1.1 200 1234"))
                .containsExactly("GET", uri, "HTTP/1.1", "200", "1234");
    }

    @Test
    void tokenize_emptyQuotedToken_includedAsEmptyString() {
        assertThat(tokenize("A \"\" B")).containsExactly("A", "", "B");
    }

    @Test
    void tokenize_dashValueOutsideQuotes_parsedAsPlainToken() {
        // Tomcat emits bare "-" for absent numeric fields (e.g. Content-Length)
        assertThat(tokenize("200 - \"-\"")).containsExactly("200", "-", "-");
    }

    // ── snapToValidOption ──────────────────────────────────────────────────────

    // Valid dropdown options in the log viewer: 2000, 5000, 10000, 20000, 50000
    @ParameterizedTest(name = "{0} lines → snaps to {1}")
    @CsvSource({
        "0,       2000",   // below minimum
        "2000,    2000",   // exact
        "3000,    2000",   // closer to 2000
        "4000,    5000",   // closer to 5000
        "5000,    5000",   // exact
        "7500,    5000",   // equidistant — smaller wins (first in array)
        "10000,  10000",   // exact
        "50000,  50000",   // exact
        "99999,  50000",   // above max
    })
    void snapToValidOption_snapsToNearestAllowedValue(int input, int expected) {
        int result = ReflectionTestUtils.invokeMethod(controller, "snapToValidOption", input);
        assertThat(result).isEqualTo(expected);
    }

    // ── reformatTs ────────────────────────────────────────────────────────────

    @Test
    void reformatTs_validTimestamp_convertedToDayMonNameYear() {
        String result = ReflectionTestUtils.invokeMethod(controller, "reformatTs",
                "2026-05-25 14:30:45.123");
        assertThat(result).isEqualTo("25-May-2026 14:30:45.123");
    }

    @Test
    void reformatTs_null_returnsNull() {
        // Must cast null explicitly so ReflectionTestUtils resolves the right overload
        String result = ReflectionTestUtils.invokeMethod(controller, "reformatTs", (Object) null);
        assertThat(result).isNull();
    }

    @Test
    void reformatTs_stringTooShort_returnedUnchanged() {
        String result = ReflectionTestUtils.invokeMethod(controller, "reformatTs", "bad");
        assertThat(result).isEqualTo("bad");
    }

    // ── parseTomcatAccess ──────────────────────────────────────────────────────

    @Test
    void parseTomcatAccess_blankLine_returnsNull() {
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", "");
        assertThat(entry).isNull();
    }

    @Test
    void parseTomcatAccess_validLine_parsesAllExpectedFields() {
        // Complete Tomcat access log line matching the pattern in application.properties.
        // Token positions (after timestamp) are documented inline with application.properties.
        //   [0]  method       [1]  "uri"         [2]  protocol     [3]  status
        //   [4]  respBytes    [5]  durationµs    [6]  ttfbµs       [7]  "remoteIp"
        //   [8]  "XFF"        [9]  "X-Real-IP"   [10] vhost:port   [11] thread
        //   [12] connFate     [13] "host"         [14] "reqCT"      [15] reqCL
        //   [16] "accept"     [17] "acceptEnc"    [18] "acceptLang" [19] "connection"
        //   [20] "cacheCtrl"  [21] "xReqId-in"    [22] "referer"    [23] "ua"
        //   [24] "respCT"     [25] respCL          [26] "respEnc"    [27] "respCC"
        //   [28] "xReqId-out"
        String uri = "/api/MTP/DIJAGNOZA";
        String line = "2026-05-25 14:30:45.123 "
                + "GET \"" + uri + "\" HTTP/1.1 200 4096 142000 5000 "
                + "\"10.0.0.1\" \"-\" \"-\" "
                + "localhost:8080 http-nio-8080-exec-1 + "
                + "\"localhost\" \"-\" - "
                + "\"*/*\" \"gzip\" \"en-US\" \"keep-alive\" \"-\" \"-\" \"-\" \"curl/8.6\" "
                + "\"application/json\" - \"-\" \"no-cache\" \"req-abc-123\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.uri()).isEqualTo(uri);
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.durationMs()).isEqualTo(142L);   // 142 000 µs ÷ 1 000
        assertThat(entry.ttfbMs()).isEqualTo(5L);          // 5 000 µs ÷ 1 000
        assertThat(entry.ip()).isEqualTo("10.0.0.1");      // remoteIp — XFF was "-"
        assertThat(entry.ua()).isEqualTo("curl/8.6");
        assertThat(entry.requestId()).isEqualTo("req-abc-123");
        assertThat(entry.timestamp()).isEqualTo("25-May-2026 14:30:45.123");
    }

    @Test
    void parseTomcatAccess_xForwardedForPresent_firstValueUsedAsClientIp() {
        // XFF is token [8]; when non-absent it takes priority over remoteIp
        String uri = "/api/MTP/DIJAGNOZA";
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"" + uri + "\" HTTP/1.1 200 512 10000 1000 "
                + "\"192.168.1.1\" \"203.0.113.5, 10.1.1.1\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        // First value from comma-separated XFF chain, trimmed
        assertThat(entry.ip()).isEqualTo("203.0.113.5");
    }

    // ── parseExclusionLine ────────────────────────────────────────────────────

    @Test
    void parseExclusionLine_fullRecord_parsesAllFields() {
        String line = "{\"ts\":\"2026-05-26 10:00:00.000\","
                + "\"method\":\"GET\","
                + "\"uri\":\"/myapp/admin/logs\","
                + "\"status\":200,"
                + "\"durationMs\":12,"
                + "\"ip\":\"10.0.0.5\","
                + "\"requestId\":\"550e8400-e29b-41d4-a716-446655440000\"}";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", line);

        assertThat(entry).isNotNull();
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.uri()).isEqualTo("/myapp/admin/logs");
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.durationMs()).isEqualTo(12L);
        assertThat(entry.ip()).isEqualTo("10.0.0.5");
        assertThat(entry.requestId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(entry.timestamp()).isEqualTo("26-May-2026 10:00:00.000");
    }

    @Test
    void parseExclusionLine_missingRequestId_returnsNullRequestId() {
        // Backward-compat: old exclusion log lines before RequestIdConfig was added
        String line = "{\"ts\":\"2026-05-26 10:00:00.000\","
                + "\"method\":\"GET\","
                + "\"uri\":\"/myapp/swagger-ui/index.html\","
                + "\"status\":200,"
                + "\"durationMs\":5,"
                + "\"ip\":\"127.0.0.1\"}";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", line);

        assertThat(entry).isNotNull();
        assertThat(entry.requestId()).isNull();
    }

    @Test
    void parseExclusionLine_withUserField_parsesUser() {
        String line = "{\"ts\":\"2026-06-18 10:00:00.000\","
                + "\"method\":\"GET\","
                + "\"uri\":\"/admin/logs\","
                + "\"status\":200,"
                + "\"durationMs\":3,"
                + "\"ip\":\"127.0.0.1\","
                + "\"requestId\":\"req-with-user\","
                + "\"user\":\"alice@example.com\"}";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", line);

        assertThat(entry).isNotNull();
        assertThat(entry.user()).isEqualTo("alice@example.com");
    }

    @Test
    void parseExclusionLine_withoutUserField_userIsNull() {
        // Backward-compat: pre-1.1 exclusion lines have no user field
        String line = "{\"ts\":\"2026-06-18 10:00:00.000\","
                + "\"method\":\"GET\","
                + "\"uri\":\"/admin/logs\","
                + "\"status\":200,"
                + "\"durationMs\":3,"
                + "\"ip\":\"127.0.0.1\","
                + "\"requestId\":\"req-no-user\"}";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", line);

        assertThat(entry).isNotNull();
        assertThat(entry.user()).isNull();
    }

    @Test
    void parseExclusionLine_blankLine_returnsNull() {
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", "");
        assertThat(entry).isNull();
    }

    @Test
    void parseExclusionLine_invalidJson_returnsNull() {
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseExclusionLine", "not-json");
        assertThat(entry).isNull();
    }

    // ── getLogsData — log type routing ────────────────────────────────────────

    @Test
    void getLogsData_typeCatalina_returns200WithEmptyArray() throws Exception {
        // logDir is tmpdir — no catalina.log → readServer returns empty list
        mockMvc.perform(get("/admin/logs/data").param("type", "catalina").param("lines", "100"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getLogsData_typeExclusions_returns200WithEmptyArray() throws Exception {
        // logDir is tmpdir — no exclusions.log → readExclusions returns empty list
        mockMvc.perform(get("/admin/logs/data").param("type", "exclusions").param("lines", "100"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    // ── parseTomcatAccess — guard on too-few tokens ───────────────────────────

    @Test
    void parseTomcatAccess_tooFewTokensAfterTimestamp_returnsNull() {
        // Only 5 tokens after timestamp (minimum is 9) — should return null, not throw
        String line = "2026-06-01 10:00:00.000 GET \"/api/MTP/DIJAGNOZA\" HTTP/1.1 200 1024";
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);
        assertThat(entry).isNull();
    }

    // ── actual file reading via @TempDir ─────────────────────────────────────

    @Nested
    class WithActualLogFiles {

        @TempDir
        Path tempDir;

        LogViewerController nestedController;
        MockMvc nestedMockMvc;

        /** Full 29-token access log line that parseTomcatAccess can parse successfully. */
        private static final String ACCESS_LINE =
                "2026-06-01 10:00:00.000 "
                + "GET \"/api/MTP/DIJAGNOZA\" HTTP/1.1 200 4096 50000 1000 "
                + "\"10.0.0.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-1 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"curl/8.6\" "
                + "\"application/json\" - \"-\" \"no-cache\" \"req-file-test\"";

        @BeforeEach
        void setUp() throws Exception {
            nestedController = new LogViewerController();
            ReflectionTestUtils.setField(nestedController, "logDir", tempDir.toString());
            ReflectionTestUtils.setField(nestedController, "htmlResource",
                    new ClassPathResource("views/log-viewer.html"));
            ReflectionTestUtils.invokeMethod(nestedController, "init");
            nestedMockMvc = MockMvcBuilders.standaloneSetup(nestedController).build();
        }

        @Test
        void getLogsData_accessLogFile_parsesEntriesAndReturnsArray() throws Exception {
            Files.writeString(tempDir.resolve("access_log.2026-06-01.log"),
                    ACCESS_LINE + "\n");

            nestedMockMvc.perform(get("/admin/logs/data")
                            .param("type", "access").param("lines", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].method").value("GET"))
                    .andExpect(jsonPath("$[0].status").value(200))
                    .andExpect(jsonPath("$[0].requestId").value("req-file-test"));
        }

        @Test
        void getLogsData_multipleAccessLogFiles_aggregatesEntriesNewestFirst() throws Exception {
            // Two log files — controller must read both and aggregate
            Files.writeString(tempDir.resolve("access_log.2026-06-01.log"),
                    ACCESS_LINE + "\n");
            Files.writeString(tempDir.resolve("access_log.2026-05-31.log"),
                    ACCESS_LINE.replace("req-file-test", "req-older").replace("10:00:00.000", "09:00:00.000") + "\n");

            nestedMockMvc.perform(get("/admin/logs/data")
                            .param("type", "access").param("lines", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        void getLogsData_exclusionsLogFile_parsesJsonLines() throws Exception {
            String exclusionLine = "{\"ts\":\"2026-06-01 10:05:00.000\","
                    + "\"method\":\"GET\","
                    + "\"uri\":\"/myapp/admin/logs\","
                    + "\"status\":200,"
                    + "\"durationMs\":8,"
                    + "\"ip\":\"127.0.0.1\","
                    + "\"requestId\":\"req-excl-1\"}";
            Files.writeString(tempDir.resolve("exclusions.log"), exclusionLine + "\n");

            nestedMockMvc.perform(get("/admin/logs/data")
                            .param("type", "exclusions").param("lines", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].uri").value("/myapp/admin/logs"))
                    .andExpect(jsonPath("$[0].requestId").value("req-excl-1"));
        }

        // ── /admin/logs/body endpoint ─────────────────────────────────────────

        @Test
        void getLogsBody_noBodiesFile_returnsEmptyObject() throws Exception {
            // No bodies.*.log files in tempDir — endpoint must degrade gracefully (no 404,
            // no 500) so the viewer modal can always call it and just render nothing.
            nestedMockMvc.perform(get("/admin/logs/body").param("requestId", "anything"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string("{}"));
        }

        @Test
        void getLogsBody_matchingRequestId_returnsMatchingLine() throws Exception {
            String matching =
                    "{\"ts\":\"2026-06-18 10:00:00.000\",\"requestId\":\"abc-match\","
                            + "\"method\":\"POST\",\"uri\":\"/api/login\",\"status\":200,"
                            + "\"reqBody\":\"{\\\"email\\\":\\\"u@x\\\"}\"}";
            String other =
                    "{\"ts\":\"2026-06-18 09:59:00.000\",\"requestId\":\"different\","
                            + "\"method\":\"GET\",\"uri\":\"/api/x\",\"status\":200}";
            Files.writeString(tempDir.resolve("bodies.2026-06-18.0.log"),
                    other + "\n" + matching + "\n");

            nestedMockMvc.perform(get("/admin/logs/body").param("requestId", "abc-match"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("abc-match"))
                    .andExpect(jsonPath("$.uri").value("/api/login"))
                    .andExpect(jsonPath("$.reqBody").value("{\"email\":\"u@x\"}"));
        }

        @Test
        void getLogsBody_unknownRequestId_returnsEmptyObject() throws Exception {
            // bodies.log present but no entry matches → still {} (not 404)
            Files.writeString(tempDir.resolve("bodies.2026-06-18.0.log"),
                    "{\"requestId\":\"present\",\"reqBody\":\"x\"}\n");

            nestedMockMvc.perform(get("/admin/logs/body").param("requestId", "missing"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("{}"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the reversed-byte stream that {@code tailLines} produces when reading
     * backwards from a file, then calls {@code decodeReversed} and returns the decoded string.
     */
    private String decodeReversed(String text) throws Exception {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = utf8.length - 1; i >= 0; i--) {
            baos.write(utf8[i]);
        }
        return ReflectionTestUtils.invokeMethod(controller, "decodeReversed", baos);
    }

    private void assertDecodedEquals(String original) throws Exception {
        assertThat(decodeReversed(original)).isEqualTo(original);
    }

    @SuppressWarnings("unchecked")
    private List<String> tokenize(String s) {
        return ReflectionTestUtils.invokeMethod(controller, "tokenize", s);
    }
}
