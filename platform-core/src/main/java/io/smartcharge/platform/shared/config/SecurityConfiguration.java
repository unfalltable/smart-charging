package io.smartcharge.platform.shared.config;

import io.smartcharge.platform.tenancy.TenantContextFilter;
import io.smartcharge.platform.shared.web.DistributedRateLimitFilter;
import io.smartcharge.platform.shared.web.RateLimitProperties;
import io.smartcharge.platform.shared.web.RequestContextFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class SecurityConfiguration {
    @Bean
    @Profile("!local")
    SecurityFilterChain productionSecurity(HttpSecurity http, TenantContextFilter tenantFilter,
                                           RequestContextFilter requestContextFilter,
                                           DistributedRateLimitFilter rateLimitFilter,
                                           TenantAccessFilter tenantAccessFilter,
                                           JwtDecoder jwtDecoder, CorsConfigurationSource cors) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(configuration -> configuration.configurationSource(cors))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/api/v1/public/**",
                                "/api/v1/auth/miniapp/**").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_internal")
                        .requestMatchers("/api/v1/admin/access/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/v1/admin/legal/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/v1/admin/operations/audit").hasAnyAuthority("SCOPE_admin", "SCOPE_auditor")
                        .requestMatchers("/api/v1/admin/operations/work-orders",
                                "/api/v1/admin/operations/work-orders/**")
                            .hasAnyAuthority("SCOPE_admin", "SCOPE_operator", "SCOPE_support")
                        .requestMatchers("/api/v1/admin/finance/**").hasAnyAuthority("SCOPE_admin", "SCOPE_finance")
                        .requestMatchers("/api/v1/admin/**", "/api/v1/operations/**")
                            .hasAnyAuthority("SCOPE_admin", "SCOPE_operator")
                        .requestMatchers("/api/v1/charging/**", "/api/v1/payments", "/api/v1/payments/**",
                                "/api/v1/customer/**")
                            .hasAuthority("SCOPE_customer")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .addFilterBefore(requestContextFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, DistributedRateLimitFilter.class)
                .addFilterAfter(tenantAccessFilter, TenantContextFilter.class)
                .build();
    }

    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http, TenantContextFilter tenantFilter,
                                      RequestContextFilter requestContextFilter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(requestContextFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${charging.web.allowed-origins:}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        configuration.setAllowedOrigins(allowed);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Tenant-Id", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    FilterRegistrationBean<TenantContextFilter> disableContainerRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> disableRequestContextContainerRegistration(RequestContextFilter filter) {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile("!local")
    FilterRegistrationBean<DistributedRateLimitFilter> disableRateLimitContainerRegistration(
            DistributedRateLimitFilter filter) {
        FilterRegistrationBean<DistributedRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile("!local")
    FilterRegistrationBean<TenantAccessFilter> disableTenantAccessContainerRegistration(TenantAccessFilter filter) {
        FilterRegistrationBean<TenantAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
