package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards platform-operator endpoints (merchant onboarding, API key issuance) with a shared secret.
 *
 * <p>If no token is configured the filter refuses every request rather than allowing them: an
 * unset secret must fail closed.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final String expectedToken;
    private final List<String> protectedPaths;
    private final SecurityErrorWriter errorWriter;

    public AdminTokenFilter(String expectedToken, List<String> protectedPaths, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.protectedPaths = List.copyOf(protectedPaths);
        this.errorWriter = new SecurityErrorWriter(objectMapper);
        if (this.expectedToken.isBlank()) {
            log.warn("openpay.security.admin-token is not set: all admin endpoints ({}) will be refused. "
                    + "Set the OPENPAY_ADMIN_TOKEN environment variable to enable them.", protectedPaths);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return protectedPaths.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedToken.isBlank()) {
            errorWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE, "admin_token_not_configured",
                    "Admin access is not configured on this deployment", request.getRequestURI());
            return;
        }

        String presented = request.getHeader(ADMIN_TOKEN_HEADER);
        if (presented == null || !constantTimeEquals(presented, expectedToken)) {
            log.warn("Rejected admin request to {}", request.getRequestURI());
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "invalid_admin_token",
                    "A valid admin token is required", request.getRequestURI());
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
