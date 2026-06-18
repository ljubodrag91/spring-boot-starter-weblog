package com.eventhorizon.weblog.filter;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link RequestIdFilter}.
 *
 * The filter is extracted from the {@link org.springframework.boot.web.servlet.FilterRegistrationBean}
 * and wired into a standalone MockMvc with a minimal dummy controller.  No Spring context needed.
 *
 * <p>Covers:
 * <ul>
 *   <li>Passthrough of valid client-supplied X-Request-Id values</li>
 *   <li>UUID generation for missing, blank, too-long, and unsafe values (log-injection prevention)</li>
 *   <li>MDC lifecycle — key set during the request, cleared after completion</li>
 * </ul>
 */
class RequestIdFilterTest {

    private MockMvc mockMvc;

    /** UUID v4 hex pattern — matches what {@link java.util.UUID#randomUUID()} produces. */
    private static final String UUID_PATTERN =
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    @BeforeEach
    void setUp() {
        Filter filter = new RequestIdFilter().requestIdFilter().getFilter();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PingController())
                .addFilters(filter)
                .build();
    }

    @RestController
    static class PingController {
        @GetMapping("/ping")
        String ping() { return "pong"; }
    }

    // ── passthrough — valid client-supplied IDs ────────────────────────────────

    @Test
    void validAlphanumericId_reflectedInResponseHeaderUnchanged() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "abc-123_XYZ"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "abc-123_XYZ"));
    }

    @Test
    void validId_exactly64Chars_accepted() throws Exception {
        String id = "a".repeat(64);
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, id))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, id));
    }

    @Test
    void validId_uuidFormat_accepted() throws Exception {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, uuid))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, uuid));
    }

    // ── UUID generation — header absent or unsafe ─────────────────────────────

    @Test
    void noRequestIdHeader_uuidGeneratedAndSetOnResponse() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void blankRequestId_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "   "))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void tooLongRequestId_65Chars_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "a".repeat(65)))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unsafeChars_jsonBraces_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "{\"inject\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unsafeChars_doubleQuote_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "id\"quotes"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unsafeChars_space_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "my id"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unsafeChars_newline_uuidGeneratedInstead() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdFilter.HEADER, "id\ninjection"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern(UUID_PATTERN)));
    }

    // ── MDC lifecycle ─────────────────────────────────────────────────────────

    @Test
    void mdcKeySetDuringRequest_clearedAfterCompletion() throws Exception {
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        // Capture filter runs AFTER requestIdFilter (added second, runs second in chain order)
        Filter captureFilter = (req, res, chain) -> {
            mdcDuringRequest.set(MDC.get(RequestIdFilter.MDC_KEY));
            chain.doFilter(req, res);
        };

        MockMvc mvcWithCapture = MockMvcBuilders
                .standaloneSetup(new PingController())
                .addFilters(new RequestIdFilter().requestIdFilter().getFilter(), captureFilter)
                .build();

        // Pre-condition: MDC is clean before the request
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();

        mvcWithCapture.perform(get("/ping").header(RequestIdFilter.HEADER, "trace-id-42"))
                .andExpect(status().isOk());

        // During request: MDC contained the request id
        assertThat(mdcDuringRequest.get()).isEqualTo("trace-id-42");

        // Post-condition: MDC key removed in finally block — no leakage across requests
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
