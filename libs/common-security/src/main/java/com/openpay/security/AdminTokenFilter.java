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
 * Guards endpoints behind a shared secret presented in a header.
 *
 * <p>Used twice with different secrets. {@code X-Admin-Token} guards platform-operator endpoints —
 * merchant onboarding, key issuance, the ledger. {@code X-Internal-Token} guards service-to-service
 * endpoints, and is a separate secret on purpose: a service that only needs to read one thing from
 * a peer should not have to hold the credential that opens everything else.
 *
 * <p>If no token is configured the filter refuses every request rather than allowing them: an
 * unset secret must fail closed.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final String headerName;
    private final String settingName;
    private final String expectedToken;
    private final List<String> protectedPaths;
    private final SecurityErrorWriter errorWriter;

    public AdminTokenFilter(String expectedToken, List<String> protectedPaths, ObjectMapper objectMapper) {
        this(ADMIN_TOKEN_HEADER, "openpay.security.admin-token", expectedToken, protectedPaths, objectMapper);
    }

    public AdminTokenFilter(
            String headerName,
            String settingName,
            String expectedToken,
            List<String> protectedPaths,
            ObjectMapper objectMapper) {
        this.headerName = headerName;
        this.settingName = settingName;
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.protectedPaths = List.copyOf(protectedPaths);
        this.errorWriter = new SecurityErrorWriter(objectMapper);
        if (this.expectedToken.isBlank() && !protectedPaths.isEmpty()) {
            log.warn("{} is not set: {} will be refused.", settingName, protectedPaths);
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
                    "This access is not configured on this deployment (" + settingName + ")",
                    request.getRequestURI());
            return;
        }

        String presented = request.getHeader(headerName);
        if (presented == null || !constantTimeEquals(presented, expectedToken)) {
            log.warn("Rejected {} request to {}", headerName, request.getRequestURI());
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "invalid_admin_token",
                    "A valid " + headerName + " is required", request.getRequestURI());
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
