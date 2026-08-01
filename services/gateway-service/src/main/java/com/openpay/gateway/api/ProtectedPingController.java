package com.openpay.gateway.api;

import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Smoke-test endpoint proving API key authentication resolves a merchant identity. */
@RestController
@RequestMapping("/api/v1/protected")
public class ProtectedPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal) {
        return Map.of(
                "status", "authenticated",
                "merchantId", principal.merchantId().toString(),
                "scope", principal.authority() == null ? "" : principal.authority());
    }
}
