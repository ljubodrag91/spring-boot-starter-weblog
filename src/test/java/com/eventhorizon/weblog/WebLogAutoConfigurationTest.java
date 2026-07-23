package com.eventhorizon.weblog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebLogAutoConfiguration#accessLogPattern(WebLogProperties)} — the builder
 * that appends the optional {@code %{name}r} request-attribute token, including the S-4 guard that
 * rejects a value which would corrupt the valve pattern.
 */
class WebLogAutoConfigurationTest {

    private static WebLogProperties props(String attr) {
        WebLogProperties p = new WebLogProperties();
        p.setRequestAttribute(attr);
        return p;
    }

    @Test
    void blankAttribute_returnsBasePatternUnchanged() {
        assertThat(WebLogAutoConfiguration.accessLogPattern(props("")))
                .isEqualTo(WebLogAutoConfiguration.ACCESS_LOG_PATTERN);
    }

    @Test
    void validAttribute_appendsRequestAttributeToken() {
        assertThat(WebLogAutoConfiguration.accessLogPattern(props("apiKey")))
                .isEqualTo(WebLogAutoConfiguration.ACCESS_LOG_PATTERN + " \"%{apiKey}r\"");
    }

    @Test
    void validAttribute_isTrimmedBeforeAppending() {
        assertThat(WebLogAutoConfiguration.accessLogPattern(props("  tenantId  ")))
                .isEqualTo(WebLogAutoConfiguration.ACCESS_LOG_PATTERN + " \"%{tenantId}r\"");
    }

    @Test
    void attributeWithPatternBreakingChars_isRejected_basePatternReturned() {
        // A '}' or '"' would terminate the token early and mis-index every field the parser reads.
        assertThat(WebLogAutoConfiguration.accessLogPattern(props("evil}\"attr")))
                .isEqualTo(WebLogAutoConfiguration.ACCESS_LOG_PATTERN);
        assertThat(WebLogAutoConfiguration.accessLogPattern(props("has space")))
                .isEqualTo(WebLogAutoConfiguration.ACCESS_LOG_PATTERN);
    }
}
