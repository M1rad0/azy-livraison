package com.azylivraison.config;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PublicApiRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 40;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/public/") || path.startsWith("/api/geocoding/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long minute = Instant.now().getEpochSecond() / 60;
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
        String key = address + ":" + minute;
        Window window = windows.compute(key, (ignored, current) -> current == null
                ? new Window(minute, 1) : new Window(minute, current.count() + 1));
        if (windows.size() > 10_000) windows.keySet().removeIf(item -> !item.endsWith(":" + minute));
        if (window.count() > MAX_REQUESTS_PER_MINUTE) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Trop de demandes. Reessayez dans une minute.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long minute, int count) {}
}
