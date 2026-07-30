package com.openpay.gateway.routing;

import com.openpay.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for merchant-facing traffic. Everything under a configured route prefix is relayed to
 * the service that owns it, so callers only ever need the gateway's address.
 */
@RestController
public class ProxyController {

    private final ReverseProxy reverseProxy;

    public ProxyController(ReverseProxy reverseProxy) {
        this.reverseProxy = reverseProxy;
    }

    @RequestMapping({"/api/v1/payments/**", "/api/v1/payments", "/api/v1/merchants/**", "/api/v1/merchants"})
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request, @RequestBody(required = false) byte[] body) {

        GatewayProperties.Route route = reverseProxy.routeFor(request.getRequestURI())
                .orElseThrow(() -> new NoRouteException(request.getRequestURI()));

        return reverseProxy.forward(route, request, body);
    }
}
