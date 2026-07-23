package com.eventhorizon.weblog.controller;



import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;

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
        // Autowired collaborators Spring would inject — the HTML view() reads webLogProperties, and
        // /inflight reads inFlightRegistry; without them those endpoints NPE.
        ReflectionTestUtils.setField(controller, "webLogProperties", new WebLogProperties());
        ReflectionTestUtils.setField(controller, "inFlightRegistry", new InFlightRegistry());
        ReflectionTestUtils.setField(controller, "htmlResource",
                new ClassPathResource("views/log-viewer.html"));
        ReflectionTestUtils.setField(controller, "cssResource",
                new ClassPathResource("views/log-viewer.css"));
        injectJsParts(controller);
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

    // ── view() — reflected-XSS guard on the `type` parameter ──────────────────

    @Test
    void getLogsHtml_maliciousType_isNeutralizedToDefault() throws Exception {
        // `type` is reflected into an inline <script> string; a crafted value must not
        // break out of the literal. It should collapse to the "access" default instead.
        String payload = "');alert(document.cookie);//";
        String body = mockMvc.perform(get("/admin/logs").param("type", payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("window.__INIT");
        assertThat(body).contains("\"access\"");            // type collapsed to the default
        assertThat(body).doesNotContain("alert(document.cookie)");
    }

    @Test
    void getLogsHtml_validType_isReflectedUnchanged() throws Exception {
        String body = mockMvc.perform(get("/admin/logs").param("type", "error"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("window.__INIT").contains("\"error\"");
    }

    // ── static asset endpoints (external CSS/JS) ──────────────────────────────

    @Test
    void getLogsHtml_linksExternalCssAndJs() throws Exception {
        String body = mockMvc.perform(get("/admin/logs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("/admin/logs/log-viewer.css");
        assertThat(body).contains("/admin/logs/log-viewer.js");
        // The CSS/JS are no longer inlined in the shell page.
        assertThat(body).doesNotContain("<style>");
    }

    @Test
    void getCssAsset_returnsCssWithEtag() throws Exception {
        mockMvc.perform(get("/admin/logs/log-viewer.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(header().exists("ETag"));
    }

    @Test
    void getJsAsset_returnsJsThatReadsInitObject() throws Exception {
        String js = mockMvc.perform(get("/admin/logs/log-viewer.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().exists("ETag"))
                .andReturn().getResponse().getContentAsString();
        assertThat(js).contains("window.__INIT");
    }

    @Test
    void getJsAsset_matchingIfNoneMatch_returns304() throws Exception {
        String etag = mockMvc.perform(get("/admin/logs/log-viewer.js"))
                .andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/admin/logs/log-viewer.js").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @ParameterizedTest(name = "type={0} → {1}")
    @CsvSource({
        "access,     access",
        "catalina,   catalina",
        "error,      error",
        "exclusions, exclusions",
        "bogus,      access",   // unknown → default
        "ACCESS,     access",   // case-sensitive → default
        "'',         access",   // empty → default
    })
    void snapToValidType_allowsKnownTypesRejectsRest(String input, String expected) {
        String result = ReflectionTestUtils.invokeMethod(controller, "snapToValidType", input);
        assertThat(result).isEqualTo(expected);
    }

    // ── decodeReversed ─────────────────────────────────────────────────────────

    @Test
    void decodeReversed_asciiContent_roundTrips() throws Exception {
        assertDecodedEquals("plain ascii log line");
    }

    @Test
    void decodeReversed_utf8MultiByteContent_roundTrips() throws Exception {
        // Multi-byte chars: Š (C5 A0), ž (C5 BE), Ć (C4 86), é (C3 A9), 日 (E6 97 A5)
        assertDecodedEquals("Škoda žaba Ćao café 日本");
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
        String uri = "/myapp/api/resource";
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

    // Valid dropdown options in the log viewer: 2000, 5000, 10000, 20000
    @ParameterizedTest(name = "{0} lines → snaps to {1}")
    @CsvSource({
        "0,       2000",   // below minimum
        "2000,    2000",   // exact
        "3000,    2000",   // closer to 2000
        "4000,    5000",   // closer to 5000
        "5000,    5000",   // exact
        "7500,    5000",   // equidistant — smaller wins (first in array)
        "10000,  10000",   // exact
        "20000,  20000",   // exact (now the largest option)
        "99999,  20000",   // above max
    })
    void snapToValidOption_snapsToNearestAllowedValue(int input, int expected) {
        int result = ReflectionTestUtils.invokeMethod(controller, "snapToValidOption", input);
        assertThat(result).isEqualTo(expected);
    }

    // ── snapToPageSize (Phase 1) — same snapping but capped at 20 000 ──────────
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "1,      2000",   // below min
        "2000,   2000",
        "5000,   5000",
        "20000, 20000",
        "50000, 20000",   // 50k option capped down
        "99999, 20000",   // above max capped
    })
    void snapToPageSize_capsAtTwentyThousand(int input, int expected) {
        int result = ReflectionTestUtils.invokeMethod(controller, "snapToPageSize", input);
        assertThat(result).isEqualTo(expected);
    }

    // ── reformatTs ────────────────────────────────────────────────────────────

    // reformatTs now lives in the extracted LogParser (same package, static).
    @Test
    void reformatTs_validTimestamp_convertedToDayMonNameYear() {
        assertThat(LogParser.reformatTs("2026-05-25 14:30:45.123"))
                .isEqualTo("25-May-2026 14:30:45.123");
    }

    @Test
    void reformatTs_null_returnsNull() {
        assertThat(LogParser.reformatTs(null)).isNull();
    }

    @Test
    void reformatTs_stringTooShort_returnedUnchanged() {
        assertThat(LogParser.reformatTs("bad")).isEqualTo("bad");
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
        String uri = "/myapp/api/resource";
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
    void parseTomcatAccess_millisTimeUnit_durationNotDividedByThousand() {
        // With access-log-time-unit=MILLIS the %D/%F tokens are already ms — no /1000.
        WebLogProperties props = new WebLogProperties();
        props.setAccessLogTimeUnit(WebLogProperties.AccessLogTimeUnit.MILLIS);
        ReflectionTestUtils.setField(controller, "webLogProperties", props);

        String line = "2026-05-25 14:30:45.123 "
                + "GET \"/myapp/api/resource\" HTTP/1.1 200 4096 142 5 "
                + "\"10.0.0.1\" \"-\" \"-\" "
                + "localhost:8080 http-nio-8080-exec-1 + "
                + "\"localhost\" \"-\" - "
                + "\"*/*\" \"gzip\" \"en-US\" \"keep-alive\" \"-\" \"-\" \"-\" \"curl/8.6\" "
                + "\"application/json\" - \"-\" \"no-cache\" \"req-abc-123\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.durationMs()).isEqualTo(142L); // 142 ms read verbatim, not 142/1000=0
        assertThat(entry.ttfbMs()).isEqualTo(5L);
    }

    @Test
    void parseTomcatAccess_xForwardedForPresent_firstValueUsedAsClientIp() {
        // XFF is token [8]; when non-absent it takes priority over remoteIp
        String uri = "/myapp/api/resource";
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

    @Test
    void parseTomcatAccess_forwardHeadersStrategySet_remoteIpWinsOverXForwardedFor() {
        ReflectionTestUtils.setField(controller, "forwardHeadersStrategy", "native");
        String uri = "/myapp/api/resource";
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
        // remoteIp (%a), not the unvalidated XFF header, once the consuming app trusts it
        assertThat(entry.ip()).isEqualTo("192.168.1.1");
    }

    @Test
    void parseTomcatAccess_rateLimitHeadersPresent_parsedIntoDedicatedFields() {
        String uri = "/myapp/api/resource";
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"" + uri + "\" HTTP/1.1 429 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"1000\" \"0\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.rateLimitLimit()).isEqualTo("1000");
        assertThat(entry.rateLimitRemaining()).isEqualTo("0");
    }

    @Test
    void parseTomcatAccess_userAuthDenyAttributesPresent_parsedIntoDedicatedFields() {
        // Tokens 31/32/33 — the user principal, the safe Authorization summary and the
        // consumer-set deny reason. A 401 with all three is the debugging case they exist for.
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"/myapp/api/resource\" HTTP/1.1 401 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"-\" \"-\" "
                + "\"u@example.com\" \"Bearer JWT sub=u@example.com len=812\" \"jwt-expired\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.user()).isEqualTo("u@example.com");
        assertThat(entry.auth()).isEqualTo("Bearer JWT sub=u@example.com len=812");
        assertThat(entry.deny()).isEqualTo("jwt-expired");
    }

    @Test
    void parseTomcatAccess_userAuthDenyAbsent_fieldsAreNull() {
        // "-" in those positions must map to null, not the literal dash.
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"/myapp/api/resource\" HTTP/1.1 200 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.user()).isNull();
        assertThat(entry.auth()).isNull();
        assertThat(entry.deny()).isNull();
    }

    @Test
    void parseTomcatAccess_apiKeyRequestAttributePresent_parsedIntoDedicatedField() {
        // The request-attribute feature is off by default now, so opt in explicitly.
        ReflectionTestUtils.setField(controller, "requestAttributeName", "apiKey");
        String uri = "/myapp/api/resource";
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"" + uri + "\" HTTP/1.1 401 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"ed5a...92fe\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        // Default request-attribute name is "apiKey" (WebLogProperties default).
        assertThat(entry.attributes()).containsEntry("apiKey", "ed5a...92fe");
    }

    @Test
    void parseTomcatAccess_customRequestAttributeName_usedAsJsonKey() {
        // Simulate log-viewer.request-attribute=tenantId
        ReflectionTestUtils.setField(controller, "requestAttributeName", "tenantId");
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"/myapp/api/resource\" HTTP/1.1 200 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"acme-corp\"";

        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        assertThat(entry).isNotNull();
        assertThat(entry.attributes())
                .containsEntry("tenantId", "acme-corp")
                .doesNotContainKey("apiKey");
    }

    @Test
    void logEntry_serializesRequestAttributeFlattenedUnderConfiguredKey() throws Exception {
        // The request-attribute feature is off by default now, so opt in explicitly.
        ReflectionTestUtils.setField(controller, "requestAttributeName", "apiKey");
        String line = "2026-05-25 10:00:00.000 "
                + "GET \"/myapp/api/resource\" HTTP/1.1 401 32 2000 0 "
                + "\"192.168.1.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-5 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                + "\"-\" - \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"-\" \"ed5a...92fe\"";
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess", line);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entry);

        // @JsonAnyGetter flattens the value to a top-level "apiKey" key, not a nested object.
        assertThat(json).contains("\"apiKey\":\"ed5a...92fe\"");
        assertThat(json).doesNotContain("\"attributes\"");
    }

    @Test
    void parseTomcatAccess_rateLimitHeadersAbsent_fieldsAreNull() {
        // Older-format line without the trailing rate-limit tokens — must not throw.
        LogViewerController.LogEntry entry =
                ReflectionTestUtils.invokeMethod(controller, "parseTomcatAccess",
                        "2026-05-25 10:00:00.000 GET \"/myapp/api/resource\" HTTP/1.1 200 512 10000 1000 "
                        + "\"192.168.1.1\" \"-\" \"-\" app:8080 http-nio-8080-exec-5 + "
                        + "\"app\" \"-\" - \"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"-\" "
                        + "\"-\" - \"-\" \"-\" \"-\"");

        assertThat(entry).isNotNull();
        assertThat(entry.rateLimitLimit()).isNull();
        assertThat(entry.rateLimitRemaining()).isNull();
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

    @Test
    void getInflight_emptyRegistry_returnsZeroCount() throws Exception {
        mockMvc.perform(get("/admin/logs/inflight"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.requests").isArray())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    void getInflight_withRegisteredRequest_reportsItAsARow() throws Exception {
        InFlightRegistry reg = new InFlightRegistry();
        reg.register(new com.eventhorizon.weblog.inflight.InFlightRequest(
                "req-live", "GET", "/api/slow", "HTTP/1.1", "host", "9.9.9.9",
                "9.9.9.9", "ua", null, "exec-1", System.nanoTime(), System.currentTimeMillis()));
        ReflectionTestUtils.setField(controller, "inFlightRegistry", reg);

        mockMvc.perform(get("/admin/logs/inflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].requestId").value("req-live"))
                .andExpect(jsonPath("$.requests[0].uri").value("/api/slow"));
    }

    @Test
    void getLogsTrace_noLogFiles_returns200WithEmptyArray() throws Exception {
        // logDir is tmpdir — no error.log → findByRequestId returns empty list
        mockMvc.perform(get("/admin/logs/trace").param("requestId", "anything"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    // ── parseTomcatAccess — guard on too-few tokens ───────────────────────────

    @Test
    void parseTomcatAccess_tooFewTokensAfterTimestamp_returnsNull() {
        // Only 5 tokens after timestamp (minimum is 9) — should return null, not throw
        String line = "2026-06-01 10:00:00.000 GET \"/myapp/api/resource\" HTTP/1.1 200 1024";
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
                + "GET \"/myapp/api/resource\" HTTP/1.1 200 4096 50000 1000 "
                + "\"10.0.0.1\" \"-\" \"-\" "
                + "app:8080 http-nio-8080-exec-1 + "
                + "\"app\" \"-\" - "
                + "\"*/*\" \"-\" \"-\" \"keep-alive\" \"-\" \"-\" \"-\" \"curl/8.6\" "
                + "\"application/json\" - \"-\" \"no-cache\" \"req-file-test\"";

        @BeforeEach
        void setUp() throws Exception {
            nestedController = new LogViewerController();
            ReflectionTestUtils.setField(nestedController, "logDir", tempDir.toString());
            ReflectionTestUtils.setField(nestedController, "webLogProperties", new WebLogProperties());
            ReflectionTestUtils.setField(nestedController, "inFlightRegistry", new InFlightRegistry());
            ReflectionTestUtils.setField(nestedController, "htmlResource",
                    new ClassPathResource("views/log-viewer.html"));
            ReflectionTestUtils.setField(nestedController, "cssResource",
                    new ClassPathResource("views/log-viewer.css"));
            injectJsParts(nestedController);
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
        void getLogsData_unparseableLineInWindow_stillReturnsFullRequestedCount() throws Exception {
            // Regression: a non-blank line the parser rejects must NOT short the tail by one.
            // Previously readByPrefix read exactly `lines` raw lines and dropped nulls without
            // backfilling, so "last N" quietly rendered N-1 whenever the window held a junk line.
            // The window must be SMALLER than the file (2000 is the min snapped option), so the file
            // is not exhausted — that is the only case the old code shorted. One junk line sits well
            // inside the newest 2000 lines.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2100; i++) {
                sb.append(ACCESS_LINE.replace("req-file-test", "id-" + i)).append('\n');
                if (i == 2095) sb.append("GARBAGE not-a-valid-access-log-line\n"); // unparseable, non-blank
            }
            Files.writeString(tempDir.resolve("access_log.2026-06-01.log"), sb.toString());

            // lines=2000 snaps to the 2000 option; the newest 2000 parsed entries must come back
            // in full despite the junk line falling inside that window.
            nestedMockMvc.perform(get("/admin/logs/data")
                            .param("type", "access").param("lines", "2000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2000));
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

        // ── error-log requestId parsing + /trace join ────────────────────────

        @Test
        void getLogsData_errorLogWithRequestId_parsesRequestIdAndStack() throws Exception {
            String content =
                    "2026-06-01 10:00:00.000 ERROR [http-nio-8080-exec-1] [req-500-test] r.r.c.Foo - boom\n"
                    + "java.lang.RuntimeException: boom\n"
                    + "\tat foo.Bar.baz(Bar.java:1)\n";
            Files.writeString(tempDir.resolve("error.2026-06-01.0.log"), content);

            nestedMockMvc.perform(get("/admin/logs/data").param("type", "error").param("lines", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].level").value("ERROR"))
                    .andExpect(jsonPath("$[0].requestId").value("req-500-test"))
                    .andExpect(jsonPath("$[0].throwable").value(org.hamcrest.Matchers.containsString("RuntimeException")));
        }

        @Test
        void getLogsData_legacyErrorLogNoRequestId_stillParsesWithNullRequestId() throws Exception {
            // A line written before the [requestId] token existed must still parse.
            String content =
                    "2026-06-01 10:00:00.000 ERROR [http-nio-8080-exec-1] r.r.c.Foo - legacy boom\n"
                    + "java.lang.RuntimeException: legacy\n";
            Files.writeString(tempDir.resolve("error.2026-06-01.0.log"), content);

            nestedMockMvc.perform(get("/admin/logs/data").param("type", "error").param("lines", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].level").value("ERROR"))
                    .andExpect(jsonPath("$[0].requestId").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        void getLogsTrace_matchingRequestId_returnsEntryWithStack() throws Exception {
            String content =
                    "2026-06-01 10:00:00.000 ERROR [exec-1] [req-abc] r.r.c.Foo - boom\n"
                    + "java.lang.IllegalStateException: nope\n"
                    + "\tat foo.Bar.baz(Bar.java:2)\n";
            Files.writeString(tempDir.resolve("error.2026-06-01.0.log"), content);

            nestedMockMvc.perform(get("/admin/logs/trace").param("requestId", "req-abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].requestId").value("req-abc"))
                    .andExpect(jsonPath("$[0].throwable").value(org.hamcrest.Matchers.containsString("IllegalStateException")));
        }

        @Test
        void getLogsTrace_noMatchingRequestId_returnsEmptyArray() throws Exception {
            String content =
                    "2026-06-01 10:00:00.000 ERROR [exec-1] [req-xyz] r.r.c.Foo - boom\n"
                    + "java.lang.RuntimeException: boom\n";
            Files.writeString(tempDir.resolve("error.2026-06-01.0.log"), content);

            nestedMockMvc.perform(get("/admin/logs/trace").param("requestId", "does-not-exist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        // ── Phase 0: server logs (catalina/error) now read compressed .gz archives ────

        @Test
        void getLogsData_catalinaGzArchive_isReadBack() throws Exception {
            // Previously readServerByPrefix returned the first NON-.gz file only, so a
            // compressed catalina archive was invisible. It must now be read.
            writeGz(tempDir.resolve("catalina.2026-06-01.0.log.gz"),
                    "2026-06-01 09:00:00.000 INFO [main] o.a.catalina.core.Foo - started\n");

            nestedMockMvc.perform(get("/admin/logs/data").param("type", "catalina").param("lines", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].level").value("INFO"))
                    .andExpect(jsonPath("$[0].message").value("started"));
        }

        @Test
        void getLogsData_serverLogAcrossPlainAndGz_aggregatesNewestFirst() throws Exception {
            // Newest day plain, older day compressed — the unified walk must read both.
            Files.writeString(tempDir.resolve("error.2026-06-02.0.log"),
                    "2026-06-02 10:00:00.000 ERROR [exec-1] [req-new] r.r.c.Foo - newer\n");
            writeGz(tempDir.resolve("error.2026-06-01.0.log.gz"),
                    "2026-06-01 10:00:00.000 ERROR [exec-1] [req-old] r.r.c.Foo - older\n");

            nestedMockMvc.perform(get("/admin/logs/data").param("type", "error").param("lines", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    // newest-first ordering across the plain/gz boundary
                    .andExpect(jsonPath("$[0].requestId").value("req-new"))
                    .andExpect(jsonPath("$[1].requestId").value("req-old"));
        }

        @Test
        void getLogsTrace_errorInGzArchive_isFound() throws Exception {
            // The stack-trace join must resolve an error that has already been compressed.
            writeGz(tempDir.resolve("error.2026-06-01.0.log.gz"),
                    "2026-06-01 10:00:00.000 ERROR [exec-1] [req-gz] r.r.c.Foo - boom\n"
                    + "java.lang.IllegalStateException: from archive\n"
                    + "\tat foo.Bar.baz(Bar.java:9)\n");

            nestedMockMvc.perform(get("/admin/logs/trace").param("requestId", "req-gz"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].requestId").value("req-gz"))
                    .andExpect(jsonPath("$[0].throwable")
                            .value(org.hamcrest.Matchers.containsString("IllegalStateException")));
        }

        /** Writes {@code content} as a gzip-compressed file at {@code path}. */
        private static void writeGz(Path path, String content) throws IOException {
            try (OutputStream fos = Files.newOutputStream(path);
                 GZIPOutputStream gz = new GZIPOutputStream(fos)) {
                gz.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }

        // ── Phase 1: paged / date-ranged reads (/page + readPage) ─────────────

        private static final LocalDate LO = LocalDate.of(2000, 1, 1);
        private static final LocalDate HI = LocalDate.of(2100, 1, 1);

        /** Writes an access-log file whose lines carry the given requestIds, in file (chronological) order. */
        private void writeAccess(String fileName, String... requestIds) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (String rid : requestIds) sb.append(ACCESS_LINE.replace("req-file-test", rid)).append('\n');
            Files.writeString(tempDir.resolve(fileName), sb.toString());
        }

        private static String rid(LogViewerController.LogEntry e) { return e.requestId(); }

        /** Invokes the private readPage() with an uncapped pageSize so paging is testable below 2000. */
        private LogViewerController.LogPage page(String type, LocalDate from, LocalDate to,
                                                String cursorFile, int cursorIndex, boolean older, int pageSize) {
            return ReflectionTestUtils.invokeMethod(nestedController, "readPage",
                    type, from, to, cursorFile, cursorIndex, older, pageSize);
        }

        @Test
        void page_initial_returnsNewestFirstWithCursorsAndFlags() throws Exception {
            writeAccess("access_log.2026-06-01.log", "e0", "e1", "e2", "e3"); // indices 0..3 chronological

            LogViewerController.LogPage p = page("access", LO, HI, null, -1, true, 2);

            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("e3", "e2");
            assertThat(p.newest().index()).isEqualTo(3);
            assertThat(p.oldest().index()).isEqualTo(2);
            assertThat(p.hasNewer()).as("initial page is at the newest edge").isFalse();
            assertThat(p.hasOlder()).isTrue();
        }

        @Test
        void page_older_walksBackFromCursorThenReportsExhausted() throws Exception {
            writeAccess("access_log.2026-06-01.log", "e0", "e1", "e2", "e3");

            LogViewerController.LogPage p =
                    page("access", LO, HI, "access_log.2026-06-01.log", 2, true, 2);
            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("e1", "e0");
            // e0/e1 are the two oldest entries, so this last full page must already report
            // hasOlder=false (N+1 look-ahead) — the Older button disables here rather than
            // enabling one click too long and dropping onto a spurious empty page.
            assertThat(p.hasOlder()).isFalse();

            // Paging older than the very first entry still yields an empty page with hasOlder=false.
            LogViewerController.LogPage end =
                    page("access", LO, HI, "access_log.2026-06-01.log", 0, true, 2);
            assertThat(end.entries()).isEmpty();
            assertThat(end.hasOlder()).isFalse();
        }

        @Test
        void page_older_crossesPlainToGzBoundaryNewestFirst() throws Exception {
            writeAccess("access_log.2026-06-02.log", "b0", "b1");            // newest, plain
            writeGz(tempDir.resolve("access_log.2026-06-01.log.gz"),          // older, compressed
                    ACCESS_LINE.replace("req-file-test", "a0") + "\n"
                    + ACCESS_LINE.replace("req-file-test", "a1") + "\n");

            LogViewerController.LogPage p = page("access", LO, HI, null, -1, true, 3);

            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("b1", "b0", "a1");
            assertThat(p.oldest().file()).isEqualTo("access_log.2026-06-01.log.gz");
            assertThat(p.oldest().index()).isEqualTo(1);
        }

        @Test
        void page_newer_returnsEntriesAfterCursorNewestFirst() throws Exception {
            writeAccess("access_log.2026-06-01.log", "e0", "e1", "e2", "e3");

            LogViewerController.LogPage p =
                    page("access", LO, HI, "access_log.2026-06-01.log", 1, false, 2);
            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("e3", "e2");
            assertThat(p.hasOlder()).isTrue();
            // e3 is the newest entry, so this last full page must already report hasNewer=false
            // (N+1 look-ahead) — the Newer button disables here rather than enabling one click too
            // long and dropping onto a spurious empty page.
            assertThat(p.hasNewer()).isFalse();

            LogViewerController.LogPage end =
                    page("access", LO, HI, "access_log.2026-06-01.log", 3, false, 2);
            assertThat(end.entries()).isEmpty();
            assertThat(end.hasNewer()).isFalse();
        }

        @Test
        void page_newer_fullPageWithMoreBeyond_reportsHasNewerAndStaysContiguous() throws Exception {
            // 6 entries, page size 2, newer from index 1 → four entries are newer (e2..e5).
            // The page must be the two CLOSEST-newer (e3, e2, newest-first), report hasNewer=true,
            // and its newest cursor (e3) must page contiguously onto e5, e4 — no gap, no repeat.
            writeAccess("access_log.2026-06-01.log", "e0", "e1", "e2", "e3", "e4", "e5");

            LogViewerController.LogPage p =
                    page("access", LO, HI, "access_log.2026-06-01.log", 1, false, 2);
            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("e3", "e2");
            assertThat(p.hasNewer()).isTrue();
            assertThat(p.newest().index()).isEqualTo(3);

            LogViewerController.LogPage next =
                    page("access", LO, HI, p.newest().file(), p.newest().index(), false, 2);
            assertThat(next.entries()).extracting(WithActualLogFiles::rid).containsExactly("e5", "e4");
            assertThat(next.hasNewer()).isFalse();
        }

        @Test
        void page_dateRange_selectsOnlyFilesWithinWindow() throws Exception {
            writeAccess("access_log.2026-06-01.log", "x0");           // out of range
            writeAccess("access_log.2026-06-10.log", "y0", "y1");     // in range

            LogViewerController.LogPage p =
                    page("access", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 15), null, -1, true, 100);

            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("y1", "y0");
        }

        @Test
        void page_staleOrTraversalCursorFile_fallsBackToNewestPageSafely() throws Exception {
            writeAccess("access_log.2026-06-01.log", "e0", "e1");

            // A cursor file not in the discovered set (incl. a path-traversal attempt) is never opened
            // as a path — indexOfFile returns -1 and we serve the newest page instead of failing.
            LogViewerController.LogPage p =
                    page("access", LO, HI, "../../etc/passwd", 5, true, 100);

            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("e1", "e0");
        }

        @Test
        void getLogsPage_endpoint_returnsLogPageJson() throws Exception {
            writeAccess("access_log.2026-06-01.log", "e0", "e1", "e2");

            nestedMockMvc.perform(get("/admin/logs/page").param("type", "access"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.entries").isArray())
                    .andExpect(jsonPath("$.entries.length()").value(3))
                    .andExpect(jsonPath("$.newest.index").value(2))
                    .andExpect(jsonPath("$.hasNewer").value(false))
                    .andExpect(jsonPath("$.hasOlder").value(false));
        }

        @Test
        void page_largeFile_returnsOnlyTheRequestedPageNotEverything() throws Exception {
            // 300 entries in one file; a page of 3 must return exactly 3 (the newest), proving the
            // response is bounded to pageSize rather than materializing the whole file.
            String[] ids = new String[300];
            for (int i = 0; i < ids.length; i++) ids[i] = "n" + i;
            writeAccess("access_log.2026-06-01.log", ids);

            LogViewerController.LogPage p = page("access", LO, HI, null, -1, true, 3);

            assertThat(p.entries()).hasSize(3);
            assertThat(p.entries()).extracting(WithActualLogFiles::rid).containsExactly("n299", "n298", "n297");
            assertThat(p.newest().index()).isEqualTo(299);
            assertThat(p.hasOlder()).isTrue();
        }

        @Test
        void getLogsTrace_withDate_findsErrorInGzArchive() throws Exception {
            writeGz(tempDir.resolve("error.2026-06-01.0.log.gz"),
                    "2026-06-01 10:00:00.000 ERROR [exec-1] [req-dated] r.r.c.Foo - boom\n"
                    + "java.lang.IllegalStateException: dated\n");

            nestedMockMvc.perform(get("/admin/logs/trace")
                            .param("requestId", "req-dated").param("date", "2026-06-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].throwable")
                            .value(org.hamcrest.Matchers.containsString("IllegalStateException")));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the reversed-byte stream that {@code tailLines} produces when reading
     * backwards from a file, then calls {@code decodeReversed} and returns the decoded string.
     */
    private String decodeReversed(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = utf8.length - 1; i >= 0; i--) {
            baos.write(utf8[i]);
        }
        // decodeReversed now lives in the extracted LogFileReader (same package, static).
        return LogFileReader.decodeReversed(baos);
    }

    private void assertDecodedEquals(String original) throws Exception {
        assertThat(decodeReversed(original)).isEqualTo(original);
    }

    /** Injects the four split viewer-script part resources the controller concatenates in init(). */
    private static void injectJsParts(LogViewerController c) {
        ReflectionTestUtils.setField(c, "jsCoreResource",   new ClassPathResource("views/log-viewer.core.js"));
        ReflectionTestUtils.setField(c, "jsRenderResource", new ClassPathResource("views/log-viewer.render.js"));
        ReflectionTestUtils.setField(c, "jsModalResource",  new ClassPathResource("views/log-viewer.modal.js"));
        ReflectionTestUtils.setField(c, "jsUiResource",     new ClassPathResource("views/log-viewer.ui.js"));
    }

    private List<String> tokenize(String s) {
        // tokenize now lives in the extracted LogParser (same package, static).
        return LogParser.tokenize(s);
    }
}
