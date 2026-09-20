package dev.glassbook.server.security;

import dev.glassbook.server.config.GlassbookProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long last;

        Bucket(double capacity, double perMinute) {
            this.capacity = capacity;
            this.refillPerNano = perMinute / 60_000_000_000.0;
            this.tokens = capacity;
            this.last = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNano);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private final int perMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Counter rejected;

    public RateLimitInterceptor(GlassbookProperties properties, MeterRegistry meters) {
        this.perMinute = properties.security().orderRatePerMinute();
        this.rejected = Counter.builder("glassbook.ratelimit.rejected").register(meters);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        Bucket bucket = buckets.computeIfAbsent(auth.getName(), k -> new Bucket(Math.max(10, perMinute / 6.0), perMinute));
        if (bucket.tryConsume()) return true;
        rejected.increment();
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"too many requests\"}");
        return false;
    }
}
