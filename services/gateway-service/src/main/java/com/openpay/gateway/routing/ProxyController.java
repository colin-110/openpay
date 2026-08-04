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

    /**
     * Every prefix this gateway proxies, and it has to be listed <em>here</em> as well as in
     * {@code openpay.gateway.routes}.
     *
     * <p>The two are not redundant, though they look it: this annotation decides whether Spring
     * dispatches the request to this controller at all, and the configuration decides where it then
     * goes. A prefix present in the configuration and missing here never reaches
     * {@link ReverseProxy} — Spring answers 404 first, and the route sits there looking correct.
     * That is exactly what happened when {@code /api/v1/tokens} was added, and the symptom was a
     * 404 from the gateway for a path whose target was configured and healthy.
     *
     * <p>Both spellings of each prefix, with and without {@code /**}, because the collection
     * endpoints ({@code POST /api/v1/payments}) do not match a pattern that requires a trailing
     * segment.
     */
    @RequestMapping({
            "/api/v1/payments/**", "/api/v1/payments",
            "/api/v1/refunds/**", "/api/v1/refunds",
            "/api/v1/merchants/**", "/api/v1/merchants",
            "/api/v1/settlements/**", "/api/v1/settlements",
            "/api/v1/webhooks/**", "/api/v1/webhooks",
            "/api/v1/tokens/**", "/api/v1/tokens"})
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request, @RequestBody(required = false) byte[] body) {

        GatewayProperties.Route route = reverseProxy.routeFor(request.getRequestURI())
                .orElseThrow(() -> new NoRouteException(request.getRequestURI()));

        return reverseProxy.forward(route, request, body);
    }
}
