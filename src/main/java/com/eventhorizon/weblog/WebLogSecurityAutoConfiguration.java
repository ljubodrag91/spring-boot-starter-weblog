package com.eventhorizon.weblog;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Fallback security configuration for the log viewer endpoint.
 *
 * <p>Only activates when {@code spring-security-web} is on the classpath (i.e. the
 * consumer app already depends on {@code spring-boot-starter-security}). Apps without
 * Spring Security need no action — the viewer is simply unprotected.
 *
 * <p>Registers a {@link SecurityFilterChain} at {@code Integer.MAX_VALUE - 5} (lowest
 * meaningful priority) that covers {@code /admin/logs/**} with HTTP Basic auth and
 * {@link SessionCreationPolicy#ALWAYS}. The session policy is required so the
 * log viewer's background {@code fetch()} calls to {@code /admin/logs/data} can reuse
 * the authenticated session cookie — browsers do not reliably re-send HTTP Basic
 * credentials on XHR/fetch requests.
 *
 * <p><b>Override:</b> define your own bean named {@code weblogAdminFilterChain} and this
 * auto-configured bean is skipped entirely.
 *
 * <p><b>Existing admin chain:</b> if the consumer already has a
 * {@link SecurityFilterChain} at a lower {@code @Order} number that matches
 * {@code /admin/**}, that chain handles {@code /admin/logs/**} and this fallback is
 * never reached — provided that chain also uses {@code SessionCreationPolicy.ALWAYS}.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebLogSecurityAutoConfiguration {

    @Bean("weblogAdminFilterChain")
    @ConditionalOnMissingBean(name = "weblogAdminFilterChain")
    @Order(Integer.MAX_VALUE - 5)
    SecurityFilterChain weblogAdminFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/admin/logs", "/admin/logs/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.ALWAYS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
