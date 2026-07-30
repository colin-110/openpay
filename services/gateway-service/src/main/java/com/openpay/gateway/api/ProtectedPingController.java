package com.openpay.gateway.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/protected")
public class ProtectedPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping(@RequestAttribute(value = "merchantId", required = false) UUID merchantId) {
        return Map.of(
            "status", "authenticated",
            "merchantId", merchantId != null ? merchantId.toString() : "unknown"
        );
    }
}
