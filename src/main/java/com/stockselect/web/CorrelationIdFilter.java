package com.stockselect.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request an {@code X-Request-Id} — honoring one the caller supplies, generating a
 * UUID otherwise — echoes it as a response header, and exposes it as a request attribute so
 * {@code ScreeningController} can thread it through to {@code ScreeningService} and the vendor
 * clients. Runs as a servlet {@code Filter} rather than a {@code HandlerInterceptor} so every
 * response gets an ID, including paths with no matching handler (the existing 404 case).
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        filterChain.doFilter(request, response);
    }
}
