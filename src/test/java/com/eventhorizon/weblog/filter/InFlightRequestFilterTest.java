package com.eventhorizon.weblog.filter;

import com.eventhorizon.weblog.WebLogProperties;
import com.eventhorizon.weblog.inflight.InFlightRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InFlightRequestFilter}: an entry exists in the registry for the duration of
 * the chain and is drained afterwards; excluded ({@code skipLog}) and non-tracked requests bypass it.
 */
class InFlightRequestFilterTest {

    private Filter filter(InFlightRegistry reg, WebLogProperties props) {
        return new InFlightRequestFilter(reg, props).inFlightRequestFilter().getFilter();
    }

    @Test
    void tracksDuringChain_drainsAfter() throws Exception {
        InFlightRegistry reg = new InFlightRegistry();
        Filter f = filter(reg, new WebLogProperties());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger sizeDuring = new AtomicInteger(-1);

        FilterChain chain = (r, s) -> sizeDuring.set(reg.size());
        f.doFilter(req, res, chain);

        assertThat(sizeDuring.get()).as("registered while in flight").isEqualTo(1);
        assertThat(reg.size()).as("removed in finally on completion").isZero();
    }

    @Test
    void drainsEvenWhenChainThrows() {
        InFlightRegistry reg = new InFlightRegistry();
        Filter f = filter(reg, new WebLogProperties());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/boom");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (r, s) -> { throw new RuntimeException("boom"); };
        try {
            f.doFilter(req, res, chain);
        } catch (Exception ignored) {
            // expected to propagate
        }
        assertThat(reg.size()).as("finally still drains the entry").isZero();
    }

    @Test
    void skipLogRequest_isNotTracked() throws Exception {
        InFlightRegistry reg = new InFlightRegistry();
        Filter f = filter(reg, new WebLogProperties());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/logs/inflight");
        req.setAttribute("skipLog", Boolean.TRUE);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger sizeDuring = new AtomicInteger(-1);

        f.doFilter(req, res, (r, s) -> sizeDuring.set(reg.size()));

        assertThat(sizeDuring.get()).as("excluded/silent paths bypass tracking").isZero();
    }

    @Test
    void bothFeaturesDisabled_isNotTracked() throws Exception {
        InFlightRegistry reg = new InFlightRegistry();
        WebLogProperties props = new WebLogProperties();
        props.setSlowRequestLoggingEnabled(false);
        props.setInflightViewEnabled(false);
        Filter f = filter(reg, props);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger sizeDuring = new AtomicInteger(-1);

        f.doFilter(req, res, (r, s) -> sizeDuring.set(reg.size()));

        assertThat(sizeDuring.get()).as("no consumer needs the registry → pass-through").isZero();
    }
}
