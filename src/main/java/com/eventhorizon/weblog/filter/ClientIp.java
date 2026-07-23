package com.eventhorizon.weblog.filter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared request-time client-IP resolution: the first {@code X-Forwarded-For} hop if present,
 * else {@code X-Real-IP}, else the raw {@link HttpServletRequest#getRemoteAddr() remote address}.
 *
 * <p>Used by {@link AccessLogExclusionFilter} and {@link InFlightRequestFilter} so the IP they log
 * is resolved identically. Note this is <b>not</b> the same as the viewer's parser, which resolves
 * a client IP <i>after the fact</i> from already-logged tokens and honours
 * {@code server.forward-headers-strategy} — a different concern that stays in the controller.
 *
 * <p>These headers are attacker-controllable and are <b>not</b> validated here; the value is used
 * only for display/audit, never for a trust decision.
 */
final class ClientIp {

    private ClientIp() {}

    static String fromRequest(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return req.getRemoteAddr();
    }
}
