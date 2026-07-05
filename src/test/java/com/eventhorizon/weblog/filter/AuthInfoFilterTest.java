package com.eventhorizon.weblog.filter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInfoFilterTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

    private static String jwt(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header  = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".c2lnbmF0dXJl";
    }

    @Test
    void absentHeader_returnsNull() {
        assertThat(AuthInfoFilter.summarize(null, NOW)).isNull();
        assertThat(AuthInfoFilter.summarize("  ", NOW)).isNull();
    }

    @Test
    void expiredJwt_showsSubTypeExpAndExpiredMarker() {
        // exp = 2026-07-05T11:42:00Z — 18 minutes before NOW
        String token = jwt("{\"sub\":\"user@example.com\",\"type\":\"access\",\"exp\":1783251720}");
        String s = AuthInfoFilter.summarize("Bearer " + token, NOW);
        assertThat(s)
                .contains("Bearer JWT")
                .contains("sub=user@example.com")
                .contains("type=access")
                .contains("exp=2026-07-05T11:42:00Z")
                .contains("(expired 18m ago)");
    }

    @Test
    void validJwt_showsRemainingValidity() {
        // exp = 2026-07-05T12:10:00Z — 10 minutes after NOW
        String token = jwt("{\"sub\":\"a@b.c\",\"exp\":1783253400}");
        String s = AuthInfoFilter.summarize("Bearer " + token, NOW);
        assertThat(s).contains("(valid 10m left)");
    }

    @Test
    void refreshTokenType_isSurfaced() {
        String token = jwt("{\"sub\":\"a@b.c\",\"type\":\"refresh\"}");
        assertThat(AuthInfoFilter.summarize("Bearer " + token, NOW)).contains("type=refresh");
    }

    @Test
    void opaqueBearer_showsOnlyLength_neverTheCredential() {
        String s = AuthInfoFilter.summarize("Bearer supersecretopaquetoken123", NOW);
        assertThat(s).isEqualTo("Bearer opaque len=25");
        assertThat(s).doesNotContain("supersecret");
    }

    @Test
    void jwt_neverLeaksSignatureOrRawToken() {
        String token = jwt("{\"sub\":\"a@b.c\",\"exp\":1783253400}");
        String s = AuthInfoFilter.summarize("Bearer " + token, NOW);
        assertThat(s).doesNotContain(token.split("\\.")[2]);
        assertThat(s).doesNotContain(token.split("\\.")[1]);
    }

    @Test
    void basicAuth_showsUsernameOnly() {
        String cred = Base64.getEncoder().encodeToString("admin:terminal22+".getBytes(StandardCharsets.UTF_8));
        String s = AuthInfoFilter.summarize("Basic " + cred, NOW);
        assertThat(s).isEqualTo("Basic user=admin");
        assertThat(s).doesNotContain("terminal22");
    }

    @Test
    void otherScheme_schemeAndLengthOnly() {
        assertThat(AuthInfoFilter.summarize("Digest abcdef", NOW)).isEqualTo("Digest len=6");
    }

    @Test
    void quotesAndControlChars_areSanitized() {
        // A malicious sub claim trying to break the quoted access-log token.
        String token = jwt("{\"sub\":\"evil\\\" 200 \\\"inject\\nnewline\"}");
        String s = AuthInfoFilter.summarize("Bearer " + token, NOW);
        assertThat(s).doesNotContain("\"").doesNotContain("\n").doesNotContain("\\");
    }

    @Test
    void longValues_areCapped() {
        String token = jwt("{\"sub\":\"" + "x".repeat(500) + "\"}");
        String s = AuthInfoFilter.summarize("Bearer " + token, NOW);
        assertThat(s.length()).isLessThanOrEqualTo(AuthInfoFilter.MAX_SUMMARY_LEN);
    }
}
