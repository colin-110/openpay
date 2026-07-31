package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a valid merchant API key on the configured path prefixes and publishes the resulting
 * {@link ApiKeyPrincipal} as a request attribute.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String PRINCIPAL_ATTRIBUTE = "openpay.principal";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final AuthServiceClient authServiceClient;
    private final List<String> protectedPaths;
    private final SecurityErrorWriter errorWriter;

    public ApiKeyAuthenticationFilter(
            AuthServiceClient authServiceClient, List<String> protectedPaths, ObjectMapper objectMapper) {
        this.authServiceClient = authServiceClient;
        this.protectedPaths = List.copyOf(protectedPaths);
        this.errorWriter = new SecurityErrorWriter(objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return protectedPaths.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(PRINCIPAL_ATTRIBUTE) != null) {
            // A session token already identified the caller; demanding an API key as well
            // would make the dashboard impossible.
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "missing_api_key",
                    "API key is required", request.getRequestURI());
            return;
        }

        ApiKeyPrincipal principal;
        try {
            principal = authServiceClient.validateApiKey(apiKey);
        } catch (InvalidApiKeyException exception) {
            log.warn("Rejected request to {} due to invalid API key", request.getRequestURI());
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "invalid_api_key",
                    exception.getMessage(), request.getRequestURI());
            return;
        } catch (AuthServiceUnavailableException exception) {
            log.error("Auth service unavailable while authenticating {}", request.getRequestURI(), exception);
            errorWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE, "auth_unavailable",
                    "Authentication is temporarily unavailable", request.getRequestURI());
            return;
        }

        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }
}
