package com.openpay.gateway.routing;

import com.openpay.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Forwards a request to the downstream service that owns its path prefix. */
public class ReverseProxy {

    /**
     * Headers that describe a single hop and must not be copied across one. Content-Length in
     * particular is recalculated by the outbound client and copying it corrupts the response.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host");

    /**
     * The gateway is the origin a browser actually talks to, so it decides the CORS answer. A
     * downstream service that also emitted these would produce two of each header, which browsers
     * treat as no permission at all.
     */
    private static final Set<String> CORS_RESPONSE_HEADERS = Set.of(
            "access-control-allow-origin", "access-control-allow-methods",
            "access-control-allow-headers", "access-control-allow-credentials",
            "access-control-expose-headers", "access-control-max-age");

    private static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    private final RestClient restClient;
    private final List<GatewayProperties.Route> routes;

    public ReverseProxy(RestClient restClient, List<GatewayProperties.Route> routes) {
        this.restClient = restClient;
        this.routes = List.copyOf(routes);
    }

    public Optional<GatewayProperties.Route> routeFor(String path) {
        return routes.stream().filter(route -> path.startsWith(route.getPathPrefix())).findFirst();
    }

    public ResponseEntity<byte[]> forward(
            GatewayProperties.Route route, HttpServletRequest request, byte[] body) {

        URI target = URI.create(route.getTarget() + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));

        HttpHeaders forwardedHeaders = copyRequestHeaders(request);

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target)
                    .headers(headers -> headers.addAll(forwardedHeaders));

            if (body != null && body.length > 0) {
                spec.body(body);
            }

            ResponseEntity<byte[]> downstream = spec.retrieve()
                    // The gateway is a conduit: downstream error statuses are relayed verbatim
                    // rather than being turned into gateway errors.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(byte[].class);

            return ResponseEntity.status(downstream.getStatusCode())
                    .headers(copyResponseHeaders(downstream.getHeaders()))
                    .body(downstream.getBody());

        } catch (ResourceAccessException exception) {
            log.error("Downstream {} unreachable for {}", route.getTarget(), request.getRequestURI(), exception);
            throw new DownstreamUnavailableException(route.getPathPrefix(), exception);
        }
    }

    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                request.getHeaders(name).asIterator()
                        .forEachRemaining(value -> headers.add(name, value));
            }
        });
        return headers;
    }

    private HttpHeaders copyResponseHeaders(HttpHeaders downstreamHeaders) {
        HttpHeaders headers = new HttpHeaders();
        downstreamHeaders.forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(lower) && !CORS_RESPONSE_HEADERS.contains(lower)) {
                headers.addAll(name, values);
            }
        });
        return headers;
    }
}
