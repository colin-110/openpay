package com.openpay.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.gateway.api.ErrorResponse;
import com.openpay.gateway.application.ApiKeyValidationResult;
import com.openpay.gateway.application.AuthServiceClient;
import com.openpay.gateway.application.InvalidApiKeyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String MERCHANT_ID_HEADER = "X-Merchant-Id";
    public static final String MERCHANT_ID_ATTRIBUTE = "merchantId";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final AuthServiceClient authServiceClient;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(AuthServiceClient authServiceClient, ObjectMapper objectMapper) {
        this.authServiceClient = authServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/protected/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            writeUnauthorized(response, request.getRequestURI(), "missing_api_key", "API key is required");
            return;
        }

        try {
            ApiKeyValidationResult result = authServiceClient.validateApiKey(apiKey);
            request.setAttribute(MERCHANT_ID_ATTRIBUTE, result.merchantId());
            response.setHeader(MERCHANT_ID_HEADER, result.merchantId().toString());
            log.info("Authenticated request for merchantId={}", result.merchantId());
            filterChain.doFilter(request, response);
        } catch (InvalidApiKeyException exception) {
            log.warn("Rejected request due to invalid API key");
            writeUnauthorized(response, request.getRequestURI(), "invalid_api_key", exception.getMessage());
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String path, String code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new ErrorResponse(code, message, path, OffsetDateTime.now()));
    }
}
