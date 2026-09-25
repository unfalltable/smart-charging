package io.smartcharge.platform.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class DistributedRateLimitFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(DistributedRateLimitFilter.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);
    private final StringRedisTemplate redis;
    private final RateLimitProperties limits;

    public DistributedRateLimitFilter(StringRedisTemplate redis, RateLimitProperties limits) {
        this.redis = redis;
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = classify(request.getRequestURI());
        String subject = subject(request);
        long minute = System.currentTimeMillis() / 60_000;
        String key = "rate:v1:" + limit.bucket() + ":" + sha256(subject) + ":" + minute;
        try {
            Long used = redis.execute(INCREMENT, List.of(key), "90");
            if (used == null) throw new IllegalStateException("Rate limiter returned no result");
            response.setHeader("X-RateLimit-Limit", Integer.toString(limit.maximum()));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(Math.max(0, limit.maximum() - used.intValue())));
            if (used > limit.maximum()) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
                return;
            }
        } catch (RuntimeException unavailable) {
            LOG.error("Distributed rate limiter is unavailable", unavailable);
            response.setStatus(503);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "5");
            response.getWriter().write("{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"message\":\"Request protection is temporarily unavailable\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Limit classify(String path) {
        if (path.equals("/api/v1/auth/miniapp/login")) return new Limit("login", positive(limits.loginPerMinute(), 20));
        if (path.startsWith("/api/v1/public/")) return new Limit("public", positive(limits.publicPerMinute(), 120));
        return new Limit("api", positive(limits.defaultPerMinute(), 600));
    }

    private static int positive(int configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }

    private static String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "subject:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Limit(String bucket, int maximum) { }
}
