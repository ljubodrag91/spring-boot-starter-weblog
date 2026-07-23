package com.eventhorizon.weblog.inflight;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InFlightRegistry}: token uniqueness (the reason it does NOT key on the
 * request id), and the register/remove/snapshot/size lifecycle.
 */
class InFlightRegistryTest {

    private static InFlightRequest req(String id) {
        return new InFlightRequest(id, "GET", "/x", "HTTP/1.1", "h", "1.2.3.4",
                null, "ua", null, "t", System.nanoTime(), System.currentTimeMillis());
    }

    @Test
    void registerReturnsUniqueTokensEvenForIdenticalRequestIds() {
        InFlightRegistry reg = new InFlightRegistry();
        long a = reg.register(req("same"));
        long b = reg.register(req("same")); // same (client-supplied) request id
        assertThat(a).isNotEqualTo(b);
        assertThat(reg.size()).isEqualTo(2);
        assertThat(reg.snapshot()).hasSize(2);
    }

    @Test
    void removeDropsExactlyTheKeyedEntry() {
        InFlightRegistry reg = new InFlightRegistry();
        long a = reg.register(req("a"));
        long b = reg.register(req("b"));
        reg.remove(a);
        assertThat(reg.size()).isEqualTo(1);
        reg.remove(b);
        assertThat(reg.size()).isZero();
        assertThat(reg.snapshot()).isEmpty();
    }

    @Test
    void removeUnknownKeyIsNoOp() {
        InFlightRegistry reg = new InFlightRegistry();
        reg.register(req("a"));
        reg.remove(999_999L);
        assertThat(reg.size()).isEqualTo(1);
    }
}
